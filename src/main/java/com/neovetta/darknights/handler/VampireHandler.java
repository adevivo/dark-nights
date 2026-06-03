package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.item.DarkNightsItems;
import com.neovetta.darknights.network.VampireSyncPacket;
import com.neovetta.darknights.saveddata.VampireSavedData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Random;
import java.util.UUID;

public class VampireHandler {

    private static final Random RANDOM = new Random();

    private static final ResourceKey<LootTable> BAT_LOOT =
        ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath("minecraft", "entities/bat"));

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
        ServerTickEvents.END_SERVER_TICK.register(VampireHandler::onServerTick);
        UseItemCallback.EVENT.register(VampireHandler::onUseItem);
        UseEntityCallback.EVENT.register(VampireHandler::onUseEntity);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(VampireHandler::onAllowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(VampireHandler::onAfterDeath);
        registerLootTables();
    }

    // -------------------------------------------------------------------------
    // Admin entry points
    // -------------------------------------------------------------------------

    public static void adminInfect(ServerPlayer player) {
        VampireSavedData data = VampireSavedData.get(player.level().getServer());
        doInfect(player, player.level().getServer(), data);
    }

    public static void adminCure(ServerPlayer player) {
        VampireSavedData data = VampireSavedData.get(player.level().getServer());
        if (data.get(player.getUUID()).isCursed()) {
            doCure(player, player.level().getServer(), data);
        }
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        if (!DarkNights.CONFIG.enableVampire) return;

        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = overworld.clockManager().getTotalTicks(clock);
        long timeOfDay  = totalTicks % 24000;
        boolean isNight = timeOfDay > 13000 && timeOfDay < 23000;

        VampireSavedData data = VampireSavedData.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VampireSavedData.PlayerVampire vamp = data.get(player.getUUID());
            if (!vamp.isCursed()) continue;

            // Drain blood every tick
            float newBlood = Math.max(0f, vamp.blood() - 0.002f);
            if (newBlood != vamp.blood()) {
                data.put(player.getUUID(), vamp.withBlood(newBlood));
                vamp = data.get(player.getUUID());
            }

            // Effect management — re-evaluate every 200 ticks
            if (totalTicks % 200 == 0) {
                if (isNight && newBlood > 0) {
                    player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, true, false));
                    player.addEffect(new MobEffectInstance(MobEffects.SPEED,        600, 1, true, false));
                    player.addEffect(new MobEffectInstance(MobEffects.STRENGTH,     600, 0, true, false));
                    player.removeEffect(MobEffects.WEAKNESS);
                } else if (newBlood == 0) {
                    // Blood depleted — lose passives, gain Weakness around the clock
                    player.removeEffect(MobEffects.NIGHT_VISION);
                    player.removeEffect(MobEffects.SPEED);
                    player.removeEffect(MobEffects.STRENGTH);
                    player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 0, true, false));
                } else {
                    // Daytime with blood — no passives, no debuffs
                    player.removeEffect(MobEffects.NIGHT_VISION);
                    player.removeEffect(MobEffects.SPEED);
                    player.removeEffect(MobEffects.STRENGTH);
                    player.removeEffect(MobEffects.WEAKNESS);
                }
            }

            // Sunlight damage: outdoors + daytime + no helmet → 1 HP/sec
            if (!isNight && totalTicks % 20 == 0 && isOutdoors(player)) {
                boolean hasHelmet = !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
                if (!hasHelmet) {
                    player.hurtServer((ServerLevel) player.level(),
                        player.level().damageSources().generic(), 1.0f);
                    player.sendOverlayMessage(
                        Component.literal("The sun burns your flesh!").withStyle(ChatFormatting.DARK_RED));
                }
            }

            // Blood bar on action bar when depleted, every 20 ticks
            if (newBlood < 0.8f && totalTicks % 20 == 0) {
                player.sendOverlayMessage(buildBloodBar(newBlood));
            }

            // Periodic client sync
            if (totalTicks % 40 == 0) {
                syncVampire(server, player, vamp);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Infect / Cure
    // -------------------------------------------------------------------------

    public static void doInfect(ServerPlayer player, MinecraftServer server, VampireSavedData data) {
        data.put(player.getUUID(), new VampireSavedData.PlayerVampire(true, 1.0f));
        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.blockPosition(),
            SoundEvents.BAT_AMBIENT, SoundSource.PLAYERS, 1.5f, 0.5f);
        player.sendSystemMessage(
            Component.literal("A cold hunger awakens within you...")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        syncVampire(server, player, data.get(player.getUUID()));
    }

    public static void doCure(ServerPlayer player, MinecraftServer server, VampireSavedData data) {
        data.put(player.getUUID(), VampireSavedData.PlayerVampire.DEFAULT);
        player.removeEffect(MobEffects.NIGHT_VISION);
        player.removeEffect(MobEffects.SPEED);
        player.removeEffect(MobEffects.STRENGTH);
        player.removeEffect(MobEffects.WEAKNESS);
        player.sendSystemMessage(
            Component.literal("The darkness lifts. The hunger is gone.").withStyle(ChatFormatting.YELLOW), false);
        syncVampire(server, player, data.get(player.getUUID()));
    }

    // -------------------------------------------------------------------------
    // UseItemCallback — Vampire Fang infection + Holy Water cure
    // -------------------------------------------------------------------------

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!DarkNights.CONFIG.enableVampire) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);
        VampireSavedData data = VampireSavedData.get(sp.level().getServer());
        VampireSavedData.PlayerVampire vamp = data.get(sp.getUUID());

        if (stack.getItem() == DarkNightsItems.VAMPIRE_FANG) {
            if (!vamp.isCursed()) {
                if (RANDOM.nextFloat() < DarkNights.CONFIG.vampireFangInfectionChance) {
                    doInfect(sp, sp.level().getServer(), data);
                } else {
                    sp.sendSystemMessage(
                        Component.literal("The fang pricks your tongue but nothing stirs.")
                            .withStyle(ChatFormatting.GRAY), false);
                }
            } else {
                sp.sendSystemMessage(
                    Component.literal("The fang crumbles to dust in your undead hands.")
                        .withStyle(ChatFormatting.GRAY), false);
            }
            stack.shrink(1);
            return InteractionResult.SUCCESS;
        }

        if (stack.getItem() == DarkNightsItems.HOLY_WATER) {
            if (vamp.isCursed()) {
                if (isDay(sp) && isOutdoors(sp)) {
                    doCure(sp, sp.level().getServer(), data);
                    level.playSound(null, sp.blockPosition(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.5f);
                } else {
                    sp.sendOverlayMessage(
                        Component.literal("The holy water has no power in darkness.")
                            .withStyle(ChatFormatting.GRAY));
                }
            } else {
                sp.sendSystemMessage(
                    Component.literal("The holy water smells faintly of garlic. Nothing happens.")
                        .withStyle(ChatFormatting.GRAY), false);
            }
            stack.shrink(1);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    // -------------------------------------------------------------------------
    // UseEntityCallback — feeding on passive mobs (bare-hand right-click)
    // -------------------------------------------------------------------------

    private static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand,
            Entity entity, EntityHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (!(entity instanceof Animal animal)) return InteractionResult.PASS;
        if (!DarkNights.CONFIG.enableVampire) return InteractionResult.PASS;

        VampireSavedData data = VampireSavedData.get(sp.level().getServer());
        VampireSavedData.PlayerVampire vamp = data.get(sp.getUUID());
        if (!vamp.isCursed()) return InteractionResult.PASS;

        ServerLevel serverLevel = (ServerLevel) world;
        animal.hurtServer(serverLevel, serverLevel.damageSources().playerAttack(sp), 2.0f);
        serverLevel.playSound(null, sp.blockPosition(),
            SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.8f, 0.5f);

        float newBlood = Math.min(1.0f, vamp.blood() + 0.2f);
        data.put(sp.getUUID(), vamp.withBlood(newBlood));
        sp.sendOverlayMessage(buildBloodBar(newBlood));
        syncVampire(sp.level().getServer(), sp, data.get(sp.getUUID()));

        return InteractionResult.SUCCESS;
    }

    // -------------------------------------------------------------------------
    // ALLOW_DAMAGE — infection spread on hit
    // -------------------------------------------------------------------------

    private static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer victim)) return true;
        if (!(source.getEntity() instanceof ServerPlayer attacker)) return true;
        if (!DarkNights.CONFIG.enableVampire) return true;

        MinecraftServer server = attacker.level().getServer();
        VampireSavedData data = VampireSavedData.get(server);
        if (!data.get(attacker.getUUID()).isCursed()) return true;
        if (data.get(victim.getUUID()).isCursed()) return true;

        if (RANDOM.nextFloat() < DarkNights.CONFIG.vampireAttackInfectionChance) {
            doInfect(victim, server, data);
            victim.sendSystemMessage(
                Component.literal("The vampire's touch courses through your veins...")
                    .withStyle(ChatFormatting.DARK_RED), false);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // AFTER_DEATH — kill rewards
    // -------------------------------------------------------------------------

    private static void onAfterDeath(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof ServerPlayer)) return;
        if (!(source.getEntity() instanceof ServerPlayer killer)) return;

        MinecraftServer server = entity.level().getServer();
        VampireSavedData data = VampireSavedData.get(server);
        if (!data.get(killer.getUUID()).isCursed()) return;

        float newBlood = Math.min(1.0f, data.get(killer.getUUID()).blood() + 0.4f);
        data.put(killer.getUUID(), data.get(killer.getUUID()).withBlood(newBlood));
        killer.sendOverlayMessage(
            Component.literal("The kill sates your hunger.").withStyle(ChatFormatting.DARK_RED));
        syncVampire(server, killer, data.get(killer.getUUID()));
    }

    // -------------------------------------------------------------------------
    // Loot tables — Vampire Fang drops from bats (10%)
    // -------------------------------------------------------------------------

    private static void registerLootTables() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (!source.isBuiltin()) return;
            if (BAT_LOOT.equals(key)) {
                tableBuilder.withPool(LootPool.lootPool()
                    .add(LootItem.lootTableItem(DarkNightsItems.VAMPIRE_FANG))
                    .when(LootItemRandomChanceCondition.randomChance(0.10f)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isDay(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        long timeOfDay = server.overworld().clockManager().getTotalTicks(getOverworldClock(server)) % 24000;
        return timeOfDay < 13000 || timeOfDay >= 23000;
    }

    private static boolean isOutdoors(ServerPlayer player) {
        return ((ServerLevel) player.level()).canSeeSky(player.blockPosition().above());
    }

    private static Component buildBloodBar(float blood) {
        int filled = Math.round(blood * 10);
        StringBuilder bar = new StringBuilder("❤ ");
        for (int i = 0; i < 10; i++) bar.append(i < filled ? '▓' : '░');
        bar.append(String.format(" %.0f%%", blood * 100));
        return Component.literal(bar.toString())
            .withStyle(blood < 0.25f ? ChatFormatting.DARK_RED : ChatFormatting.RED);
    }

    private static void syncVampire(MinecraftServer server, ServerPlayer player,
            VampireSavedData.PlayerVampire vamp) {
        VampireSyncPacket packet = new VampireSyncPacket(player.getUUID(), vamp.isCursed(), vamp.blood());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, packet);
        }
    }
}
