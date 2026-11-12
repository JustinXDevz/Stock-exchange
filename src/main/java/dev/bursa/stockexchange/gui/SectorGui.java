package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Menu utama: saham dibagi per sektor. */
public final class SectorGui extends Gui {
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    public SectorGui(StockExchangePlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Bursa Saham - Pilih Sektor");
    }

    @Override
    protected int size() {
        return 45;
    }

    @Override
    public void render() {
        fillAll();
        List<Market.SectorStat> stats = plugin.market().sectorStats();
        for (int i = 0; i < Math.min(SLOTS.length, stats.size()); i++) {
            Market.SectorStat st = stats.get(i);
            Material mat = Material.matchMaterial(st.icon());
            if (mat == null || !mat.isItem() || mat.isAir()) mat = Material.PAPER;

            List<String> lore = new ArrayList<>();
            lore.add("<gray>Saham: <white>" + st.count() + (st.ipo() > 0 ? " <light_purple>(" + st.ipo() + " IPO)" : ""));
            if (st.count() - st.ipo() > 0) {
                lore.add("<gray>Rata-rata 24 jam: " + Text.pct(st.avgChange()));
                if (st.best() != null) lore.add("<gray>Terbaik: <white>" + st.best().ticker + " " + Text.pct(st.best().changePercent()));
                if (st.worst() != null) lore.add("<gray>Terburuk: <white>" + st.worst().ticker + " " + Text.pct(st.worst().changePercent()));
            } else {
                lore.add("<dark_gray>Belum ada saham aktif");
            }
            lore.add("");
            lore.add("<yellow>Klik untuk membuka");
            final String id = st.id();
            set(SLOTS[i], Items.of(mat, 1, "<white><bold>" + Text.esc(st.name()), lore),
                    click -> new MarketGui(plugin, player, id).open());
        }

        set(38, Items.of(Material.CHEST, "<aqua>Portofolio", "<gray>Saham yang kamu miliki"),
                click -> new PortfolioGui(plugin, player).open());
        set(39, Items.of(Material.GOLD_BLOCK, "<gold>Top Investor"), click -> new TopGui(plugin, player).open());
        set(40, Items.of(Material.NETHER_STAR, "<white>Semua Saham", "<gray>Tanpa filter sektor"),
                click -> new MarketGui(plugin, player).open());
        set(41, Items.of(Material.BOOK, "<white>Berita Pasar"), click -> new NewsGui(plugin, player).open());
        set(42, Items.of(Material.EMERALD, "<green>Perusahaanku"), click -> new MyCompaniesGui(plugin, player).open());
    }
}
