package cool.kilereq.kill_Nicotine;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class CigaretteCoreListener implements Listener {

    private final CigaretteManager manager;

    public CigaretteCoreListener(CigaretteManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        manager.handlePlayerInteract(e);
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent e) {
        manager.handleHeldChange(e);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.handleQuit(e);
    }
}
