package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public abstract class Gui implements InventoryHolder {
    protected final StockExchangePlugin plugin;
    protected final Player player;
    protected Inventory inv;
    private final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();

    protected Gui(StockExchangePlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    protected abstract Component title();

    protected abstract int size();

    public abstract void render();

    public Player player() {
        return player;
    }

    public void open() {
        inv = Bukkit.createInventory(this, size(), title());
        render();
        player.openInventory(inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    protected void set(int slot, ItemStack item, Consumer<ClickType> action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    protected void fillAll() {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, Items.filler());
    }

    protected void refresh() {
        actions.clear();
        inv.clear();
        render();
    }

    public void handleClick(int slot, ClickType click) {
        Consumer<ClickType> a = actions.get(slot);
        if (a != null) a.accept(click);
    }

    protected void feedback(Market.Result r) {
        player.sendMessage(Text.mm(Text.PREFIX + (r.ok() ? "<green>" : "<red>") + r.message()));
    }
}
