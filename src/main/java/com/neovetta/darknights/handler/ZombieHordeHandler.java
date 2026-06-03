package com.neovetta.darknights.handler;

import com.neovetta.darknights.DarkNights;
import com.neovetta.darknights.saveddata.LycanthropySavedData;
import com.neovetta.darknights.saveddata.ZombieHordeSavedData;
import com.neovetta.darknights.saveddata.ZombieSavedData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ZombieHordeHandler {

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
        ServerTickEvents.END_SERVER_TICK.register(ZombieHordeHandler::onServerTick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(ZombieHordeHandler::onAllowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(ZombieHordeHandler::onAfterDeath);
    }

    // -------------------------------------------------------------------------
    // Main tick — bond nearby undead, maintain horde
    // -------------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        if (!DarkNights.CONFIG.enableZombiePlague) return;

        ServerLevel overworld = server.overworld();
        Holder<WorldClock> clock = getOverworldClock(server);
        long totalTicks = overworld.clockManager().getTotalTicks(clock);
        long timeOfDay  = totalTicks % 24000;
        boolean isNight = timeOfDay > 13000 && timeOfDay < 23000;

        ZombieHordeSavedData horde  = ZombieHordeSavedData.get(server);
        ZombieSavedData      zombie = ZombieSavedData.get(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!zombie.get(player.getUUID()).isCursed()) continue;

            if (isNight) {
                int cap     = DarkNights.CONFIG.maxHordeCap;
                int current = horde.getFamiliarCount(player.getUUID());
                if (current < cap) {
                    tryAttachNearby(player, server, horde, cap - current);
                }
            }

            maintainHorde(player, server, horde);
        }
    }

    private static void tryAttachNearby(ServerPlayer player, MinecraftServer server,
            ZombieHordeSavedData horde, int slotsAvailable) {
        ServerLevel level = (ServerLevel) player.level();
        AABB searchBox = player.getBoundingBox().inflate(10, 4, 10);

        List<Zombie> candidates = level.getEntities(
            EntityTypeTest.forClass(Zombie.class), searchBox,
            z -> !(z instanceof ZombifiedPiglin)
                && !horde.isFamiliar(z.getUUID())
                && !z.isNoAi()
        );

        candidates.stream()
            .sorted((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
            .limit(slotsAvailable)
            .forEach(z -> attach(z, player, horde));
    }

    private static void maintainHorde(ServerPlayer player, MinecraftServer server,
            ZombieHordeSavedData horde) {
        ServerLevel level = (ServerLevel) player.level();
        List<UUID> members = new ArrayList<>(horde.getPlayerFamiliars(player.getUUID()));

        for (UUID mobUuid : members) {
            var entity = level.getEntity(mobUuid);
            if (entity == null) continue; // unloaded chunk — don't detach

            if (!(entity instanceof Zombie z)) {
                horde.detach(mobUuid);
                continue;
            }

            // No-friendly-fire: clear target if it's the owner
            if (z.getTarget() != null && z.getTarget().getUUID().equals(player.getUUID())) {
                z.setTarget(null);
            }

            // Follow owner when idle
            if (z.getTarget() == null && z.distanceToSqr(player) > 4 * 4) {
                z.getNavigation().moveTo(player, 1.2);
            }

            // Teleport back if strayed too far
            if (z.distanceToSqr(player) > 32 * 32) {
                z.teleportTo(
                    player.getX() + (Math.random() - 0.5) * 3,
                    player.getY(),
                    player.getZ() + (Math.random() - 0.5) * 3
                );
            }

            // Faction combat — target transformed werewolf players within 15 blocks
            if (DarkNights.CONFIG.enableFactionCombat && z.getTarget() == null) {
                applyFactionTargeting(z, player, server);
            }
        }
    }

    private static void applyFactionTargeting(Zombie zombie, ServerPlayer owner, MinecraftServer server) {
        LycanthropySavedData lyc = LycanthropySavedData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(owner.getUUID())) continue;
            if (!lyc.get(p.getUUID()).isTransformed()) continue;
            if (zombie.distanceToSqr(p) < 15.0 * 15.0) {
                zombie.setTarget(p);
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Attach
    // -------------------------------------------------------------------------

    private static void attach(Zombie zombie, ServerPlayer player, ZombieHordeSavedData horde) {
        zombie.setTarget(null);
        horde.attach(zombie.getUUID(), player.getUUID());

        int count = horde.getFamiliarCount(player.getUUID());
        player.sendOverlayMessage(
            Component.literal("The dead heed your call. (" + count + "/" + DarkNights.CONFIG.maxHordeCap + ")")
                .withStyle(ChatFormatting.DARK_GREEN));
    }

    // -------------------------------------------------------------------------
    // ALLOW_DAMAGE — no-friendly-fire between horde and owner
    // -------------------------------------------------------------------------

    private static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(source.getEntity() instanceof Zombie attacker)) return true;

        MinecraftServer server = entity.level().getServer();
        if (server == null) return true;

        ZombieHordeSavedData horde = ZombieHordeSavedData.get(server);
        if (!horde.isFamiliar(attacker.getUUID())) return true;

        UUID ownerUuid = horde.getFamiliarOwner(attacker.getUUID());
        if (entity.getUUID().equals(ownerUuid)) {
            attacker.setTarget(null);
            return false; // cancel damage to owner
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // AFTER_DEATH — clean up dead horde members
    // -------------------------------------------------------------------------

    private static void onAfterDeath(LivingEntity entity, DamageSource source) {
        if (entity.level().isClientSide()) return;
        if (!(entity instanceof Zombie)) return;

        MinecraftServer server = entity.level().getServer();
        if (server == null) return;

        ZombieHordeSavedData horde = ZombieHordeSavedData.get(server);
        UUID dead = entity.getUUID();
        if (!horde.isFamiliar(dead)) return;

        UUID ownerUuid = horde.getFamiliarOwner(dead);
        horde.detach(dead);

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) {
            int remaining = horde.getFamiliarCount(ownerUuid);
            owner.sendOverlayMessage(
                Component.literal("One of your undead has fallen. Horde: " + remaining)
                    .withStyle(ChatFormatting.DARK_GREEN));
        }
    }
}
