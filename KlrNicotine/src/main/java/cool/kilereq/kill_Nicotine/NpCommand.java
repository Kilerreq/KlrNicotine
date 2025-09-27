package cool.kilereq.kill_Nicotine;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class NpCommand implements CommandExecutor, TabCompleter {

    private final MainNicotine plugin;

    public NpCommand(MainNicotine plugin) {
        this.plugin = plugin;
    }

    private void sendToSender(CommandSender sender, String langKey) {
        if (sender instanceof Player) sender.sendMessage(plugin.getMessage(langKey));
        else sender.sendMessage(plugin.getRawMessage(langKey));
    }

    private void sendToSenderFormatted(CommandSender sender, String langKey, String placeholder, String value) {
        String outPlayer = plugin.getMessage(langKey).replace(placeholder, value);
        String outConsole = plugin.getRawMessage(langKey).replace(placeholder, value);
        if (sender instanceof Player) sender.sendMessage(outPlayer);
        else sender.sendMessage(outConsole);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sendToSender(sender, "unknown_command");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "version":
                sendToSenderFormatted(sender, "version", "%version%", plugin.getDescription().getVersion());
                return true;

            case "reload":
                plugin.reloadConfig();
                plugin.cfg = plugin.getConfig();
                plugin.loadLang();
                if (plugin.getSnusManager() != null) {
                    plugin.loadCigaretteConfig();
                    plugin.getSnusManager().loadSnusConfig();
                }
                sender.sendMessage(plugin.getMessage("config_reloaded"));
                return true;

            case "give":
                if (args.length < 3) {
                    sendToSender(sender, "usage_give");
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sendToSender(sender, "player_only");
                    return true;
                }
                Player player = (Player) sender;
                String type = args[1].toLowerCase();
                String id = args[2];

                if (type.equals("cigarette")) {
                    boolean ok = plugin.getCigaretteManager() != null &&
                            plugin.getCigaretteManager().givePack(player, id, "cigarette_packs");
                    if (ok) sendToSenderFormatted(sender, "np_give_cigarette_pack", "%pack%", id);
                    else sendToSenderFormatted(sender, "np_cigarette_pack_not_found", "%pack%", id);
                    return true;

                } else if (type.equals("snus")) {
                    boolean ok = plugin.getSnusManager() != null &&
                            plugin.getSnusManager().givePack(player, id, "snus_packs");
                    if (ok) sendToSenderFormatted(sender, "np_give_snus_pack", "%pack%", id);
                    else sendToSenderFormatted(sender, "np_snus_pack_not_found", "%pack%", id);
                    return true;

                } else if (type.equals("addon") || type.equals("addons")) {
                    if (id.equalsIgnoreCase("lighter")) {
                        var it = plugin.getAddonsManager().createLighterFromConfig();
                        player.getInventory().addItem(it);
                        sender.sendMessage(plugin.getMessage("np_give_lighter"));
                        return true;
                    } else {
                        sendToSenderFormatted(sender, "np_addon_not_found", "%addon%", id);
                        return true;
                    }
                } else {
                    sendToSender(sender, "unknown_command");
                    return true;
                }

            case "wypluj":
                if (!(sender instanceof Player)) {
                    sendToSender(sender, "player_only");
                    return true;
                }
                Player p = (Player) sender;
                if (plugin.getSnusManager() != null) {
                    plugin.getSnusManager().wypluj(p);
                } else {
                    sender.sendMessage(ChatColor.RED + "SnusManager not available.");
                }
                return true;

            case "help":
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getRawMessage("help_header")));
                String rawList = plugin.getRawMessage("help_list");
                if (rawList != null && !rawList.isEmpty()) {
                    for (String line : rawList.split("\n")) {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line.trim()));
                    }
                }
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getRawMessage("help_footer")));
                return true;

            case "info":
                sendToSender(sender, "info_placeholder");
                return true;

            case "stats":
                sendToSender(sender, "stats_placeholder");
                return true;

            default:
                sendToSender(sender, "unknown_command");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("help");
            subs.add("version");
            subs.add("reload");
            subs.add("give");
            subs.add("wypluj");
            subs.add("info");
            subs.add("stats");
            final String pref = args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(pref)).collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> types = new ArrayList<>();
            types.add("cigarette");
            types.add("snus");
            types.add("addon");
            final String pref = args[1].toLowerCase();
            return types.stream().filter(s -> s.startsWith(pref)).collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String type = args[1].toLowerCase();

            if (type.equals("addon") || type.equals("addons")) {
                String pref = args[2].toLowerCase();
                return java.util.List.of("lighter").stream()
                        .filter(s -> s.startsWith(pref)).toList();
            }

            ConfigurationSection sec = null;

            if (type.equals("cigarette")) {
                FileConfiguration ccf = plugin.getCigaretteCfg();
                if (ccf != null) sec = ccf.getConfigurationSection("cigarette_packs");
                if (sec == null) {
                    FileConfiguration base = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
                    sec = base.getConfigurationSection("cigarette_packs");
                }
            } else if (type.equals("snus")) {
                FileConfiguration snusCfg = (plugin.getSnusManager() != null) ? plugin.getSnusManager().getSnusCfg() : null;
                if (snusCfg != null) {
                    sec = snusCfg.getConfigurationSection("snus.packs");
                    if (sec == null) sec = snusCfg.getConfigurationSection("snus_packs");
                }
                if (sec == null) {
                    FileConfiguration base = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
                    sec = base.getConfigurationSection("snus.packs");
                    if (sec == null) sec = base.getConfigurationSection("snus_packs");
                }
            } else {
                return Collections.emptyList();
            }

            if (sec == null) return Collections.emptyList();

            String prefix = args[2].toLowerCase();
            List<String> ids = new ArrayList<>(sec.getKeys(false));
            return ids.stream().filter(k -> k.toLowerCase().startsWith(prefix)).toList();
        }

        return Collections.emptyList();
    }
}
