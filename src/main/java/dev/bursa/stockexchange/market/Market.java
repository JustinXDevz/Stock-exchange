package dev.bursa.stockexchange.market;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.data.Database;
import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.model.Company.Status;
import dev.bursa.stockexchange.model.Holding;
import dev.bursa.stockexchange.util.Text;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Inti bursa. Semua method dipanggil dari main thread.
 * Model: market maker. Pemain membeli/menjual ke pasar (bukan antar pemain).
 * Pembelian terbatas oleh poolShares (saham yang tersedia di pasar).
 */
public final class Market {
    public record Result(boolean ok, String message) {}

    private static Result ok(String m) { return new Result(true, m); }
    private static Result fail(String m) { return new Result(false, m); }

    private final StockExchangePlugin plugin;
    private final Database db;
    private final Map<String, Company> companies = new LinkedHashMap<>();
    private final Deque<String> news = new ArrayDeque<>();
    private final Random random = new Random();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("dd/MM HH:mm");
    private final Map<String, Double> pressure = new HashMap<>();
    private final Map<UUID, Double> contributed = new HashMap<>();
    private boolean frozen;
    private int tickCounter;

    public Market(StockExchangePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void load() throws SQLException {
        for (Company c : db.loadAll()) companies.put(c.ticker, c);
    }

    // ---------- Config helper ----------

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private double d(String path) { return cfg().getDouble(path); }
    private int i(String path) { return cfg().getInt(path); }
    private double feeFraction() { return d("market.fee-percent") / 100.0; }

    // ---------- Query ----------

    public Company get(String ticker) {
        return ticker == null ? null : companies.get(ticker.toUpperCase(Locale.ROOT));
    }

    public Collection<Company> all() {
        return Collections.unmodifiableCollection(companies.values());
    }

    /** Saham yang bisa dilihat di bursa: IPO dulu, lalu LISTED, urut ticker. */
    public List<Company> tradable() {
        List<Company> l = new ArrayList<>();
        for (Company c : companies.values()) {
            if (c.status == Status.LISTED || c.status == Status.IPO) l.add(c);
        }
        l.sort(Comparator.comparing((Company c) -> c.status != Status.IPO).thenComparing(c -> c.ticker));
        return l;
    }

    public List<Company> ownedBy(UUID owner) {
        List<Company> l = new ArrayList<>();
        for (Company c : companies.values()) {
            if (owner.equals(c.owner) && c.status != Status.DELISTED) l.add(c);
        }
        return l;
    }

    public List<String> news() {
        return new ArrayList<>(news);
    }

    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean f) { this.frozen = f; }

    public List<Map.Entry<UUID, Double>> topInvestors(int limit) {
        Map<UUID, Double> worth = new HashMap<>();
        for (Company c : companies.values()) {
            if (c.status != Status.LISTED) continue;
            for (Map.Entry<UUID, Holding> e : c.holders.entrySet()) {
                worth.merge(e.getKey(), e.getValue().shares * c.price, Double::sum);
            }
        }
        return worth.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(limit)
                .toList();
    }

    // ---------- Harga & kuotasi ----------

    public int maxTrade(Company c) {
        return Math.max(1, (int) (c.totalShares * d("market.max-trade-percent") / 100.0));
    }

    private double impact(Company c, int qty) {
        return d("market.price-impact") * qty / Math.max(1, c.totalShares);
    }

    /** Total biaya (termasuk fee) untuk membeli qty saham. */
    public double quoteBuy(Company c, int qty) {
        if (c.status == Status.IPO) return Text.round2(qty * c.ipoPrice);
        double avg = c.price * (1 + impact(c, qty) / 2.0);
        double cost = Text.round2(avg * qty);
        return Text.round2(cost + Text.round2(cost * feeFraction()));
    }

    /** Uang bersih (setelah fee) yang diterima untuk menjual qty saham. */
    public double quoteSell(Company c, int qty) {
        double avg = c.price * (1 - impact(c, qty) / 2.0);
        double rev = Text.round2(avg * qty);
        return Text.round2(rev - Text.round2(rev * feeFraction()));
    }

    // ---------- Ekonomi (Vault) ----------

    /** @return pesan error, atau null jika berhasil. */
    private String charge(OfflinePlayer p, double amt) {
        if (amt <= 0) return null;
        Economy eco = plugin.economy();
        if (eco == null) return "Sistem ekonomi (Vault) belum siap.";
        if (!eco.has(p, amt)) return "Uangmu kurang. Dibutuhkan " + Text.money(amt) + ".";
        EconomyResponse r = eco.withdrawPlayer(p, amt);
        return r.transactionSuccess() ? null : "Transaksi ekonomi gagal: " + Text.esc(String.valueOf(r.errorMessage));
    }

    private boolean pay(OfflinePlayer p, double amt) {
        if (amt <= 0) return true;
        Economy eco = plugin.economy();
        if (eco == null) {
            plugin.getLogger().warning("Vault belum siap, gagal membayar " + amt + " ke " + p.getUniqueId());
            return false;
        }
        EconomyResponse r = eco.depositPlayer(p, amt);
        if (!r.transactionSuccess()) {
            plugin.getLogger().warning("Gagal membayar " + amt + " ke " + p.getUniqueId() + ": " + r.errorMessage);
            return false;
        }
        return true;
    }

    // ---------- Util internal ----------

    private void addHolding(Company c, UUID u, int qty, double unitCost) {
        Holding h = c.holders.get(u);
        if (h == null) {
            h = new Holding(qty, unitCost);
            c.holders.put(u, h);
        } else {
            double tot = h.shares + qty;
            h.avgCost = (h.avgCost * h.shares + unitCost * qty) / tot;
            h.shares += qty;
        }
        db.saveHolding(c.ticker, u, h.shares, h.avgCost);
    }

    private void removeHolding(Company c, UUID u, int qty) {
        Holding h = c.holders.get(u);
        if (h == null) return;
        h.shares -= qty;
        if (h.shares <= 0) {
            c.holders.remove(u);
            db.deleteHolding(c.ticker, u);
        } else {
            db.saveHolding(c.ticker, u, h.shares, h.avgCost);
        }
    }

    private void pushHistory(Company c) {
        c.history.addLast(c.price);
        while (c.history.size() > 48) c.history.removeFirst();
    }

    private void addNews(String mm) {
        news.addFirst("<gray>[" + timeFmt.format(new Date()) + "]</gray> " + mm);
        while (news.size() > 20) news.removeLast();
    }

    private void broadcast(String mm) {
        Bukkit.broadcast(Text.mm(Text.PREFIX + mm));
    }

    private void notifyPlayer(UUID u, String mm) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.sendMessage(Text.mm(Text.PREFIX + mm));
    }

    private String requireOwner(Player p, Company c) {
        if (c.system || c.owner == null || !c.owner.equals(p.getUniqueId())) {
            return "Kamu bukan pemilik perusahaan ini.";
        }
        return null;
    }

    // ---------- Beli / Jual ----------

    public Result buy(Player p, Company c, int qty) {
        if (frozen) return fail("Bursa sedang dibekukan admin.");
        if (qty <= 0) return fail("Jumlah harus lebih dari 0.");
        if (c.status == Status.IPO) return buyIpo(p, c, qty);
        if (c.status != Status.LISTED) return fail("Saham ini tidak diperdagangkan.");

        int maxTrade = maxTrade(c);
        if (qty > maxTrade) return fail("Maksimal " + maxTrade + " saham per transaksi.");
        if (qty > c.poolShares) return fail("Saham yang tersedia di pasar hanya " + c.poolShares + ".");

        double k = impact(c, qty);
        double avg = c.price * (1 + k / 2.0);
        double cost = Text.round2(avg * qty);
        double fee = Text.round2(cost * feeFraction());
        double total = Text.round2(cost + fee);

        String err = charge(p, total);
        if (err != null) return fail(err);

        c.poolShares -= qty;
        addHolding(c, p.getUniqueId(), qty, total / qty);
        c.price = Math.max(d("market.min-price"), c.price * (1 + k));
        db.saveCompany(c);
        db.logTransaction(c.ticker, p.getUniqueId(), "BUY", qty, avg, fee);
        return ok("Membeli " + qty + " " + c.ticker + " @ rata-rata " + Text.money(avg)
                + ". Total " + Text.money(total) + " (fee " + Text.money(fee) + ").");
    }

    public Result sell(Player p, Company c, int qty) {
        if (frozen) return fail("Bursa sedang dibekukan admin.");
        if (qty <= 0) return fail("Jumlah harus lebih dari 0.");
        if (c.status != Status.LISTED) return fail("Saham ini tidak bisa dijual sekarang.");

        Holding h = c.holders.get(p.getUniqueId());
        if (h == null || h.shares < qty) {
            return fail("Kamu hanya punya " + (h == null ? 0 : h.shares) + " saham " + c.ticker + ".");
        }
        if (c.owner != null && c.owner.equals(p.getUniqueId())) {
            long unlock = c.listedAt + (long) (d("company.lockup-hours") * 3600_000L);
            if (System.currentTimeMillis() < unlock) {
                return fail("Masa lock-up pendiri masih " + Text.duration(unlock - System.currentTimeMillis()) + " lagi.");
            }
        }
        int maxTrade = maxTrade(c);
        if (qty > maxTrade) return fail("Maksimal " + maxTrade + " saham per transaksi.");

        double k = impact(c, qty);
        double avg = c.price * (1 - k / 2.0);
        double revenue = Text.round2(avg * qty);
        double fee = Text.round2(revenue * feeFraction());
        double net = Text.round2(revenue - fee);

        double costBasis = h.avgCost * qty;
        if (!pay(p, net)) return fail("Gagal membayar ke dompetmu. Coba lagi.");

        removeHolding(c, p.getUniqueId(), qty);
        c.poolShares += qty;
        c.price = Math.max(d("market.min-price"), c.price * (1 - k));
        db.saveCompany(c);
        db.logTransaction(c.ticker, p.getUniqueId(), "SELL", qty, avg, fee);

        String pnl = costBasis > 0
                ? " Profit/Loss: " + (net - costBasis >= 0 ? "+" : "-") + Text.money(Math.abs(net - costBasis)) + "."
                : "";
        return ok("Menjual " + qty + " " + c.ticker + " @ rata-rata " + Text.money(avg)
                + ". Diterima " + Text.money(net) + " (fee " + Text.money(fee) + ")." + pnl);
    }

    // ---------- Perusahaan ----------

    public Result createCompany(Player p, String ticker, int total, String name) {
        ticker = ticker.toUpperCase(Locale.ROOT);
        if (!ticker.matches("[A-Z0-9]{2,6}")) return fail("Ticker harus 2-6 karakter huruf/angka.");
        if (companies.containsKey(ticker)) return fail("Ticker " + ticker + " sudah dipakai.");
        if (!name.matches("[\\p{L}\\p{N} .'&-]{3,24}")) {
            return fail("Nama harus 3-24 karakter (huruf, angka, spasi, . ' & -).");
        }
        int min = i("company.min-total-shares");
        int max = i("company.max-total-shares");
        if (total < min || total > max) return fail("Total saham harus antara " + min + " dan " + max + ".");
        if (ownedBy(p.getUniqueId()).size() >= i("company.max-per-player")) {
            return fail("Kamu sudah mencapai batas jumlah perusahaan.");
        }

        String err = charge(p, d("company.creation-fee"));
        if (err != null) return fail(err);

        long now = System.currentTimeMillis();
        Company c = new Company(ticker, name);
        c.owner = p.getUniqueId();
        c.totalShares = total;
        c.status = Status.PRIVATE;
        c.createdAt = now;
        c.volatility = d("market.default-volatility");
        companies.put(ticker, c);
        addHolding(c, p.getUniqueId(), total, 0);
        db.saveCompany(c);
        return ok("Perusahaan " + Text.esc(name) + " (" + ticker + ") berdiri dengan " + total
                + " saham. Selanjutnya: /company ipo " + ticker + " [jumlah] [harga] [jam]");
    }

    public Result startIpo(Player p, Company c, int offered, double price, int hours) {
        String own = requireOwner(p, c);
        if (own != null) return fail(own);
        if (frozen) return fail("Bursa sedang dibekukan admin.");
        if (c.status != Status.PRIVATE) return fail("Hanya perusahaan berstatus PRIVATE yang bisa IPO.");
        if (price < d("company.ipo.min-price") || price > d("company.ipo.max-price")) {
            return fail("Harga IPO harus antara " + Text.money(d("company.ipo.min-price")) + " dan "
                    + Text.money(d("company.ipo.max-price")) + ".");
        }
        int maxHours = i("company.ipo.max-duration-hours");
        if (hours < 1 || hours > maxHours) return fail("Durasi IPO harus 1-" + maxHours + " jam.");

        Holding founder = c.holders.get(p.getUniqueId());
        int have = founder == null ? 0 : founder.shares;
        int minKeep = (int) Math.ceil(c.totalShares * d("company.min-founder-percent") / 100.0);
        if (offered < 1 || have - offered < minKeep) {
            return fail("Kamu wajib menahan minimal " + minKeep + " saham. Maksimal yang bisa di-IPO-kan: "
                    + Math.max(0, have - minKeep) + ".");
        }

        String err = charge(p, d("company.ipo.listing-fee"));
        if (err != null) return fail(err);

        removeHolding(c, p.getUniqueId(), offered);
        c.ipoOffered = offered;
        c.ipoRemaining = offered;
        c.ipoPrice = price;
        c.ipoEnd = System.currentTimeMillis() + hours * 3600_000L;
        c.ipoOrders.clear();
        c.status = Status.IPO;
        db.deleteIpoOrders(c.ticker);
        db.saveCompany(c);

        String msg = "<light_purple>IPO dibuka!</light_purple> <white>" + Text.esc(c.name) + "</white> (" + c.ticker + ") menawarkan "
                + offered + " saham @ " + Text.money(price) + " selama " + hours + " jam. Buka /stock";
        addNews(msg);
        broadcast(msg);
        return ok("IPO " + c.ticker + " dimulai.");
    }

    private Result buyIpo(Player p, Company c, int qty) {
        if (System.currentTimeMillis() >= c.ipoEnd) return fail("IPO sudah berakhir, menunggu penyelesaian.");
        if (p.getUniqueId().equals(c.owner)) return fail("Pendiri tidak bisa membeli IPO perusahaannya sendiri.");
        if (qty > c.ipoRemaining) return fail("Sisa saham IPO hanya " + c.ipoRemaining + ".");

        int cap = Math.max(1, (int) Math.ceil(c.ipoOffered * d("company.ipo.max-per-player-percent") / 100.0));
        int already = c.ipoOrders.getOrDefault(p.getUniqueId(), 0);
        if (already + qty > cap) {
            return fail("Batas pembelian IPO per pemain: " + cap + " saham (kamu sudah punya " + already + ").");
        }

        double cost = Text.round2(qty * c.ipoPrice);
        String err = charge(p, cost);
        if (err != null) return fail(err);

        c.ipoRemaining -= qty;
        int now = already + qty;
        c.ipoOrders.put(p.getUniqueId(), now);
        db.saveIpoOrder(c.ticker, p.getUniqueId(), now);
        db.saveCompany(c);
        db.logTransaction(c.ticker, p.getUniqueId(), "IPO_BUY", qty, c.ipoPrice, 0);

        if (c.ipoRemaining == 0) finishIpo(c);
        return ok("Memesan " + qty + " saham IPO " + c.ticker + " @ " + Text.money(c.ipoPrice)
                + " = " + Text.money(cost) + ". Saham masuk setelah IPO sukses; jika gagal, uang dikembalikan.");
    }

    public void checkIpos() {
        long now = System.currentTimeMillis();
        for (Company c : new ArrayList<>(companies.values())) {
            if (c.status == Status.IPO && now >= c.ipoEnd) finishIpo(c);
        }
    }

    private void finishIpo(Company c) {
        int sold = c.ipoOffered - c.ipoRemaining;
        int minSold = (int) Math.ceil(c.ipoOffered * d("company.ipo.min-sold-percent") / 100.0);
        if (sold > 0 && sold >= minSold) {
            successIpo(c, sold);
        } else {
            failIpo(c);
        }
    }

    private void successIpo(Company c, int sold) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(c.ipoOrders.entrySet())) {
            addHolding(c, e.getKey(), e.getValue(), c.ipoPrice);
            notifyPlayer(e.getKey(), "<green>IPO " + c.ticker + " sukses! " + e.getValue() + " saham masuk portofoliomu.");
        }
        if (c.owner != null && c.ipoRemaining > 0) addHolding(c, c.owner, c.ipoRemaining, 0);
        c.treasury += sold * c.ipoPrice;
        c.ipoOrders.clear();
        db.deleteIpoOrders(c.ticker);
        c.ipoRemaining = 0;
        c.ipoOffered = 0;
        c.status = Status.LISTED;
        c.listedAt = now;
        c.price = c.ipoPrice;
        c.basePrice = c.ipoPrice;
        c.openPrice = c.ipoPrice;
        c.openTs = now;
        c.history.clear();
        pushHistory(c);
        db.saveCompany(c);

        String msg = "<green>" + Text.esc(c.name) + " (" + c.ticker + ") resmi tercatat di bursa @ " + Text.money(c.price) + "!";
        addNews(msg);
        broadcast(msg);
    }

    private void failIpo(Company c) {
        if (plugin.economy() == null) return; // coba lagi nanti, uang harus bisa dikembalikan
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(c.ipoOrders.entrySet())) {
            double refund = Text.round2(e.getValue() * c.ipoPrice);
            pay(Bukkit.getOfflinePlayer(e.getKey()), refund);
            notifyPlayer(e.getKey(), "<yellow>IPO " + c.ticker + " gagal. Dana " + Text.money(refund) + " dikembalikan.");
        }
        int offered = c.ipoOffered;
        if (c.owner != null && offered > 0) addHolding(c, c.owner, offered, 0);
        c.ipoOrders.clear();
        db.deleteIpoOrders(c.ticker);
        c.ipoRemaining = 0;
        c.ipoOffered = 0;
        c.status = Status.PRIVATE;
        db.saveCompany(c);

        String msg = "<red>IPO " + Text.esc(c.name) + " (" + c.ticker + ") gagal (peminat kurang).";
        addNews(msg);
        broadcast(msg);
    }

    public Result deposit(Player p, Company c, double amount) {
        String own = requireOwner(p, c);
        if (own != null) return fail(own);
        if (c.status == Status.DELISTED) return fail("Perusahaan sudah dibubarkan.");
        String err = charge(p, amount);
        if (err != null) return fail(err);
        c.treasury += amount;
        db.saveCompany(c);
        return ok("Kas " + c.ticker + " bertambah " + Text.money(amount) + ". Total kas: " + Text.money(c.treasury) + ".");
    }

    public Result dividend(Player p, Company c, double amount) {
        String own = requireOwner(p, c);
        if (own != null) return fail(own);
        if (c.status != Status.LISTED) return fail("Dividen hanya untuk perusahaan yang sudah tercatat (LISTED).");
        double maxAmt = c.treasury * d("company.dividend.max-percent-of-treasury") / 100.0;
        if (amount > maxAmt) {
            return fail("Maksimal dividen sekali bayar " + Text.money(maxAmt) + " ("
                    + d("company.dividend.max-percent-of-treasury") + "% kas).");
        }
        long next = c.lastDividend + (long) (d("company.dividend.cooldown-hours") * 3600_000L);
        if (System.currentTimeMillis() < next) {
            return fail("Dividen berikutnya bisa dibagi " + Text.duration(next - System.currentTimeMillis()) + " lagi.");
        }
        int held = c.heldByPlayers();
        if (held <= 0) return fail("Tidak ada pemegang saham.");
        if (plugin.economy() == null) return fail("Sistem ekonomi (Vault) belum siap.");

        double paid = 0;
        for (Map.Entry<UUID, Holding> e : new ArrayList<>(c.holders.entrySet())) {
            double share = Text.round2(amount * e.getValue().shares / held);
            if (share <= 0) continue;
            if (pay(Bukkit.getOfflinePlayer(e.getKey()), share)) {
                paid += share;
                notifyPlayer(e.getKey(), "<green>Dividen " + c.ticker + ": kamu menerima " + Text.money(share) + ".");
            }
        }
        c.treasury = Math.max(0, c.treasury - paid);
        c.lastDividend = System.currentTimeMillis();
        db.saveCompany(c);
        addNews("<aqua>" + Text.esc(c.name) + " (" + c.ticker + ") membagikan dividen " + Text.money(paid) + ".");
        return ok("Dividen " + Text.money(paid) + " dibagikan ke pemegang saham.");
    }

    public Result delist(Player p, Company c) {
        String own = requireOwner(p, c);
        if (own != null) return fail(own);
        if (c.status == Status.IPO) return fail("Tidak bisa dibubarkan saat IPO berlangsung.");
        if (c.status == Status.DELISTED) return fail("Perusahaan sudah dibubarkan.");
        if (c.status == Status.LISTED) {
            long unlock = c.listedAt + (long) (d("company.lockup-hours") * 3600_000L);
            if (System.currentTimeMillis() < unlock) {
                return fail("Masa lock-up masih " + Text.duration(unlock - System.currentTimeMillis()) + " lagi.");
            }
        }
        liquidate(c, "dibubarkan oleh pemilik");
        return ok("Perusahaan " + c.ticker + " dibubarkan. Kas dibagi ke pemegang saham.");
    }

    /** Bagikan kas ke pemegang saham (sesuai jumlah saham), hapus kepemilikan, tandai DELISTED. */
    private void liquidate(Company c, String reason) {
        int held = c.heldByPlayers();
        double treasury = c.treasury;
        if (held > 0 && treasury > 0) {
            for (Map.Entry<UUID, Holding> e : new ArrayList<>(c.holders.entrySet())) {
                double amt = Text.round2(treasury * e.getValue().shares / held);
                pay(Bukkit.getOfflinePlayer(e.getKey()), amt);
                notifyPlayer(e.getKey(), "<yellow>" + Text.esc(c.name) + " dilikuidasi. Kamu menerima " + Text.money(amt) + ".");
            }
        }
        for (UUID u : new ArrayList<>(c.holders.keySet())) db.deleteHolding(c.ticker, u);
        c.holders.clear();
        c.treasury = 0;
        c.poolShares = 0;
        c.status = Status.DELISTED;
        db.saveCompany(c);

        String msg = "<red>" + Text.esc(c.name) + " (" + c.ticker + ") dihapus dari bursa (" + reason + ").";
        addNews(msg);
        broadcast(msg);
    }

    // ---------- Admin ----------

    public Result createSystemStock(String ticker, String name, double price, double volatility, int totalShares) {
        ticker = ticker.toUpperCase(Locale.ROOT);
        if (!ticker.matches("[A-Z0-9]{2,6}")) return fail("Ticker harus 2-6 karakter huruf/angka.");
        if (companies.containsKey(ticker)) return fail("Ticker sudah dipakai.");
        if (price < d("market.min-price")) return fail("Harga terlalu kecil.");
        long now = System.currentTimeMillis();
        Company c = new Company(ticker, name);
        c.system = true;
        c.owner = null;
        c.totalShares = totalShares;
        c.poolShares = totalShares;
        c.price = price;
        c.basePrice = price;
        c.openPrice = price;
        c.openTs = now;
        c.createdAt = now;
        c.listedAt = now;
        c.volatility = volatility;
        c.status = Status.LISTED;
        pushHistory(c);
        companies.put(ticker, c);
        db.saveCompany(c);
        return ok("Saham sistem " + ticker + " dibuat @ " + Text.money(price) + ".");
    }

    public Result adminDelete(String ticker) {
        Company c = get(ticker);
        if (c == null) return fail("Ticker tidak ditemukan.");
        if (c.system) {
            for (Map.Entry<UUID, Holding> e : new ArrayList<>(c.holders.entrySet())) {
                pay(Bukkit.getOfflinePlayer(e.getKey()), Text.round2(e.getValue().shares * c.price));
                notifyPlayer(e.getKey(), "<yellow>Saham " + c.ticker + " dihapus. Sahammu dibayar harga pasar.");
            }
        } else {
            if (c.status == Status.IPO) failIpo(c);
            if (c.status == Status.IPO) return fail("Gagal membatalkan IPO (ekonomi belum siap). Coba lagi.");
            if (c.status != Status.DELISTED) liquidate(c, "dihapus admin");
        }
        companies.remove(c.ticker);
        db.deleteCompany(c.ticker);
        return ok("Ticker " + c.ticker + " dihapus.");
    }

    public Result setPrice(String ticker, double price) {
        Company c = get(ticker);
        if (c == null) return fail("Ticker tidak ditemukan.");
        if (c.status != Status.LISTED) return fail("Hanya saham LISTED yang bisa diubah harganya.");
        c.price = Math.max(d("market.min-price"), price);
        if (c.system) c.basePrice = c.price;
        c.openPrice = c.price;
        c.openTs = System.currentTimeMillis();
        pushHistory(c);
        db.saveCompany(c);
        return ok("Harga " + c.ticker + " diatur ke " + Text.money(c.price) + ".");
    }

    public Result setVolatility(String ticker, double vol) {
        Company c = get(ticker);
        if (c == null) return fail("Ticker tidak ditemukan.");
        c.volatility = Math.max(0, Math.min(0.2, vol));
        db.saveCompany(c);
        return ok("Volatilitas " + c.ticker + " = " + c.volatility + ".");
    }

    public Result setIcon(Company c, String materialName) {
        org.bukkit.Material m = org.bukkit.Material.matchMaterial(materialName);
        if (m == null || !m.isItem() || m.isAir()) return fail("Material tidak valid: " + Text.esc(materialName));
        c.icon = m.name();
        db.saveCompany(c);
        return ok("Ikon " + c.ticker + " diubah ke " + m.name() + ".");
    }

    public Result setDescription(Company c, String text) {
        String clean = text.replace("<", "").replace(">", "");
        if (clean.length() > 80) clean = clean.substring(0, 80);
        c.description = clean;
        db.saveCompany(c);
        return ok("Deskripsi diperbarui.");
    }

    public Result applyEvent(Company c, boolean boom) {
        if (c.status != Status.LISTED) return fail("Event hanya untuk saham LISTED.");
        c.drift = d("events.drift") * (boom ? 1 : -1);
        c.driftTicks = Math.max(1, i("events.duration-ticks"));
        List<String> msgs = cfg().getStringList(boom ? "events.boom-messages" : "events.crash-messages");
        String template = msgs.isEmpty()
                ? (boom ? "<green>Saham {name} ({ticker}) melonjak!" : "<red>Saham {name} ({ticker}) anjlok!")
                : msgs.get(random.nextInt(msgs.size()));
        String msg = template.replace("{name}", Text.esc(c.name)).replace("{ticker}", c.ticker);
        addNews(msg);
        broadcast(msg);
        return ok("Event " + (boom ? "boom" : "crash") + " diterapkan ke " + c.ticker + ".");
    }

    // ---------- Integrasi shop (EconomyShopGUI) ----------

    /** id sektor -> nama tampilan, dari config. */
    public Map<String, String> sectors() {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection secs = cfg().getConfigurationSection("shop.sectors");
        if (secs != null) {
            for (String id : secs.getKeys(false)) out.put(id, secs.getString(id + ".name", id));
        }
        return out;
    }

    public Result setSector(Player p, Company c, String id) {
        String own = requireOwner(p, c);
        if (own != null) return fail(own);
        if (c.status == Status.DELISTED) return fail("Perusahaan sudah dibubarkan.");
        if (id.equalsIgnoreCase("none")) {
            c.sector = "";
            db.saveCompany(c);
            return ok("Sektor " + c.ticker + " dilepas.");
        }
        for (String key : sectors().keySet()) {
            if (key.equalsIgnoreCase(id)) {
                c.sector = key;
                db.saveCompany(c);
                return ok(c.ticker + " kini di sektor " + sectors().get(key)
                        + ". Sebagian omzet shop sektor ini masuk ke kas (jika sudah LISTED).");
            }
        }
        return fail("Sektor tidak dikenal. Lihat daftar: /company sector");
    }

    /**
     * Dipanggil dari ShopHook untuk tiap transaksi shop.
     * @param buy true = pemain membeli dari shop, false = menjual ke shop, null = tidak diketahui
     * @return id sektor yang cocok, atau null
     */
    public String recordShopTransaction(UUID player, Boolean buy, double value, Set<String> tokens) {
        if (frozen || value <= 0) return null;
        ConfigurationSection secs = cfg().getConfigurationSection("shop.sectors");
        if (secs == null) return null;

        String sectorId = null;
        for (String id : secs.getKeys(false)) {
            for (String match : secs.getStringList(id + ".match")) {
                if (tokens.contains(match.toUpperCase(Locale.ROOT))) {
                    sectorId = id;
                    break;
                }
            }
            if (sectorId != null) break;
        }
        if (sectorId == null) return null;

        // 1) Tekanan supply/demand ke saham sektor
        String stockTicker = secs.getString(sectorId + ".stock", "");
        Company stock = (stockTicker == null || stockTicker.isEmpty()) ? null : get(stockTicker);
        if (buy != null && stock != null && stock.status == Status.LISTED) {
            double cap = d("shop.max-per-player-per-tick");
            double used = contributed.getOrDefault(player, 0.0);
            double eff = Math.max(0, Math.min(value, cap - used));
            contributed.put(player, used + eff);
            double dir = buy ? d("shop.buy-direction") : d("shop.sell-direction");
            pressure.merge(stock.ticker, dir * eff / Math.max(1.0, d("shop.volume-reference")), Double::sum);
        }

        // 2) Omzet ke kas perusahaan pemain di sektor ini
        double pool = value * d("shop.revenue.share-percent") / 100.0;
        if (pool > 0) {
            List<Company> linked = new ArrayList<>();
            for (Company c : companies.values()) {
                if (!c.system && c.status == Status.LISTED && sectorId.equalsIgnoreCase(c.sector)) linked.add(c);
            }
            if (!linked.isEmpty()) {
                double per = pool / linked.size();
                double cap = d("shop.revenue.daily-cap-per-company");
                long now = System.currentTimeMillis();
                for (Company c : linked) {
                    if (now - c.revenueDayStart >= 86_400_000L) {
                        c.revenueDayStart = now;
                        c.revenueToday = 0;
                    }
                    double add = Math.max(0, Math.min(per, cap - c.revenueToday));
                    if (add <= 0) continue;
                    c.treasury += add;
                    c.revenueToday += add;
                    db.saveCompany(c);
                }
            }
        }
        return sectorId;
    }

    // ---------- Scheduler ----------

    public void randomEvent() {
        if (frozen || !cfg().getBoolean("events.enabled", true)) return;
        if (random.nextDouble() > d("events.chance")) return;
        List<Company> listed = new ArrayList<>();
        for (Company c : companies.values()) if (c.status == Status.LISTED) listed.add(c);
        if (listed.isEmpty()) return;
        applyEvent(listed.get(random.nextInt(listed.size())), random.nextBoolean());
    }

    public void tick() {
        if (frozen) return;
        tickCounter++;
        long now = System.currentTimeMillis();
        double min = d("market.min-price");
        double bookMult = d("market.book-value-multiplier");
        double bookPull = d("market.book-pull");
        double basePull = d("market.base-pull");
        double delistPrice = d("market.delist-price");
        int delistTicks = i("market.delist-ticks");
        int histEvery = Math.max(1, i("market.history-interval-ticks"));

        List<Company> bankrupt = new ArrayList<>();
        for (Company c : companies.values()) {
            if (c.status != Status.LISTED) continue;

            double change = random.nextGaussian() * c.volatility;
            if (c.driftTicks > 0) {
                change += c.drift;
                c.driftTicks--;
                if (c.driftTicks == 0) c.drift = 0;
            }
            Double pr = pressure.remove(c.ticker);
            if (pr != null) {
                double maxC = d("shop.max-change-per-tick");
                change += Math.max(-maxC, Math.min(maxC, pr * d("shop.sensitivity")));
            }
            change = Math.max(-0.2, Math.min(0.2, change));

            double p = c.price * (1 + change);
            if (c.system) {
                p += basePull * (c.basePrice - p);
            } else {
                double target = c.treasury * bookMult / Math.max(1, c.totalShares);
                p += bookPull * (target - p);
            }
            p = Math.max(min, p);
            c.price = p;

            if (now - c.openTs >= 86_400_000L) {
                c.openPrice = p;
                c.openTs = now;
            }
            if (!c.system) {
                if (p < delistPrice) c.lowTicks++; else c.lowTicks = 0;
                if (c.lowTicks >= delistTicks) bankrupt.add(c);
            }
            if (tickCounter % histEvery == 0) {
                pushHistory(c);
                db.insertHistory(c.ticker, now, p);
            }
            db.saveCompany(c);
        }
        pressure.clear();
        contributed.clear();
        for (Company c : bankrupt) liquidate(c, "bangkrut: harga terlalu lama di bawah batas");
    }
}
