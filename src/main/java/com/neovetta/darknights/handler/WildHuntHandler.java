package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.item.DarkNightsItems;
import com.neovetta.darknights.saveddata.WildHuntSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

public class WildHuntHandler {

    // Claim window in ticks (30 seconds)
    private static final int CLAIM_WINDOW = 600;
    // Safety end: one full day cycle
    private static final long HUNT_MAX_TICKS = 24000L;

    private static final Identifier MEDALLION_ATTR_ID =
        Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, "medallion_attack");

    // ---- Hunt state (ephemeral — not persisted) ----
    private static boolean isHuntActive = false;
    private static boolean wasNight = false;
    private static long huntStartTick = 0L;

    // playerUUID → current wave (1, 2, 3)
    private static final Map<UUID, Integer> claimedPlayers = new HashMap<>();
    // playerUUID → entity UUIDs in current wave
    private static final Map<UUID, Set<UUID>> waveEntities = new HashMap<>();
    // all hunt mob UUIDs for cleanup
    private static final Set<UUID> allHuntMobs = new HashSet<>();

    private static Holder<WorldClock> overworldClock = null;

    private static final Random RANDOM = new Random();

    private static Holder<WorldClock> getOverworldClock(MinecraftServer server) {
        if (overworldClock == null) {
            HolderLookup.RegistryLookup<WorldClock> reg =
                server.overworld().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
            overworldClock = reg.getOrThrow(WorldClocks.OVERWORLD);
        }
        return overworldClock;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WildHuntHandler::onServerTick);
        UseItemCallback.EVENT.register(WildHuntHandler::onUseItem);
    }

    // -------------------------------------------------------------------------
    // Admin entry point
    // -------------------------------------------------------------------------

    public static void adminStartHunt(MinecraftServer server) {
        if (!isHuntActive) startHunt(server);
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = overworld.clockManager().getTotalTicks(clock);
        long timeOfDay = totalTicks % 24000;
        boolean isNight = timeOfDay > 13000 && timeOfDay < 23000;

        // Reapply medallion modifiers every tick for all players
        if (DarkNights.CONFIG.enableWildHunt) {
            applyMedallionModifiers(server);
        }

        // Nightfall — roll for hunt
        if (isNight && !wasNight && DarkNights.CONFIG.enableWildHunt && !isHuntActive) {
            if (!BloodMoonHandler.isActive() && RANDOM.nextFloat() < DarkNights.CONFIG.wildHuntChance) {
                startHunt(server);
            }
        }

        // Dawn — end any active hunt
        if (!isNight && wasNight && isHuntActive) {
            endHunt(server, false);
        }

        wasNight = isNight;

        if (!isHuntActive) return;

        long elapsed = totalTicks - huntStartTick;

        // Safety timeout
        if (elapsed > HUNT_MAX_TICKS) {
            endHunt(server, false);
            return;
        }

        // 30-second warning ticks
        if (elapsed == 20) {
            broadcastWarning(server, "Seek shelter — the Hunt claims the exposed in 30 seconds!",
                ChatFormatting.RED);
        }
        if (elapsed == 200) {
            broadcastOverlay(server, "20 seconds to find shelter...", ChatFormatting.YELLOW);
        }
        if (elapsed == 400) {
            broadcastOverlay(server, "10 seconds!", ChatFormatting.GOLD);
        }

        // Claim window closes
        if (elapsed == CLAIM_WINDOW) {
            claimOutdoorPlayers(server, totalTicks);
        }

        // Wave management — check every 20 ticks
        if (elapsed > CLAIM_WINDOW && totalTicks % 20 == 0) {
            tickWaves(server, totalTicks);
        }
    }

    // -------------------------------------------------------------------------
    // Hunt lifecycle
    // -------------------------------------------------------------------------

    public static void startHunt(MinecraftServer server) {
        isHuntActive = true;
        Holder<WorldClock> clock = getOverworldClock(server);
        huntStartTick = server.overworld().clockManager().getTotalTicks(clock);

        DarkNights.LOGGER.info("The Wild Hunt begins.");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(
                Component.literal("✦ THE WILD HUNT RIDES TONIGHT ✦")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            p.sendOverlayMessage(
                Component.literal("Seek shelter — the Hunt claims the exposed!")
                    .withStyle(ChatFormatting.RED));
            p.level().playSound(null, p.blockPosition(),
                SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.AMBIENT, 1.5f, 0.5f);
        }
    }

    private static void endHunt(MinecraftServer server, boolean allClear) {
        isHuntActive = false;
        claimedPlayers.clear();
        waveEntities.clear();

        // Discard all remaining hunt mobs
        for (ServerLevel level : server.getAllLevels()) {
            for (UUID uuid : allHuntMobs) {
                Entity e = level.getEntity(uuid);
                if (e != null) e.discard();
            }
        }
        allHuntMobs.clear();

        String msg = allClear
            ? "The Wild Hunt retreats. The brave have proven themselves."
            : "The Wild Hunt fades with the night.";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(
                Component.literal(msg).withStyle(ChatFormatting.DARK_PURPLE), false);
        }
        DarkNights.LOGGER.info("Wild Hunt ended.");
    }

    // -------------------------------------------------------------------------
    // Player claiming
    // -------------------------------------------------------------------------

    private static void claimOutdoorPlayers(MinecraftServer server, long totalTicks) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isOutdoors(player)) {
                claimedPlayers.put(player.getUUID(), 1);
                player.sendSystemMessage(
                    Component.literal("You have been claimed by the Wild Hunt! Survive three waves.")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
                spawnWave(player, 1);
            }
        }
        if (claimedPlayers.isEmpty()) {
            broadcastWarning(server, "All sheltered from the Wild Hunt.", ChatFormatting.GRAY);
            endHunt(server, true);
        }
    }

    // -------------------------------------------------------------------------
    // Wave management
    // -------------------------------------------------------------------------

    private static void tickWaves(MinecraftServer server, long totalTicks) {
        List<UUID> toReward = new ArrayList<>();
        List<UUID> toLogoff = new ArrayList<>();

        for (UUID uuid : new ArrayList<>(claimedPlayers.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                toLogoff.add(uuid);
                continue;
            }

            Set<UUID> mobs = waveEntities.getOrDefault(uuid, Collections.emptySet());
            boolean waveClear = mobs.stream().allMatch(mobUuid -> {
                Entity e = ((ServerLevel) player.level()).getEntity(mobUuid);
                return e == null || e.isRemoved();
            });

            if (!waveClear) continue;

            int wave = claimedPlayers.get(uuid);
            if (wave < 3) {
                int nextWave = wave + 1;
                claimedPlayers.put(uuid, nextWave);
                player.sendSystemMessage(
                    Component.literal("Wave " + wave + " cleared! Prepare for wave " + nextWave + "...")
                        .withStyle(ChatFormatting.YELLOW), false);
                spawnWave(player, nextWave);
            } else {
                toReward.add(uuid);
            }
        }

        for (UUID uuid : toLogoff) {
            claimedPlayers.remove(uuid);
            waveEntities.remove(uuid);
        }

        for (UUID uuid : toReward) {
            claimedPlayers.remove(uuid);
            waveEntities.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) giveReward(player);
        }

        if (claimedPlayers.isEmpty()) {
            endHunt(server, true);
        }
    }

    private static void spawnWave(ServerPlayer player, int wave) {
        ServerLevel level = (ServerLevel) player.level();
        Set<UUID> mobs = new HashSet<>();

        switch (wave) {
            case 1 -> {
                for (int i = 0; i < 3; i++) addMob(spawnHuntSkeleton(level, player, 1), mobs);
            }
            case 2 -> {
                for (int i = 0; i < 2; i++) addMob(spawnHuntSkeleton(level, player, 2), mobs);
                for (int i = 0; i < 2; i++) addMob(spawnHuntWitch(level, player), mobs);
            }
            case 3 -> {
                addMob(spawnSkeletonKnight(level, player), mobs);
                for (int i = 0; i < 2; i++) addMob(spawnHuntSkeleton(level, player, 3), mobs);
            }
        }

        waveEntities.put(player.getUUID(), mobs);
        allHuntMobs.addAll(mobs);

        player.sendOverlayMessage(
            Component.literal("⚔ Wave " + wave + " — " + mobs.size() + " enemies incoming!")
                .withStyle(ChatFormatting.DARK_RED));
        level.playSound(null, player.blockPosition(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 0.8f, 1.5f);
    }

    private static void addMob(Entity mob, Set<UUID> mobs) {
        if (mob != null) mobs.add(mob.getUUID());
    }

    // -------------------------------------------------------------------------
    // Mob spawners
    // -------------------------------------------------------------------------

    private static Skeleton spawnHuntSkeleton(ServerLevel level, ServerPlayer player, int wave) {
        BlockPos pos = findSpawnPos(level, player, 6 + RANDOM.nextInt(6));
        Skeleton s = (Skeleton) EntityType.SKELETON.create(level, EntitySpawnReason.EVENT);
        if (s == null) return null;

        s.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        s.setYRot(RANDOM.nextFloat() * 360f);
        s.setCustomName(Component.literal("Hunt Rider").withStyle(ChatFormatting.DARK_PURPLE));
        s.setCustomNameVisible(true);

        double hp = 20 + wave * 10; // 30 / 40 / 50
        var hpAttr = s.getAttribute(Attributes.MAX_HEALTH);
        if (hpAttr != null) { hpAttr.setBaseValue(hp); s.setHealth((float) hp); }

        var spdAttr = s.getAttribute(Attributes.MOVEMENT_SPEED);
        if (spdAttr != null) spdAttr.setBaseValue(0.27 + 0.01 * wave);

        s.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, Integer.MAX_VALUE, wave - 1, false, false));
        s.setTarget(player);
        level.addFreshEntity(s);
        return s;
    }

    private static Witch spawnHuntWitch(ServerLevel level, ServerPlayer player) {
        BlockPos pos = findSpawnPos(level, player, 8 + RANDOM.nextInt(6));
        Witch w = (Witch) EntityType.WITCH.create(level, EntitySpawnReason.EVENT);
        if (w == null) return null;

        w.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        w.setYRot(RANDOM.nextFloat() * 360f);
        w.setCustomName(Component.literal("Hunt Caster").withStyle(ChatFormatting.DARK_PURPLE));
        w.setCustomNameVisible(true);

        var hpAttr = w.getAttribute(Attributes.MAX_HEALTH);
        if (hpAttr != null) { hpAttr.setBaseValue(40.0); w.setHealth(40f); }

        w.setTarget(player);
        level.addFreshEntity(w);
        return w;
    }

    private static Skeleton spawnSkeletonKnight(ServerLevel level, ServerPlayer player) {
        BlockPos pos = findSpawnPos(level, player, 5 + RANDOM.nextInt(4));
        Skeleton boss = (Skeleton) EntityType.SKELETON.create(level, EntitySpawnReason.EVENT);
        if (boss == null) return null;

        boss.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        boss.setYRot(RANDOM.nextFloat() * 360f);
        boss.setCustomName(Component.literal("✦ Skeleton Knight ✦").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
        boss.setCustomNameVisible(true);

        var hpAttr = boss.getAttribute(Attributes.MAX_HEALTH);
        if (hpAttr != null) { hpAttr.setBaseValue(80.0); boss.setHealth(80f); }

        var atkAttr = boss.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkAttr != null) atkAttr.setBaseValue(8.0);

        var spdAttr = boss.getAttribute(Attributes.MOVEMENT_SPEED);
        if (spdAttr != null) spdAttr.setBaseValue(0.30);

        var armorAttr = boss.getAttribute(Attributes.ARMOR);
        if (armorAttr != null) armorAttr.setBaseValue(16.0);

        boss.setItemSlot(EquipmentSlot.HEAD,     new ItemStack(Items.IRON_HELMET));
        boss.setItemSlot(EquipmentSlot.CHEST,    new ItemStack(Items.IRON_CHESTPLATE));
        boss.setItemSlot(EquipmentSlot.LEGS,     new ItemStack(Items.IRON_LEGGINGS));
        boss.setItemSlot(EquipmentSlot.FEET,     new ItemStack(Items.IRON_BOOTS));
        boss.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));

        boss.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, Integer.MAX_VALUE, 2, false, false));
        boss.setTarget(player);
        level.addFreshEntity(boss);
        return boss;
    }

    // -------------------------------------------------------------------------
    // Reward
    // -------------------------------------------------------------------------

    private static void giveReward(ServerPlayer player) {
        ItemStack medallion = new ItemStack(DarkNightsItems.WILD_HUNT_MEDALLION);
        player.getInventory().add(medallion);
        player.sendSystemMessage(
            Component.literal("You have survived the Wild Hunt! A medallion is yours.")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        player.level().playSound(null, player.blockPosition(),
            SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 0.5f);
    }

    // -------------------------------------------------------------------------
    // Medallion use + permanent buff application
    // -------------------------------------------------------------------------

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() != DarkNightsItems.WILD_HUNT_MEDALLION) return InteractionResult.PASS;

        WildHuntSavedData data = WildHuntSavedData.get(sp.level().getServer());
        WildHuntSavedData.PlayerHunt hunt = data.get(sp.getUUID());

        if (hunt.stacks() >= 3) {
            sp.sendSystemMessage(
                Component.literal("The medallion's power has already reached its peak.")
                    .withStyle(ChatFormatting.GRAY), false);
        } else {
            WildHuntSavedData.PlayerHunt updated = hunt.addStack();
            data.put(sp.getUUID(), updated);
            sp.sendSystemMessage(
                Component.literal("The Hunt's fury fills you. +" + updated.stacks() + " Attack Damage permanently. (" + updated.stacks() + "/3)")
                    .withStyle(ChatFormatting.GOLD), false);
            sp.level().playSound(null, sp.blockPosition(),
                SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 0.8f);
        }
        stack.shrink(1);
        return InteractionResult.SUCCESS;
    }

    private static void applyMedallionModifiers(MinecraftServer server) {
        WildHuntSavedData data = WildHuntSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int stacks = data.get(player.getUUID()).stacks();
            var instance = player.getAttribute(Attributes.ATTACK_DAMAGE);
            if (instance == null) continue;
            if (stacks > 0) {
                instance.addOrUpdateTransientModifier(
                    new AttributeModifier(MEDALLION_ATTR_ID, (double) stacks, Operation.ADD_VALUE));
            } else {
                instance.removeModifier(MEDALLION_ATTR_ID);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BlockPos findSpawnPos(ServerLevel level, ServerPlayer player, int radius) {
        double angle = RANDOM.nextDouble() * Math.PI * 2;
        int x = (int) (player.getX() + Math.cos(angle) * radius);
        int z = (int) (player.getZ() + Math.sin(angle) * radius);
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            BlockPos.containing(x, 0, z));
        return surface;
    }

    private static boolean isOutdoors(ServerPlayer player) {
        return ((ServerLevel) player.level()).canSeeSky(player.blockPosition().above());
    }

    private static void broadcastWarning(MinecraftServer server, String msg, ChatFormatting color) {
        Component c = Component.literal(msg).withStyle(color);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(c, false);
        }
    }

    private static void broadcastOverlay(MinecraftServer server, String msg, ChatFormatting color) {
        Component c = Component.literal(msg).withStyle(color);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendOverlayMessage(c);
        }
    }
}
