package com.dragonegg.manager;

import com.dragonegg.DragonEggPlugin;
import com.dragonegg.item.DragonEggItemFactory;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EggStateManager {

    private final DragonEggPlugin plugin;
    private final DragonEggItemFactory itemFactory;
    private final File file;

    private UUID currentWielderUuid = null;
    private String currentWielderName = "None";
    private int chargePercent = 0;
    private int secondsAccumulator = 0;
    private long breathCooldownExpiryMs = 0L;

    private final Map<UUID, BossBar> playerBars = new ConcurrentHashMap<>();
    private BukkitTask tickerTask;

    public EggStateManager(DragonEggPlugin plugin, DragonEggItemFactory itemFactory) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.file = new File(plugin.getDataFolder(), "state.yml");
    }

    public UUID getCurrentWielderUuid() {
        return currentWielderUuid;
    }

    public String getCurrentWielderName() {
        return currentWielderName;
    }

    public int getChargePercent() {
        return chargePercent;
    }

    public int getMaxCharge() {
        return Math.max(1, plugin.getConfig().getInt("dragons-throw.max-charge", 100));
    }

    public void setChargePercent(int value) {
        this.chargePercent = Math.max(0, Math.min(getMaxCharge(), value));
        this.secondsAccumulator = 0;
        save();
        refreshAllBossBars();
    }

    public int consumeCharge() {
        int current = this.chargePercent;
        this.chargePercent = 0;
        this.secondsAccumulator = 0;
        save();
        refreshAllBossBars();
        return current;
    }

    public boolean isBreathOnCooldown() {
        return System.currentTimeMillis() < breathCooldownExpiryMs;
    }

    public long getBreathCooldownRemainingSeconds() {
        long diff = breathCooldownExpiryMs - System.currentTimeMillis();
        if (diff <= 0L) {
            return 0L;
        }
        return (long) Math.ceil(diff / 1000.0);
    }

    public void startBreathCooldown() {
        long seconds = plugin.getConfig().getLong("dragons-breath.cooldown-seconds", 60L);
        this.breathCooldownExpiryMs = System.currentTimeMillis() + (seconds * 1000L);
        save();
        refreshAllBossBars();
    }

    public void resetBreathCooldown() {
        this.breathCooldownExpiryMs = 0L;
        save();
        refreshAllBossBars();
    }

    public void load() {
        if (!file.exists()) {
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String uuidStr = yaml.getString("wielder-uuid", "");
        if (uuidStr != null && !uuidStr.isBlank()) {
            try {
                this.currentWielderUuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ignored) {
                this.currentWielderUuid = null;
            }
        } else {
            this.currentWielderUuid = null;
        }
        this.currentWielderName = yaml.getString("wielder-name", "None");
        this.chargePercent = Math.max(0, Math.min(getMaxCharge(), yaml.getInt("throw-charge", 0)));
        this.breathCooldownExpiryMs = Math.max(0L, yaml.getLong("breath-cooldown-expiry", 0L));
    }

    public void save() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("wielder-uuid", currentWielderUuid != null ? currentWielderUuid.toString() : "");
        yaml.set("wielder-name", currentWielderName != null ? currentWielderName : "None");
        yaml.set("throw-charge", chargePercent);
        yaml.set("breath-cooldown-expiry", breathCooldownExpiryMs);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save state.yml: " + e.getMessage());
        }
    }

    public void updateWielder(Player newWielder, boolean broadcast) {
        if (newWielder == null) {
            return;
        }
        UUID newId = newWielder.getUniqueId();
        boolean changed = !newId.equals(this.currentWielderUuid);

        this.currentWielderUuid = newId;
        this.currentWielderName = newWielder.getName();
        save();

        if (changed && broadcast && plugin.getConfig().getBoolean("egg.broadcast-on-wielder-change", true)) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String raw = plugin.getConfig().getString("messages.wielder-changed-broadcast", "")
                    .replace("{player}", newWielder.getName());
            Bukkit.broadcast(plugin.parse(prefix + raw));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.55f, 1.15f);
            }
        }

        updateBossBarFor(newWielder);
    }

    public void startTasks() {
        stopTasks();
        // Runs every 1 second (20 ticks) for crisp BossBar updates and exact 1% / 2s charge rate
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickOneSecond, 20L, 20L);
    }

    public void stopTasks() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        for (Map.Entry<UUID, BossBar> entry : playerBars.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) {
                p.hideBossBar(entry.getValue());
            }
        }
        playerBars.clear();
    }

    private void tickOneSecond() {
        boolean chargeOnlyHeld = plugin.getConfig().getBoolean("egg.charge-only-when-held", false);
        int intervalSec = Math.max(1, plugin.getConfig().getInt("dragons-throw.charge-interval-seconds", 2));
        int gain = Math.max(1, plugin.getConfig().getInt("dragons-throw.charge-per-interval", 1));
        int maxCharge = getMaxCharge();

        Player activeCarrier = findOnlineEggCarrier();
        if (activeCarrier != null) {
            // Keep wielder synced if someone obtained it via edge cases (e.g., /give or plugins)
            if (!activeCarrier.getUniqueId().equals(currentWielderUuid)) {
                itemFactory.upgradePlayerDragonEggs(activeCarrier);
                updateWielder(activeCarrier, true);
            }

            boolean canCharge = !chargeOnlyHeld || itemFactory.playerHoldingDragonEgg(activeCarrier);
            if (canCharge && chargePercent < maxCharge) {
                secondsAccumulator++;
                if (secondsAccumulator >= intervalSec) {
                    secondsAccumulator = 0;
                    chargePercent = Math.min(maxCharge, chargePercent + gain);
                    if (chargePercent == maxCharge) {
                        activeCarrier.playSound(activeCarrier.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.5f);
                    }
                    // Persist periodically every 10% or at max charge to avoid disk spam
                    if (chargePercent % 10 == 0 || chargePercent == maxCharge) {
                        save();
                    }
                }
            }
        }

        refreshAllBossBars();
    }

    private Player findOnlineEggCarrier() {
        if (currentWielderUuid != null) {
            Player current = Bukkit.getPlayer(currentWielderUuid);
            if (current != null && current.isOnline() && itemFactory.playerHasDragonEgg(current)) {
                return current;
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (itemFactory.playerHasDragonEgg(p)) {
                return p;
            }
        }
        return null;
    }

    public void refreshAllBossBars() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateBossBarFor(p);
        }
    }

    public void updateBossBarFor(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean showBar = plugin.getConfig().getBoolean("egg.show-bossbar-while-held", true);
        boolean holding = itemFactory.playerHoldingDragonEgg(player);

        if (!showBar || !holding) {
            hideBossBar(player);
            return;
        }

        int maxCharge = getMaxCharge();
        float progress = Math.max(0.0f, Math.min(1.0f, (float) chargePercent / (float) maxCharge));
        double maxHeight = plugin.getConfig().getDouble("dragons-throw.max-launch-height-blocks", 200.0);
        int estHeight = (int) Math.round((chargePercent / (double) maxCharge) * maxHeight);

        long breathRem = getBreathCooldownRemainingSeconds();
        String breathText = (breathRem <= 0L)
                ? "<green>READY</green>"
                : "<red>" + breathRem + "s</red>";

        String chargeColor = (chargePercent >= maxCharge) ? "<light_purple><bold>" : "<white>";
        String chargeClose = (chargePercent >= maxCharge) ? "</bold></light_purple>" : "</white>";

        Component title = plugin.parse(
                "<dark_purple><bold>🐉 Dragon's Throw:</bold></dark_purple> "
                        + chargeColor + chargePercent + "%" + chargeClose
                        + " <gray>(" + estHeight + " blks)</gray>"
                        + " <dark_gray>|</dark_gray> <dark_purple>Breath:</dark_purple> " + breathText
        );

        BossBar.Color barColor = (chargePercent >= maxCharge) ? BossBar.Color.PINK : BossBar.Color.PURPLE;

        BossBar bar = playerBars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar created = BossBar.bossBar(title, progress, barColor, BossBar.Overlay.NOTCHED_10);
            player.showBossBar(created);
            return created;
        });

        bar.name(title);
        bar.progress(progress);
        bar.color(barColor);
    }

    public void hideBossBar(Player player) {
        if (player == null) {
            return;
        }
        BossBar bar = playerBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }
}
