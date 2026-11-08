package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.StockExchangePlugin;
import dev.bursa.stockexchange.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Input angka lewat chat (menggantikan anvil/sign GUI). */
public final class Prompts implements Listener {
    private final StockExchangePlugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public Prompts(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String question, Consumer<String> handler) {
        pending.put(p.getUniqueId(), handler);
        p.closeInventory();
        p.sendMessage(Text.mm(Text.PREFIX + "<yellow>" + question + " <gray>(ketik 'batal' untuk membatalkan)"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> handler = pending.remove(e.getPlayer().getUniqueId());
        if (handler == null) return;
        e.setCancelled(true);
        final Player p = e.getPlayer();
        final String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("batal") || text.equalsIgnoreCase("cancel")) {
                p.sendMessage(Text.mm(Text.PREFIX + "<gray>Dibatalkan."));
            } else {
                handler.accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
