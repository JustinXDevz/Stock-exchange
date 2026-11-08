package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

public final class ManageGui extends Gui {
    private final String ticker;

    public ManageGui(StockExchangePlugin plugin, Player player, String ticker) {
        super(plugin, player);
        this.ticker = ticker;
    }

    @Override
    protected Component title() {
        return Text.mm("<dark_gray>Kelola " + Text.esc(ticker));
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    public void render() {
        fillAll();
        Company c = plugin.market().get(ticker);
        set(22, Items.of(Material.ARROW, "<yellow>◀ Kembali"), click -> new MyCompaniesGui(plugin, player).open());
        if (c == null || !player.getUniqueId().equals(c.owner)) {
            set(13, Items.of(Material.BARRIER, "<red>Bukan perusahaanmu"), null);
            return;
        }

        List<String> info = new ArrayList<>();
        info.add("<gray>Status: <white>" + c.status.name());
        info.add("<gray>Kas: <white>" + Text.money(c.treasury));
        info.add("<gray>Total saham: <white>" + c.totalShares);
        if (c.status == Company.Status.LISTED) info.add("<gray>Harga: <white>" + Text.money(c.price));
        set(4, Items.of(Items.icon(c), 1, "<white><bold>" + c.ticker + "</bold> <gray>" + Text.esc(c.name), info),
                click -> new DetailGui(plugin, player, ticker).open());

        set(10, Items.of(Material.GOLD_INGOT, "<yellow>Setor kas",
                        "<gray>Tambah kas dari dompetmu.", "<gray>Kas tidak bisa ditarik, hanya", "<gray>keluar lewat dividen."),
                click -> plugin.prompts().ask(player, "Ketik jumlah uang yang disetor ke kas:", s -> {
                    Double amt = Text.parseMoney(s);
                    if (amt == null) plugin.msg(player, "<red>Jumlah tidak valid.");
                    else feedback(plugin.market().deposit(player, plugin.market().get(ticker), amt));
                    new ManageGui(plugin, player, ticker).open();
                }));

        set(12, Items.of(Material.EMERALD, "<green>Bagi dividen",
                        "<gray>Dibayar dari kas ke semua", "<gray>pemegang saham sesuai jumlah saham."),
                click -> plugin.prompts().ask(player, "Ketik total dividen yang dibagikan:", s -> {
                    Double amt = Text.parseMoney(s);
                    if (amt == null) plugin.msg(player, "<red>Jumlah tidak valid.");
                    else feedback(plugin.market().dividend(player, plugin.market().get(ticker), amt));
                    new ManageGui(plugin, player, ticker).open();
                }));

        set(14, Items.of(Material.PAPER, "<light_purple>Mulai IPO",
                        "<gray>Gunakan perintah:",
                        "<white>/company ipo " + c.ticker + " [jumlah] [harga] [jam]"),
                click -> plugin.msg(player, "<yellow>Ketik: <white>/company ipo " + ticker + " [jumlah] [harga] [jam]"));

        set(16, Items.of(Material.TNT, "<red>Bubarkan perusahaan",
                        "<gray>Kas dibagi ke pemegang saham.", "<red>SHIFT + klik kiri untuk konfirmasi"),
                click -> {
                    if (click != ClickType.SHIFT_LEFT) {
                        plugin.msg(player, "<red>Tahan SHIFT dan klik kiri untuk membubarkan.");
                        return;
                    }
                    feedback(plugin.market().delist(player, plugin.market().get(ticker)));
                    new MyCompaniesGui(plugin, player).open();
                });
    }
}
