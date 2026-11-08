package dev.bursa.stockexchange.gui;

import dev.bursa.stockexchange.model.Company;
import dev.bursa.stockexchange.util.Text;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class Items {
    private Items() {}

    public static ItemStack of(Material m, String name, String... lore) {
        return of(m, 1, name, List.of(lore));
    }

    public static ItemStack of(Material m, String name, List<String> lore) {
        return of(m, 1, name, lore);
    }

    public static ItemStack of(Material m, int amount, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.mm(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream().map(Text::mm).toList());
        }
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    public static Material icon(Company c) {
        Material m = Material.matchMaterial(c.icon);
        if (m == null || !m.isItem() || m.isAir()) return Material.PAPER;
        return m;
    }
}
