package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.saveddata.FamiliarsSavedData;
import com.neovetta.darknights.saveddata.LycanthropySavedData;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FamiliarHandler {

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(FamiliarHandler::onServerTick);
        EntitySleepEvents.STOP_SLEEPING.register(FamiliarHandler::onStopSleeping);
        ServerLivingEntityEvents.AFTER_DEATH.register(FamiliarHandler::onAfterDeath);
        ServerEntityEvents.ENTITY_LOAD.register(FamiliarHandler::onEntityLoad);
    }

    // -------------------------------------------------------------------------
    // Main tick — attach nearby wolves, maintain pack
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        if (!DarkNights.CONFIG.enableLycanthropy) return;

        LycanthropySavedData lyc = LycanthropySavedData.get(server);
        FamiliarsSavedData fam  = FamiliarsSavedData.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerUuid = player.getUUID();
            LycanthropySavedData.PlayerLycanthropy data = lyc.get(playerUuid);
            if (!data.isCursed()) continue;

            int cap     = Math.min(data.lunarAge() + 1, DarkNights.CONFIG.maxFamiliarCap);
            int current = fam.getFamiliarCount(playerUuid);

            if (current < cap) {
                tryAttachNearbyWolves(player, server, fam, lyc, cap - current);
            }

            maintainPack(player, server, fam);
        }
    }

    private static void tryAttachNearbyWolves(ServerPlayer player, MinecraftServer server,
            FamiliarsSavedData fam, LycanthropySavedData lyc, int slotsAvailable) {
        ServerLevel level = (ServerLevel) player.level();
        AABB searchBox = player.getBoundingBox().inflate(10, 4, 10);

        List<Wolf> candidates = level.getEntities(
            EntityTypeTest.forClass(Wolf.class), searchBox,
            w -> !w.isTame()
                && w.getOwnerReference() == null
                && !fam.isFamiliar(w.getUUID())
        );

        candidates.stream()
            .sorted((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
            .limit(slotsAvailable)
            .forEach(wolf -> attach(wolf, player, server, fam, lyc));
    }

    private static void maintainPack(ServerPlayer player, MinecraftServer server,
            FamiliarsSavedData fam) {
        ServerLevel level = (ServerLevel) player.level();
        List<UUID> familiar = new ArrayList<>(fam.getPlayerFamiliars(player.getUUID()));

        for (UUID mobUuid : familiar) {
            Entity entity = level.getEntity(mobUuid);

            // Entity may be in an unloaded chunk — skip, don't detach
            if (entity == null) continue;

            if (!(entity instanceof Wolf wolf)) {
                fam.detach(mobUuid);
                continue;
            }

            // Teleport back if strayed too far
            if (wolf.distanceToSqr(player) > 30 * 30) {
                wolf.teleportTo(
                    player.getX() + (Math.random() - 0.5) * 3,
                    player.getY(),
                    player.getZ() + (Math.random() - 0.5) * 3
                );
            }

            // Faction combat hook — populated when Zombie Plague is implemented
            if (DarkNights.CONFIG.enableFactionCombat) {
                applyFactionTargeting(wolf, player, server);
            }
        }
    }

    // Placeholder — will set wolf targets to zombie-cursed players once ZombieSavedData exists
    private static void applyFactionTargeting(Wolf wolf, ServerPlayer owner, MinecraftServer server) {
        // ZombieSavedData zombie = ZombieSavedData.get(server);
        // for (ServerPlayer p : nearbyPlayers) {
        //     if (zombie.get(p.getUUID()).isCursed() && p != owner) { wolf.setTarget(p); break; }
        // }
    }

    // -------------------------------------------------------------------------
    // Attach / Detach
    // -------------------------------------------------------------------------

    private static void attach(Wolf wolf, ServerPlayer player, MinecraftServer server,
            FamiliarsSavedData fam, LycanthropySavedData lyc) {
        wolf.setTame(true, false);
        wolf.setOwnerReference(EntityReference.of(player.getUUID()));
        wolf.setOrderedToSit(false);

        fam.attach(wolf.getUUID(), player.getUUID());

        int count = fam.getFamiliarCount(player.getUUID());
        int cap   = Math.min(lyc.get(player.getUUID()).lunarAge() + 1, DarkNights.CONFIG.maxFamiliarCap);

        player.sendOverlayMessage(
            Component.literal("A wolf joins your pack. (" + count + "/" + cap + ")")
                .withStyle(ChatFormatting.GOLD));
    }

    private static void detachWolf(Wolf wolf, FamiliarsSavedData fam) {
        wolf.setTame(false, false);
        wolf.setOwnerReference(null);
        fam.detach(wolf.getUUID());
    }

    public static void detachAll(ServerPlayer player, MinecraftServer server) {
        FamiliarsSavedData fam = FamiliarsSavedData.get(server);
        List<UUID> mobUuids = fam.detachAll(player.getUUID());
        if (mobUuids.isEmpty()) return;

        ServerLevel level = (ServerLevel) player.level();
        for (UUID mobUuid : mobUuids) {
            Entity entity = level.getEntity(mobUuid);
            if (entity instanceof Wolf wolf) {
                wolf.setTame(false, false);
                wolf.setOwnerReference(null);
            }
        }

        player.sendOverlayMessage(
            Component.literal("Your pack disperses.").withStyle(ChatFormatting.GRAY));
    }

    // -------------------------------------------------------------------------
    // Sleep — pack disperses when the player sleeps
    // -------------------------------------------------------------------------

    private static void onStopSleeping(LivingEntity entity, BlockPos pos) {
        if (!(entity instanceof ServerPlayer player)) return;
        detachAll(player, player.level().getServer());
    }

    // -------------------------------------------------------------------------
    // Death — clean up if a familiar dies
    // -------------------------------------------------------------------------

    private static void onAfterDeath(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) return;
        MinecraftServer server = entity.level().getServer();
        FamiliarsSavedData fam = FamiliarsSavedData.get(server);

        UUID dead = entity.getUUID();
        if (!fam.isFamiliar(dead)) return;

        UUID ownerUuid = fam.getFamiliarOwner(dead);
        fam.detach(dead);

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) {
            int remaining = fam.getFamiliarCount(ownerUuid);
            owner.sendOverlayMessage(
                Component.literal("A wolf has fallen. Pack: " + remaining)
                    .withStyle(ChatFormatting.RED));
        }
    }

    // -------------------------------------------------------------------------
    // Entity load — re-apply tame state after chunk reload
    // -------------------------------------------------------------------------

    private static void onEntityLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof Wolf wolf)) return;

        FamiliarsSavedData fam = FamiliarsSavedData.get(level.getServer());
        if (!fam.isFamiliar(wolf.getUUID())) return;

        UUID ownerUuid = fam.getFamiliarOwner(wolf.getUUID());
        wolf.setTame(true, false);
        wolf.setOwnerReference(EntityReference.of(ownerUuid));
        wolf.setOrderedToSit(false);
    }
}
