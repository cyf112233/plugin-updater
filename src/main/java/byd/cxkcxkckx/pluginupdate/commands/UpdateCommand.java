package byd.cxkcxkckx.pluginupdate.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import byd.cxkcxkckx.pluginupdate.gui.UpdateGUIManager;
import byd.cxkcxkckx.pluginupdate.pluginupdate;
import byd.cxkcxkckx.pluginupdate.updater.PluginUpdater;

public class UpdateCommand implements CommandExecutor {

    private final pluginupdate plugin;

    public UpdateCommand(pluginupdate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("pluginupdate.use")) {
            sender.sendMessage("§c你没有权限执行此命令。");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§e请在游戏内使用该命令以打开更新界面。");
            return true;
        }
        Player player = (Player) sender;

        // 先检查玩家背包是否有空间；若没有，主标题提示并倒计10秒后再开始检查
        final int requiredSlots = 1; // 为放置更新报告书至少保留1格
        final Runnable startCheck = () -> {
            // 检查玩家是否仍在线
            if (!player.isOnline()) return;
            
            sender.sendMessage("§a正在检查插件更新...");
            // 创建BossBar并显示初始进度
            final org.bukkit.boss.BossBar bar = org.bukkit.Bukkit.createBossBar("§a更新进度§7 - 检查中 §f0%", org.bukkit.boss.BarColor.BLUE, org.bukkit.boss.BarStyle.SEGMENTED_10);
            bar.addPlayer(player);
            bar.setProgress(0.0);

            PluginUpdater updater = plugin.getPluginUpdater();
            updater.loadConfig();
            updater.checkUpdatesWithProgress((done, total) -> {
                double progress = total <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (double) done / (double) total));
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        bar.setColor(org.bukkit.boss.BarColor.BLUE);
                        bar.setProgress(progress);
                        bar.setTitle("§a更新进度§7 - 检查中 §f" + (int) Math.round(progress * 100) + "%");
                        // 动作栏提示
                        try {
                            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                    new net.md_5.bungee.api.chat.TextComponent("§e检查更新中 §7(" + (int) Math.round(progress * 100) + "%)"));
                        } catch (Throwable t) {
                            try { player.sendTitle("", "§e检查更新中 §7(" + (int) Math.round(progress * 100) + "%)", 0, 40, 10); } catch (Throwable ignore) {}
                        }
                    } catch (Throwable ignore) {}
                });
            }).thenAccept(updates -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try { bar.removeAll(); } catch (Throwable ignore) {}
                    new UpdateGUIManager().openGUI(player, updates);
                    sender.sendMessage("§a已打开插件更新界面。");
                });
            }).exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try { bar.removeAll(); } catch (Throwable ignore) {}
                    sender.sendMessage("§c检查更新失败: " + ex.getMessage());
                });
                return null;
            });
        };

        if (player.getInventory().firstEmpty() == -1) {
            // 无空间：倒计时提示10秒后继续
            final java.util.concurrent.atomic.AtomicInteger seconds = new java.util.concurrent.atomic.AtomicInteger(10);
            final org.bukkit.scheduler.BukkitTask[] tRef = new org.bukkit.scheduler.BukkitTask[1];
            tRef[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    // 检查玩家是否仍在线，如果下线则取消任务
                    if (!player.isOnline()) {
                        try { tRef[0].cancel(); } catch (Throwable ignore) {}
                        return;
                    }
                    
                    int s = seconds.getAndDecrement();
                    if (s > 0) {
                        try {
                            player.sendTitle("§e请在背包预留至少 " + requiredSlots + " 格", "§7等待: " + s + "s", 0, 20, 0);
                        } catch (Throwable ignore) {}
                    } else {
                        try { tRef[0].cancel(); } catch (Throwable ignore) {}
                        startCheck.run();
                    }
                }
            }, 0L, 20L);
        } else {
            // 有空间：直接开始检查
            startCheck.run();
        }

        return true;
    }
}
