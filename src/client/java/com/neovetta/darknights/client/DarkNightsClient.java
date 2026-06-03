package com.neovetta.darknights.client;

import com.neovetta.darknights.client.packet.ClientPacketReceiver;
import com.neovetta.darknights.client.render.WerewolfRenderLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

import java.util.Random;

public class DarkNightsClient implements ClientModInitializer {

    private static int particleTick = 0;
    private static final Random RANDOM = new Random();

    @Override
    public void onInitializeClient() {
        ClientPacketReceiver.register();
        WerewolfRenderLayer.register();
        registerBloodMoonParticles();
    }

    private static void registerBloodMoonParticles() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!ClientPacketReceiver.isBloodMoonActive) return;
            if (client.player == null || client.level == null) return;
            if (!client.level.canSeeSky(client.player.blockPosition().above())) return;

            particleTick++;
            if (particleTick < 3) return;
            particleTick = 0;

            // Scatter a few crimson spore particles above the player
            double px = client.player.getX();
            double py = client.player.getY() + 2.5;
            double pz = client.player.getZ();
            for (int i = 0; i < 3; i++) {
                double ox = (RANDOM.nextDouble() - 0.5) * 6;
                double oz = (RANDOM.nextDouble() - 0.5) * 6;
                client.level.addParticle(ParticleTypes.CRIMSON_SPORE, px + ox, py, pz + oz, 0, -0.05, 0);
            }
        });
    }
}
