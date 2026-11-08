package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class NewsGui extends Gui {
    public NewsGui(StockExchangePlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Berita Pasar");
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    public void render() {
        fillAll();
        List<String> news = plugin.market().news();
        for (int i = 0; i < Math.min(18, news.size()); i++) {
            set(i, Items.of(Material.PAPER, news.get(i)), null);
        }
        if (news.isEmpty()) {
            set(13, Items.of(Material.BARRIER, "<gray>Belum ada berita"), null);
        }
        set(22, Items.of(Material.ARROW, "<yellow>◀ Kembali"), click -> new MarketGui(plugin, player).open());
    }
}
