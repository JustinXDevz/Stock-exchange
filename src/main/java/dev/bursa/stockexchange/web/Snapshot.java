package dev.bursa.stockexchange.web;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.model.Company.Status;
import dev.bursa.stockexchange.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Membangun snapshot JSON dari Market di main thread. Thread web hanya membaca string hasilnya,
 * jadi tidak pernah menyentuh state Market langsung.
 */
public final class Snapshot {
    public record Data(String summary, Map<String, String> details) {
        public static final Data EMPTY = new Data("{}", Map.of());
    }

    private static final int LIVE_POINTS = 180;

    private final StockExchangePlugin plugin;
    private final Map<String, Deque<Double>> live = new HashMap<>();
    private volatile Data data = Data.EMPTY;

    public Snapshot(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public Data data() {
        return data;
    }

    /** Harus dipanggil dari main thread. */
    public void refresh() {
        Market m = plugin.market();
        var cfg = plugin.getConfig();
        int refreshSec = Math.max(1, cfg.getInt("web.refresh-seconds", 5));
        int stepSec = Math.max(5, cfg.getInt("market.update-interval-seconds", 60))
                * Math.max(1, cfg.getInt("market.history-interval-ticks", 5));

        List<Company> list = m.tradable();
        Set<String> present = new HashSet<>();
        for (Company c : list) {
            present.add(c.ticker);
            if (c.status == Status.LISTED) {
                Deque<Double> q = live.computeIfAbsent(c.ticker, k -> new ArrayDeque<>());
                q.addLast(c.price);
                while (q.size() > LIVE_POINTS) q.removeFirst();
            }
        }
        live.keySet().retainAll(present);

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        kv(sb, "ts", System.currentTimeMillis()).append(',');
        kv(sb, "title", cfg.getString("web.title", "Bursa Saham")).append(',');
        kv(sb, "currency", Text.currency).append(',');
        sb.append("\"frozen\":").append(m.isFrozen()).append(',');
        kv(sb, "refreshSec", refreshSec).append(',');

        sb.append("\"sectors\":[");
        boolean first = true;
        for (Market.SectorStat st : m.sectorStats()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            kv(sb, "id", st.id()).append(',');
            kv(sb, "n", st.name()).append(',');
            kv(sb, "c", st.count()).append(',');
            kv(sb, "a", st.avgChange());
            sb.append('}');
        }
        sb.append("],\"stocks\":[");

        Map<String, String> details = new HashMap<>();
        first = true;
        for (Company c : list) {
            if (!first) sb.append(',');
            first = false;
            String sector = m.sectorOf(c);
            boolean listed = c.status == Status.LISTED;

            sb.append('{');
            kv(sb, "t", c.ticker).append(',');
            kv(sb, "n", c.name).append(',');
            kv(sb, "s", sector).append(',');
            kv(sb, "st", c.status.name()).append(',');
            kv(sb, "p", listed ? c.price : c.ipoPrice).append(',');
            kv(sb, "c", listed ? c.changePercent() : 0.0).append(',');
            kv(sb, "pool", c.poolShares).append(',');
            kv(sb, "total", c.totalShares).append(',');
            sb.append("\"sys\":").append(c.system).append(',');
            kv(sb, "owner", c.owner == null ? "Bursa" : Text.playerName(c.owner)).append(',');
            if (!c.system) kv(sb, "treasury", c.treasury).append(',');
            if (c.status == Status.IPO) {
                kv(sb, "ipoSold", c.ipoOffered - c.ipoRemaining).append(',');
                kv(sb, "ipoOffered", c.ipoOffered).append(',');
                kv(sb, "ipoEnd", c.ipoEnd).append(',');
            }
            sb.append("\"sp\":");
            List<Double> hist = new ArrayList<>(c.history);
            if (listed) hist.add(c.price);
            numArray(sb, tail(hist, 16));
            sb.append('}');

            if (listed) {
                StringBuilder d = new StringBuilder(2048);
                d.append('{');
                kv(d, "t", c.ticker).append(',');
                kv(d, "n", c.name).append(',');
                kv(d, "desc", c.description).append(',');
                kv(d, "s", m.sectorName(sector)).append(',');
                kv(d, "p", c.price).append(',');
                kv(d, "o", c.openPrice).append(',');
                kv(d, "c", c.changePercent()).append(',');
                kv(d, "pool", c.poolShares).append(',');
                kv(d, "total", c.totalShares).append(',');
                kv(d, "holders", c.holders.size()).append(',');
                kv(d, "owner", c.owner == null ? "Bursa" : Text.playerName(c.owner)).append(',');
                if (!c.system) kv(d, "treasury", c.treasury).append(',');
                kv(d, "hs", stepSec).append(',');
                kv(d, "ls", refreshSec).append(',');
                d.append("\"h\":");
                numArray(d, hist);
                d.append(",\"l\":");
                numArray(d, new ArrayList<>(live.getOrDefault(c.ticker, new ArrayDeque<>())));
                d.append('}');
                details.put(c.ticker, d.toString());
            }
        }
        sb.append("],\"news\":[");
        first = true;
        for (String n : m.news()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(plain(n))).append('"');
        }
        sb.append(']');

        if (cfg.getBoolean("web.show-investors", false)) {
            sb.append(",\"top\":[");
            first = true;
            for (Map.Entry<UUID, Double> e : m.topInvestors(10)) {
                if (!first) sb.append(',');
                first = false;
                sb.append('{');
                kv(sb, "n", Text.playerName(e.getKey())).append(',');
                kv(sb, "v", e.getValue());
                sb.append('}');
            }
            sb.append(']');
        }
        sb.append('}');
        data = new Data(sb.toString(), details);
    }

    // ---------- JSON helper (tanpa library) ----------

    private static List<Double> tail(List<Double> l, int n) {
        return l.size() <= n ? l : new ArrayList<>(l.subList(l.size() - n, l.size()));
    }

    private static String plain(String mm) {
        return mm.replaceAll("<[^>]*>", "").replace("\\<", "<").replace("\\>", ">");
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        return sb.append('"').append(k).append("\":\"").append(esc(v)).append('"');
    }

    private static StringBuilder kv(StringBuilder sb, String k, long v) {
        return sb.append('"').append(k).append("\":").append(v);
    }

    private static StringBuilder kv(StringBuilder sb, String k, int v) {
        return sb.append('"').append(k).append("\":").append(v);
    }

    private static StringBuilder kv(StringBuilder sb, String k, double v) {
        return sb.append('"').append(k).append("\":").append(num(v));
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        return String.format(Locale.US, "%.4f", v);
    }

    private static void numArray(StringBuilder sb, List<Double> vals) {
        sb.append('[');
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(num(vals.get(i)));
        }
        sb.append(']');
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
    }
}
