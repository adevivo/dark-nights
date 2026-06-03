package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.item.DarkNightsItems;
import com.neovetta.darknights.saveddata.RuneSavedData;
import com.neovetta.darknights.saveddata.RuneSavedData.RuneType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

public class RuneHandler {

    // Cooldowns in ticks
    private static final long FIRE_COOLDOWN  = 600L;  // 30s
    private static final long WATER_COOLDOWN = 400L;  // 20s
    private static final long EARTH_COOLDOWN = 900L;  // 45s
    private static final long AIR_COOLDOWN   = 500L;  // 25s

    private static final Identifier FIRE_ATTR_ID =
        Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "rune_fire_attack");

    private static Holder<WorldClock> overworldClock = null;

    private static Holder<WorldClock> getOverworldClock(MinecraftServer server) {
        if (overworldClock == null) {
            HolderLookup.RegistryLookup<WorldClock> reg =
                server.overworld().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
            overworldClock = reg.getOrThrow(WorldClocks.OVERWORLD);
        }
        return overworldClock;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(RuneHandler::onServerTick);
        UseItemCallback.EVENT.register(RuneHandler::onUseItem);
    }

    // -------------------------------------------------------------------------
    // Main tick — apply passive effects
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        if (!DarkNights.CONFIG.enableRunicAttunement) return;

        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = overworld.clockManager().getTotalTicks(clock);

        RuneSavedData data = RuneSavedData.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RuneType rune = data.get(player.getUUID()).attunement();
            if (rune == RuneType.NONE) continue;

            if (totalTicks % 200 != 0) {
                // Only apply the FIRE attack modifier every tick (transient, idempotent)
                if (rune == RuneType.FIRE) applyFireAttackMod(player);
                continue;
            }

            switch (rune) {
                case FIRE -> {
                    player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600, 0, true, false));
                    applyFireAttackMod(player);
                }
                case WATER -> {
                    player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 600, 0, true, false));
                    if (player.isInWater()) {
                        player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 600, 0, true, false));
                    }
                }
                case EARTH -> {
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 600, 0, true, false));
                    if (player.getBlockY() < 32) {
                        player.addEffect(new MobEffectInstance(MobEffects.HASTE, 600, 0, true, false));
                    }
                }
                case AIR -> {
                    player.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0, true, false));
                }
                default -> {}
            }
        }
    }

    private static void applyFireAttackMod(ServerPlayer player) {
        var instance = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(
                new AttributeModifier(FIRE_ATTR_ID, 1.0, Operation.ADD_VALUE));
        }
    }

    private static void removeFireAttackMod(ServerPlayer player) {
        var instance = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (instance != null) instance.removeModifier(FIRE_ATTR_ID);
    }

    // -------------------------------------------------------------------------
    // UseItemCallback — attune or activate
    // -------------------------------------------------------------------------

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!DarkNights.CONFIG.enableRunicAttunement) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);
        RuneType held = runeTypeFor(stack.getItem());
        if (held == RuneType.NONE) return InteractionResult.PASS;

        RuneSavedData data = RuneSavedData.get(sp.level().getServer());
        RuneSavedData.PlayerRune current = data.get(sp.getUUID());

        if (current.attunement() != held) {
            // Attune to new rune
            if (current.attunement() == RuneType.FIRE) removeFireAttackMod(sp);
            data.put(sp.getUUID(), current.withAttunement(held));
            sp.sendSystemMessage(attuneMessage(held), false);
            sp.level().playSound(null, sp.blockPosition(),
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.8f, 1.2f);
        } else {
            // Trigger active ability
            activateRune(sp, held, data, current);
        }

        return InteractionResult.SUCCESS;
    }

    private static RuneType runeTypeFor(Item item) {
        if (item == DarkNightsItems.FIRE_RUNE)  return RuneType.FIRE;
        if (item == DarkNightsItems.WATER_RUNE) return RuneType.WATER;
        if (item == DarkNightsItems.EARTH_RUNE) return RuneType.EARTH;
        if (item == DarkNightsItems.AIR_RUNE)   return RuneType.AIR;
        return RuneType.NONE;
    }

    // -------------------------------------------------------------------------
    // Active abilities
    // -------------------------------------------------------------------------

    private static void activateRune(ServerPlayer player, RuneType rune,
            RuneSavedData data, RuneSavedData.PlayerRune current) {
        MinecraftServer server = player.level().getServer();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = server.overworld().clockManager().getTotalTicks(clock);
        long cooldown = cooldownFor(rune);

        if (totalTicks < current.cooldownTick()) {
            long remaining = (current.cooldownTick() - totalTicks) / 20;
            player.sendOverlayMessage(
                Component.literal("Cooldown: " + remaining + "s remaining").withStyle(ChatFormatting.GRAY));
            return;
        }

        switch (rune) {
            case FIRE  -> doFireActive(player);
            case WATER -> doWaterActive(player);
            case EARTH -> doEarthActive(player);
            case AIR   -> doAirActive(player);
            default    -> {}
        }

        data.put(player.getUUID(), current.withCooldown(totalTicks + cooldown));
    }

    // Fire — ignite the entity the player is looking at (within 8 blocks)
    private static void doFireActive(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        LivingEntity target = findTargetInLOS(player, level, 8.0);
        if (target != null) {
            target.setRemainingFireTicks(100); // 5 seconds
            player.sendOverlayMessage(
                Component.literal("Target ignited!").withStyle(ChatFormatting.GOLD));
            level.playSound(null, player.blockPosition(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0f, 0.8f);
        } else {
            player.sendOverlayMessage(
                Component.literal("No target in range.").withStyle(ChatFormatting.GRAY));
        }
    }

    // Water — cleanse fire/poison/wither from self, grant Regen I for 10s
    private static void doWaterActive(ServerPlayer player) {
        player.clearFire();
        player.removeEffect(MobEffects.POISON);
        player.removeEffect(MobEffects.WITHER);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0, false, true));
        player.sendOverlayMessage(
            Component.literal("Purified.").withStyle(ChatFormatting.AQUA));
        player.level().playSound(null, player.blockPosition(),
            SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.8f, 1.5f);
    }

    // Earth — shockwave all entities within 6 blocks away from player
    private static void doEarthActive(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        AABB box = player.getBoundingBox().inflate(6, 4, 6);
        List<Entity> nearby = level.getEntities(
            EntityTypeTest.forClass(Entity.class), box, e -> !e.is(player));

        for (Entity e : nearby) {
            Vec3 away = e.position().subtract(player.position());
            double dist = away.length();
            if (dist < 0.1) continue;
            Vec3 impulse = away.normalize().scale(2.5 * (1.0 - dist / 8.0));
            e.setDeltaMovement(
                e.getDeltaMovement().x + impulse.x,
                0.5,
                e.getDeltaMovement().z + impulse.z);
            e.hurtMarked = true;
        }
        player.sendOverlayMessage(
            Component.literal("Shockwave!").withStyle(ChatFormatting.GREEN));
        level.playSound(null, player.blockPosition(),
            SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.PLAYERS, 1.0f, 0.5f);
    }

    // Air — dash 8 blocks in look direction
    private static void doAirActive(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        double tx = player.getX() + look.x * 8;
        double tz = player.getZ() + look.z * 8;
        player.teleportTo((ServerLevel) player.level(),
            tx, player.getY(), tz,
            Collections.emptySet(), player.getYRot(), player.getXRot(), false);
        player.sendOverlayMessage(
            Component.literal("Dash!").withStyle(ChatFormatting.YELLOW));
        player.level().playSound(null, player.blockPosition(),
            SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.5f, 2.0f);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LivingEntity findTargetInLOS(ServerPlayer player, ServerLevel level, double range) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        AABB searchBox = new AABB(
            eyePos.x - range, eyePos.y - range, eyePos.z - range,
            eyePos.x + range, eyePos.y + range, eyePos.z + range);

        return level.getEntities(EntityTypeTest.forClass(LivingEntity.class), searchBox,
                e -> !e.is(player) && isInLOS(eyePos, look, e, range))
            .stream()
            .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
            .orElse(null);
    }

    private static boolean isInLOS(Vec3 eyePos, Vec3 lookDir, Entity entity, double range) {
        Vec3 toEntity = entity.getBoundingBox().getCenter().subtract(eyePos);
        if (toEntity.lengthSqr() > range * range) return false;
        return toEntity.normalize().dot(lookDir) > 0.7;
    }

    private static long cooldownFor(RuneType rune) {
        return switch (rune) {
            case FIRE  -> FIRE_COOLDOWN;
            case WATER -> WATER_COOLDOWN;
            case EARTH -> EARTH_COOLDOWN;
            case AIR   -> AIR_COOLDOWN;
            default    -> 0L;
        };
    }

    private static Component attuneMessage(RuneType rune) {
        return switch (rune) {
            case FIRE  -> Component.literal("Attuned to Fire. Flame flows through you.")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            case WATER -> Component.literal("Attuned to Water. The tide answers your call.")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
            case EARTH -> Component.literal("Attuned to Earth. The ground knows your name.")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
            case AIR   -> Component.literal("Attuned to Air. The wind carries you.")
                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
            default    -> Component.empty();
        };
    }
}
