package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.network.TransformSyncPacket;
import com.neovetta.darknights.saveddata.LycanthropySavedData;
import com.neovetta.darknights.saveddata.LycanthropySavedData.PlayerLycanthropy;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

public class WerewolfHandler {

    private static final Identifier MOD_ID_ATTR = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "werewolf");

    // Wolf entity loot table
    private static final ResourceKey<LootTable> WOLF_LOOT =
        ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("entities/wolf"));

    // Cached clock holder
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
        ServerTickEvents.END_SERVER_TICK.register(WerewolfHandler::onServerTick);
        UseItemCallback.EVENT.register(WerewolfHandler::onUseItem);
        PlayerBlockBreakEvents.BEFORE.register(WerewolfHandler::onBeforeBlockBreak);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(WerewolfHandler::onAllowDamage);
        registerLootTable();
    }

    public static void adminTransform(ServerPlayer player) {
        LycanthropySavedData data = LycanthropySavedData.get(player.level().getServer());
        data.setCursed(player.getUUID(), true);
        doTransform(player, player.level().getServer(), data);
    }

    public static void adminRevert(ServerPlayer player) {
        LycanthropySavedData data = LycanthropySavedData.get(player.level().getServer());
        if (data.get(player.getUUID()).isTransformed()) {
            doRevert(player, player.level().getServer(), data);
        }
    }

    // -------------------------------------------------------------------------
    // Tick loop — transform / revert / sustain effects
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        if (!DarkNights.CONFIG.enableLycanthropy) return;

        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = overworld.clockManager().getTotalTicks(clock);
        long timeOfDay  = totalTicks % 24000;
        int  moonPhase  = (int)((totalTicks / 24000) % 8);
        boolean isNight = timeOfDay > 13000 && timeOfDay < 23000;

        LycanthropySavedData data = LycanthropySavedData.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            PlayerLycanthropy lycanthropy = data.get(uuid);
            if (!lycanthropy.isCursed()) continue;

            boolean outdoors = isOutdoors(player);
            boolean fullMoon = moonPhase == 0;
            boolean bloodMoon = BloodMoonHandler.isActive();

            boolean shouldTransform = isNight && outdoors && (fullMoon || (bloodMoon && DarkNights.CONFIG.transformOnBloodMoon));
            boolean shouldRevert    = lycanthropy.isTransformed() && (!isNight || (!fullMoon && !bloodMoon));

            if (shouldTransform && !lycanthropy.isTransformed()) {
                doTransform(player, server, data);
            } else if (shouldRevert) {
                doRevert(player, server, data);
            }

            if (lycanthropy.isTransformed()) {
                sustainEffects(player, totalTicks);
            }
        }
    }

    private static void sustainEffects(ServerPlayer player, long totalTicks) {
        // Re-apply Night Vision + Regeneration II every 200 ticks
        if (totalTicks % 200 == 0) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 1, true, false));
        }
        // Permanent hunger drain
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 40, 0, true, false));
    }

    // -------------------------------------------------------------------------
    // Transform
    // -------------------------------------------------------------------------

    private static void doTransform(ServerPlayer player, MinecraftServer server, LycanthropySavedData data) {
        applyAttributeModifiers(player);
        player.setHealth(player.getMaxHealth());

        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,  600, 0, true, false));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,  600, 1, true, false));

        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.blockPosition(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.0f, 0.8f);

        player.sendSystemMessage(
            Component.literal("The moon calls to your blood...").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD),
            false);

        data.setTransformed(player.getUUID(), true);
        broadcastTransform(server, player.getUUID(), true);
    }

    // -------------------------------------------------------------------------
    // Revert
    // -------------------------------------------------------------------------

    private static void doRevert(ServerPlayer player, MinecraftServer server, LycanthropySavedData data) {
        removeAttributeModifiers(player);
        player.removeEffect(MobEffects.NIGHT_VISION);
        player.removeEffect(MobEffects.REGENERATION);

        float clampedHealth = Math.min(player.getHealth(), 20f);
        if (player.getHealth() > 20f) player.setHealth(clampedHealth);

        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,  600, 0, true, false));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,  600, 0, true, false));

        player.sendSystemMessage(
            Component.literal("The curse releases you... for now.").withStyle(ChatFormatting.GRAY),
            false);

        data.setTransformed(player.getUUID(), false);
        data.incrementLunarAge(player.getUUID());
        broadcastTransform(server, player.getUUID(), false);
    }

    // -------------------------------------------------------------------------
    // Attribute modifiers
    // -------------------------------------------------------------------------

    private static void applyAttributeModifiers(ServerPlayer player) {
        applyMod(player, Attributes.MAX_HEALTH,           20.0);
        applyMod(player, Attributes.ATTACK_DAMAGE,         7.0);
        applyMod(player, Attributes.MOVEMENT_SPEED,        0.06);
        applyMod(player, Attributes.ARMOR,                12.0);
        applyMod(player, Attributes.ARMOR_TOUGHNESS,       4.0);
        applyMod(player, Attributes.KNOCKBACK_RESISTANCE,  0.4);
        applyMod(player, Attributes.ATTACK_KNOCKBACK,      2.0);
        applyMod(player, Attributes.JUMP_STRENGTH,         0.3);
        applyMod(player, Attributes.ATTACK_SPEED,          1.0);
    }

    private static void applyMod(ServerPlayer player,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
            double amount) {
        var instance = player.getAttribute(attr);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(new AttributeModifier(MOD_ID_ATTR, amount, Operation.ADD_VALUE));
        }
    }

    private static void removeAttributeModifiers(ServerPlayer player) {
        removeModFor(player, Attributes.MAX_HEALTH);
        removeModFor(player, Attributes.ATTACK_DAMAGE);
        removeModFor(player, Attributes.MOVEMENT_SPEED);
        removeModFor(player, Attributes.ARMOR);
        removeModFor(player, Attributes.ARMOR_TOUGHNESS);
        removeModFor(player, Attributes.KNOCKBACK_RESISTANCE);
        removeModFor(player, Attributes.ATTACK_KNOCKBACK);
        removeModFor(player, Attributes.JUMP_STRENGTH);
        removeModFor(player, Attributes.ATTACK_SPEED);
    }

    private static void removeModFor(ServerPlayer player,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        var instance = player.getAttribute(attr);
        if (instance != null) {
            instance.removeModifier(MOD_ID_ATTR);
        }
    }

    // -------------------------------------------------------------------------
    // UseItemCallback — weapons blocked, wolf meat infection, howl
    // -------------------------------------------------------------------------

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        LycanthropySavedData data = LycanthropySavedData.get(sp.level().getServer());
        PlayerLycanthropy lycanthropy = data.get(sp.getUUID());

        ItemStack stack = player.getItemInHand(hand);

        // Raw wolf meat — infection check regardless of transform state
        if (!lycanthropy.isCursed() && stack.getItem() == com.neovetta.darknights.item.DarkNightsItems.RAW_WOLF_MEAT) {
            if (new java.util.Random().nextFloat() < DarkNights.CONFIG.wolfMeatInfectionChance) {
                data.setCursed(sp.getUUID(), true);
                sp.sendSystemMessage(
                    Component.literal("Something stirs in your blood...").withStyle(ChatFormatting.DARK_PURPLE),
                    false);
            }
        }

        if (!lycanthropy.isTransformed()) return InteractionResult.PASS;

        // Transformed: block weapon/tool use
        if (!stack.isEmpty() && isWeaponOrTool(stack)) {
            sp.sendOverlayMessage(Component.literal("Your claws are too large.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }

        // Transformed + empty hand = howl
        if (stack.isEmpty() && hand == InteractionHand.MAIN_HAND) {
            doHowl(sp, data);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    private static void doHowl(ServerPlayer player, LycanthropySavedData data) {
        PlayerLycanthropy lycanthropy = data.get(player.getUUID());
        ServerLevel level = (ServerLevel) player.level();
        long currentTick = level.getGameTime();

        if (currentTick - lycanthropy.howlCooldownTick() < 1800) {
            int remaining = (int)((1800 - (currentTick - lycanthropy.howlCooldownTick())) / 20);
            player.sendOverlayMessage(
                Component.literal("Howl cooling down (" + remaining + "s)").withStyle(ChatFormatting.GRAY));
            return;
        }

        // Debuff nearby hostiles
        AABB box = player.getBoundingBox().inflate(15, 8, 15);
        level.getEntities(EntityTypeTest.forClass(LivingEntity.class), box,
            e -> e != player && e.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
        ).forEach(mob -> {
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,  160, 1, true, false));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,  160, 0, true, false));
        });

        // Buff owned tame wolves
        level.getEntities(EntityTypeTest.forClass(TamableAnimal.class), player.getBoundingBox().inflate(25, 10, 25),
            e -> { var ref = e.getOwnerReference(); return ref != null && player.getUUID().equals(ref.getUUID()); }
        ).forEach(wolf -> {
            wolf.addEffect(new MobEffectInstance(MobEffects.STRENGTH,     600, 1, true, false));
            wolf.addEffect(new MobEffectInstance(MobEffects.SPEED,        600, 1, true, false));
        });

        level.playSound(null, player.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.0f, 0.7f);
        player.sendSystemMessage(
            Component.literal("You unleash a fearsome howl!").withStyle(ChatFormatting.DARK_PURPLE), false);

        data.setHowlCooldown(player.getUUID(), currentTick);
    }

    private static boolean isWeaponOrTool(ItemStack stack) {
        String id = stack.getItem().getClass().getSimpleName().toLowerCase();
        return id.contains("sword") || id.contains("axe") || id.contains("pickaxe")
            || id.contains("shovel") || id.contains("hoe") || id.contains("trident");
    }

    // -------------------------------------------------------------------------
    // Block break — cancel while transformed
    // -------------------------------------------------------------------------

    private static boolean onBeforeBlockBreak(Level level, Player player,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        if (level.isClientSide()) return true;
        if (!(player instanceof ServerPlayer sp)) return true;

        LycanthropySavedData data = LycanthropySavedData.get(level.getServer());
        if (data.get(sp.getUUID()).isTransformed()) {
            sp.sendOverlayMessage(Component.literal("You cannot mine in wolf form.").withStyle(ChatFormatting.GRAY));
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Damage event — infection spread from transformed werewolf
    // -------------------------------------------------------------------------

    private static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer victim)) return true;
        if (!(source.getEntity() instanceof ServerPlayer attacker)) return true;

        LycanthropySavedData data = LycanthropySavedData.get(attacker.level().getServer());
        if (!data.get(attacker.getUUID()).isTransformed()) return true;
        if (data.get(victim.getUUID()).isCursed()) return true;
        if (!DarkNights.CONFIG.enableLycanthropy) return true;

        if (new java.util.Random().nextFloat() < DarkNights.CONFIG.infectionChance) {
            data.setCursed(victim.getUUID(), true);
            victim.sendSystemMessage(
                Component.literal("Something stirs in your blood...").withStyle(ChatFormatting.DARK_PURPLE),
                false);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Wolf meat loot table injection
    // -------------------------------------------------------------------------

    private static void registerLootTable() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (source.isBuiltin() && WOLF_LOOT.equals(key)) {
                LootPool.Builder pool = LootPool.lootPool()
                    .add(LootItem.lootTableItem(com.neovetta.darknights.item.DarkNightsItems.RAW_WOLF_MEAT))
                    .when(LootItemRandomChanceCondition.randomChance(0.4f));
                tableBuilder.withPool(pool);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void broadcastTransform(MinecraftServer server, UUID uuid, boolean transformed) {
        TransformSyncPacket packet = new TransformSyncPacket(uuid, transformed);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(p, packet);
        }
    }

    private static boolean isOutdoors(ServerPlayer player) {
        return ((ServerLevel) player.level()).canSeeSky(player.blockPosition().above());
    }
}
