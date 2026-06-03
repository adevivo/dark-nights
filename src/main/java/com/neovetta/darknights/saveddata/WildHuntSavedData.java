package com.neovetta.darknights.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class WildHuntSavedData extends SavedData {

    public record PlayerHunt(int stacks) {
        public static final PlayerHunt DEFAULT = new PlayerHunt(0);
        public static final Codec<PlayerHunt> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.fieldOf("stacks").forGetter(PlayerHunt::stacks)
        ).apply(inst, PlayerHunt::new));

        public PlayerHunt addStack() { return new PlayerHunt(Math.min(stacks + 1, 3)); }
    }

    private record Entry(String uuid, PlayerHunt data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
            PlayerHunt.CODEC.fieldOf("data").forGetter(Entry::data)
        ).apply(inst, Entry::new));
    }

    private static final Codec<WildHuntSavedData> CODEC =
        Entry.CODEC.listOf().xmap(
            list -> {
                WildHuntSavedData d = new WildHuntSavedData();
                for (Entry e : list) {
                    try { d.map.put(UUID.fromString(e.uuid()), e.data()); }
                    catch (IllegalArgumentException ignored) {}
                }
                return d;
            },
            d -> d.map.entrySet().stream()
                .map(e -> new Entry(e.getKey().toString(), e.getValue()))
                .toList()
        );

    private static final SavedDataType<WildHuntSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darknights", "wild_hunt"),
        WildHuntSavedData::new,
        CODEC,
        null
    );

    private final Map<UUID, PlayerHunt> map = new HashMap<>();

    public WildHuntSavedData() {}

    public static WildHuntSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public PlayerHunt get(UUID uuid) {
        return map.getOrDefault(uuid, PlayerHunt.DEFAULT);
    }

    public void put(UUID uuid, PlayerHunt data) {
        map.put(uuid, data);
        setDirty();
    }
}
