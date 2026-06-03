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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class WerewolfHandler {

    private static final Identifier MOD_ID_ATTR = Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "werewolf");
    private static final Random RANDOM = new Random();

    private static final ResourceKey<LootTable> WOLF_LOOT =
        ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("entities/wolf"));

    // Armor slots saved/restored on transform — order matters for restore
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    // Raw meats that trigger Meat Feast
    private static final java.util.Set<net.minecraft.world.item.Item> RAW_MEATS = java.util.Set.of(
        Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.RABBIT, Items.MUTTON
    );
    private static final java.util.Set<net.minecraft.world.item.Item> COOKED_MEATS = java.util.Set.of(
        Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN,
        Items.COOKED_RABBIT, Items.COOKED_MUTTON
    );

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
    // Tick loop
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

            boolean outdoors  = isOutdoors(player);
            boolean fullMoon  = moonPhase == 0;
            boolean bloodMoon = BloodMoonHandler.isActive();

            boolean shouldTransform = isNight && outdoors
                && (fullMoon || (bloodMoon && DarkNights.CONFIG.transformOnBloodMoon));
            boolean shouldRevert = lycanthropy.isTransformed()
                && (!isNight || (!fullMoon && !bloodMoon));

            if (shouldTransform && !lycanthropy.isTransformed()) {
                doTransform(player, server, data);
            } else if (shouldRevert) {
                doRevert(player, server, data);
            }

            if (data.get(uuid).isTransformed()) {
                sustainEffects(player, totalTicks, data);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sustained effects — called every tick while transformed
    // -------------------------------------------------------------------------

    private static void sustainEffects(ServerPlayer player, long totalTicks, LycanthropySavedData data) {
        // Night Vision + Regeneration II re-applied every 200 ticks
        if (totalTicks % 200 == 0) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 1, true, false));
        }

        // Feral Hunger — constant food drain
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 40, 0, true, false));

        // No flying in wolf form
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }

        // Keen Scent — hostile mobs within 32 blocks glow, capped at 20
        keenScent(player);

        // Pack Leader (passive) — buff owned wolves every 4 seconds
        if (totalTicks % 80 == 0) {
            packLeaderAura(player);
        }
    }

    // Keen Scent: nearest 20 hostile mobs within 32 blocks emit Glowing outline
    private static void keenScent(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        AABB box = player.getBoundingBox().inflate(32, 16, 32);
        List<LivingEntity> hostiles = level.getEntities(
            EntityTypeTest.forClass(LivingEntity.class), box,
            e -> e.getType().getCategory() == MobCategory.MONSTER
        );
        // Sort by distance, take closest 20
        hostiles.stream()
            .sorted((a, b) -> Double.compare(
                a.distanceToSqr(player), b.distanceToSqr(player)))
            .limit(20)
            .forEach(mob -> mob.addEffect(
                new MobEffectInstance(MobEffects.GLOWING, 3, 0, true, false)));
    }

    // Pack Leader aura: owned tame animals within 20 blocks get a persistent combat buff
    private static void packLeaderAura(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        level.getEntities(
            EntityTypeTest.forClass(TamableAnimal.class),
            player.getBoundingBox().inflate(20, 8, 20),
            e -> { var ref = e.getOwnerReference(); return ref != null && player.getUUID().equals(ref.getUUID()); }
        ).forEach(wolf -> {
            wolf.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 120, 0, true, false));
            wolf.addEffect(new MobEffectInstance(MobEffects.SPEED,    120, 0, true, false));
        });
    }

    // -------------------------------------------------------------------------
    // Transform
    // -------------------------------------------------------------------------

    private static void doTransform(ServerPlayer player, MinecraftServer server, LycanthropySavedData data) {
        // Strip and save armor before applying modifiers
        saveAndStripArmor(player, data);

        applyAttributeModifiers(player);
        player.setHealth(player.getMaxHealth());

        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600, 0, true, false));
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 1, true, false));

        ServerLevel level = (ServerLevel) player.level();
        double x = player.getX(), y = player.getY() + 1.0, z = player.getZ();

        // Particle burst
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 30, 1.0, 1.0, 1.0, 0.08);
        level.sendParticles(ParticleTypes.SMOKE,           x, y, z, 20, 0.8, 0.8, 0.8, 0.05);

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
        player.removeEffect(MobEffects.GLOWING);

        if (player.getHealth() > 20f) player.setHealth(20f);

        // Restore saved armor
        restoreArmor(player, data);

        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 600, 0, true, false));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 0, true, false));

        player.sendSystemMessage(
            Component.literal("You wake at dawn. Your claws are bloody.").withStyle(ChatFormatting.GRAY),
            false);

        data.setTransformed(player.getUUID(), false);
        data.incrementLunarAge(player.getUUID());
        broadcastTransform(server, player.getUUID(), false);
    }

    // -------------------------------------------------------------------------
    // Armor strip / restore
    // -------------------------------------------------------------------------

    private static void saveAndStripArmor(ServerPlayer player, LycanthropySavedData data) {
        List<ItemStack> saved = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            saved.add(stack.copy());
            player.setItemSlot(slot, ItemStack.EMPTY);
        }
        data.saveClearArmor(player.getUUID(), saved);
    }

    private static void restoreArmor(ServerPlayer player, LycanthropySavedData data) {
        List<ItemStack> saved = data.getSavedArmor(player.getUUID());
        if (saved.isEmpty()) return;
        for (int i = 0; i < ARMOR_SLOTS.length && i < saved.size(); i++) {
            ItemStack stack = saved.get(i);
            if (!stack.isEmpty()) {
                player.setItemSlot(ARMOR_SLOTS[i], stack);
            }
        }
        data.clearSavedArmor(player.getUUID());
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
            Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double amount) {
        var instance = player.getAttribute(attr);
        if (instance != null)
            instance.addOrUpdateTransientModifier(new AttributeModifier(MOD_ID_ATTR, amount, Operation.ADD_VALUE));
    }

    private static void removeAttributeModifiers(ServerPlayer player) {
        for (var attr : List.of(
                Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE, Attributes.MOVEMENT_SPEED,
                Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.KNOCKBACK_RESISTANCE,
                Attributes.ATTACK_KNOCKBACK, Attributes.JUMP_STRENGTH, Attributes.ATTACK_SPEED)) {
            var instance = player.getAttribute(attr);
            if (instance != null) instance.removeModifier(MOD_ID_ATTR);
        }
    }

    // -------------------------------------------------------------------------
    // UseItemCallback — infection, Meat Feast, weapon block, howl
    // -------------------------------------------------------------------------

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        LycanthropySavedData data = LycanthropySavedData.get(sp.level().getServer());
        PlayerLycanthropy lycanthropy = data.get(sp.getUUID());
        ItemStack stack = player.getItemInHand(hand);

        // Wolf meat infection — always checked, regardless of transform state
        if (!lycanthropy.isCursed()
                && stack.getItem() == com.neovetta.darknights.item.DarkNightsItems.RAW_WOLF_MEAT) {
            if (RANDOM.nextFloat() < DarkNights.CONFIG.wolfMeatInfectionChance) {
                data.setCursed(sp.getUUID(), true);
                sp.sendSystemMessage(
                    Component.literal("Something stirs in your blood...").withStyle(ChatFormatting.DARK_PURPLE),
                    false);
            }
        }

        if (!lycanthropy.isTransformed()) return InteractionResult.PASS;

        // Meat Feast — bonus effect when eating raw or cooked meat while transformed
        if (RAW_MEATS.contains(stack.getItem())) {
            sp.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0, false, false));
            sp.getFoodData().eat(4, 0.8f); // +4 saturation bonus on top of vanilla nutrition
        } else if (COOKED_MEATS.contains(stack.getItem())) {
            sp.getFoodData().eat(1, 0.3f); // smaller bonus for cooked — thematic preference for raw
        }

        // Block weapon / tool use
        if (!stack.isEmpty() && isWeaponOrTool(stack)) {
            sp.sendOverlayMessage(Component.literal("Your claws are too large.").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }

        // Empty-hand right-click = Feral Howl
        if (stack.isEmpty() && hand == InteractionHand.MAIN_HAND) {
            doHowl(sp, data);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    // -------------------------------------------------------------------------
    // Feral Howl
    // -------------------------------------------------------------------------

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

        // Debuff hostile mobs within 15 blocks
        level.getEntities(
            EntityTypeTest.forClass(LivingEntity.class),
            player.getBoundingBox().inflate(15, 8, 15),
            e -> e != player && e.getType().getCategory() == MobCategory.MONSTER
        ).forEach(mob -> {
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 160, 1, true, false));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 0, true, false));
        });

        // Buff owned tame animals within 25 blocks — stronger than the passive Pack Leader
        level.getEntities(
            EntityTypeTest.forClass(TamableAnimal.class),
            player.getBoundingBox().inflate(25, 10, 25),
            e -> { var ref = e.getOwnerReference(); return ref != null && player.getUUID().equals(ref.getUUID()); }
        ).forEach(wolf -> {
            wolf.addEffect(new MobEffectInstance(MobEffects.STRENGTH,          600, 1, true, false));
            wolf.addEffect(new MobEffectInstance(MobEffects.SPEED,             600, 1, true, false));
            wolf.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 600, 2, true, false));
        });

        // Particle burst at player
        double x = player.getX(), y = player.getY() + 1.0, z = player.getZ();
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 15, 1.5, 1.0, 1.5, 0.1);

        level.playSound(null, player.blockPosition(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.0f, 0.7f);
        player.sendSystemMessage(
            Component.literal("You unleash a fearsome howl!").withStyle(ChatFormatting.DARK_PURPLE), false);

        data.setHowlCooldown(player.getUUID(), currentTick);
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

        if (LycanthropySavedData.get(level.getServer()).get(sp.getUUID()).isTransformed()) {
            sp.sendOverlayMessage(Component.literal("You cannot mine in wolf form.").withStyle(ChatFormatting.GRAY));
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Damage — infection spread from transformed werewolf
    // -------------------------------------------------------------------------

    private static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer victim)) return true;
        if (!(source.getEntity() instanceof ServerPlayer attacker)) return true;
        if (!DarkNights.CONFIG.enableLycanthropy) return true;

        LycanthropySavedData data = LycanthropySavedData.get(attacker.level().getServer());
        if (!data.get(attacker.getUUID()).isTransformed()) return true;
        if (data.get(victim.getUUID()).isCursed()) return true;

        if (RANDOM.nextFloat() < DarkNights.CONFIG.infectionChance) {
            data.setCursed(victim.getUUID(), true);
            victim.sendSystemMessage(
                Component.literal("Something stirs in your blood...").withStyle(ChatFormatting.DARK_PURPLE),
                false);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Loot table — raw wolf meat drop
    // -------------------------------------------------------------------------

    private static void registerLootTable() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (source.isBuiltin() && WOLF_LOOT.equals(key)) {
                tableBuilder.withPool(LootPool.lootPool()
                    .add(LootItem.lootTableItem(com.neovetta.darknights.item.DarkNightsItems.RAW_WOLF_MEAT))
                    .when(LootItemRandomChanceCondition.randomChance(0.4f)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isWeaponOrTool(ItemStack stack) {
        String id = stack.getItem().getClass().getSimpleName().toLowerCase();
        return id.contains("sword") || id.contains("axe") || id.contains("pickaxe")
            || id.contains("shovel") || id.contains("hoe") || id.contains("trident");
    }

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
