package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.model.Company.Status;
import dev.bursa.stockexchange.model.Holding;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DetailGui extends Gui {
    private final String ticker;

    public DetailGui(StockExchangePlugin plugin, Player player, String ticker) {
        super(plugin, player);
        this.ticker = ticker;
    }

    @Override
    protected Component title() {
        Company c = plugin.market().get(ticker);
        String name = c == null ? ticker : c.ticker + " - " + c.name;
        return Text.mm("<dark_gray>" + Text.esc(name));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    public void render() {
        fillAll();
        Company c = plugin.market().get(ticker);
        set(0, Items.of(Material.ARROW, "<yellow>◀ Kembali"), click -> new MarketGui(plugin, player).open());
        if (c == null) return;

        set(4, infoItem(c), null);
        set(8, Items.of(Material.CHEST, "<aqua>Portofolio"), click -> new PortfolioGui(plugin, player).open());
        if (player.getUniqueId().equals(c.owner)) {
            set(6, Items.of(Material.NETHER_STAR, "<gold>Kelola Perusahaan", "<gray>Kas, dividen, IPO"),
                    click -> new ManageGui(plugin, player, ticker).open());
        }
        if (c.status != Status.IPO && c.status != Status.LISTED) return;

        Market m = plugin.market();
        String verb = c.status == Status.IPO ? "Beli IPO" : "Beli";
        int[] qtys = {1, 10, 64};
        int[] buySlots = {10, 11, 12};
        for (int i = 0; i < qtys.length; i++) {
            final int q = qtys[i];
            set(buySlots[i],
                    Items.of(Material.LIME_CONCRETE, q, "<green>" + verb + " x" + q,
                            List.of("<gray>Estimasi bayar: <white>" + Text.money(m.quoteBuy(c, q)),
                                    "<gray>Klik untuk membeli")),
                    click -> buy(q));
        }
        set(13, Items.of(Material.EMERALD, "<green>" + verb + " jumlah lain", "<gray>Ketik jumlah di chat"),
                click -> plugin.prompts().ask(player, "Ketik jumlah saham yang ingin dibeli:", s -> {
                    Integer q = Text.parseInt(s);
                    if (q == null) {
                        plugin.msg(player, "<red>Jumlah tidak valid.");
                    } else {
                        feedback(plugin.market().buy(player, plugin.market().get(ticker), q));
                    }
                    new DetailGui(plugin, player, ticker).open();
                }));

        Holding h = c.holders.get(player.getUniqueId());
        set(15, positionItem(c, h), null);

        if (c.status == Status.LISTED) {
            int[] sellSlots = {19, 20, 21};
            for (int i = 0; i < qtys.length; i++) {
                final int q = qtys[i];
                set(sellSlots[i],
                        Items.of(Material.RED_CONCRETE, q, "<red>Jual x" + q,
                                List.of("<gray>Estimasi terima: <white>" + Text.money(m.quoteSell(c, q)),
                                        "<gray>Klik untuk menjual")),
                        click -> sell(q));
            }
            int maxSell = h == null ? 0 : Math.min(h.shares, m.maxTrade(c));
            set(22, Items.of(Material.ORANGE_CONCRETE, "<gold>Jual maksimal (" + maxSell + ")",
                            "<gray>Dibatasi " + m.maxTrade(c) + " saham per transaksi"),
                    click -> {
                        Holding hh = plugin.market().get(ticker).holders.get(player.getUniqueId());
                        int q = hh == null ? 0 : Math.min(hh.shares, plugin.market().maxTrade(plugin.market().get(ticker)));
                        if (q <= 0) plugin.msg(player, "<red>Kamu tidak punya saham ini.");
                        else sell(q);
                    });
            drawChart(c);
        }
    }

    private void buy(int q) {
        Company c = plugin.market().get(ticker);
        if (c == null) return;
        feedback(plugin.market().buy(player, c, q));
        refresh();
    }

    private void sell(int q) {
        Company c = plugin.market().get(ticker);
        if (c == null) return;
        feedback(plugin.market().sell(player, c, q));
        refresh();
    }

    private ItemStack infoItem(Company c) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Status: <white>" + c.status.name());
        if (c.status == Status.IPO) {
            int sold = c.ipoOffered - c.ipoRemaining;
            lore.add("<gray>Harga IPO: <white>" + Text.money(c.ipoPrice));
            lore.add("<gray>Terjual: <white>" + sold + "/" + c.ipoOffered);
            lore.add("<gray>Berakhir: <white>" + Text.duration(c.ipoEnd - System.currentTimeMillis()));
            lore.add("<gray>Jika kurang peminat, uang dikembalikan.");
        } else if (c.status == Status.LISTED) {
            lore.add("<gray>Harga: <white>" + Text.money(c.price));
            lore.add("<gray>24 jam: " + Text.pct(c.changePercent()));
            lore.add("<gray>Total saham: <white>" + c.totalShares);
            lore.add("<gray>Tersedia di pasar: <white>" + c.poolShares);
        }
        if (!c.system) lore.add("<gray>Kas perusahaan: <white>" + Text.money(c.treasury));
        if (!c.sector.isEmpty()) {
            lore.add("<gray>Sektor: <white>" + Text.esc(plugin.market().sectors().getOrDefault(c.sector, c.sector)));
        }
        lore.add("<gray>Pemilik: <white>" + (c.owner == null ? "Bursa (sistem)" : Text.esc(Text.playerName(c.owner))));
        if (!c.description.isEmpty()) {
            lore.add("");
            lore.add("<italic><gray>" + Text.esc(c.description));
        }
        return Items.of(Items.icon(c), 1, "<white><bold>" + c.ticker + "</bold> <gray>" + Text.esc(c.name), lore);
    }

    private ItemStack positionItem(Company c, Holding h) {
        if (h == null) {
            int ord = c.ipoOrders.getOrDefault(player.getUniqueId(), 0);
            if (ord > 0) {
                return Items.of(Material.PAPER, "<aqua>Pesanan IPO-mu",
                        "<gray>Saham dipesan: <white>" + ord, "<gray>Masuk portofolio saat IPO sukses");
            }
            return Items.of(Material.PAPER, "<aqua>Posisimu", "<gray>Kamu belum punya saham ini.");
        }
        double unit = c.status == Status.IPO ? c.ipoPrice : c.price;
        double value = h.shares * unit;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Saham: <white>" + h.shares);
        lore.add("<gray>Harga beli rata-rata: <white>" + (h.avgCost > 0 ? Text.money(h.avgCost) : "-"));
        lore.add("<gray>Nilai sekarang: <white>" + Text.money(value));
        if (h.avgCost > 0) {
            double pnl = value - h.avgCost * h.shares;
            double pct = (unit - h.avgCost) / h.avgCost * 100.0;
            lore.add("<gray>Profit/Loss: " + (pnl >= 0 ? "<green>+" : "<red>-") + Text.money(Math.abs(pnl)) + " " + Text.pct(pct));
        }
        return Items.of(Material.PAPER, "<aqua>Posisimu", lore);
    }

    /** Grafik 9 kolom x 3 baris dari titik-titik riwayat harga. */
    private void drawChart(Company c) {
        List<Double> pts = new ArrayList<>(c.history);
        pts.add(c.price);
        if (pts.size() > 9) pts = new ArrayList<>(pts.subList(pts.size() - 9, pts.size()));
        double min = Collections.min(pts);
        double max = Collections.max(pts);
        int start = 9 - pts.size();
        for (int i = 0; i < pts.size(); i++) {
            double v = pts.get(i);
            int level = (max - min < 1e-9) ? 2 : 1 + (int) Math.round((v - min) / (max - min) * 2.0);
            boolean up = i == 0 || v >= pts.get(i - 1);
            Material mat = up ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            int col = start + i;
            for (int j = 0; j < level; j++) {
                inv.setItem(45 - 9 * j + col, Items.of(mat, "<white>" + Text.money(v)));
            }
        }
    }
}
