package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.item.DarkNightsItems;
import com.neovetta.darknights.network.ZombieSyncPacket;
import com.neovetta.darknights.saveddata.LycanthropySavedData;
import com.neovetta.darknights.saveddata.ZombieSavedData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;

import java.util.List;
import java.util.Random;
import java.util.UUID;

public class ZombieHandler {

    private static final Identifier MOD_ID_ATTR = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "zombie");
    private static final Random RANDOM = new Random();

    private static final ResourceKey<LootTable> ZOMBIE_VILLAGER_LOOT =
        ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("entities/zombie_villager"));
    private static final ResourceKey<LootTable> DROWNED_LOOT =
        ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("entities/drowned"));

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
        ServerTickEvents.END_SERVER_TICK.register(ZombieHandler::onServerTick);
        UseItemCallback.EVENT.register(ZombieHandler::onUseItem);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(ZombieHandler::onAllowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(ZombieHandler::onAfterDeath);
        registerLootTables();
    }

    // -------------------------------------------------------------------------
    // Admin entry points
    // -------------------------------------------------------------------------

    public static void adminInfect(ServerPlayer player) {
        ZombieSavedData data = ZombieSavedData.get(player.level().getServer());
        doInfect(player, player.level().getServer(), data);
    }

    public static void adminCure(ServerPlayer player) {
        ZombieSavedData data = ZombieSavedData.get(player.level().getServer());
        if (data.get(player.getUUID()).isCursed()) {
            doCure(player, player.level().getServer(), data);
        }
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        if (!DarkNights.CONFIG.enableZombiePlague) return;

        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = overworld.clockManager().getTotalTicks(clock);
        long timeOfDay  = totalTicks % 24000;
        boolean isNight = timeOfDay > 13000 && timeOfDay < 23000;

        ZombieSavedData data = ZombieSavedData.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!data.get(player.getUUID()).isCursed()) continue;

            // Modifiers are transient — re-apply every tick so they survive relogs
            applyAttributeModifiers(player);

            sustainEffects(player, totalTicks, isNight);
        }
    }

    // -------------------------------------------------------------------------
    // Sustained effects
    // -------------------------------------------------------------------------

    private static void sustainEffects(ServerPlayer player, long totalTicks, boolean isNight) {
        if (totalTicks % 200 == 0) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, true, false));
            if (isNight) {
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 0, true, false));
            } else {
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 0, true, false));
            }
        }

        // Sunlight damage — outdoors + daytime, 0.5 HP/sec
        if (!isNight && totalTicks % 20 == 0 && isOutdoors(player)) {
            player.hurtServer((ServerLevel) player.level(), player.level().damageSources().generic(), 0.5f);
        }

        // Flesh craving — food below 6 triggers extra Weakness
        if (player.getFoodData().getFoodLevel() < 6) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1, true, false));
        }

        // Rotten flesh is proper food — remove the vanilla Hunger debuff reactively
        if (player.hasEffect(MobEffects.HUNGER)) {
            player.removeEffect(MobEffects.HUNGER);
        }

        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
    }

    // -------------------------------------------------------------------------
    // Infect / Cure
    // -------------------------------------------------------------------------

    public static void doInfect(ServerPlayer player, MinecraftServer server, ZombieSavedData data) {
        data.setCursed(player.getUUID(), true);
        applyAttributeModifiers(player);
        player.setHealth(player.getMaxHealth()); // fill new max immediately

        ServerLevel level = (ServerLevel) player.level();
        double x = player.getX(), y = player.getY() + 1.0, z = player.getZ();
        level.sendParticles(ParticleTypes.MYCELIUM, x, y, z, 30, 0.8, 0.8, 0.8, 0.03);
        level.playSound(null, player.blockPosition(),
            SoundEvents.ZOMBIE_AMBIENT, SoundSource.PLAYERS, 1.5f, 0.5f);

        player.sendSystemMessage(
            Component.literal("Your flesh begins to rot...").withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.BOLD),
            false);

        broadcastSync(server, player.getUUID(), true);
    }

    public static void doCure(ServerPlayer player, MinecraftServer server, ZombieSavedData data) {
        data.setCursed(player.getUUID(), false);
        removeAttributeModifiers(player);
        player.removeEffect(MobEffects.NIGHT_VISION);
        player.removeEffect(MobEffects.REGENERATION);
        player.removeEffect(MobEffects.WEAKNESS);

        if (player.getHealth() > 20f) player.setHealth(20f);

        player.sendSystemMessage(
            Component.literal("The infection recedes. You feel almost human again.").withStyle(ChatFormatting.GREEN),
            false);

        broadcastSync(server, player.getUUID(), false);
    }

    // -------------------------------------------------------------------------
    // Attribute modifiers
    // -------------------------------------------------------------------------

    private static void applyAttributeModifiers(ServerPlayer player) {
        applyMod(player, Attributes.MAX_HEALTH,          10.0);
        applyMod(player, Attributes.ATTACK_DAMAGE,        4.0);
        applyMod(player, Attributes.MOVEMENT_SPEED,      -0.02);
        applyMod(player, Attributes.ARMOR,                6.0);
        applyMod(player, Attributes.KNOCKBACK_RESISTANCE, 0.6);
    }

    private static void applyMod(ServerPlayer player,
            Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double amount) {
        var instance = player.getAttribute(attr);
        if (instance != null)
            instance.addOrUpdateTransientModifier(new AttributeModifier(MOD_ID_ATTR, amount, Operation.ADD_VALUE));
    }

    private static void removeAttributeModifiers(ServerPlayer player) {
        for (var attr : List.of(
                Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE, Attributes.MOVEMENT_SPEED,
                Attributes.ARMOR, Attributes.KNOCKBACK_RESISTANCE)) {
            var instance = player.getAttribute(attr);
            if (instance != null) instance.removeModifier(MOD_ID_ATTR);
        }
    }

    // -------------------------------------------------------------------------
    // UseItemCallback — infection via food, rotten flesh buff, potion reversal, cure
    // -------------------------------------------------------------------------

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        ZombieSavedData data = ZombieSavedData.get(sp.level().getServer());
        boolean isCursed = data.get(sp.getUUID()).isCursed();
        ItemStack stack = player.getItemInHand(hand);

        // Non-cursed players can contract plague via food
        if (!isCursed) {
            if (stack.getItem() == Items.ROTTEN_FLESH) {
                if (RANDOM.nextFloat() < DarkNights.CONFIG.rottenFleshInfectionChance) {
                    doInfect(sp, sp.level().getServer(), data);
                }
            } else if (stack.getItem() == DarkNightsItems.INFECTED_BRAIN) {
                if (RANDOM.nextFloat() < DarkNights.CONFIG.infectedBrainInfectionChance) {
                    doInfect(sp, sp.level().getServer(), data);
                }
            }
            return InteractionResult.PASS;
        }

        // Zombie player eats a golden apple — cures the plague
        if (stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
            doCure(sp, sp.level().getServer(), data);
            return InteractionResult.PASS; // let vanilla golden apple effects also apply
        }

        // Potion reversal — healing damages, harming heals
        if (stack.getItem() == Items.POTION) {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents != null) {
                boolean hasInstantHealth = false;
                boolean hasInstantDamage = false;
                int healthAmp = 0, damageAmp = 0;
                for (MobEffectInstance effect : contents.getAllEffects()) {
                    if (effect.getEffect() == MobEffects.INSTANT_HEALTH) {
                        hasInstantHealth = true;
                        healthAmp = effect.getAmplifier();
                    }
                    if (effect.getEffect() == MobEffects.INSTANT_DAMAGE) {
                        hasInstantDamage = true;
                        damageAmp = effect.getAmplifier();
                    }
                }
                if (hasInstantHealth) {
                    stack.shrink(1);
                    sp.hurtServer((ServerLevel) sp.level(), sp.level().damageSources().generic(), 4.0f * (healthAmp + 1));
                    sp.sendOverlayMessage(Component.literal("The healing potion burns!").withStyle(ChatFormatting.RED));
                    return InteractionResult.SUCCESS;
                }
                if (hasInstantDamage) {
                    stack.shrink(1);
                    sp.heal(4.0f * (damageAmp + 1));
                    sp.sendOverlayMessage(
                        Component.literal("The poison feeds your hunger.").withStyle(ChatFormatting.DARK_GREEN));
                    return InteractionResult.SUCCESS;
                }
            }
        }

        return InteractionResult.PASS;
    }

    // -------------------------------------------------------------------------
    // ALLOW_DAMAGE — fire heals, infection spread
    // -------------------------------------------------------------------------

    private static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        // Fire damage heals zombie-cursed players instead
        if (entity instanceof ServerPlayer sp) {
            ZombieSavedData data = ZombieSavedData.get(sp.level().getServer());
            if (data.get(sp.getUUID()).isCursed() && source.is(DamageTypeTags.IS_FIRE)) {
                sp.heal(amount * 0.5f);
                sp.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1, true, false));
                return false;
            }
        }

        // Infection spread — zombie player hits a non-cursed player
        if (entity instanceof ServerPlayer victim && source.getEntity() instanceof ServerPlayer attacker) {
            if (!DarkNights.CONFIG.enableZombiePlague) return true;
            ZombieSavedData data = ZombieSavedData.get(attacker.level().getServer());
            if (data.get(attacker.getUUID()).isCursed() && !data.get(victim.getUUID()).isCursed()) {
                if (RANDOM.nextFloat() < DarkNights.CONFIG.zombieInfectionChance) {
                    doInfect(victim, attacker.level().getServer(), data);
                    victim.sendSystemMessage(
                        Component.literal("You feel diseased...").withStyle(ChatFormatting.DARK_GREEN), false);
                }
            }
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // AFTER_DEATH — faction kill rewards
    // -------------------------------------------------------------------------

    private static void onAfterDeath(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof ServerPlayer dead)) return;
        if (!(source.getEntity() instanceof ServerPlayer killer)) return;

        MinecraftServer server = entity.level().getServer();
        ZombieSavedData zombie = ZombieSavedData.get(server);
        LycanthropySavedData lyc = LycanthropySavedData.get(server);

        // Werewolf kills zombie → food reward
        if (zombie.get(dead.getUUID()).isCursed()
                && lyc.get(killer.getUUID()).isTransformed()) {
            killer.getFoodData().eat(2, 0.5f);
            killer.sendOverlayMessage(
                Component.literal("Prey taken. The hunt feeds you.").withStyle(ChatFormatting.DARK_PURPLE));
        }

        // Zombie kills werewolf → adrenaline
        if (lyc.get(dead.getUUID()).isCursed()
                && zombie.get(killer.getUUID()).isCursed()) {
            killer.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 1200, 0, false, true));
            killer.sendOverlayMessage(
                Component.literal("Wolf blood surges through you!").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    // -------------------------------------------------------------------------
    // Loot tables — Infected Brain drop
    // -------------------------------------------------------------------------

    private static void registerLootTables() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (!source.isBuiltin()) return;
            if (ZOMBIE_VILLAGER_LOOT.equals(key) || DROWNED_LOOT.equals(key)) {
                tableBuilder.withPool(LootPool.lootPool()
                    .add(LootItem.lootTableItem(DarkNightsItems.INFECTED_BRAIN))
                    .when(LootItemRandomChanceCondition.randomChance(0.05f)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isOutdoors(ServerPlayer player) {
        return ((ServerLevel) player.level()).canSeeSky(player.blockPosition().above());
    }

    private static void broadcastSync(MinecraftServer server, UUID uuid, boolean isCursed) {
        ZombieSyncPacket packet = new ZombieSyncPacket(uuid, isCursed);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, packet);
        }
    }
}
