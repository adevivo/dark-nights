package com.neovetta.darknights.saveddata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class ZombieHordeSavedData extends SavedData {

    private final Map<UUID, UUID> mobToPlayer = new HashMap<>();
    private final Map<UUID, List<UUID>> playerToMobs = new HashMap<>();

    private record Bond(String mobUuid, String playerUuid) {
        static final Codec<Bond> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("mobUuid").forGetter(Bond::mobUuid),
            Codec.STRING.fieldOf("playerUuid").forGetter(Bond::playerUuid)
        ).apply(inst, Bond::new));
    }

    private static final Codec<ZombieHordeSavedData> CODEC = Bond.CODEC.listOf().xmap(
        bonds -> {
            ZombieHordeSavedData d = new ZombieHordeSavedData();
            for (Bond b : bonds) {
                try {
                    UUID mob    = UUID.fromString(b.mobUuid());
                    UUID player = UUID.fromString(b.playerUuid());
                    d.mobToPlayer.put(mob, player);
                    d.playerToMobs.computeIfAbsent(player, k -> new ArrayList<>()).add(mob);
                } catch (IllegalArgumentException ignored) {}
            }
            return d;
        },
        d -> d.mobToPlayer.entrySet().stream()
            .map(e -> new Bond(e.getKey().toString(), e.getValue().toString()))
            .toList()
    );

    private static final SavedDataType<ZombieHordeSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("darknights", "zombie_horde"),
        ZombieHordeSavedData::new,
        CODEC,
        null
    );

    public ZombieHordeSavedData() {}

    public static ZombieHordeSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean isFamiliar(UUID mobUuid) {
        return mobToPlayer.containsKey(mobUuid);
    }

    public UUID getFamiliarOwner(UUID mobUuid) {
        return mobToPlayer.get(mobUuid);
    }

    public List<UUID> getPlayerFamiliars(UUID playerUuid) {
        return Collections.unmodifiableList(
            playerToMobs.getOrDefault(playerUuid, Collections.emptyList()));
    }

    public int getFamiliarCount(UUID playerUuid) {
        return playerToMobs.getOrDefault(playerUuid, Collections.emptyList()).size();
    }

    public void attach(UUID mobUuid, UUID playerUuid) {
        detach(mobUuid);
        mobToPlayer.put(mobUuid, playerUuid);
        playerToMobs.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(mobUuid);
        setDirty();
    }

    public void detach(UUID mobUuid) {
        UUID owner = mobToPlayer.remove(mobUuid);
        if (owner != null) {
            List<UUID> list = playerToMobs.get(owner);
            if (list != null) {
                list.remove(mobUuid);
                if (list.isEmpty()) playerToMobs.remove(owner);
            }
            setDirty();
        }
    }

    public List<UUID> detachAll(UUID playerUuid) {
        List<UUID> mobs = playerToMobs.remove(playerUuid);
        if (mobs == null) return Collections.emptyList();
        List<UUID> copy = new ArrayList<>(mobs);
        for (UUID mob : copy) mobToPlayer.remove(mob);
        setDirty();
        return copy;
    }
}
