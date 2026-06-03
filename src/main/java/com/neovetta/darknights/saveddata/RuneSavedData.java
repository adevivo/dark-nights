package com.neovetta.darknights.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class RuneSavedData extends SavedData {

    public enum RuneType { NONE, FIRE, WATER, EARTH, AIR }

    private static final Codec<RuneType> RUNE_CODEC =
        Codec.STRING.xmap(
            s -> { try { return RuneType.valueOf(s); } catch (Exception e) { return RuneType.NONE; } },
            RuneType::name
        );

    public record PlayerRune(RuneType attunement, long cooldownTick) {
        public static final PlayerRune DEFAULT = new PlayerRune(RuneType.NONE, 0L);
        public static final Codec<PlayerRune> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            RUNE_CODEC.fieldOf("attunement").forGetter(PlayerRune::attunement),
            Codec.LONG.fieldOf("cooldownTick").forGetter(PlayerRune::cooldownTick)
        ).apply(inst, PlayerRune::new));

        public PlayerRune withAttunement(RuneType t) { return new PlayerRune(t, cooldownTick); }
        public PlayerRune withCooldown(long tick) { return new PlayerRune(attunement, tick); }
    }

    private record Entry(String uuid, PlayerRune data) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("uuid").forGetter(Entry::uuid),
            PlayerRune.CODEC.fieldOf("data").forGetter(Entry::data)
        ).apply(inst, Entry::new));
    }

    private static final Codec<RuneSavedData> CODEC =
        Entry.CODEC.listOf().xmap(
            list -> {
                RuneSavedData d = new RuneSavedData();
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

    private static final SavedDataType<RuneSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darknights", "rune"),
        RuneSavedData::new,
        CODEC,
        null
    );

    private final Map<UUID, PlayerRune> map = new HashMap<>();

    public RuneSavedData() {}

    public static RuneSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public PlayerRune get(UUID uuid) {
        return map.getOrDefault(uuid, PlayerRune.DEFAULT);
    }

    public void put(UUID uuid, PlayerRune data) {
        map.put(uuid, data);
        setDirty();
    }
}
