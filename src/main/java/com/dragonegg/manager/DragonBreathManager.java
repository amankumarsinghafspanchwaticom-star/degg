package com.dragonegg.manager;

import com.dragonegg.DragonEggPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DragonBreathManager {

    private final DragonEggPlugin plugin;
    private final Set<BukkitTask> activeZones = ConcurrentHashMap.newKeySet();

    public DragonBreathManager(DragonEggPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Spawns a server-managed 8x8 Dragon's Breath cloud around the wielder for 10 seconds.
     * Damages all other players in the 8x8 zone while keeping the wielder 100% immune.
     */
    public void summonBreathCloud(Player caster) {
        if (caster == null || !caster.isOnline()) {
            return;
        }

        UUID casterId = caster.getUniqueId();
        Location center = caster.getLocation().clone();
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double halfWidth = plugin.getConfig().getDouble("dragons-breath.area-half-width", 4.0);
        int durationTicks = Math.max(20, plugin.getConfig().getInt("dragons-breath.duration-seconds", 10) * 20);
        int damageInterval = Math.max(2, plugin.getConfig().getInt("dragons-breath.damage-interval-ticks", 10));
        double damagePerPulse = plugin.getConfig().getDouble("dragons-breath.damage-per-pulse", 3.0);
        boolean targetCreative = plugin.getConfig().getBoolean("dragons-breath.target-creative", false);
        boolean targetMobs = plugin.getConfig().getBoolean("dragons-breath.target-mobs", false);

        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.2f, 0.9f);
        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.9f, 1.1f);

        final int[] elapsed = {0};
        final BukkitTask[] holder = new BukkitTask[1];

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (elapsed[0] >= durationTicks || !world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
                if (holder[0] != null) {
                    holder[0].cancel();
                    activeZones.remove(holder[0]);
                }
                return;
            }

            // Render dense purple Dragon's Breath mist across the exact 8x8 footprint every 2 ticks
            if (elapsed[0] % 2 == 0) {
                renderBreathArea(world, center, halfWidth);
            }

            // Ambient sizzle sound every 1.5 seconds
            if (elapsed[0] % 30 == 0 && elapsed[0] > 0) {
                world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 0.7f, 0.65f);
            }

            // Damage pulse
            if (elapsed[0] % damageInterval == 0) {
                Player onlineCaster = Bukkit.getPlayer(casterId);
                DamageSource source = buildBreathDamageSource(onlineCaster);

                for (Entity entity : world.getNearbyEntities(center, halfWidth + 0.6, 3.5, halfWidth + 0.6)) {
                    if (!(entity instanceof LivingEntity living) || living.isDead()) {
                        continue;
                    }
                    // Strict Wielder Immunity — completely unaffected
                    if (living.getUniqueId().equals(casterId)) {
                        continue;
                    }

                    if (living instanceof Player targetPlayer) {
                        if (targetPlayer.getGameMode() == GameMode.SPECTATOR) {
                            continue;
                        }
                        if (!targetCreative && targetPlayer.getGameMode() == GameMode.CREATIVE) {
                            continue;
                        }
                        if (targetPlayer.hasPermission("dragonegg.exempt")) {
                            continue;
                        }
                    } else if (!targetMobs) {
                        continue;
                    }

                    Location loc = living.getLocation();
                    if (Math.abs(loc.getX() - center.getX()) <= halfWidth
                            && Math.abs(loc.getZ() - center.getZ()) <= halfWidth
                            && loc.getY() >= center.getY() - 2.0
                            && loc.getY() <= center.getY() + 3.5) {
                        living.damage(damagePerPulse, source);
                        DragonThrowManager.spawnSafeDragonBreath(world, loc.clone().add(0, 0.8, 0), 6, 0.25, 0.3, 0.25, 0.04);
                    }
                }
            }

            elapsed[0]++;
        }, 0L, 1L);

        holder[0] = task;
        activeZones.add(task);
    }

    private DamageSource buildBreathDamageSource(Player onlineCaster) {
        DamageSource.Builder builder = DamageSource.builder(DamageType.DRAGON_BREATH);
        if (onlineCaster != null && onlineCaster.isOnline()) {
            builder.withCausingEntity(onlineCaster).withDirectEntity(onlineCaster);
        }
        return builder.build();
    }

    private void renderBreathArea(World world, Location center, double halfWidth) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();

        // Spawn 26 ground-hugging Dragon's Breath puffs spread evenly across the 8x8 square
        for (int i = 0; i < 26; i++) {
            double rx = cx + rng.nextDouble(-halfWidth, halfWidth);
            double rz = cz + rng.nextDouble(-halfWidth, halfWidth);
            double surfaceY = findSurfaceY(world, rx, cy, rz);
            Location pt = new Location(world, rx, surfaceY + 0.15 + rng.nextDouble(0.0, 0.45), rz);
            DragonThrowManager.spawnSafeDragonBreath(world, pt, 1, 0.12, 0.08, 0.12, 0.02);
        }

        // Crisp 8x8 perimeter wisps so players can read the exact danger boundary
        for (int i = 0; i < 8; i++) {
            int side = rng.nextInt(4);
            double along = rng.nextDouble(-halfWidth, halfWidth);
            double bx = switch (side) {
                case 0 -> cx - halfWidth;
                case 1 -> cx + halfWidth;
                default -> cx + along;
            };
            double bz = switch (side) {
                case 2 -> cz - halfWidth;
                case 3 -> cz + halfWidth;
                default -> cz + along;
            };
            double surfaceY = findSurfaceY(world, bx, cy, bz);
            world.spawnParticle(Particle.WITCH, bx, surfaceY + 0.25, bz, 1, 0.05, 0.15, 0.05, 0.01);
        }
    }

    private double findSurfaceY(World world, double x, double refY, double z) {
        int bx = Location.locToBlock(x);
        int bz = Location.locToBlock(z);
        int startY = Location.locToBlock(refY);
        int minY = world.getMinHeight();

        for (int dy = 2; dy >= -3; dy--) {
            int y = startY + dy;
            if (y <= minY) {
                continue;
            }
            Block block = world.getBlockAt(bx, y, bz);
            Block below = world.getBlockAt(bx, y - 1, bz);
            if (!block.getType().isSolid() && below.getType().isSolid()) {
                return y;
            }
        }
        return refY;
    }

    public void stopAll() {
        for (BukkitTask task : activeZones) {
            task.cancel();
        }
        activeZones.clear();
    }
}
