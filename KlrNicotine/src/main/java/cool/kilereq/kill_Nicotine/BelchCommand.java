package cool.kilereq.kill_Nicotine;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BelchCommand implements CommandExecutor {
    private final MainNicotine plugin;
    public BelchCommand(MainNicotine plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getMessage("player_only"));
            return true;
        }
        plugin.getSnusManager().wypluj(p);
        return true;
    }
}
