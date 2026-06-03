package com.neovetta.darknights.client.packet;

import com.neovetta.darknights.network.BloodMoonSyncPacket;
import com.neovetta.darknights.network.TransformSyncPacket;
import com.neovetta.darknights.network.VampireSyncPacket;
import com.neovetta.darknights.network.ZombieSyncPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClientPacketReceiver {

    public static boolean isBloodMoonActive = false;
    public static final Map<UUID, Boolean> transformedPlayers = new HashMap<>();
    public static final Map<UUID, Boolean> zombiePlayers = new HashMap<>();
    public static final Map<UUID, Boolean> vampirePlayers = new HashMap<>();

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BloodMoonSyncPacket.TYPE, (payload, context) ->
            context.client().execute(() -> isBloodMoonActive = payload.active())
        );

        ClientPlayNetworking.registerGlobalReceiver(TransformSyncPacket.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.isTransformed()) {
                    transformedPlayers.put(payload.playerUuid(), true);
                } else {
                    transformedPlayers.remove(payload.playerUuid());
                }
            })
        );

        ClientPlayNetworking.registerGlobalReceiver(ZombieSyncPacket.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.isZombieCursed()) {
                    zombiePlayers.put(payload.playerUuid(), true);
                } else {
                    zombiePlayers.remove(payload.playerUuid());
                }
            })
        );

        ClientPlayNetworking.registerGlobalReceiver(VampireSyncPacket.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.isCursed()) {
                    vampirePlayers.put(payload.playerUuid(), true);
                } else {
                    vampirePlayers.remove(payload.playerUuid());
                }
            })
        );
    }
}
