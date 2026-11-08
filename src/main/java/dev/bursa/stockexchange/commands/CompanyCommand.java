package dev.bursa.stockexchange.commands;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.gui.MyCompaniesGui;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.model.Holding;
import dev.bursa.stockexchange.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CompanyCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("create", "ipo", "info", "deposit", "dividend", "delist",
            "icon", "desc", "sector", "list", "help");
    private final StockExchangePlugin plugin;

    public CompanyCommand(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("info")) {
            if (args.length < 2) {
                plugin.msg(sender, "<red>Gunakan: /company info [ticker]");
                return true;
            }
            Company c = plugin.market().get(args[1]);
            if (c == null) plugin.msg(sender, "<red>Ticker tidak ditemukan.");
            else info(sender, c);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Perintah ini hanya untuk pemain.");
            return true;
        }
        Market m = plugin.market();

        switch (sub) {
            case "list" -> new MyCompaniesGui(plugin, p).open();
            case "create" -> {
                if (args.length < 4) {
                    plugin.msg(p, "<red>Gunakan: /company create [ticker] [totalSaham] [nama perusahaan]");
                    return true;
                }
                Integer total = Text.parseInt(args[2]);
                if (total == null) {
                    plugin.msg(p, "<red>Total saham harus angka bulat positif.");
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                reply(p, m.createCompany(p, args[1], total, name));
            }
            case "ipo" -> {
                if (args.length < 5) {
                    plugin.msg(p, "<red>Gunakan: /company ipo [ticker] [jumlahSaham] [harga] [jam]");
                    return true;
                }
                Company c = own(p, args[1]);
                Integer shares = Text.parseInt(args[2]);
                Double price = Text.parseMoney(args[3]);
                Integer hours = Text.parseInt(args[4]);
                if (c == null) return true;
                if (shares == null || price == null || hours == null) {
                    plugin.msg(p, "<red>Jumlah, harga, dan jam harus angka positif.");
                    return true;
                }
                reply(p, m.startIpo(p, c, shares, price, hours));
            }
            case "deposit", "dividend" -> {
                if (args.length < 3) {
                    plugin.msg(p, "<red>Gunakan: /company " + sub + " [ticker] [jumlah]");
                    return true;
                }
                Company c = own(p, args[1]);
                Double amt = Text.parseMoney(args[2]);
                if (c == null) return true;
                if (amt == null) {
                    plugin.msg(p, "<red>Jumlah harus angka positif.");
                    return true;
                }
                reply(p, sub.equals("deposit") ? m.deposit(p, c, amt) : m.dividend(p, c, amt));
            }
            case "delist" -> {
                if (args.length < 2) {
                    plugin.msg(p, "<red>Gunakan: /company delist [ticker] confirm");
                    return true;
                }
                Company c = own(p, args[1]);
                if (c == null) return true;
                if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                    plugin.msg(p, "<yellow>Ini akan membubarkan " + c.ticker + " dan membagi kas ke pemegang saham. "
                            + "Ketik <white>/company delist " + c.ticker + " confirm");
                    return true;
                }
                reply(p, m.delist(p, c));
            }
            case "icon" -> {
                if (args.length < 3) {
                    plugin.msg(p, "<red>Gunakan: /company icon [ticker] [MATERIAL]");
                    return true;
                }
                Company c = own(p, args[1]);
                if (c != null) reply(p, m.setIcon(c, args[2]));
            }
            case "sector" -> {
                if (args.length < 3) {
                    plugin.msg(p, "<yellow>Sektor tersedia:");
                    for (Map.Entry<String, String> e : m.sectors().entrySet()) {
                        plugin.msg(p, "<gray>- <white>" + Text.esc(e.getKey()) + " <gray>(" + Text.esc(e.getValue()) + ")");
                    }
                    plugin.msg(p, "<gray>Pakai: /company sector [ticker] [sektor|none]");
                    return true;
                }
                Company c = own(p, args[1]);
                if (c != null) reply(p, m.setSector(p, c, args[2]));
            }
            case "desc" -> {
                if (args.length < 3) {
                    plugin.msg(p, "<red>Gunakan: /company desc [ticker] [teks]");
                    return true;
                }
                Company c = own(p, args[1]);
                if (c != null) reply(p, m.setDescription(c, String.join(" ", Arrays.copyOfRange(args, 2, args.length))));
            }
            default -> help(sender);
        }
        return true;
    }

    private Company own(Player p, String ticker) {
        Company c = plugin.market().get(ticker);
        if (c == null) {
            plugin.msg(p, "<red>Ticker tidak ditemukan.");
            return null;
        }
        if (c.owner == null || !c.owner.equals(p.getUniqueId())) {
            plugin.msg(p, "<red>Kamu bukan pemilik perusahaan ini.");
            return null;
        }
        return c;
    }

    private void reply(CommandSender to, Market.Result r) {
        plugin.msg(to, (r.ok() ? "<green>" : "<red>") + r.message());
    }

    private void help(CommandSender s) {
        plugin.msg(s, "<yellow>Perintah /company:");
        plugin.msg(s, "<gray>/company create [ticker] [totalSaham] [nama]");
        plugin.msg(s, "<gray>/company ipo [ticker] [jumlahSaham] [harga] [jam]");
        plugin.msg(s, "<gray>/company deposit|dividend [ticker] [jumlah]");
        plugin.msg(s, "<gray>/company sector [ticker] [sektor|none] <dark_gray>(omzet shop masuk kas)");
        plugin.msg(s, "<gray>/company info [ticker] | list | icon | desc | delist");
    }

    private void info(CommandSender s, Company c) {
        plugin.msg(s, "<white><bold>" + c.ticker + "</bold> <gray>" + Text.esc(c.name) + " <dark_gray>[" + c.status.name() + "]");
        if (!c.description.isEmpty()) plugin.msg(s, "<gray>" + Text.esc(c.description));
        plugin.msg(s, "<gray>Pemilik: <white>" + (c.owner == null ? "Bursa (sistem)" : Text.esc(Text.playerName(c.owner))));
        if (c.status == Company.Status.LISTED) {
            plugin.msg(s, "<gray>Harga: <white>" + Text.money(c.price) + " " + Text.pct(c.changePercent()));
        }
        if (!c.system) plugin.msg(s, "<gray>Kas: <white>" + Text.money(c.treasury));
        if (!c.sector.isEmpty()) plugin.msg(s, "<gray>Sektor: <white>" + Text.esc(plugin.market().sectors().getOrDefault(c.sector, c.sector)));
        plugin.msg(s, "<gray>Total saham: <white>" + c.totalShares + " <gray>| di pasar: <white>" + c.poolShares);

        List<Map.Entry<UUID, Holding>> top = new ArrayList<>(c.holders.entrySet());
        top.sort(Comparator.comparingInt((Map.Entry<UUID, Holding> e) -> -e.getValue().shares));
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            Map.Entry<UUID, Holding> e = top.get(i);
            double pct = e.getValue().shares * 100.0 / c.totalShares;
            plugin.msg(s, "<gray>#" + (i + 1) + " <white>" + Text.esc(Text.playerName(e.getKey())) + " <gray>- "
                    + e.getValue().shares + " saham (" + String.format(Locale.US, "%.1f", pct) + "%)");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("sector")) {
            out.add("none");
            out.addAll(plugin.market().sectors().keySet());
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            boolean ownedOnly = !args[0].equalsIgnoreCase("info");
            for (Company c : plugin.market().all()) {
                if (c.status == Company.Status.DELISTED) continue;
                if (ownedOnly && !(sender instanceof Player p && p.getUniqueId().equals(c.owner))) continue;
                if (c.ticker.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(c.ticker);
            }
        }
        return out;
    }
}
