package com.neovetta.darknights.network;

import com.neovetta.darknights.DarkNights;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record VampireSyncPacket(UUID playerUuid, boolean isCursed, float blood) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "vampire_sync");
    public static final CustomPacketPayload.Type<VampireSyncPacket> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, VampireSyncPacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.playerUuid());
                buf.writeBoolean(pkt.isCursed());
                buf.writeFloat(pkt.blood());
            },
            buf -> new VampireSyncPacket(buf.readUUID(), buf.readBoolean(), buf.readFloat())
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
