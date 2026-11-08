package dev.bursa.stockexchange.model;

public final class Holding {
    public int shares;
    public double avgCost;

    public Holding(int shares, double avgCost) {
        this.shares = shares;
        this.avgCost = avgCost;
    }
}
