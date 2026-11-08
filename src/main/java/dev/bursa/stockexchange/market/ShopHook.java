package dev.bursa.stockexchange.market;

import dev.bursa.stockexchange.StockExchangePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Menyambungkan EconomyShopGUI ke bursa lewat PostTransactionEvent.
 * Memakai reflection karena nama method event tidak bisa dipastikan di semua versi;
 * isi event dibaca secara heuristik. Gunakan /stockadmin shopdebug untuk melihat isi event asli.
 */
public final class ShopHook {
    private static final String EVENT_CLASS = "me.gypopo.economyshopgui.api.events.PostTransactionEvent";

    private record Parsed(UUID player, Boolean buy, double value, Set<String> tokens, String dump) {}

    private final StockExchangePlugin plugin;
    private boolean hooked;
    private String hookedName = "-";
    private volatile boolean debug;
    private int processed;
    private String last = "belum ada transaksi";

    public ShopHook(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public void hook() {
        if (!plugin.getConfig().getBoolean("shop.enabled", true)) {
            plugin.getLogger().info("Integrasi shop dimatikan di config.");
            return;
        }
        Plugin esg = null;
        for (String n : new String[]{"EconomyShopGUI-Premium", "EconomyShopGUI"}) {
            Plugin p = Bukkit.getPluginManager().getPlugin(n);
            if (p != null && p.isEnabled()) {
                esg = p;
                break;
            }
        }
        if (esg == null) {
            plugin.getLogger().info("EconomyShopGUI tidak ditemukan, integrasi shop nonaktif.");
            return;
        }
        try {
            Class<?> raw = esg.getClass().getClassLoader().loadClass(EVENT_CLASS);
            if (!Event.class.isAssignableFrom(raw)) {
                throw new IllegalStateException(EVENT_CLASS + " bukan Event");
            }
            Class<? extends Event> evClass = raw.asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(evClass, new Listener() {
            }, EventPriority.MONITOR, (listener, event) -> handle(event), plugin, true);
            hooked = true;
            hookedName = esg.getName();
            plugin.getLogger().info("Terhubung ke " + hookedName + " (PostTransactionEvent).");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "Gagal terhubung ke " + esg.getName() + ". Integrasi shop nonaktif.", t);
        }
    }

    public boolean toggleDebug() {
        debug = !debug;
        return debug;
    }

    public String status() {
        return "Terhubung: " + (hooked ? "ya (" + hookedName + ")" : "tidak")
                + " | transaksi diproses: " + processed + " | terakhir: " + last;
    }

    // ---------- Event ----------

    private void handle(Event e) {
        final Parsed ps;
        try {
            ps = parse(e);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Gagal membaca event shop", t);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            processed++;
            String sector = null;
            if (ps.player() != null && ps.value() > 0 && !ps.tokens().isEmpty()) {
                sector = plugin.market().recordShopTransaction(ps.player(), ps.buy(), ps.value(), ps.tokens());
            }
            last = (ps.buy() == null ? "?" : ps.buy() ? "BUY" : "SELL") + " nilai=" + ps.value()
                    + " sektor=" + sector + " token=" + ps.tokens();
            if (debug) {
                plugin.getLogger().info("[shop] event: " + ps.dump());
                plugin.getLogger().info("[shop] hasil: " + last);
            }
            if (ps.value() <= 0 && processed <= 3) {
                plugin.getLogger().warning("[shop] Nilai transaksi tidak ditemukan di event. "
                        + "Jalankan /stockadmin shopdebug lalu lakukan 1 transaksi dan cek log.");
            }
        });
    }

    private Parsed parse(Event e) {
        UUID player = null;
        Boolean buy = null;
        double byPrice = -1;
        double byCost = -1;
        double byTotal = -1;
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder dump = new StringBuilder();

        for (Method m : e.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
            Class<?> decl = m.getDeclaringClass();
            if (decl == Object.class || decl == Event.class) continue;
            String name = m.getName();
            String lname = name.toLowerCase(Locale.ROOT);
            if (lname.equals("gethandlers") || lname.equals("iscancelled") || lname.equals("hashcode")
                    || lname.equals("tostring") || lname.equals("getclass")) continue;

            Object v;
            try {
                v = m.invoke(e);
            } catch (Throwable t) {
                continue;
            }
            if (v == null) continue;
            dump.append(name).append('=').append(shortStr(v)).append("; ");

            if (v instanceof OfflinePlayer op) {
                if (player == null) player = op.getUniqueId();
            } else if (v instanceof Enum<?> en) {
                String s = en.name().toUpperCase(Locale.ROOT);
                if (s.contains("BUY") || s.contains("PURCHASE")) buy = Boolean.TRUE;
                else if (s.contains("SELL")) buy = Boolean.FALSE;
            } else if (v instanceof Double || v instanceof Float) {
                double dv = ((Number) v).doubleValue();
                if (lname.contains("price")) byPrice = Math.max(byPrice, dv);
                else if (lname.contains("cost")) byCost = Math.max(byCost, dv);
                else if (lname.contains("total")) byTotal = Math.max(byTotal, dv);
            } else if (v instanceof Map<?, ?> map && lname.contains("price")) {
                for (Map.Entry<?, ?> en : map.entrySet()) {
                    if (en.getValue() instanceof Number num && String.valueOf(en.getKey()).toUpperCase(Locale.ROOT).contains("VAULT")) {
                        byPrice = Math.max(byPrice, num.doubleValue());
                    }
                }
            } else {
                collect(v, tokens, 0);
            }
        }
        double value = byPrice > 0 ? byPrice : byCost > 0 ? byCost : byTotal;
        return new Parsed(player, buy, value, tokens, dump.toString());
    }

    private void collect(Object o, Set<String> tokens, int depth) {
        if (o == null || depth > 3 || tokens.size() > 200) return;
        if (o instanceof ItemStack is) {
            addTokens(tokens, is.getType().name());
        } else if (o instanceof Material mat) {
            addTokens(tokens, mat.name());
        } else if (o instanceof String s) {
            if (depth > 0 && s.length() < 80) addTokens(tokens, s);
        } else if (o instanceof Collection<?> c) {
            for (Object x : c) collect(x, tokens, depth + 1);
        } else if (o instanceof Map<?, ?> mp) {
            for (Map.Entry<?, ?> en : mp.entrySet()) {
                collect(en.getKey(), tokens, depth + 1);
                collect(en.getValue(), tokens, depth + 1);
            }
        } else if (o.getClass().getSimpleName().contains("ShopItem")) {
            collectShopItem(o, tokens);
        }
    }

    private void collectShopItem(Object item, Set<String> tokens) {
        for (Method m : item.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String name = m.getName();
            if (!name.startsWith("get")) continue;
            Class<?> rt = m.getReturnType();
            boolean isString = rt == String.class;
            if (!(isString || rt == ItemStack.class || rt == Material.class)) continue;
            if (isString) {
                String l = name.toLowerCase(Locale.ROOT);
                if (!(l.contains("path") || l.contains("section") || l.contains("name")
                        || l.contains("material") || l.contains("type") || l.contains("id"))) continue;
            }
            try {
                Object v = m.invoke(item);
                if (v instanceof String s) addTokens(tokens, s);
                else if (v instanceof ItemStack is) addTokens(tokens, is.getType().name());
                else if (v instanceof Material mat) addTokens(tokens, mat.name());
            } catch (Throwable ignored) {
                // abaikan method yang gagal
            }
        }
    }

    private static void addTokens(Set<String> tokens, String raw) {
        String up = raw.toUpperCase(Locale.ROOT).trim();
        if (up.isEmpty()) return;
        tokens.add(up);
        for (String t : up.split("[^A-Z0-9]+")) {
            if (!t.isEmpty()) tokens.add(t);
        }
    }

    private static String shortStr(Object v) {
        String s = String.valueOf(v);
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
