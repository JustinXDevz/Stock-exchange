package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TopGui extends Gui {
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};

    public TopGui(StockExchangePlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Top Investor");
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    public void render() {
        fillAll();
        List<Map.Entry<UUID, Double>> top = plugin.market().topInvestors(SLOTS.length);
        for (int i = 0; i < top.size(); i++) {
            UUID u = top.get(i).getKey();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            sm.displayName(Text.mm("<gold>#" + (i + 1) + " <white>" + Text.esc(Text.playerName(u))));
            sm.lore(List.of(Text.mm("<gray>Nilai portofolio: <green>" + Text.money(top.get(i).getValue()))));
            head.setItemMeta(sm);
            set(SLOTS[i], head, null);
        }
        if (top.isEmpty()) {
            set(13, Items.of(Material.BARRIER, "<gray>Belum ada investor"), null);
        }
        set(22, Items.of(Material.ARROW, "<yellow>◀ Kembali"), click -> new SectorGui(plugin, player).open());
    }
}
