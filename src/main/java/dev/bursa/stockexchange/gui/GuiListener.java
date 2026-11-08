package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class GuiListener implements Listener {
    private final StockExchangePlugin plugin;

    public GuiListener(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof Gui gui)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.getUniqueId().equals(gui.player().getUniqueId())) return;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null || clicked != top) return;

        final int slot = e.getSlot();
        final ClickType type = e.getClick();
        // Jalankan di tick berikutnya agar aman membuka/menutup inventory.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() == gui) {
                gui.handleClick(slot, type);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Gui) {
            e.setCancelled(true);
        }
    }
}
