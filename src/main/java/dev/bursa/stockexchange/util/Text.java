package dev.bursa.stockexchange.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.UUID;

public final class Text {
    public static final String PREFIX = "<gold>[Bursa]</gold> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static String currency = "$";

    private Text() {}

    /** MiniMessage -> Component, tanpa miring (italic) default pada item. */
    public static Component mm(String s) {
        return MM.deserialize("<!italic>" + s);
    }

    public static String esc(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }

    public static String num(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    public static String money(double v) {
        return currency + num(v);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String pct(double p) {
        if (Math.abs(p) < 0.005) return "<gray>- 0.00%</gray>";
        String col = p > 0 ? "green" : "red";
        String arrow = p > 0 ? "▲" : "▼";
        return "<" + col + ">" + arrow + " " + String.format(Locale.US, "%.2f", Math.abs(p)) + "%</" + col + ">";
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) return d + "hr " + h + "j";
        if (h > 0) return h + "j " + m + "m";
        return m + "m " + (s % 60) + "d";
    }

    public static String playerName(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        String n = op.getName();
        return n != null ? n : u.toString().substring(0, 8);
    }

    public static Integer parseInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Double parseMoney(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return (v > 0 && Double.isFinite(v)) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
