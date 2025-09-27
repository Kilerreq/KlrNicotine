package cool.kilereq.kill_Nicotine;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NpTabCompleter implements TabCompleter {

    private final MainNicotine plugin;

    public NpTabCompleter(MainNicotine plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (args.length == 1) {
            List<String> sub = Arrays.asList("help", "version", "reload", "give", "wypluj", "info", "stats");
            return sub.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Arrays.asList("cigarette", "snus", "addon").stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String type = args[1].toLowerCase();

            java.util.Set<String> keys = new java.util.LinkedHashSet<>();

            if (type.equals("cigarette")) {
                if (plugin.getCigaretteCfg() != null) {
                    org.bukkit.configuration.ConfigurationSection secCig =
                            plugin.getCigaretteCfg().getConfigurationSection("cigarette_packs");
                    if (secCig != null) keys.addAll(secCig.getKeys(false));
                }
            } else if (type.equals("snus")) {
                org.bukkit.configuration.ConfigurationSection secCfg =
                        plugin.cfg != null ? plugin.cfg.getConfigurationSection("snus_packs") : null;
                if (secCfg != null) keys.addAll(secCfg.getKeys(false));

                if (plugin.getSnusManager() != null && plugin.getSnusManager().getSnusCfg() != null) {
                    org.bukkit.configuration.ConfigurationSection secSnus =
                            plugin.getSnusManager().getSnusCfg().getConfigurationSection("snus_packs");
                    if (secSnus != null) keys.addAll(secSnus.getKeys(false));
                }
            } else if (type.equals("addon") || type.equals("addons")) {
                keys.add("lighter");
            }

            if (keys.isEmpty()) return java.util.Collections.emptyList();

            final String prefix = args[2].toLowerCase();
            return keys.stream()
                    .filter(k -> k.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return new ArrayList<>();
    }
}
