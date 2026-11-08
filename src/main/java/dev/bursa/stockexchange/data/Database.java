package dev.bursa.stockexchange.data;

import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.model.Holding;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQLite. Semua tulis dilakukan di thread terpisah (urut) agar server tidak lag. */
public final class Database {
    @FunctionalInterface
    private interface SqlTask {
        void run() throws SQLException;
    }

    public record Tx(long ts, String ticker, String type, int shares, double price, double fee) {}

    private static final String UPSERT_COMPANY =
            "INSERT OR REPLACE INTO companies (ticker, name, description, icon, owner, is_system, total_shares, "
                    + "pool_shares, price, base_price, volatility, treasury, open_price, open_ts, created_at, listed_at, "
                    + "last_dividend, status, ipo_offered, ipo_remaining, ipo_price, ipo_end, low_ticks, sector) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final Logger log;
    private final Connection conn;
    private final ExecutorService exec;

    public Database(File file, Logger log) throws SQLException {
        this.log = log;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver SQLite tidak ditemukan di server ini", e);
        }
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        this.exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "StockExchange-DB"));
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS companies ("
                    + "ticker TEXT PRIMARY KEY, name TEXT, description TEXT, icon TEXT, owner TEXT, "
                    + "is_system INTEGER, total_shares INTEGER, pool_shares INTEGER, price REAL, base_price REAL, "
                    + "volatility REAL, treasury REAL, open_price REAL, open_ts INTEGER, created_at INTEGER, "
                    + "listed_at INTEGER, last_dividend INTEGER, status TEXT, ipo_offered INTEGER, "
                    + "ipo_remaining INTEGER, ipo_price REAL, ipo_end INTEGER, low_ticks INTEGER, sector TEXT)");
            try {
                st.execute("ALTER TABLE companies ADD COLUMN sector TEXT");
            } catch (SQLException ignored) {
                // kolom sudah ada
            }
            st.execute("CREATE TABLE IF NOT EXISTS holdings ("
                    + "ticker TEXT, uuid TEXT, shares INTEGER, avg_cost REAL, PRIMARY KEY (ticker, uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS ipo_orders ("
                    + "ticker TEXT, uuid TEXT, shares INTEGER, PRIMARY KEY (ticker, uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS price_history ("
                    + "ticker TEXT, ts INTEGER, price REAL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_hist ON price_history (ticker, ts)");
            st.execute("CREATE TABLE IF NOT EXISTS transactions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, ticker TEXT, uuid TEXT, "
                    + "type TEXT, shares INTEGER, price REAL, fee REAL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tx ON transactions (uuid, ts)");
        }
    }

    // ---------- Muat data (sinkron, hanya saat startup) ----------

    public List<Company> loadAll() throws SQLException {
        Map<String, Company> map = new HashMap<>();
        List<Company> out = new ArrayList<>();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM companies")) {
            while (rs.next()) {
                Company c = new Company(rs.getString("ticker"), rs.getString("name"));
                c.description = nz(rs.getString("description"));
                c.icon = rs.getString("icon") == null ? "GOLD_INGOT" : rs.getString("icon");
                String owner = rs.getString("owner");
                c.owner = (owner == null || owner.isEmpty()) ? null : UUID.fromString(owner);
                c.system = rs.getInt("is_system") == 1;
                c.totalShares = rs.getInt("total_shares");
                c.poolShares = rs.getInt("pool_shares");
                c.price = rs.getDouble("price");
                c.basePrice = rs.getDouble("base_price");
                c.volatility = rs.getDouble("volatility");
                c.treasury = rs.getDouble("treasury");
                c.openPrice = rs.getDouble("open_price");
                c.openTs = rs.getLong("open_ts");
                c.createdAt = rs.getLong("created_at");
                c.listedAt = rs.getLong("listed_at");
                c.lastDividend = rs.getLong("last_dividend");
                c.status = Company.Status.valueOf(rs.getString("status"));
                c.ipoOffered = rs.getInt("ipo_offered");
                c.ipoRemaining = rs.getInt("ipo_remaining");
                c.ipoPrice = rs.getDouble("ipo_price");
                c.ipoEnd = rs.getLong("ipo_end");
                c.lowTicks = rs.getInt("low_ticks");
                String sec = rs.getString("sector");
                c.sector = sec == null ? "" : sec;
                map.put(c.ticker, c);
                out.add(c);
            }
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ticker, uuid, shares, avg_cost FROM holdings")) {
            while (rs.next()) {
                Company c = map.get(rs.getString(1));
                if (c != null) c.holders.put(UUID.fromString(rs.getString(2)), new Holding(rs.getInt(3), rs.getDouble(4)));
            }
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ticker, uuid, shares FROM ipo_orders")) {
            while (rs.next()) {
                Company c = map.get(rs.getString(1));
                if (c != null) c.ipoOrders.put(UUID.fromString(rs.getString(2)), rs.getInt(3));
            }
        }

        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM price_history WHERE ts < ?")) {
            del.setLong(1, cutoff);
            del.executeUpdate();
        }
        for (Company c : out) {
            List<Double> pts = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT price FROM price_history WHERE ticker = ? ORDER BY ts DESC LIMIT 48")) {
                ps.setString(1, c.ticker);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) pts.add(rs.getDouble(1));
                }
            }
            Collections.reverse(pts);
            c.history.addAll(pts);
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------- Tulis (async) ----------

    private void async(SqlTask t) {
        exec.execute(() -> {
            try {
                t.run();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Gagal menulis ke database", e);
            }
        });
    }

    public void saveCompany(Company c) {
        final Object[] v = {
                c.ticker, c.name, c.description, c.icon, c.owner == null ? "" : c.owner.toString(),
                c.system ? 1 : 0, c.totalShares, c.poolShares, c.price, c.basePrice, c.volatility, c.treasury,
                c.openPrice, c.openTs, c.createdAt, c.listedAt, c.lastDividend, c.status.name(),
                c.ipoOffered, c.ipoRemaining, c.ipoPrice, c.ipoEnd, c.lowTicks,
                c.sector == null ? "" : c.sector
        };
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_COMPANY)) {
                for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
                ps.executeUpdate();
            }
        });
    }

    public void saveHolding(String ticker, UUID uuid, int shares, double avgCost) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO holdings (ticker, uuid, shares, avg_cost) VALUES (?,?,?,?)")) {
                ps.setString(1, ticker);
                ps.setString(2, uuid.toString());
                ps.setInt(3, shares);
                ps.setDouble(4, avgCost);
                ps.executeUpdate();
            }
        });
    }

    public void deleteHolding(String ticker, UUID uuid) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM holdings WHERE ticker = ? AND uuid = ?")) {
                ps.setString(1, ticker);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
        });
    }

    public void saveIpoOrder(String ticker, UUID uuid, int shares) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO ipo_orders (ticker, uuid, shares) VALUES (?,?,?)")) {
                ps.setString(1, ticker);
                ps.setString(2, uuid.toString());
                ps.setInt(3, shares);
                ps.executeUpdate();
            }
        });
    }

    public void deleteIpoOrders(String ticker) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ipo_orders WHERE ticker = ?")) {
                ps.setString(1, ticker);
                ps.executeUpdate();
            }
        });
    }

    public void insertHistory(String ticker, long ts, double price) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO price_history (ticker, ts, price) VALUES (?,?,?)")) {
                ps.setString(1, ticker);
                ps.setLong(2, ts);
                ps.setDouble(3, price);
                ps.executeUpdate();
            }
        });
    }

    public void logTransaction(String ticker, UUID uuid, String type, int shares, double price, double fee) {
        final long ts = System.currentTimeMillis();
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (ts, ticker, uuid, type, shares, price, fee) VALUES (?,?,?,?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, ticker);
                ps.setString(3, uuid.toString());
                ps.setString(4, type);
                ps.setInt(5, shares);
                ps.setDouble(6, price);
                ps.setDouble(7, fee);
                ps.executeUpdate();
            }
        });
    }

    public void deleteCompany(String ticker) {
        async(() -> {
            for (String table : new String[]{"companies", "holdings", "ipo_orders", "price_history"}) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE ticker = ?")) {
                    ps.setString(1, ticker);
                    ps.executeUpdate();
                }
            }
        });
    }

    public CompletableFuture<List<Tx>> recentTransactions(UUID uuid, int limit) {
        CompletableFuture<List<Tx>> f = new CompletableFuture<>();
        exec.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ts, ticker, type, shares, price, fee FROM transactions "
                            + "WHERE uuid = ? ORDER BY ts DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                List<Tx> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Tx(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), rs.getDouble(5), rs.getDouble(6)));
                    }
                }
                f.complete(list);
            } catch (SQLException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    public void close() {
        exec.shutdown();
        try {
            exec.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
