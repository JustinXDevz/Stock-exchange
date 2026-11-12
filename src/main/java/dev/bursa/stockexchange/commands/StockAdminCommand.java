package dev.bursa.stockexchange.commands;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class StockAdminCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("create", "delete", "setprice", "setvol", "seticon",
            "event", "freeze", "unfreeze", "reload", "list", "shop", "shopdebug", "setsector", "sectors");
    private final StockExchangePlugin plugin;

    public StockAdminCommand(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            plugin.msg(s, "<yellow>/stockadmin " + String.join("|", SUBS));
            return true;
        }
        Market m = plugin.market();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 5) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin create [ticker] [harga] [sektor|none] [nama...]");
                    plugin.msg(s, "<gray>Sektor: " + String.join(", ", m.sectors().keySet()));
                    return true;
                }
                Double price = Text.parseMoney(args[2]);
                if (price == null) {
                    plugin.msg(s, "<red>Harga tidak valid.");
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                if (!name.matches("[\\p{L}\\p{N} .'&-]{2,24}")) {
                    plugin.msg(s, "<red>Nama harus 2-24 karakter (huruf, angka, spasi, . ' & -).");
                    return true;
                }
                int shares = plugin.getConfig().getInt("admin.default-total-shares", 10000);
                double vol = plugin.getConfig().getDouble("market.default-volatility", 0.01);
                reply(s, m.createSystemStock(args[1], name, price, vol, shares, args[3]));
            }
            case "delete" -> {
                if (args.length < 2) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin delete [ticker]");
                    return true;
                }
                reply(s, m.adminDelete(args[1]));
            }
            case "setprice" -> {
                Double price = args.length < 3 ? null : Text.parseMoney(args[2]);
                if (price == null) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin setprice [ticker] [harga]");
                    return true;
                }
                reply(s, m.setPrice(args[1], price));
            }
            case "setvol" -> {
                Double v = args.length < 3 ? null : Text.parseMoney(args[2]);
                if (v == null) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin setvol [ticker] [0.001-0.2]");
                    return true;
                }
                reply(s, m.setVolatility(args[1], v));
            }
            case "seticon" -> {
                Company c = args.length < 3 ? null : m.get(args[1]);
                if (c == null) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin seticon [ticker] [MATERIAL]");
                    return true;
                }
                reply(s, m.setIcon(c, args[2]));
            }
            case "event" -> {
                Company c = args.length < 3 ? null : m.get(args[2]);
                if (c == null || !(args[1].equalsIgnoreCase("boom") || args[1].equalsIgnoreCase("crash"))) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin event [boom|crash] [ticker]");
                    return true;
                }
                reply(s, m.applyEvent(c, args[1].equalsIgnoreCase("boom")));
            }
            case "freeze" -> {
                m.setFrozen(true);
                plugin.msg(s, "<yellow>Bursa dibekukan.");
            }
            case "unfreeze" -> {
                m.setFrozen(false);
                plugin.msg(s, "<green>Bursa dibuka kembali.");
            }
            case "reload" -> {
                plugin.reloadConfig();
                m.reloadSectors();
                Text.currency = plugin.getConfig().getString("currency-symbol", "$");
                plugin.msg(s, "<green>Config dimuat ulang (interval timer butuh restart).");
            }
            case "setsector" -> {
                Company c = args.length < 3 ? null : m.get(args[1]);
                if (c == null) {
                    plugin.msg(s, "<red>Gunakan: /stockadmin setsector [ticker] [sektor|none]");
                    return true;
                }
                reply(s, m.setSectorAdmin(c, args[2]));
            }
            case "sectors" -> {
                for (Market.SectorStat st : m.sectorStats()) {
                    plugin.msg(s, "<white>" + Text.esc(st.id()) + " <gray>(" + Text.esc(st.name()) + ") saham: <white>" + st.count()
                            + " <gray>rata-rata 24j: " + Text.pct(st.avgChange()));
                }
            }
            case "shop" -> plugin.msg(s, "<gray>" + Text.esc(plugin.shopHook().status()));
            case "shopdebug" -> plugin.msg(s, plugin.shopHook().toggleDebug()
                    ? "<yellow>Debug shop AKTIF. Lakukan 1 transaksi di shop lalu lihat console."
                    : "<green>Debug shop dimatikan.");
            case "list" -> {
                for (Company c : m.all()) {
                    plugin.msg(s, "<white>" + c.ticker + " <gray>" + c.status.name() + " "
                            + (c.system ? "[sistem] " : "") + Text.money(c.price));
                }
            }
            default -> plugin.msg(s, "<red>Subperintah tidak dikenal.");
        }
        return true;
    }

    private void reply(CommandSender to, Market.Result r) {
        plugin.msg(to, (r.ok() ? "<green>" : "<red>") + r.message());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && List.of("delete", "setprice", "setvol", "seticon", "setsector").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Company c : plugin.market().all()) {
                if (c.ticker.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(c.ticker);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setsector")) {
            out.add("none");
            out.addAll(plugin.market().sectors().keySet());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            out.add("none");
            out.addAll(plugin.market().sectors().keySet());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("event")) {
            out.addAll(List.of("boom", "crash"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("event")) {
            for (Company c : plugin.market().all()) out.add(c.ticker);
        }
        return out;
    }
}
