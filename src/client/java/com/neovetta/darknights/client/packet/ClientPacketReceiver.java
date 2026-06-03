package com.neovetta.darknights.client.packet;

import com.neovetta.darknights.network.BloodMoonSyncPacket;
import com.neovetta.darknights.network.TransformSyncPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClientPacketReceiver {

    public static boolean isBloodMoonActive = false;
    public static final Map<UUID, Boolean> transformedPlayers = new HashMap<>();

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
    }
}
