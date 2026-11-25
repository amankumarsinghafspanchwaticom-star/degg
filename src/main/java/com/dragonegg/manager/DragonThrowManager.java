package com.dragonegg.manager;

import com.dragonegg.DragonEggPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DragonThrowManager {

    private static final double THRUST_VELOCITY = 3.2;
    private static final double COAST_HANDOFF_BLOCKS = 45.0;

    private final DragonEggPlugin plugin;
    private final Map<UUID, BukkitTask> activeLaunches = new ConcurrentHashMap<>();

    public DragonThrowManager(DragonEggPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Launches the target vertically to an exact height (up to 200 blocks at 100% charge).
     * Uses smooth multi-tick thrust below Minecraft's 4.095 blocks/tick velocity packet limit,
     * then hands off to natural Minecraft gravity/drag for the final ~45 blocks so the arc feels crisp.
     */
    public void launchEntity(Player caster, LivingEntity target, int chargePercent, int maxCharge) {
        if (target == null || !target.isValid() || target.isDead()) {
            return;
        }

        cancelLaunch(target.getUniqueId());

        double maxHeight = plugin.getConfig().getDouble("dragons-throw.max-launch-height-blocks", 200.0);
        double targetHeight = Math.max(2.0, ((double) chargePercent / (double) Math.max(1, maxCharge)) * maxHeight);
        double targetApexY = target.getLocation().getY() + targetHeight;

        World startWorld = target.getWorld();
        Location startLoc = target.getLocation();
        startWorld.playSound(startLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.35f);
        startWorld.playSound(startLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.1f, 0.85f);
        spawnBurstParticles(startWorld, startLoc);

        // Short launches (<= 45 blocks) can be executed in a single exact velocity impulse
        if (targetHeight <= COAST_HANDOFF_BLOCKS) {
            double vy = solveCoastVelocity(targetHeight);
            target.setVelocity(new Vector(0.0, vy, 0.0));
            return;
        }

        UUID targetId = target.getUniqueId();
        final int[] ticks = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks[0]++;

            if (!target.isValid() || target.isDead() || ticks[0] > 160) {
                cancelLaunch(targetId);
                return;
            }
            if (target instanceof Player p) {
                if (!p.isOnline() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) {
                    cancelLaunch(targetId);
                    return;
                }
            }

            Location loc = target.getLocation();
            World world = loc.getWorld();
            if (world == null || !world.equals(startWorld)) {
                cancelLaunch(targetId);
                return;
            }

            // Stop cleanly if the target hits a solid ceiling block above their head
            Block aboveHead = world.getBlockAt(
                    loc.getBlockX(),
                    Location.locToBlock(loc.getY() + target.getHeight() + 0.35),
                    loc.getBlockZ()
            );
            if (aboveHead.getType().isSolid()) {
                cancelLaunch(targetId);
                return;
            }

            double remaining = targetApexY - loc.getY();
            if (remaining <= 0.5) {
                cancelLaunch(targetId);
                return;
            }

            if (remaining <= COAST_HANDOFF_BLOCKS) {
                double finalVy = solveCoastVelocity(remaining);
                target.setVelocity(new Vector(0.0, finalVy, 0.0));
                spawnTrailParticles(world, loc);
                cancelLaunch(targetId);
                return;
            }

            target.setVelocity(new Vector(0.0, THRUST_VELOCITY, 0.0));
            spawnTrailParticles(world, loc);
        }, 0L, 1L);

        activeLaunches.put(targetId, task);
    }

    public void cancelLaunch(UUID entityId) {
        BukkitTask existing = activeLaunches.remove(entityId);
        if (existing != null) {
            existing.cancel();
        }
    }

    public void stopAll() {
        for (BukkitTask task : activeLaunches.values()) {
            task.cancel();
        }
        activeLaunches.clear();
    }

    /**
     * Exact binary search solving Minecraft's per-tick vertical drag equation:
     * y += v; v = (v - 0.08) * 0.98
     */
    private double solveCoastVelocity(double desiredHeight) {
        double lo = 0.0;
        double hi = 3.85;
        for (int i = 0; i < 28; i++) {
            double mid = (lo + hi) * 0.5;
            if (simulateCoastHeight(mid) < desiredHeight) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5;
    }

    private double simulateCoastHeight(double v0) {
        double y = 0.0;
        double v = v0;
        while (v > 0.0) {
            y += v;
            v = (v - 0.08) * 0.98;
        }
        return y;
    }

    private void spawnBurstParticles(World world, Location loc) {
        world.spawnParticle(Particle.REVERSE_PORTAL, loc.clone().add(0, 0.5, 0), 35, 0.5, 0.4, 0.5, 0.12);
        world.spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.6, 0), 18, 0.4, 0.3, 0.4, 0.15);
        spawnSafeDragonBreath(world, loc.clone().add(0, 0.3, 0), 25, 0.5, 0.2, 0.5, 0.08);
    }

    private void spawnTrailParticles(World world, Location loc) {
        world.spawnParticle(Particle.REVERSE_PORTAL, loc, 6, 0.25, 0.1, 0.25, 0.04);
        world.spawnParticle(Particle.END_ROD, loc, 3, 0.2, 0.1, 0.2, 0.02);
    }

    public static void spawnSafeDragonBreath(World world, Location loc, int count, double ox, double oy, double oz, double speed) {
        Class<?> dataType = Particle.DRAGON_BREATH.getDataType();
        if (dataType == Float.class) {
            world.spawnParticle(Particle.DRAGON_BREATH, loc, count, ox, oy, oz, speed, 1.0f);
        } else {
            world.spawnParticle(Particle.DRAGON_BREATH, loc, count, ox, oy, oz, speed);
        }
    }
}
