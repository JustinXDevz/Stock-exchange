package dev.bursa.stockexchange.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Company {
    public enum Status { PRIVATE, IPO, LISTED, DELISTED }

    public final String ticker;
    public String name;
    public String description = "";
    public String icon = "GOLD_INGOT";
    public UUID owner;            // null = saham sistem (admin)
    public boolean system;

    public int totalShares;
    public int poolShares;        // saham yang tersedia di pasar (dijual market maker)

    public double price;
    public double basePrice;
    public double volatility;
    public double treasury;
    public double openPrice;
    public long openTs;
    public long createdAt;
    public long listedAt;
    public long lastDividend;
    public Status status = Status.PRIVATE;

    public int ipoOffered;
    public int ipoRemaining;
    public double ipoPrice;
    public long ipoEnd;

    public int lowTicks;

    public String sector = "";
    public double revenueToday;     // hanya di memori
    public long revenueDayStart;    // hanya di memori

    // Efek event (hanya di memori)
    public double drift;
    public int driftTicks;

    public final Deque<Double> history = new ArrayDeque<>();
    public final Map<UUID, Holding> holders = new HashMap<>();
    public final Map<UUID, Integer> ipoOrders = new HashMap<>();

    public Company(String ticker, String name) {
        this.ticker = ticker;
        this.name = name;
    }

    public int heldByPlayers() {
        int sum = 0;
        for (Holding h : holders.values()) sum += h.shares;
        return sum;
    }

    /** Perubahan harga (persen) sejak harga pembukaan 24 jam. */
    public double changePercent() {
        return openPrice > 0 ? (price - openPrice) / openPrice * 100.0 : 0.0;
    }
}
