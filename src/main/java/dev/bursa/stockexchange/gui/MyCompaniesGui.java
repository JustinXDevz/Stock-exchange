package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class MyCompaniesGui extends Gui {
    public MyCompaniesGui(StockExchangePlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Perusahaanku");
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    public void render() {
        fillAll();
        List<Company> mine = plugin.market().ownedBy(player.getUniqueId());
        for (int i = 0; i < Math.min(9, mine.size()); i++) {
            Company c = mine.get(i);
            final String ticker = c.ticker;
            set(9 + i, Items.of(Items.icon(c), 1, "<white><bold>" + c.ticker + "</bold> <gray>" + Text.esc(c.name),
                    List.of("<gray>Status: <white>" + c.status.name(),
                            "<gray>Kas: <white>" + Text.money(c.treasury),
                            "",
                            "<yellow>Klik untuk mengelola")),
                    click -> new ManageGui(plugin, player, ticker).open());
        }
        if (mine.isEmpty()) {
            set(13, Items.of(Material.BARRIER, "<gray>Kamu belum punya perusahaan",
                    "<gray>Buat dengan: <white>/company create [ticker] [saham] [nama]"), null);
        }
        set(22, Items.of(Material.ARROW, "<yellow>◀ Kembali"), click -> new MarketGui(plugin, player).open());
    }
}
