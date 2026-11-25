package com.dragonegg.command;

import com.dragonegg.DragonEggPlugin;
import com.dragonegg.item.DragonEggItemFactory;
import com.dragonegg.manager.EggStateManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DragonEggCommand implements CommandExecutor, TabCompleter {

    private final DragonEggPlugin plugin;
    private final DragonEggItemFactory itemFactory;
    private final EggStateManager stateManager;

    public DragonEggCommand(
            DragonEggPlugin plugin,
            DragonEggItemFactory itemFactory,
            EggStateManager stateManager
    ) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.stateManager = stateManager;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> handleGive(sender, args);
            case "charge" -> handleCharge(sender, args);
            case "reset" -> handleReset(sender);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dragonegg.admin.give")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(plugin.parse("<red>Player not found: " + args[1] + "</red>"));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.parse("<red>Usage: /dragonegg give <player></red>"));
            return;
        }

        ItemStack egg = itemFactory.createMythicDragonEgg();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(egg);
        overflow.values().forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));

        stateManager.updateWielder(target, true);
        sender.sendMessage(plugin.parse("<green>Gave <dark_purple><bold>🐉 DRAGON EGG</bold></dark_purple> to <white>" + target.getName() + "</white>.</green>"));
    }

    private void handleCharge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dragonegg.admin.charge")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.parse("<red>Usage: /dragonegg charge <0-100></red>"));
            return;
        }
        try {
            int pct = Integer.parseInt(args[1]);
            stateManager.setChargePercent(pct);
            sender.sendMessage(plugin.parse("<green>Set Dragon's Throw charge to <white>" + stateManager.getChargePercent() + "%</white>.</green>"));
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.parse("<red>Enter a number between 0 and 100.</red>"));
        }
    }

    private void handleReset(CommandSender sender) {
        if (!sender.hasPermission("dragonegg.admin.reset")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        stateManager.resetBreathCooldown();
        sender.sendMessage(plugin.parse("<green>Reset Dragon's Breath cooldown.</green>"));
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("dragonegg.admin.status")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        long breathRem = stateManager.getBreathCooldownRemainingSeconds();
        String breathStatus = (breathRem <= 0L) ? "<green>READY</green>" : "<red>" + breathRem + "s</red>";
        sender.sendMessage(plugin.parse(
                "<dark_purple><bold>🐉 Dragon Egg Status:</bold></dark_purple> "
                        + "<gray>Wielder:</gray> <white>" + stateManager.getCurrentWielderName() + "</white>"
                        + " <dark_gray>|</dark_gray> <gray>Throw Charge:</gray> <light_purple>" + stateManager.getChargePercent() + "%</light_purple>"
                        + " <dark_gray>|</dark_gray> <gray>Breath:</gray> " + breathStatus
        ));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("dragonegg.admin.reload")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        plugin.reloadConfig();
        stateManager.refreshAllBossBars();
        sender.sendMessage(plugin.msg("reloaded"));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(plugin.parse("<dark_purple><bold>DragonEgg Commands:</bold></dark_purple>"));
        sender.sendMessage(plugin.parse("<gray>/dragonegg give [player]</gray>"));
        sender.sendMessage(plugin.parse("<gray>/dragonegg charge <0-100></gray>"));
        sender.sendMessage(plugin.parse("<gray>/dragonegg reset</gray>"));
        sender.sendMessage(plugin.parse("<gray>/dragonegg status</gray>"));
        sender.sendMessage(plugin.parse("<gray>/dragonegg reload</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return List.of("give", "charge", "reset", "status", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("charge")) {
            return List.of("0", "25", "50", "75", "100").stream()
                    .filter(s -> s.startsWith(args[1]))
                    .toList();
        }
        return List.of();
    }
}
