package com.dragonegg.item;

import com.dragonegg.DragonEggPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class DragonEggItemFactory {

    private final DragonEggPlugin plugin;
    private final NamespacedKey mythicEggKey;

    public DragonEggItemFactory(DragonEggPlugin plugin) {
        this.plugin = plugin;
        this.mythicEggKey = new NamespacedKey(plugin, "mythic_dragon_egg");
    }

    public ItemStack createMythicDragonEgg() {
        ItemStack egg = new ItemStack(Material.DRAGON_EGG, 1);
        applyMythicMeta(egg);
        return egg;
    }

    /**
     * Any Dragon Egg (including the vanilla one dropped at the End Portal after defeating the Ender Dragon)
     * counts as the Mythic Dragon Egg and gets seamlessly upgraded with mythic formatting.
     */
    public boolean isDragonEgg(ItemStack item) {
        return item != null && item.getType() == Material.DRAGON_EGG;
    }

    public boolean hasMythicTag(ItemStack item) {
        if (!isDragonEgg(item) || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(mythicEggKey, PersistentDataType.BYTE);
    }

    public void applyMythicMeta(ItemStack item) {
        if (!isDragonEgg(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        String rawName = plugin.getConfig().getString("egg.name", "<dark_purple><bold>🐉 DRAGON EGG</bold></dark_purple>");
        meta.displayName(plugin.parse(rawName).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        List<String> loreRaw = plugin.getConfig().getStringList("egg.lore");
        if (!loreRaw.isEmpty()) {
            List<Component> lore = loreRaw.stream()
                    .map(line -> plugin.parse(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                    .toList();
            meta.lore(lore);
        }

        if (plugin.getConfig().getBoolean("egg.enchantment-glint", true)) {
            meta.setEnchantmentGlintOverride(true);
        }

        meta.getPersistentDataContainer().set(mythicEggKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    public boolean upgradeIfVanilla(ItemStack item) {
        if (!isDragonEgg(item) || hasMythicTag(item)) {
            return false;
        }
        applyMythicMeta(item);
        return true;
    }

    public boolean playerHasDragonEgg(Player player) {
        if (player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        for (ItemStack item : inv.getContents()) {
            if (isDragonEgg(item)) {
                return true;
            }
        }
        return isDragonEgg(player.getItemOnCursor());
    }

    public boolean playerHoldingDragonEgg(Player player) {
        if (player == null) {
            return false;
        }
        return isDragonEgg(player.getInventory().getItemInMainHand())
                || isDragonEgg(player.getInventory().getItemInOffHand());
    }

    public boolean upgradePlayerDragonEggs(Player player) {
        if (player == null) {
            return false;
        }
        boolean found = false;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (isDragonEgg(item)) {
                found = true;
                if (upgradeIfVanilla(item)) {
                    inv.setItem(i, item);
                }
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isDragonEgg(cursor)) {
            found = true;
            if (upgradeIfVanilla(cursor)) {
                player.setItemOnCursor(cursor);
            }
        }
        return found;
    }
}
