package byd.cxkcxkckx.pluginupdate.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class UpdateGUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!UpdateGUIManager.isUpdateGUI(event.getView())) {
            return;
        }
        // Prevent taking items or placing items
        event.setCancelled(true);

        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            int rawSlot = event.getRawSlot();
            // Only handle clicks within top inventory
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                new UpdateGUIManager().handleClick(player, rawSlot);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!UpdateGUIManager.isUpdateGUI(event.getView())) {
            return;
        }
        // Cancel any dragging inside the top inventory
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!UpdateGUIManager.isUpdateGUI(event.getView())) {
            return;
        }
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        if (UpdateGUIManager.shouldSkipCloseCleanup(uuid)) {
            return;
        }
        // 延迟检测：若数tick后玩家仍未重新打开更新GUI，则进行清理；否则保留数据，避免“全选后后续点击无反应”
        org.bukkit.entity.HumanEntity he = event.getPlayer();
        byd.cxkcxkckx.pluginupdate.pluginupdate.getInstance().getServer().getScheduler().runTaskLater(
            byd.cxkcxkckx.pluginupdate.pluginupdate.getInstance(),
            () -> {
                boolean online = true;
                if (he instanceof org.bukkit.entity.Player) {
                    online = ((org.bukkit.entity.Player) he).isOnline();
                }
                if (!online) {
                    UpdateGUIManager.clearPlayerData(uuid);
                    return;
                }
                org.bukkit.inventory.InventoryView current = he.getOpenInventory();
                if (current == null || !UpdateGUIManager.isUpdateGUI(current)) {
                    UpdateGUIManager.clearPlayerData(uuid);
                }
            },
            2L
        );
    }
}
