package com.neovetta.darknights.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.util.ExtraCodecs;

import java.util.*;

public class LycanthropySavedData extends SavedData {

    public record PlayerLycanthropy(
        boolean isCursed,
        boolean isTransformed,
        int lunarAge,
        long howlCooldownTick
    ) {
        public static final Codec<PlayerLycanthropy> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.BOOL.fieldOf("isCursed").forGetter(PlayerLycanthropy::isCursed),
            Codec.BOOL.fieldOf("isTransformed").forGetter(PlayerLycanthropy::isTransformed),
            Codec.INT.fieldOf("lunarAge").forGetter(PlayerLycanthropy::lunarAge),
            Codec.LONG.fieldOf("howlCooldownTick").forGetter(PlayerLycanthropy::howlCooldownTick)
        ).apply(inst, PlayerLycanthropy::new));

        public static final PlayerLycanthropy DEFAULT =
            new PlayerLycanthropy(false, false, 0, 0L);

        public PlayerLycanthropy withCursed(boolean cursed) {
            return new PlayerLycanthropy(cursed, isTransformed, lunarAge, howlCooldownTick);
        }
        public PlayerLycanthropy withTransformed(boolean t) {
            return new PlayerLycanthropy(isCursed, t, lunarAge, howlCooldownTick);
        }
        public PlayerLycanthropy withLunarAge(int age) {
            return new PlayerLycanthropy(isCursed, isTransformed, age, howlCooldownTick);
        }
        public PlayerLycanthropy withHowlCooldown(long tick) {
            return new PlayerLycanthropy(isCursed, isTransformed, lunarAge, tick);
        }
    }

    // Record codec: List of [uuid-string, PlayerLycanthropy] pairs
    private record Entry(String uuid, PlayerLycanthropy data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
            PlayerLycanthropy.CODEC.fieldOf("data").forGetter(Entry::data)
        ).apply(inst, Entry::new));
    }

    private static final Codec<LycanthropySavedData> CODEC =
        Entry.CODEC.listOf().xmap(
            list -> {
                LycanthropySavedData d = new LycanthropySavedData();
                for (Entry e : list) {
                    try {
                        d.map.put(UUID.fromString(e.uuid()), e.data());
                    } catch (IllegalArgumentException ignored) {}
                }
                return d;
            },
            d -> d.map.entrySet().stream()
                .map(e -> new Entry(e.getKey().toString(), e.getValue()))
                .toList()
        );

    private static final SavedDataType<LycanthropySavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darknights", "lycanthropy"),
        LycanthropySavedData::new,
        CODEC,
        null
    );

    private final Map<UUID, PlayerLycanthropy> map = new HashMap<>();

    public LycanthropySavedData() {}

    public static LycanthropySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public PlayerLycanthropy get(UUID uuid) {
        return map.getOrDefault(uuid, PlayerLycanthropy.DEFAULT);
    }

    public void put(UUID uuid, PlayerLycanthropy data) {
        map.put(uuid, data);
        setDirty();
    }

    public void setCursed(UUID uuid, boolean cursed) {
        put(uuid, get(uuid).withCursed(cursed));
    }

    public void setTransformed(UUID uuid, boolean transformed) {
        put(uuid, get(uuid).withTransformed(transformed));
    }

    public void incrementLunarAge(UUID uuid) {
        PlayerLycanthropy p = get(uuid);
        put(uuid, p.withLunarAge(p.lunarAge() + 1));
    }

    public void setHowlCooldown(UUID uuid, long tick) {
        put(uuid, get(uuid).withHowlCooldown(tick));
    }

    public Collection<UUID> allUuids() {
        return Collections.unmodifiableSet(map.keySet());
    }
}
