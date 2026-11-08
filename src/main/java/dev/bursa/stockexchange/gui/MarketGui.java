package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class MarketGui extends Gui {
    private int page = 0;

    public MarketGui(StockExchangePlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Bursa Saham");
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    public void render() {
        for (int i = 45; i < 54; i++) inv.setItem(i, Items.filler());

        List<Company> list = plugin.market().tradable();
        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        if (page >= pages) page = pages - 1;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Company c = list.get(idx);
            final String ticker = c.ticker;
            set(i, companyItem(c), click -> new DetailGui(plugin, player, ticker).open());
        }

        if (page > 0) {
            set(45, Items.of(Material.ARROW, "<yellow>◀ Sebelumnya"), click -> {
                page--;
                refresh();
            });
        }
        if (page < pages - 1) {
            set(53, Items.of(Material.ARROW, "<yellow>Berikutnya ▶"), click -> {
                page++;
                refresh();
            });
        }

        set(47, Items.of(Material.CHEST, "<aqua>Portofolio", "<gray>Lihat saham yang kamu miliki"),
                click -> new PortfolioGui(plugin, player).open());
        set(48, Items.of(Material.GOLD_BLOCK, "<gold>Top Investor", "<gray>Portofolio terkaya"),
                click -> new TopGui(plugin, player).open());
        set(49, Items.of(Material.BOOK, "<white>Berita Pasar", "<gray>Event & pengumuman terbaru"),
                click -> new NewsGui(plugin, player).open());
        set(50, Items.of(Material.EMERALD, "<green>Perusahaanku", "<gray>Kelola perusahaan buatanmu"),
                click -> new MyCompaniesGui(plugin, player).open());
        set(51, Items.of(Material.CLOCK, "<yellow>Segarkan"), click -> refresh());
    }

    private ItemStack companyItem(Company c) {
        List<String> lore = new ArrayList<>();
        if (c.status == Company.Status.IPO) {
            int sold = c.ipoOffered - c.ipoRemaining;
            lore.add("<light_purple>✦ IPO BERLANGSUNG");
            lore.add("<gray>Harga IPO: <white>" + Text.money(c.ipoPrice));
            lore.add("<gray>Terjual: <white>" + sold + "/" + c.ipoOffered);
            lore.add("<gray>Berakhir: <white>" + Text.duration(c.ipoEnd - System.currentTimeMillis()));
        } else {
            lore.add("<gray>Harga: <white>" + Text.money(c.price));
            lore.add("<gray>24 jam: " + Text.pct(c.changePercent()));
            lore.add("<gray>Tersedia: <white>" + c.poolShares + "<gray>/" + c.totalShares);
        }
        lore.add("<gray>Pemilik: <white>" + (c.owner == null ? "Bursa (sistem)" : Text.esc(Text.playerName(c.owner))));
        lore.add("");
        lore.add("<yellow>Klik untuk membuka");
        return Items.of(Items.icon(c), 1, "<white><bold>" + c.ticker + "</bold> <gray>" + Text.esc(c.name), lore);
    }
}
