package dev.bursa.stockexchange.commands;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.data.Database;
import dev.bursa.stockexchange.gui.MarketGui;
import dev.bursa.stockexchange.gui.NewsGui;
import dev.bursa.stockexchange.gui.PortfolioGui;
import dev.bursa.stockexchange.gui.SectorGui;
import dev.bursa.stockexchange.gui.TopGui;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class StockCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("buy", "sell", "price", "portfolio", "top", "news", "history", "all", "web", "help");
    private final StockExchangePlugin plugin;

    public StockCommand(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Perintah ini hanya untuk pemain.");
            return true;
        }
        Market m = plugin.market();
        if (args.length == 0) {
            new SectorGui(plugin, p).open();
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buy", "sell" -> {
                boolean buy = args[0].equalsIgnoreCase("buy");
                if (args.length < 3) {
                    plugin.msg(p, "<red>Gunakan: /stock " + args[0].toLowerCase(Locale.ROOT) + " [ticker] [jumlah]");
                    return true;
                }
                Company c = m.get(args[1]);
                Integer qty = Text.parseInt(args[2]);
                if (c == null) {
                    plugin.msg(p, "<red>Ticker tidak ditemukan.");
                } else if (qty == null) {
                    plugin.msg(p, "<red>Jumlah harus angka bulat positif.");
                } else {
                    Market.Result r = buy ? m.buy(p, c, qty) : m.sell(p, c, qty);
                    plugin.msg(p, (r.ok() ? "<green>" : "<red>") + r.message());
                }
            }
            case "price" -> {
                if (args.length < 2) {
                    plugin.msg(p, "<red>Gunakan: /stock price [ticker]");
                    return true;
                }
                Company c = m.get(args[1]);
                if (c == null) {
                    plugin.msg(p, "<red>Ticker tidak ditemukan.");
                    return true;
                }
                plugin.msg(p, "<white>" + c.ticker + " <gray>(" + Text.esc(c.name) + ") status <white>" + c.status.name());
                if (c.status == Company.Status.LISTED) {
                    plugin.msg(p, "<gray>Harga <white>" + Text.money(c.price) + " " + Text.pct(c.changePercent())
                            + " <gray>| tersedia <white>" + c.poolShares + "/" + c.totalShares);
                } else if (c.status == Company.Status.IPO) {
                    plugin.msg(p, "<gray>Harga IPO <white>" + Text.money(c.ipoPrice) + " <gray>| sisa <white>" + c.ipoRemaining
                            + "/" + c.ipoOffered + " <gray>| berakhir <white>"
                            + Text.duration(c.ipoEnd - System.currentTimeMillis()));
                }
            }
            case "all" -> new MarketGui(plugin, p).open();
            case "web" -> {
                String url = plugin.getConfig().getString("web.public-url", "");
                if (!plugin.getConfig().getBoolean("web.enabled", true)) {
                    plugin.msg(p, "<red>Website bursa tidak aktif di server ini.");
                } else if (url == null || url.isBlank()) {
                    plugin.msg(p, "<yellow>Website bursa aktif di port <white>" + plugin.getConfig().getInt("web.port", 8123)
                            + "<yellow>. Tanyakan admin alamat lengkapnya.");
                } else {
                    plugin.msg(p, "<yellow>Pantau harga saham realtime: <aqua>" + Text.esc(url));
                }
            }
            case "portfolio" -> new PortfolioGui(plugin, p).open();
            case "top" -> new TopGui(plugin, p).open();
            case "news" -> new NewsGui(plugin, p).open();
            case "history" -> {
                SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
                plugin.msg(p, "<gray>Mengambil riwayat transaksi...");
                dbHistory(p, fmt);
            }
            default -> {
                plugin.msg(p, "<yellow>Perintah /stock:");
                plugin.msg(p, "<gray>/stock <white>- buka bursa (GUI per sektor)");
                plugin.msg(p, "<gray>/stock all | web");
                plugin.msg(p, "<gray>/stock buy|sell [ticker] [jumlah]");
                plugin.msg(p, "<gray>/stock price [ticker]");
                plugin.msg(p, "<gray>/stock portfolio | top | news | history");
                plugin.msg(p, "<gray>Perusahaan: <white>/company help");
            }
        }
        return true;
    }

    private void dbHistory(Player p, SimpleDateFormat fmt) {
        Database db = plugin.database();
        db.recentTransactions(p.getUniqueId(), 10).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (list.isEmpty()) {
                        plugin.msg(p, "<gray>Belum ada transaksi.");
                        return;
                    }
                    for (Database.Tx t : list) {
                        plugin.msg(p, "<gray>" + fmt.format(new Date(t.ts())) + " <white>" + t.type() + " "
                                + t.shares() + " " + Text.esc(t.ticker()) + " @ " + Text.money(t.price())
                                + " <gray>(fee " + Text.money(t.fee()) + ")");
                    }
                })
        ).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "<red>Gagal membaca riwayat."));
            return null;
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && List.of("buy", "sell", "price").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Company c : plugin.market().tradable()) {
                if (c.ticker.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(c.ticker);
            }
        }
        return out;
    }
}
