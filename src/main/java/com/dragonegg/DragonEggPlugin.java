package com.dragonegg;

import com.dragonegg.command.DragonEggCommand;
import com.dragonegg.item.DragonEggItemFactory;
import com.dragonegg.listener.DragonEggAbilityListener;
import com.dragonegg.listener.EggLifecycleListener;
import com.dragonegg.manager.DragonBreathManager;
import com.dragonegg.manager.DragonThrowManager;
import com.dragonegg.manager.EggStateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class DragonEggPlugin extends JavaPlugin {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private DragonEggItemFactory itemFactory;
    private EggStateManager stateManager;
    private DragonThrowManager throwManager;
    private DragonBreathManager breathManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemFactory = new DragonEggItemFactory(this);
        this.stateManager = new EggStateManager(this, itemFactory);
        this.throwManager = new DragonThrowManager(this);
        this.breathManager = new DragonBreathManager(this);

        stateManager.load();
        stateManager.startTasks();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new EggLifecycleListener(this, itemFactory, stateManager), this);
        pm.registerEvents(new DragonEggAbilityListener(this, itemFactory, stateManager, throwManager, breathManager), this);

        PluginCommand cmd = getCommand("dragonegg");
        if (cmd != null) {
            DragonEggCommand executor = new DragonEggCommand(this, itemFactory, stateManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("DragonEgg v" + getPluginMeta().getVersion() + " enabled. Current wielder: "
                + stateManager.getCurrentWielderName() + " | Throw charge: " + stateManager.getChargePercent() + "%");
    }

    @Override
    public void onDisable() {
        if (throwManager != null) {
            throwManager.stopAll();
        }
        if (breathManager != null) {
            breathManager.stopAll();
        }
        if (stateManager != null) {
            stateManager.stopTasks();
            stateManager.save();
        }
    }

    public Component msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "");
        String raw = getConfig().getString("messages." + path, "");
        return MINI_MESSAGE.deserialize(prefix + raw);
    }

    public Component rawMsg(String path) {
        String raw = getConfig().getString("messages." + path, "");
        return MINI_MESSAGE.deserialize(raw);
    }

    public Component parse(String miniMessage) {
        return MINI_MESSAGE.deserialize(miniMessage);
    }
}
