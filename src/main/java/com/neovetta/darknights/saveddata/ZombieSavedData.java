package com.neovetta.darknights.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class ZombieSavedData extends SavedData {

    public record PlayerZombie(boolean isCursed) {
        public static final Codec<PlayerZombie> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.BOOL.fieldOf("isCursed").forGetter(PlayerZombie::isCursed)
        ).apply(inst, PlayerZombie::new));

        public static final PlayerZombie DEFAULT = new PlayerZombie(false);

        public PlayerZombie withCursed(boolean cursed) {
            return new PlayerZombie(cursed);
        }
    }

    private record Entry(String uuid, PlayerZombie data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
            PlayerZombie.CODEC.fieldOf("data").forGetter(Entry::data)
        ).apply(inst, Entry::new));
    }

    private static final Codec<ZombieSavedData> CODEC =
        Entry.CODEC.listOf().xmap(
            list -> {
                ZombieSavedData d = new ZombieSavedData();
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

    private static final SavedDataType<ZombieSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darknights", "zombie"),
        ZombieSavedData::new,
        CODEC,
        null
    );

    private final Map<UUID, PlayerZombie> map = new HashMap<>();

    public ZombieSavedData() {}

    public static ZombieSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public PlayerZombie get(UUID uuid) {
        return map.getOrDefault(uuid, PlayerZombie.DEFAULT);
    }

    public void put(UUID uuid, PlayerZombie data) {
        map.put(uuid, data);
        setDirty();
    }

    public void setCursed(UUID uuid, boolean cursed) {
        put(uuid, get(uuid).withCursed(cursed));
    }

    public Collection<UUID> allUuids() {
        return Collections.unmodifiableSet(map.keySet());
    }
}
