package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.model.Company.Status;
import dev.bursa.stockexchange.model.Holding;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class PortfolioGui extends Gui {
    private record Row(Company c, int shares, double avg, boolean pending) {
        double unit() {
            return c.status == Status.IPO ? c.ipoPrice : c.price;
        }

        double value() {
            return shares * unit();
        }
    }

    public PortfolioGui(StockExchangePlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Portofolio");
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    public void render() {
        for (int i = 45; i < 54; i++) inv.setItem(i, Items.filler());
        UUID u = player.getUniqueId();

        List<Row> rows = new ArrayList<>();
        for (Company c : plugin.market().all()) {
            if (c.status != Status.LISTED && c.status != Status.IPO) continue;
            Holding h = c.holders.get(u);
            if (h != null) rows.add(new Row(c, h.shares, h.avgCost, false));
            Integer o = c.ipoOrders.get(u);
            if (o != null && c.status == Status.IPO) rows.add(new Row(c, o, c.ipoPrice, true));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> -r.value()));

        double totalValue = 0;
        double totalCost = 0;
        int shown = 0;
        for (Row r : rows) {
            totalValue += r.value();
            if (r.avg() > 0) totalCost += r.avg() * r.shares();
            if (shown >= 45) continue;

            List<String> lore = new ArrayList<>();
            if (r.pending()) lore.add("<light_purple>Pesanan IPO (menunggu hasil)");
            lore.add("<gray>Saham: <white>" + r.shares());
            lore.add("<gray>Harga beli rata-rata: <white>" + (r.avg() > 0 ? Text.money(r.avg()) : "-"));
            lore.add("<gray>Harga sekarang: <white>" + Text.money(r.unit()));
            lore.add("<gray>Nilai: <white>" + Text.money(r.value()));
            if (r.avg() > 0) {
                double pnl = r.value() - r.avg() * r.shares();
                double pct = (r.unit() - r.avg()) / r.avg() * 100.0;
                lore.add("<gray>Profit/Loss: " + (pnl >= 0 ? "<green>+" : "<red>-") + Text.money(Math.abs(pnl)) + " " + Text.pct(pct));
            }
            lore.add("");
            lore.add("<yellow>Klik untuk membuka");
            final String ticker = r.c().ticker;
            set(shown, Items.of(Items.icon(r.c()), 1, "<white><bold>" + ticker + "</bold> <gray>" + Text.esc(r.c().name), lore),
                    click -> new DetailGui(plugin, player, ticker).open());
            shown++;
        }

        set(45, Items.of(Material.ARROW, "<yellow>◀ Kembali"), click -> new MarketGui(plugin, player).open());
        List<String> sum = new ArrayList<>();
        sum.add("<gray>Total nilai: <white>" + Text.money(totalValue));
        if (totalCost > 0) {
            double pnl = totalValue - totalCost;
            sum.add("<gray>Profit/Loss (yang punya modal): " + (pnl >= 0 ? "<green>+" : "<red>-") + Text.money(Math.abs(pnl)));
        }
        if (rows.isEmpty()) sum.add("<gray>Belum ada saham. Buka bursa untuk mulai.");
        set(49, Items.of(Material.EMERALD, "<green>Ringkasan", sum), null);
    }
}
