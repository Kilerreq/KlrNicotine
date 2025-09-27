package cool.kilereq.kill_Nicotine;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AddonsManager {

    private final MainNicotine plugin;

    public AddonsManager(MainNicotine plugin) {
        this.plugin = plugin;
    }

    public ItemStack createLighterFromConfig() {
        var cfg = plugin.getAddonsCfg();
        var sec = (cfg != null) ? cfg.getConfigurationSection("addons.lighter") : null;
        if (sec == null) {
            // fallback
            ItemStack it = new ItemStack(Material.FLINT_AND_STEEL, 1);
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                m.setDisplayName(plugin.color("&6&lZapalniczka"));
                m.getPersistentDataContainer().set(plugin.KEY_LIGHTER, PersistentDataType.INTEGER, 1);
                m.getPersistentDataContainer().set(plugin.KEY_UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());
                it.setItemMeta(m);
            }
            return it;
        }

        String matName = sec.getString("material", "FLINT_AND_STEEL");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.FLINT_AND_STEEL;

        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(sec.getString("name", "&6&lZapalniczka")));

            List<String> rawLore = sec.getStringList("lore");
            if (rawLore != null && !rawLore.isEmpty()) {
                List<String> lore = new ArrayList<>(rawLore.size());
                for (String l : rawLore) lore.add(plugin.color(l));
                meta.setLore(lore);
            }

            int cmd = sec.getInt("custom_model_data", 0);
            if (cmd > 0) meta.setCustomModelData(cmd);

            meta.getPersistentDataContainer().set(plugin.KEY_LIGHTER, PersistentDataType.INTEGER, 1);
            meta.getPersistentDataContainer().set(plugin.KEY_UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isLighter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(plugin.KEY_LIGHTER, PersistentDataType.INTEGER);
    }
}
