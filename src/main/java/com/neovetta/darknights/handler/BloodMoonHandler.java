package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.network.BloodMoonSyncPacket;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Random;

public class BloodMoonHandler {

    private static boolean isBloodMoon = false;
    private static boolean wasNight = false;
    private static final Random RANDOM = new Random();

    private static int mobBuffTick = 0;
    private static int spawnTick = 0;

    // Cached clock holder — lazily initialised on first tick
    private static Holder<WorldClock> overworldClock = null;

    public static boolean isActive() {
        return isBloodMoon;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BloodMoonHandler::onServerTick);
    }

    private static Holder<WorldClock> getOverworldClock(MinecraftServer server) {
        if (overworldClock == null) {
            HolderLookup.RegistryLookup<WorldClock> reg =
                server.overworld().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
            overworldClock = reg.getOrThrow(WorldClocks.OVERWORLD);
        }
        return overworldClock;
    }

    private static void onServerTick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);

        long totalTicks = overworld.clockManager().getTotalTicks(clock);
        long time = totalTicks % 24000;
        int moonPhase = (int)((totalTicks / 24000) % 8);
        boolean isNight = time > 13000 && time < 23000;

        // Nightfall transition
        if (isNight && !wasNight) {
            if (!isBloodMoon && moonPhase != 0 && DarkNights.CONFIG.enableBloodMoon) {
                if (RANDOM.nextFloat() < DarkNights.CONFIG.bloodMoonChance) {
                    startBloodMoon(server);
                }
            }
        }

        // Dawn transition
        if (!isNight && wasNight && isBloodMoon) {
            endBloodMoon(server);
        }

        wasNight = isNight;

        if (!isBloodMoon) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        // Weakness to outdoor players
        for (ServerPlayer player : players) {
            if (isOutdoors(player)) {
                if (!player.hasEffect(MobEffects.WEAKNESS)) {
                    player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, true, false));
                }
            }
        }

        // Buff nearby hostiles every 30 ticks
        mobBuffTick++;
        if (mobBuffTick >= 30) {
            mobBuffTick = 0;
            for (ServerPlayer player : players) {
                buffNearbyHostiles(player);
            }
        }

        // Spawn extra hostile every 60 ticks
        spawnTick++;
        if (spawnTick >= 60) {
            spawnTick = 0;
            for (ServerPlayer player : players) {
                if (isOutdoors(player)) {
                    spawnExtraHostile(player);
                }
            }
        }
    }

    private static void startBloodMoon(MinecraftServer server) {
        isBloodMoon = true;
        DarkNights.LOGGER.info("Blood Moon rises!");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(
                Component.literal("A Blood Moon rises...").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                false
            );
            ServerPlayNetworking.send(player, new BloodMoonSyncPacket(true));
        }
    }

    private static void endBloodMoon(MinecraftServer server) {
        isBloodMoon = false;
        DarkNights.LOGGER.info("Blood Moon sets.");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.removeEffect(MobEffects.WEAKNESS);
            player.sendSystemMessage(
                Component.literal("The Blood Moon sets.").withStyle(ChatFormatting.GRAY),
                false
            );
            ServerPlayNetworking.send(player, new BloodMoonSyncPacket(false));
        }
    }

    private static void buffNearbyHostiles(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        AABB box = player.getBoundingBox().inflate(64, 32, 64);
        List<LivingEntity> hostiles = level.getEntities(
            EntityTypeTest.forClass(LivingEntity.class),
            box,
            e -> e.getType().getCategory() == MobCategory.MONSTER
        );
        for (LivingEntity mob : hostiles) {
            if (!mob.hasEffect(MobEffects.STRENGTH)) {
                mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0, true, false));
            }
            if (!mob.hasEffect(MobEffects.SPEED)) {
                mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0, true, false));
            }
        }
    }

    private static void spawnExtraHostile(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        if (level.dimension() != Level.OVERWORLD) return;

        double angle = RANDOM.nextDouble() * Math.PI * 2;
        double dist = 8 + RANDOM.nextDouble() * 12;
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;
        double y = player.getY();

        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.canSeeSky(pos)) return;

        EntityType<?> type = RANDOM.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON;
        net.minecraft.world.entity.Mob mob =
            (net.minecraft.world.entity.Mob) type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (mob == null) return;
        mob.teleportTo(x, y, z);
        mob.setYRot(RANDOM.nextFloat() * 360f);
        level.addFreshEntity(mob);
    }

    private static boolean isOutdoors(ServerPlayer player) {
        return ((ServerLevel) player.level()).canSeeSky(player.blockPosition().above());
    }
}
