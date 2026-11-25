package com.dragonegg.listener;

import com.dragonegg.DragonEggPlugin;
import com.dragonegg.item.DragonEggItemFactory;
import com.dragonegg.manager.EggStateManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

public final class EggLifecycleListener implements Listener {

    private final DragonEggPlugin plugin;
    private final DragonEggItemFactory itemFactory;
    private final EggStateManager stateManager;

    public EggLifecycleListener(
            DragonEggPlugin plugin,
            DragonEggItemFactory itemFactory,
            EggStateManager stateManager
    ) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.stateManager = stateManager;
    }

    /**
     * When the vanilla Dragon Egg drops from the End Portal (torch trick, piston, etc.),
     * automatically bless it into the Mythic Dragon Egg item.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        ItemStack stack = itemEntity.getItemStack();
        if (itemFactory.isDragonEgg(stack) && itemFactory.upgradeIfVanilla(stack)) {
            itemEntity.setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!plugin.getConfig().getBoolean("egg.indestructible-drop", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (itemFactory.isDragonEgg(item.getItemStack())
                && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent event) {
        if (!plugin.getConfig().getBoolean("egg.indestructible-drop", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (itemFactory.isDragonEgg(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("egg.prevent-place", true)) {
            return;
        }
        if (itemFactory.isDragonEgg(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(plugin.rawMsg("place-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupEgg(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (itemFactory.isDragonEgg(stack)) {
            itemFactory.upgradeIfVanilla(stack);
            event.getItem().setItemStack(stack);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    itemFactory.upgradePlayerDragonEggs(player);
                    stateManager.updateWielder(player, true);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (itemFactory.upgradePlayerDragonEggs(player)) {
                stateManager.updateWielder(player, true);
            } else {
                stateManager.updateBossBarFor(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (itemFactory.isDragonEgg(event.getOldCursor())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && itemFactory.upgradePlayerDragonEggs(player)) {
                    stateManager.updateWielder(player, true);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHotbarSwitch(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                stateManager.updateBossBarFor(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                stateManager.updateBossBarFor(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                stateManager.updateBossBarFor(player);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (itemFactory.upgradePlayerDragonEggs(player)) {
            stateManager.updateWielder(player, true);
        } else {
            stateManager.updateBossBarFor(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stateManager.hideBossBar(event.getPlayer());
    }
}
