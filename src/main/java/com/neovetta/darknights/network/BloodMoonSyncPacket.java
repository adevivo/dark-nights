package com.neovetta.darknights.network;

import com.neovetta.darknights.DarkNights;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BloodMoonSyncPacket(boolean active) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "blood_moon_sync");
    public static final CustomPacketPayload.Type<BloodMoonSyncPacket> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, BloodMoonSyncPacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.active()),
            buf -> new BloodMoonSyncPacket(buf.readBoolean())
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
