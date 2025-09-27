package cool.kilereq.kill_Nicotine;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SnusListener implements Listener {

    private final SnusManager snusManager;

    public SnusListener(SnusManager snusManager) {
        this.snusManager = snusManager;
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (item == null || snusManager == null) return;

        if (snusManager.isSnusPack(item)) {
            ItemStack taken = snusManager.takeSnusFromPack(item);
            if (taken != null) {
                e.getPlayer().getInventory().addItem(taken);
            }
            e.setCancelled(true);
            return;
        }

        if (snusManager.isSnus(item)) {
            boolean handled = snusManager.handlePlayerInteract(e.getPlayer(), item);
            if (handled) e.setCancelled(true);
        }
    }
}
