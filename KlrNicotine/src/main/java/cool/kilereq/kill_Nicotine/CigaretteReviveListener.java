package cool.kilereq.kill_Nicotine;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class CigaretteReviveListener implements Listener {

    private final MainNicotine plugin;
    private final CigaretteManager manager;

    public CigaretteReviveListener(MainNicotine plugin, CigaretteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage();
        String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
        handlePotentialReviveCommand(cmd);
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent e) {
        handlePotentialReviveCommand(e.getCommand());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        manager.resetOverdose(e.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        manager.resetOverdose(e.getPlayer());
    }

    private void handlePotentialReviveCommand(String commandLine) {
        if (plugin == null || plugin.cfg == null) return;

        var od = plugin.cfg.getConfigurationSection("overdose");
        if (od == null) return;
        var kd = od.getConfigurationSection("knockdown");
        if (kd == null) return;

        var prefixes = kd.getStringList("unlock_on_commands");
        if (prefixes == null || prefixes.isEmpty()) return;

        String lc = commandLine == null ? "" : commandLine.trim().toLowerCase();

        String[] parts = lc.split("\\s+");
        String targetName = parts.length >= 2 ? parts[parts.length - 1] : null;

        for (String pref : prefixes) {
            String pp = pref == null ? "" : pref.toLowerCase();
            if (!pp.isEmpty() && lc.startsWith(pp)) {
                if (targetName != null && !targetName.isEmpty()) {
                    Player target = Bukkit.getPlayerExact(targetName);
                    if (target == null) {
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            if (online.getName().equalsIgnoreCase(targetName)) {
                                target = online;
                                break;
                            }
                        }
                    }
                    if (target != null && target.isOnline()) {
                        manager.resetOverdose(target);
                        String msg = plugin.getMessage("overdose_unlocked");
                        if (msg != null && !msg.isEmpty()) target.sendMessage(msg);
                    }
                }
                break;
            }
        }
    }
}
