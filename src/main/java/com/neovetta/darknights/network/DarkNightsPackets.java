package com.neovetta.darknights.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class DarkNightsPackets {

    public static void registerServerbound() {
        // Client-bound packets registered here (server → client)
        PayloadTypeRegistry.clientboundPlay().register(BloodMoonSyncPacket.TYPE,  BloodMoonSyncPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TransformSyncPacket.TYPE,  TransformSyncPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ZombieSyncPacket.TYPE,     ZombieSyncPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VampireSyncPacket.TYPE,    VampireSyncPacket.CODEC);
    }
}
