package byd.cxkcxkckx.pluginupdate;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

import byd.cxkcxkckx.pluginupdate.commands.UpdateCommand;
import byd.cxkcxkckx.pluginupdate.gui.UpdateGUIListener;
import top.mrxiaom.pluginbase.BukkitPlugin;
import top.mrxiaom.pluginbase.paper.PaperFactory;
import top.mrxiaom.pluginbase.utils.inventory.InventoryFactory;
import top.mrxiaom.pluginbase.utils.item.ItemEditor;

public class pluginupdate extends BukkitPlugin {
    private byd.cxkcxkckx.pluginupdate.updater.PluginUpdater pluginUpdater;

    public static pluginupdate getInstance() {
        return (pluginupdate) BukkitPlugin.getInstance();
    }

    public byd.cxkcxkckx.pluginupdate.updater.PluginUpdater getPluginUpdater() {
        return pluginUpdater;
    }

    public pluginupdate() {
        super(options()
                .bungee(false)
                .adventure(true)
                .database(false)
                .reconnectDatabaseWhenReloadConfig(false)
                .scanIgnore("top.m.libs")
        );
        // this.scheduler = new FoliaLibScheduler(this);
    }

    @Override
    public @NotNull ItemEditor initItemEditor() {
        return PaperFactory.createItemEditor();
    }

    @Override
    public @NotNull InventoryFactory initInventoryFactory() {
        return PaperFactory.createInventoryFactory();
    }

    @Override
    public void onLoad() {
        try {
            java.io.File data = getDataFolder();
            // 每次启动清空 downloads/ 并重新创建空目录
            java.io.File downloadsDir = new java.io.File(data, "downloads");
            deleteRecursively(downloadsDir);
            try { downloadsDir.mkdirs(); } catch (Exception ignore) {}
            getLogger().info("已清空下载目录。");
        } catch (Exception ex) {
            getLogger().warning("清空下载目录时出现异常: " + ex.getMessage());
        }
    }

    // 从 JAR 的 plugin.yml/bungee.yml 读取插件名，用于匹配删除旧版文件
    private String readPluginNameFromJar(java.io.File jarFile) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            java.util.zip.ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) entry = jar.getEntry("bungee.yml");
            if (entry != null) {
                try (java.io.InputStream is = jar.getInputStream(entry)) {
                    java.util.Map<?, ?> yml = new org.yaml.snakeyaml.Yaml().load(is);
                    Object name = yml == null ? null : yml.get("name");
                    return name == null ? null : name.toString();
                }
            }
        } catch (java.io.IOException e) {
            // ignore
        }
        return null;
    }

    private void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        try { f.delete(); } catch (Exception ignore) {}
    }

    @Override
    protected void afterEnable() {
        // 创建单例 PluginUpdater（避免重复创建线程池）
        pluginUpdater = new byd.cxkcxkckx.pluginupdate.updater.PluginUpdater(this);
        Objects.requireNonNull(getCommand("update")).setExecutor(new UpdateCommand(this));
        getServer().getPluginManager().registerEvents(new UpdateGUIListener(), this);
        getLogger().info("plugin-update 加载完毕");
    }

    @Override
    protected void beforeDisable() {
        // 关闭 PluginUpdater 的线程池
        if (pluginUpdater != null) {
            pluginUpdater.shutdown();
        }
        // 清理所有玩家的 GUI 数据和 BossBar
        byd.cxkcxkckx.pluginupdate.gui.UpdateGUIManager.cleanupAll();
        getLogger().info("plugin-update 已清理所有资源");
    }
}
