package dev.bursa.stockexchange;

import dev.bursa.stockexchange.commands.CompanyCommand;
import dev.bursa.stockexchange.commands.StockAdminCommand;
import dev.bursa.stockexchange.commands.StockCommand;
import dev.bursa.stockexchange.data.Database;
import dev.bursa.stockexchange.gui.GuiListener;
import dev.bursa.stockexchange.gui.Prompts;
import dev.bursa.stockexchange.market.Market;
import dev.bursa.stockexchange.market.ShopHook;
import dev.bursa.stockexchange.util.Text;
import dev.bursa.stockexchange.web.Snapshot;
import dev.bursa.stockexchange.web.WebServer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class StockExchangePlugin extends JavaPlugin {
    private Database database;
    private Market market;
    private Prompts prompts;
    private Economy economy;
    private ShopHook shopHook;
    private WebServer webServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Text.currency = getConfig().getString("currency-symbol", "$");

        try {
            File dbFile = new File(getDataFolder(), getConfig().getString("database-file", "stockexchange.db"));
            database = new Database(dbFile, getLogger());
            market = new Market(this, database);
            market.load();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Gagal memuat database, plugin dimatikan.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        prompts = new Prompts(this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(prompts, this);

        register("stock", new StockCommand(this));
        register("company", new CompanyCommand(this));
        register("stockadmin", new StockAdminCommand(this));

        long priceTicks = 20L * Math.max(5, getConfig().getInt("market.update-interval-seconds", 60));
        getServer().getScheduler().runTaskTimer(this, market::tick, priceTicks, priceTicks);
        getServer().getScheduler().runTaskTimer(this, market::checkIpos, 600L, 600L);
        long eventTicks = 20L * 60L * Math.max(1, getConfig().getInt("events.interval-minutes", 30));
        getServer().getScheduler().runTaskTimer(this, market::randomEvent, eventTicks, eventTicks);

        if (getConfig().getBoolean("web.enabled", true)) {
            Snapshot snapshot = new Snapshot(this);
            snapshot.refresh();
            webServer = new WebServer(this, snapshot);
            if (webServer.start()) {
                long webTicks = 20L * Math.max(1, getConfig().getInt("web.refresh-seconds", 5));
                getServer().getScheduler().runTaskTimer(this, snapshot::refresh, webTicks, webTicks);
            } else {
                webServer = null;
            }
        }

        shopHook = new ShopHook(this);
        getServer().getScheduler().runTask(this, shopHook::hook);

        getServer().getScheduler().runTask(this, () -> {
            if (economy() == null) {
                getLogger().warning("Tidak ada provider ekonomi Vault. Pasang plugin ekonomi (mis. EssentialsX/CMI) agar bursa berfungsi.");
            }
        });
        getLogger().info("StockExchange aktif: " + market.all().size() + " perusahaan dimuat.");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        if (database != null) database.close();
    }

    private void register(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command " + name + " tidak ada di plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    public Market market() { return market; }
    public Database database() { return database; }
    public Prompts prompts() { return prompts; }
    public ShopHook shopHook() { return shopHook; }

    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    public void msg(CommandSender to, String mm) {
        to.sendMessage(Text.mm(Text.PREFIX + mm));
    }
}
