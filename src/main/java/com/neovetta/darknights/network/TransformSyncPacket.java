package com.neovetta.darknights.network;

import com.neovetta.darknights.DarkNights;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record TransformSyncPacket(UUID playerUuid, boolean isTransformed) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "transform_sync");
    public static final CustomPacketPayload.Type<TransformSyncPacket> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, TransformSyncPacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.playerUuid());
                buf.writeBoolean(pkt.isTransformed());
            },
            buf -> new TransformSyncPacket(buf.readUUID(), buf.readBoolean())
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
