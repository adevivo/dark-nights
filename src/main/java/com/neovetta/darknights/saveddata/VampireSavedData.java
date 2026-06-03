package com.neovetta.darknights.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class VampireSavedData extends SavedData {

    public record PlayerVampire(boolean isCursed, float blood) {
        public static final PlayerVampire DEFAULT = new PlayerVampire(false, 1.0f);
        public static final Codec<PlayerVampire> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.BOOL.fieldOf("isCursed").forGetter(PlayerVampire::isCursed),
            Codec.FLOAT.fieldOf("blood").forGetter(PlayerVampire::blood)
        ).apply(inst, PlayerVampire::new));

        public PlayerVampire withCursed(boolean c) { return new PlayerVampire(c, blood); }
        public PlayerVampire withBlood(float b) { return new PlayerVampire(isCursed, Math.max(0f, Math.min(1f, b))); }
    }

    private record Entry(String uuid, PlayerVampire data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
            PlayerVampire.CODEC.fieldOf("data").forGetter(Entry::data)
        ).apply(inst, Entry::new));
    }

    private static final Codec<VampireSavedData> CODEC =
        Entry.CODEC.listOf().xmap(
            list -> {
                VampireSavedData d = new VampireSavedData();
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

    private static final SavedDataType<VampireSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darknights", "vampire"),
        VampireSavedData::new,
        CODEC,
        null
    );

    private final Map<UUID, PlayerVampire> map = new HashMap<>();

    public VampireSavedData() {}

    public static VampireSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public PlayerVampire get(UUID uuid) {
        return map.getOrDefault(uuid, PlayerVampire.DEFAULT);
    }

    public void put(UUID uuid, PlayerVampire data) {
        map.put(uuid, data);
        setDirty();
    }

    public void setCursed(UUID uuid, boolean cursed) {
        put(uuid, get(uuid).withCursed(cursed));
    }

    public void setBlood(UUID uuid, float blood) {
        put(uuid, get(uuid).withBlood(blood));
    }

    public Collection<UUID> allUuids() {
        return Collections.unmodifiableSet(map.keySet());
    }
}
