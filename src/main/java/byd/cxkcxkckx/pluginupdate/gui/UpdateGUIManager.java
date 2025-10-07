package byd.cxkcxkckx.pluginupdate.gui;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import byd.cxkcxkckx.pluginupdate.pluginupdate;
import byd.cxkcxkckx.pluginupdate.updater.UpdateInfo;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

public class UpdateGUIManager {

    public static final String GUI_TITLE = "§2插件更新列表";
    private static final int PLUGINS_PER_PAGE = 45; // 5 rows * 9 columns

    // Store GUI data per player
    private static final Map<UUID, List<UpdateInfo>> playerUpdateData = new HashMap<>();
    private static final Map<UUID, Integer> playerCurrentPage = new HashMap<>();
    private static final Map<UUID, List<UpdateInfo>> playerSelections = new HashMap<>();
    private static final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    // 标记因内部重绘导致的InventoryClose，避免误清理玩家数据
    private static final java.util.Set<UUID> skipCloseCleanup = new java.util.HashSet<>();
    // 标记正在进行的页面重绘（关闭→打开），在完成前避免任何Close清理导致数据丢失
    private static final java.util.Set<UUID> pendingReopen = new java.util.HashSet<>();

    // InventoryHolder用于稳定识别我们的GUI，并携带页码
    private static final class UpdateGUIHolder implements org.bukkit.inventory.InventoryHolder {
        private final int page;
        UpdateGUIHolder(int page) { this.page = page; }
        int getPage() { return page; }
        @Override public org.bukkit.inventory.Inventory getInventory() { return null; } // 未使用
    }

    public static boolean shouldSkipCloseCleanup(UUID uuid) {
        return skipCloseCleanup.contains(uuid) || pendingReopen.contains(uuid);
    }

    private static void markSkipCloseCleanup(UUID uuid) {
        skipCloseCleanup.add(uuid);
    }

    private static void unmarkSkipCloseCleanup(UUID uuid) {
        skipCloseCleanup.remove(uuid);
    }

    private static void markPendingReopen(UUID uuid) {
        pendingReopen.add(uuid);
    }

    private static void unmarkPendingReopen(UUID uuid) {
        pendingReopen.remove(uuid);
    }

    public void openGUI(Player player, List<UpdateInfo> updates) {
        UUID playerUUID = player.getUniqueId();
        playerUpdateData.put(playerUUID, updates);
        playerCurrentPage.put(playerUUID, 0);
        playerSelections.put(playerUUID, new ArrayList<>());
        player.openInventory(createPage(player, 0));
    }

    public static void clearPlayerData(UUID uuid) {
        // 如果是内部触发的页面重绘导致的关闭事件，则跳过清理，避免"全选后界面失效"
        if (shouldSkipCloseCleanup(uuid)) {
            return;
        }
        playerUpdateData.remove(uuid);
        playerCurrentPage.remove(uuid);
        playerSelections.remove(uuid);
        BossBar bar = playerBossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
    }

    // 插件禁用时清理所有玩家数据
    public static void cleanupAll() {
        // 清理所有BossBar
        for (BossBar bar : playerBossBars.values()) {
            if (bar != null) {
                try { bar.removeAll(); } catch (Exception ignore) {}
            }
        }
        // 清空所有集合
        playerUpdateData.clear();
        playerCurrentPage.clear();
        playerSelections.clear();
        playerBossBars.clear();
        skipCloseCleanup.clear();
        pendingReopen.clear();
    }

    public static boolean isUpdateGUI(org.bukkit.inventory.InventoryView view) {
        org.bukkit.inventory.Inventory top = view.getTopInventory();
        org.bukkit.inventory.InventoryHolder holder = top != null ? top.getHolder() : null;
        return holder instanceof UpdateGUIHolder;
    }

    public void handleClick(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        List<UpdateInfo> updates = playerUpdateData.get(uuid);
        if (updates == null) return;

        int page = playerCurrentPage.getOrDefault(uuid, 0);
        int startIndex = page * PLUGINS_PER_PAGE;

        // Plugin items area
        if (slot >= 0 && slot < PLUGINS_PER_PAGE) {
            int index = startIndex + slot;
            if (index < updates.size()) {
                UpdateInfo info = updates.get(index);
                List<UpdateInfo> selections = playerSelections.get(uuid);
                if (selections.contains(info)) {
                    selections.remove(info);
                } else {
                    selections.add(info);
                }
                // 在下一tick刷新该槽位，避免同tick修改被客户端回滚（特别是经历过全选后的重绘）
                Bukkit.getScheduler().runTask(pluginupdate.getInstance(), () -> {
                    if (player.isOnline() && player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
                        Inventory inv = player.getOpenInventory().getTopInventory();
                        inv.setItem(slot, createPluginItem(info, selections.contains(info)));
                        try { player.updateInventory(); } catch (Throwable ignore) {}
                    }
                });
            }
            return;
        }

        // Control bar
        switch (slot) {
            case 45: // Prev page
                if (page > 0) {
                    page--;
                    playerCurrentPage.put(uuid, page);
                    reopenPage(player, page);
                }
                break;
            case 53: // Next page
                int totalPages = (int) Math.ceil((double) updates.size() / PLUGINS_PER_PAGE);
                if (page + 1 < Math.max(totalPages, 1)) {
                    page++;
                    playerCurrentPage.put(uuid, page);
                    reopenPage(player, page);
                }
                break;
            case 46: // Toggle select all on current page
                toggleSelectPage(player, page);
                break;
            case 49: // Confirm update selected
                // 备份所选插件到插件数据目录的 backup/时间戳/ 子目录
                List<UpdateInfo> selectionsForBackup = playerSelections.getOrDefault(uuid, new ArrayList<>());
                if (selectionsForBackup.isEmpty()) {
                    player.sendMessage("§e未选择任何插件，已取消。");
                    player.closeInventory();
                    break;
                }

                // 先在主线程解析待备份的插件JAR路径（避免在异步线程访问Bukkit API）
                Map<String, Path> toCopy = new HashMap<>();
                int resolveFail = 0;
                for (UpdateInfo info : selectionsForBackup) {
                    try {
                        Plugin target = Bukkit.getPluginManager().getPlugin(info.getPluginName());
                        if (target == null) {
                            resolveFail++;
                            continue;
                        }
                        Path jarPath = Paths.get(target.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                        toCopy.put(info.getPluginName(), jarPath);
                    } catch (Exception ex) {
                        resolveFail++;
                    }
                }

                // 关闭界面，提示开始备份与下载
                player.closeInventory();
                player.sendMessage("§a开始备份与下载更新包...");
    
                // 路径与变量
                Plugin main = pluginupdate.getInstance();
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                File backupDir = new File(main.getDataFolder(), "backup" + File.separator + timestamp);
                File downloadsDir = new File(main.getDataFolder(), "downloads" + File.separator + timestamp);
                final int resolveFailCount = resolveFail;
                final List<UpdateInfo> selected = new ArrayList<>(selectionsForBackup);
                final File pluginsDir = main.getDataFolder().getParentFile(); // plugins/

                // 准备 BossBar 与行动栏
                prepareProgressUI(player, "准备中", 0.0);

                // 异步执行：备份 -> 下载(最多5次重试) -> 原地卸载释放ClassLoader -> 替换旧文件 -> 自动重启
                main.getServer().getScheduler().runTaskAsynchronously(main, () -> {
                    int backupOk = 0, backupFail = 0;
                    java.util.concurrent.atomic.AtomicInteger downloadOk = new java.util.concurrent.atomic.AtomicInteger(0), downloadFail = new java.util.concurrent.atomic.AtomicInteger(0), excluded = new java.util.concurrent.atomic.AtomicInteger(0);
                    int replaceOk = 0, replaceFail = 0;
                    java.util.Set<String> replaceSuccessSet = new java.util.HashSet<>();
                    java.util.Set<String> replaceFailSet = new java.util.HashSet<>();

                    // 计算总步数（按尝试次数累计，成功或失败都会推进进度）
                    final int totalSteps = toCopy.size()                  // 备份每个已解析JAR
                            + selected.size()                             // 下载每个选择项（成功或失败都记一步）
                            + selected.size()                             // 卸载每个成功有下载的项（按已下载项推进）
                            + selected.size();                            // 替换每个成功有下载的项

                    final AtomicInteger currentSteps = new AtomicInteger(0);

                    // 创建目录
                    try { backupDir.mkdirs(); } catch (Exception ignore) {}
                    try { downloadsDir.mkdirs(); } catch (Exception ignore) {}
                    if (!backupDir.exists() || !backupDir.isDirectory()) {
                        final String err = "备份目录创建失败";
                        runUiUpdate(main, player, "错误：" + err, currentSteps.get(), Math.max(totalSteps, 1), BarColor.RED);
                        return;
                    }

                    // 执行备份
                    runUiUpdate(main, player, "备份中", currentSteps.get(), Math.max(totalSteps, 1), BarColor.BLUE);
                    for (Map.Entry<String, Path> entry : toCopy.entrySet()) {
                        try {
                            String jarName = entry.getValue().getFileName().toString();
                            Path dest = backupDir.toPath().resolve(jarName);
                            Files.copy(entry.getValue(), dest, StandardCopyOption.REPLACE_EXISTING);
                            backupOk++;
                        } catch (Exception ex) {
                            backupFail++;
                        } finally {
                            int cs = currentSteps.incrementAndGet();
                            runUiUpdate(main, player, "备份中", cs, Math.max(totalSteps, 1), BarColor.BLUE);
                        }
                    }

                    // 下载新JAR（并行：一插件一任务，按 CPU 核数限制并发）
                    Map<String, Path> downloadedMap = new java.util.concurrent.ConcurrentHashMap<>();
                    runUiUpdate(main, player, "下载中", currentSteps.get(), Math.max(totalSteps, 1), BarColor.YELLOW);
                    int workers = Math.min(selected.size(), Math.max(2, Runtime.getRuntime().availableProcessors()));
                    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
                    java.util.concurrent.CountDownLatch dlatch = new java.util.concurrent.CountDownLatch(selected.size());
                    for (UpdateInfo info : selected) {
                        pool.submit(() -> {
                            try {
                                String name = info.getPluginName();
                                Path oldJar = toCopy.get(name);
                                if (oldJar == null) {
                                    downloadFail.incrementAndGet();
                                    int cs = currentSteps.incrementAndGet();
                                    runUiUpdate(main, player, "下载中", cs, Math.max(totalSteps, 1), BarColor.YELLOW);
                                    return;
                                }
                                String url = info.getDownloadUrl();
                                Path newJar = downloadsDir.toPath().resolve(name + "-" + info.getNewVersion() + ".jar");
                                boolean downloaded = false;
                                if (url != null && !url.isEmpty()) {
                                    downloaded = downloadFile(url, newJar, 5);
                                }
                                if (!downloaded) {
                                    excluded.incrementAndGet();
                                } else {
                                    downloadedMap.put(name, newJar);
                                    downloadOk.incrementAndGet();
                                }
                            } finally {
                                int cs = currentSteps.incrementAndGet();
                                runUiUpdate(main, player, "下载中", cs, Math.max(totalSteps, 1), BarColor.YELLOW);
                                dlatch.countDown();
                            }
                        });
                    }
                    pool.shutdown();
                    try { dlatch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                    // 同步主线程：原地安全卸载（释放类加载器与文件锁）
                    CountDownLatch latch = new CountDownLatch(1);
                    main.getServer().getScheduler().runTask(main, () -> {
                        try {
                            runUiUpdate(main, player, "卸载中", currentSteps.get(), Math.max(totalSteps, 1), BarColor.PURPLE);
                            for (UpdateInfo info : selected) {
                                String name = info.getPluginName();
                                // 仅卸载成功下载的那些
                                if (downloadedMap.containsKey(name)) {
                                    safeUnloadPlugin(main, name);
                                }
                                int cs = currentSteps.incrementAndGet();
                                runUiUpdate(main, player, "卸载中", cs, Math.max(totalSteps, 1), BarColor.PURPLE);
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                    try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                    // 卸载完成后：异步替换文件（删除旧JAR -> 复制新JAR到原路径名）
                    runUiUpdate(main, player, "替换中", currentSteps.get(), Math.max(totalSteps, 1), BarColor.GREEN);
                    for (UpdateInfo info : selected) {
                        String name = info.getPluginName();
                        Path oldJar = toCopy.get(name);
                        Path newJar = downloadedMap.get(name);
                        if (oldJar == null || newJar == null) {
                            int cs = currentSteps.incrementAndGet();
                            runUiUpdate(main, player, "替换中", cs, Math.max(totalSteps, 1), BarColor.GREEN);
                            continue;
                        }
                        try {
                            Path target = pluginsDir.toPath().resolve(oldJar.getFileName());
                            try {
                                Files.deleteIfExists(target);
                            } catch (Exception ignore) {}
                            Files.copy(newJar, target, StandardCopyOption.REPLACE_EXISTING);
                            replaceOk++;
                            replaceSuccessSet.add(name);
                        } catch (Exception ex) {
                            replaceFail++;
                            replaceFailSet.add(name);
                        } finally {
                            int cs = currentSteps.incrementAndGet();
                            runUiUpdate(main, player, "替换中", cs, Math.max(totalSteps, 1), BarColor.GREEN);
                        }
                    }

                    final int backupOkF = backupOk;
                    final int backupFailF = resolveFailCount + backupFail;
                    final int downloadOkF = downloadOk.get();
                    final int downloadFailF = downloadFail.get();
                    final int excludedF = excluded.get();
                    final int replaceOkF = replaceOk;
                    final int replaceFailF = replaceFail;
                    final String backupPath = backupDir.getAbsolutePath();

                    // 完成并自动重启（主线程）
                    main.getServer().getScheduler().runTask(main, () -> {
                        // 100% 进度与提示
                        runUiUpdate(main, player, "完成，即将重启服务器", Math.max(totalSteps, 1), Math.max(totalSteps, 1), BarColor.GREEN);
                        player.sendMessage("§a备份完成。成功: " + backupOkF + "，失败: " + backupFailF + "。");
                        player.sendMessage("§7备份目录: " + backupPath);
                        player.sendMessage("§a下载完成。成功: " + downloadOkF + "，失败: " + downloadFailF + "，移出列表: " + excludedF + "。");
                        player.sendMessage("§a替换完成。成功: " + replaceOkF + "，失败: " + replaceFailF + "。");

                        // 在重启前，尝试生成更新报告书并放入玩家背包（若有空间）
                        try {
                            if (player.isOnline() && player.getInventory().firstEmpty() != -1) {
                                // 计算每个插件的下载/替换结果
                                java.util.Set<String> downloadSuccessSet = new java.util.HashSet<>(downloadedMap.keySet());
                                java.util.Set<String> downloadFailSet = new java.util.HashSet<>();
                                for (UpdateInfo info : selected) {
                                    if (!downloadSuccessSet.contains(info.getPluginName())) {
                                        downloadFailSet.add(info.getPluginName());
                                    }
                                }

                                String tsHuman = timestamp.replace('_', ' ');
                                StringBuilder sb = new StringBuilder();
                                sb.append("更新时间: ").append(tsHuman).append("\n");
                                sb.append("备份目录: ").append(backupPath).append("\n\n");
                                sb.append("下载 成功/失败/移出: ").append(downloadOkF).append("/").append(downloadFailF).append("/").append(excludedF).append("\n");
                                sb.append("替换 成功/失败: ").append(replaceOkF).append("/").append(replaceFailF).append("\n\n");

                                for (UpdateInfo info : selected) {
                                    String name = info.getPluginName();
                                    sb.append("插件: ").append(name).append("\n");
                                    sb.append("版本: ").append(info.getCurrentVersion()).append(" -> ").append(info.getNewVersion()).append("\n");
                                    // 来源信息（仅展示平台名称）
                                    String st = info.getSourceType();
                                    if (st != null && !st.isEmpty()) {
                                        String stLabel;
                                        switch (st.toLowerCase()) {
                                            case "modrinth": stLabel = "Modrinth"; break;
                                            case "spigot": stLabel = "Spigot"; break;
                                            case "github": stLabel = "GitHub"; break;
                                            default: stLabel = st;
                                        }
                                        sb.append("来源: ").append(stLabel);
                                        String rn = info.getResolvedName();
                                        if (rn != null && !rn.isEmpty() && "spigot".equalsIgnoreCase(st)) {
                                            sb.append(" (匹配: ").append(rn).append(")");
                                        }
                                        sb.append("\n");
                                    }
                                    // 不显示源链接与下载链接
                                    boolean dlOk = downloadSuccessSet.contains(name);
                                    boolean repOk = replaceSuccessSet.contains(name);
                                    if (!dlOk && downloadFailSet.contains(name)) {
                                        sb.append("下载: 失败").append("\n");
                                    } else {
                                        sb.append("下载: ").append(dlOk ? "成功" : "未执行").append("\n");
                                    }
                                    if (replaceFailSet.contains(name)) {
                                        sb.append("替换: 失败").append("\n");
                                    } else if (replaceSuccessSet.contains(name)) {
                                        sb.append("替换: 成功").append("\n");
                                    } else {
                                        sb.append("替换: 未执行").append("\n");
                                    }
                                    sb.append("\n");
                                }

                                // 分页写入书本（每页约240字符）
                                String all = sb.toString();
                                java.util.List<String> pages = new java.util.ArrayList<>();
                                int pageSize = 240;
                                for (int i = 0; i < all.length(); i += pageSize) {
                                    int end = Math.min(all.length(), i + pageSize);
                                    pages.add(all.substring(i, end));
                                }
                                org.bukkit.inventory.ItemStack book = new org.bukkit.inventory.ItemStack(org.bukkit.Material.WRITTEN_BOOK);
                                org.bukkit.inventory.meta.BookMeta bm = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
                                if (bm != null) {
                                    bm.setTitle("更新报告 " + tsHuman);
                                    bm.setAuthor("plugin-update");
                                    bm.setPages(pages);
                                    book.setItemMeta(bm);
                                }
                                player.getInventory().addItem(book);
                                player.sendMessage("§a已将本次更新报告放入你的背包。");
                            } else {
                                player.sendMessage("§e你的背包没有空格，未能放入更新报告，将直接重启。");
                            }
                        } catch (Throwable ignore) {}

                        // 清理 BossBar
                        BossBar bar = playerBossBars.remove(player.getUniqueId());
                        if (bar != null) bar.removeAll();
                        // 自动重启：优先尝试 /restart，失败则 /stop
                        boolean restarted = dispatchSafely("restart");
                        if (!restarted) {
                            dispatchSafely("stop");
                        }
                    });
                });

                // 清理该玩家的临时数据（BossBar保留至完成时移除）
                playerUpdateData.remove(uuid);
                playerCurrentPage.remove(uuid);
                playerSelections.remove(uuid);
                break;
            case 50: // Close
                player.closeInventory();
                break;
            default:
                break;
        }
    }

    private void toggleSelectPage(Player player, int page) {
        UUID uuid = player.getUniqueId();
        List<UpdateInfo> updates = playerUpdateData.get(uuid);
        List<UpdateInfo> selections = playerSelections.get(uuid);
        if (updates == null || selections == null) return;

        int startIndex = page * PLUGINS_PER_PAGE;
        boolean allSelected = true;
        for (int i = 0; i < PLUGINS_PER_PAGE; i++) {
            int idx = startIndex + i;
            if (idx >= updates.size()) break;
            if (!selections.contains(updates.get(idx))) {
                allSelected = false;
                break;
            }
        }

        // If all selected, unselect all; else select all
        for (int i = 0; i < PLUGINS_PER_PAGE; i++) {
            int idx = startIndex + i;
            if (idx >= updates.size()) break;
            UpdateInfo info = updates.get(idx);
            if (allSelected) {
                selections.remove(info);
            } else {
                if (!selections.contains(info)) selections.add(info);
            }
        }
        // 下一tick仅刷新物品区域并强制同步，避免close/open引发状态清理导致后续点击无效
        Bukkit.getScheduler().runTaskLater(pluginupdate.getInstance(), () -> {
            if (player.isOnline() && player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
                refreshPageItems(player, page);
                try { player.updateInventory(); } catch (Throwable ignore) {}
            }
        }, 1L);
    }

    // 避免在InventoryClick回调中立即openInventory导致界面“卡死”，
    // 对于全选/全不选仅刷新物品区域；翻页时再延迟一tick重新打开
    private void refreshPageItems(Player player, int page) {
        UUID uuid = player.getUniqueId();
        List<UpdateInfo> updates = playerUpdateData.get(uuid);
        List<UpdateInfo> selections = playerSelections.get(uuid);
        if (updates == null || selections == null) return;

        int startIndex = page * PLUGINS_PER_PAGE;
        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < PLUGINS_PER_PAGE; i++) {
            int updateIndex = startIndex + i;
            if (updateIndex >= updates.size()) {
                inv.setItem(i, null);
                continue;
            }
            UpdateInfo info = updates.get(updateIndex);
            boolean selected = selections.contains(info);
            inv.setItem(i, createPluginItem(info, selected));
        }
    }

    private void reopenPage(Player player, int page) {
        UUID uuid = player.getUniqueId();
        // 标记即将因内部重绘触发的关闭事件，避免Listener误清理数据
        markSkipCloseCleanup(uuid);
        // 标记正在重绘，以防止Close事件过早清理状态
        markPendingReopen(uuid);

        // 先在本tick关闭，再在下一tick重新创建并打开，避免客户端“半刷新”导致后续点击无效
        Bukkit.getScheduler().runTask(pluginupdate.getInstance(), () -> {
            if (player.isOnline()) {
                try { player.closeInventory(); } catch (Throwable ignore) {}
            }
        });

        Bukkit.getScheduler().runTaskLater(pluginupdate.getInstance(), () -> {
            if (player.isOnline()) {
                try {
                    player.openInventory(createPage(player, page));
                } catch (Throwable ignore) {}
            }
            // 关闭→打开序列完成后撤销标记
            unmarkSkipCloseCleanup(uuid);
            unmarkPendingReopen(uuid);
        }, 2L);
    }

    private Inventory createPage(Player player, int page) {
        UUID playerUUID = player.getUniqueId();
        List<UpdateInfo> updates = playerUpdateData.get(playerUUID);
        if (updates == null) updates = new ArrayList<>();
        int totalPages = (int) Math.ceil((double) updates.size() / PLUGINS_PER_PAGE);
        if (totalPages <= 0) totalPages = 1;

        Inventory gui = Bukkit.createInventory(new UpdateGUIHolder(page), 54, GUI_TITLE + " §8(" + (page + 1) + "/" + totalPages + ")");

        // Add plugin items
        int startIndex = page * PLUGINS_PER_PAGE;
        List<UpdateInfo> selections = playerSelections.getOrDefault(playerUUID, new ArrayList<>());
        for (int i = 0; i < PLUGINS_PER_PAGE; i++) {
            int updateIndex = startIndex + i;
            if (updateIndex >= updates.size()) {
                break;
            }
            UpdateInfo info = updates.get(updateIndex);
            boolean selected = selections.contains(info);
            gui.setItem(i, createPluginItem(info, selected));
        }

        // Control items (last row: 45-53)
        gui.setItem(45, makeButton(Material.ARROW, "§a上一页", "§7点击切换到上一页"));
        gui.setItem(46, makeButton(Material.YELLOW_WOOL, "§e本页全选/全不选", "§7对当前页所有项进行选择或取消选择"));
        gui.setItem(49, makeButton(Material.LIME_WOOL, "§a确认更新所选插件", "§7备份/下载 并自动重启"));
        gui.setItem(50, makeButton(Material.BARRIER, "§c关闭", "§7关闭该界面"));
        gui.setItem(53, makeButton(Material.ARROW, "§a下一页", "§7点击切换到下一页"));

        return gui;
    }

    private ItemStack createPluginItem(UpdateInfo info, boolean selected) {
        ItemStack item = new ItemStack(selected ? Material.ENCHANTED_BOOK : Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b" + info.getPluginName());
            List<String> lore = new ArrayList<>();
            lore.add("§7当前版本: §e" + info.getCurrentVersion());
            lore.add("§7最新版本: §a" + info.getNewVersion());
            String st = info.getSourceType();
            if (st != null && !st.isEmpty()) {
                String stLabel;
                switch (st.toLowerCase()) {
                    case "modrinth": stLabel = "Modrinth"; break;
                    case "spigot": stLabel = "Spigot"; break;
                    case "github": stLabel = "GitHub"; break;
                    default: stLabel = st;
                }
                lore.add("§7来源: §f" + stLabel);
            }
            // 若来源为 Spigot（泛搜索），在物品描述中附加实际匹配到的资源名称
            if ("spigot".equalsIgnoreCase(info.getSourceType())) {
                String rname = info.getResolvedName();
                if (rname != null && !rname.isEmpty()) {
                }
            }
            // 若来源通过搜索得到（配置未指定具体来源），提示可能不精确，并显示搜索命中
            if (info.isFromSearch()) {
                lore.add("§c注意: 该更新来自搜索结果，可能不完全匹配");
                String rn = info.getResolvedName();
                if (rn != null && !rn.isEmpty()) {
                    lore.add("§7搜索命中: §f" + rn);
                }
            }
            lore.add("");
            lore.add(selected ? "§a已选择（再次点击取消）" : "§6[点击] 选择/取消选择");
            meta.setLore(lore);
            if (selected) {
                meta.addEnchant(Enchantment.DURABILITY, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeButton(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            lore.add(loreLine);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean downloadFile(String urlStr, Path dest, int maxTries) {
        for (int i = 1; i <= maxTries; i++) {
            long startTime = System.currentTimeMillis();
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "PluginUpdateDownloader/1.0");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.connect();
                int code = conn.getResponseCode();
                if (code >= 400) {
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(dest.toFile());
                     BufferedOutputStream out = new BufferedOutputStream(fos)) {
                    byte[] buf = new byte[8192];
                    int len;
                    long total = 0L;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                        total += len;
                        // 单个插件下载超过60秒则放弃
                        if (System.currentTimeMillis() - startTime > 60000L) {
                            try { fos.close(); } catch (Exception ignore) {}
                            try { Files.deleteIfExists(dest); } catch (Exception ignore) {}
                            return false;
                        }
                    }
                    out.flush();
                    if (total > 0L) {
                        return true;
                    }
                }
            } catch (Exception ignore) {
                // retry
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    // ========== 原地卸载释放 ClassLoader（反射方式） ==========

    private static void safeUnloadPlugin(Plugin loggerPlugin, String pluginName) {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null) return;
        try {
            // 1) 先 disable，触发 onDisable，释放外部资源
            if (target.isEnabled()) {
                Bukkit.getPluginManager().disablePlugin(target);
            }
            // 2) 取消任务、注销监听器
            try { Bukkit.getScheduler().cancelTasks(target); } catch (Exception ignore) {}
            try { HandlerList.unregisterAll(target); } catch (Exception ignore) {}

            // 3) 注销命令
            unregisterCommands(target);

            // 4) 从 SimplePluginManager 的集合中移除引用
            try {
                Object pm = Bukkit.getPluginManager();
                Class<?> spm = pm.getClass();
                if ("org.bukkit.plugin.SimplePluginManager".equals(spm.getName())) {
                    Field pluginsF = spm.getDeclaredField("plugins");
                    Field lookupF = spm.getDeclaredField("lookupNames");
                    pluginsF.setAccessible(true);
                    lookupF.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    List<Plugin> plugins = (List<Plugin>) pluginsF.get(pm);
                    @SuppressWarnings("unchecked")
                    Map<String, Plugin> lookup = (Map<String, Plugin>) lookupF.get(pm);
                    if (plugins != null) plugins.remove(target);
                    if (lookup != null) {
                        Iterator<Map.Entry<String, Plugin>> it = lookup.entrySet().iterator();
                        while (it.hasNext()) {
                            if (it.next().getValue() == target) it.remove();
                        }
                    }
                }
            } catch (Throwable ignore) {}

            // 5) 关闭并清理其 ClassLoader（释放 JAR 文件锁）
            tryCloseClassLoader(target.getClass().getClassLoader());
        } catch (Throwable t) {
            try { loggerPlugin.getLogger().warning("卸载插件失败: " + pluginName + " - " + t.getMessage()); } catch (Exception ignore) {}
        }
    }

    private static void unregisterCommands(Plugin plugin) {
        try {
            Object pm = Bukkit.getPluginManager();
            Class<?> spm = pm.getClass();
            if (!"org.bukkit.plugin.SimplePluginManager".equals(spm.getName())) return;
            Field commandMapF = spm.getDeclaredField("commandMap");
            commandMapF.setAccessible(true);
            Object commandMap = commandMapF.get(pm);
            if (commandMap == null) return;

            Field knownF = commandMap.getClass().getDeclaredField("knownCommands");
            knownF.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> known = (Map<String, Command>) knownF.get(commandMap);
            if (known == null) return;

            Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Command> en = it.next();
                Command cmd = en.getValue();
                if (cmd instanceof PluginCommand) {
                    PluginCommand pc = (PluginCommand) cmd;
                    if (pc.getPlugin() == plugin) {
                        try {
                            Method unregister = cmd.getClass().getSuperclass().getDeclaredMethod("unregister", commandMap.getClass());
                            unregister.setAccessible(true);
                            unregister.invoke(cmd, commandMap);
                        } catch (Throwable ignore) {}
                        it.remove();
                    }
                }
            }
        } catch (Throwable ignore) {}
    }

    private static void tryCloseClassLoader(ClassLoader cl) {
        if (cl == null) return;
        // 尝试调用 close()
        try {
            try {
                Method m = cl.getClass().getDeclaredMethod("close");
                m.setAccessible(true);
                m.invoke(cl);
            } catch (NoSuchMethodException nsme) {
                if (cl instanceof java.net.URLClassLoader) {
                    try { ((java.net.URLClassLoader) cl).close(); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}

        // 关闭可能存在的内部 JarFile 字段
        try {
            Field f = cl.getClass().getDeclaredField("jar");
            f.setAccessible(true);
            Object jarObj = f.get(cl);
            if (jarObj instanceof java.util.jar.JarFile) {
                try { ((java.util.jar.JarFile) jarObj).close(); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}

        // 置空常见引用，帮助 GC
        try {
            Field pluginField = cl.getClass().getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(cl, null);
        } catch (Throwable ignore) {}
        try {
            Field pluginInitField = cl.getClass().getDeclaredField("pluginInit");
            pluginInitField.setAccessible(true);
            // 有的实现是 boolean
            try { pluginInitField.setBoolean(cl, false); } catch (Throwable e) { pluginInitField.set(cl, null); }
        } catch (Throwable ignore) {}
    }

    // ================= UI 与重启辅助 =================

    private void prepareProgressUI(Player player, String label, double progress) {
        BossBar bar = playerBossBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar(composeTitle(label, progress), BarColor.BLUE, BarStyle.SEGMENTED_10);
            playerBossBars.put(player.getUniqueId(), bar);
            bar.addPlayer(player);
        } else {
            bar.setTitle(composeTitle(label, progress));
        }
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        sendAction(player, "§e" + label + " §7(" + (int) Math.round(progress * 100) + "%)");
    }

    private void runUiUpdate(Plugin main, Player player, String label, int current, int total, BarColor color) {
        double progress = total <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (double) current / (double) total));
        main.getServer().getScheduler().runTask(main, () -> {
            BossBar bar = playerBossBars.get(player.getUniqueId());
            if (bar == null) {
                bar = Bukkit.createBossBar(composeTitle(label, progress), color, BarStyle.SEGMENTED_10);
                playerBossBars.put(player.getUniqueId(), bar);
                bar.addPlayer(player);
            }
            bar.setColor(color);
            bar.setProgress(progress);
            bar.setTitle(composeTitle(label, progress));
            sendAction(player, "§e" + label + " §7(" + (int) Math.round(progress * 100) + "%)");
        });
    }

    private String composeTitle(String label, double progress) {
        int pct = (int) Math.round(progress * 100.0);
        return "§a更新进度§7 - " + label + " §f" + pct + "%";
    }

    private void sendAction(Player player, String text) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
        } catch (Throwable t) {
            try {
                player.sendTitle("", text, 0, 40, 10);
            } catch (Throwable ignore) {}
        }
    }

    private boolean dispatchSafely(String command) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Throwable t) {
            return false;
        }
    }
}
