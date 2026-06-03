package com.neovetta.darknights.network;

import com.neovetta.darknights.DarkNights;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record ZombieSyncPacket(UUID playerUuid, boolean isZombieCursed) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "zombie_sync");
    public static final CustomPacketPayload.Type<ZombieSyncPacket> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, ZombieSyncPacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.playerUuid());
                buf.writeBoolean(pkt.isZombieCursed());
            },
            buf -> new ZombieSyncPacket(buf.readUUID(), buf.readBoolean())
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
