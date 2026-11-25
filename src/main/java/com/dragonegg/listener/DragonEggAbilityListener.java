package com.dragonegg.listener;

import com.dragonegg.DragonEggPlugin;
import com.dragonegg.item.DragonEggItemFactory;
import com.dragonegg.manager.DragonBreathManager;
import com.dragonegg.manager.DragonThrowManager;
import com.dragonegg.manager.EggStateManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DragonEggAbilityListener implements Listener {

    private final DragonEggPlugin plugin;
    private final DragonEggItemFactory itemFactory;
    private final EggStateManager stateManager;
    private final DragonThrowManager throwManager;
    private final DragonBreathManager breathManager;

    private final Map<UUID, ClickRecord> lastLeftClick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLeftEventDedupe = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRightClick = new ConcurrentHashMap<>();

    public DragonEggAbilityListener(
            DragonEggPlugin plugin,
            DragonEggItemFactory itemFactory,
            EggStateManager stateManager,
            DragonThrowManager throwManager,
            DragonBreathManager breathManager
    ) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.stateManager = stateManager;
        this.throwManager = throwManager;
        this.breathManager = breathManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!itemFactory.isDragonEgg(event.getItem())) {
            return;
        }

        Player player = event.getPlayer();
        Action action = event.getAction();

        // Ability 1: Crouch + Aim at Opponent + Double Left-Click -> Dragon's Throw
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (!player.isSneaking()) {
                return;
            }
            handleThrowLeftClick(player, null);
            return;
        }

        // Ability 2: Crouch + Look Downward + Double Right-Click -> Dragon's Breath
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            double minPitch = plugin.getConfig().getDouble("dragons-breath.min-look-down-pitch", 45.0);
            if (!player.isSneaking() || player.getLocation().getPitch() < minPitch) {
                return;
            }

            event.setCancelled(true);

            if (!player.hasPermission("dragonegg.use.breath")) {
                player.sendActionBar(plugin.rawMsg("no-permission"));
                return;
            }

            long now = System.currentTimeMillis();
            long window = plugin.getConfig().getLong("dragons-breath.double-click-window-ms", 450L);
            Long prev = lastRightClick.put(player.getUniqueId(), now);

            if (prev != null && (now - prev) <= window) {
                lastRightClick.remove(player.getUniqueId());
                triggerDragonsBreath(player);
            }
        }
    }

    /**
     * Also catches direct melee left-clicks on an opponent (when Minecraft fires EntityDamageByEntityEvent
     * instead of PlayerInteractEvent LEFT_CLICK_AIR).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!player.isSneaking()) {
            return;
        }
        if (!itemFactory.isDragonEgg(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity hitEntity)) {
            return;
        }
        if (!isValidThrowTarget(player, hitEntity)) {
            return;
        }
        handleThrowLeftClick(player, hitEntity);
    }

    private void handleThrowLeftClick(Player caster, LivingEntity directTarget) {
        long now = System.currentTimeMillis();
        // Prevent the same physical swing from firing both EntityDamageByEntityEvent and PlayerInteractEvent
        Long lastEvent = lastLeftEventDedupe.get(caster.getUniqueId());
        if (lastEvent != null && (now - lastEvent) < 40L) {
            return;
        }
        lastLeftEventDedupe.put(caster.getUniqueId(), now);

        LivingEntity aimed = (directTarget != null) ? directTarget : findAimedTarget(caster);
        if (aimed == null) {
            return;
        }

        if (!caster.hasPermission("dragonegg.use.throw")) {
            caster.sendActionBar(plugin.rawMsg("no-permission"));
            return;
        }

        long window = plugin.getConfig().getLong("dragons-throw.double-click-window-ms", 450L);
        ClickRecord prev = lastLeftClick.put(caster.getUniqueId(), new ClickRecord(aimed.getUniqueId(), now));

        if (prev != null && (now - prev.timestampMs()) <= window && prev.targetId().equals(aimed.getUniqueId())) {
            lastLeftClick.remove(caster.getUniqueId());
            triggerDragonsThrow(caster, aimed);
        }
    }

    private void triggerDragonsThrow(Player caster, LivingEntity target) {
        int minCharge = Math.max(1, plugin.getConfig().getInt("dragons-throw.min-charge-to-fire", 1));
        int currentCharge = stateManager.getChargePercent();

        if (currentCharge < minCharge) {
            String raw = plugin.getConfig().getString("messages.throw-no-charge", "")
                    .replace("{min}", String.valueOf(minCharge));
            caster.sendActionBar(plugin.parse(raw));
            caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.75f);
            return;
        }

        int usedCharge = stateManager.consumeCharge();
        int maxCharge = stateManager.getMaxCharge();
        double maxHeight = plugin.getConfig().getDouble("dragons-throw.max-launch-height-blocks", 200.0);
        int launchHeight = (int) Math.round(Math.max(2.0, ((double) usedCharge / (double) maxCharge) * maxHeight));

        throwManager.launchEntity(caster, target, usedCharge, maxCharge);

        String targetName = (target instanceof Player tp) ? tp.getName() : target.getType().name();
        String casterMsg = plugin.getConfig().getString("messages.throw-launched-caster", "")
                .replace("{target}", targetName)
                .replace("{height}", String.valueOf(launchHeight))
                .replace("{charge}", String.valueOf(usedCharge));
        caster.sendActionBar(plugin.parse(casterMsg));

        if (target instanceof Player victimPlayer) {
            String victimMsg = plugin.getConfig().getString("messages.throw-launched-victim", "")
                    .replace("{caster}", caster.getName())
                    .replace("{height}", String.valueOf(launchHeight));
            victimPlayer.sendActionBar(plugin.parse(victimMsg));
        }
    }

    private void triggerDragonsBreath(Player caster) {
        if (!caster.hasPermission("dragonegg.cooldown.bypass") && stateManager.isBreathOnCooldown()) {
            long rem = stateManager.getBreathCooldownRemainingSeconds();
            String raw = plugin.getConfig().getString("messages.breath-on-cooldown", "")
                    .replace("{time}", String.valueOf(rem));
            caster.sendActionBar(plugin.parse(raw));
            caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.75f);
            return;
        }

        breathManager.summonBreathCloud(caster);
        if (!caster.hasPermission("dragonegg.cooldown.bypass")) {
            stateManager.startBreathCooldown();
        }

        int duration = plugin.getConfig().getInt("dragons-breath.duration-seconds", 10);
        String raw = plugin.getConfig().getString("messages.breath-activated", "")
                .replace("{duration}", String.valueOf(duration));
        caster.sendActionBar(plugin.parse(raw));
    }

    private LivingEntity findAimedTarget(Player caster) {
        World world = caster.getWorld();
        Location eye = caster.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double maxRange = plugin.getConfig().getDouble("dragons-throw.target-range-blocks", 24.0);

        // Clip entity raytrace distance by the closest solid block in line of sight
        double effectiveRange = maxRange;
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, maxRange, FluidCollisionMode.NEVER, true);
        if (blockHit != null) {
            effectiveRange = Math.min(effectiveRange, blockHit.getHitPosition().distance(eye.toVector()));
        }

        RayTraceResult entityHit = world.rayTraceEntities(
                eye,
                dir,
                effectiveRange,
                0.75,
                entity -> isValidThrowTarget(caster, entity)
        );

        if (entityHit != null && entityHit.getHitEntity() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private boolean isValidThrowTarget(Player caster, Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
            return false;
        }
        if (living.getUniqueId().equals(caster.getUniqueId())) {
            return false;
        }
        if (living instanceof Player targetPlayer) {
            if (targetPlayer.getGameMode() == GameMode.SPECTATOR) {
                return false;
            }
            if (!plugin.getConfig().getBoolean("dragons-throw.target-creative", false)
                    && targetPlayer.getGameMode() == GameMode.CREATIVE) {
                return false;
            }
            return !targetPlayer.hasPermission("dragonegg.exempt");
        }
        return plugin.getConfig().getBoolean("dragons-throw.target-mobs", false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastLeftClick.remove(id);
        lastLeftEventDedupe.remove(id);
        lastRightClick.remove(id);
    }

    private record ClickRecord(UUID targetId, long timestampMs) {
    }
}
