package cool.kilereq.kill_Nicotine;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class MainNicotine extends JavaPlugin {

    public FileConfiguration cfg;
    private FileConfiguration cigaretteCfg;
    private FileConfiguration addonsCfg;
    private AddonsManager addonsManager;

    private File langFile;
    private FileConfiguration lang;
    private FileConfiguration langFallback;

    public NamespacedKey KEY_PACK_WARNING;
    public NamespacedKey KEY_PACK_ID;
    public NamespacedKey KEY_PACK_REMAINING;
    public NamespacedKey KEY_CIG_ID;
    public NamespacedKey KEY_CIG_USES;
    public NamespacedKey KEY_CIG_MAX;
    public NamespacedKey KEY_UNIQUE;

    public NamespacedKey KEY_SNUS_PACK_ID;
    public NamespacedKey KEY_SNUS_PACK_REMAINING;
    public NamespacedKey KEY_SNUS_TYPE;
    public NamespacedKey KEY_SNUS_IN_MOUTH;
    public NamespacedKey KEY_SNUS_ENDED;
    public NamespacedKey KEY_CIG_LIT;
    public NamespacedKey KEY_LIGHTER;

    public CigaretteManager cigaretteManager;
    private SnusManager snusManager;

    public FileConfiguration getAddonsCfg() { return addonsCfg; }
    public AddonsManager getAddonsManager() { return addonsManager; }
    public FileConfiguration getCigaretteCfg() { return cigaretteCfg; }
    public SnusManager getSnusManager() { return snusManager; }
    public CigaretteManager getCigaretteManager() { return cigaretteManager; }

    @Override
    public void onEnable() {
        try {
            KEY_PACK_WARNING = new NamespacedKey(this, "pack_warning");
            KEY_PACK_ID = new NamespacedKey(this, "pack_id");
            KEY_PACK_REMAINING = new NamespacedKey(this, "pack_remaining");
            KEY_CIG_ID = new NamespacedKey(this, "cig_id");
            KEY_CIG_USES = new NamespacedKey(this, "cig_uses");
            KEY_CIG_MAX = new NamespacedKey(this, "cig_max");
            KEY_UNIQUE = new NamespacedKey(this, "unique_id");

            KEY_SNUS_PACK_ID = new NamespacedKey(this, "snus_pack_id");
            KEY_SNUS_PACK_REMAINING = new NamespacedKey(this, "snus_pack_remaining");
            KEY_SNUS_TYPE = new NamespacedKey(this, "snus_type");
            KEY_SNUS_IN_MOUTH = new NamespacedKey(this, "snus_in_mouth");
            KEY_SNUS_ENDED = new NamespacedKey(this, "snus_ended");

            KEY_CIG_LIT = new NamespacedKey(this, "cig_lit");
            KEY_LIGHTER = new NamespacedKey(this, "lighter");

            saveDefaultConfig();
            saveResourceIfNotExists("snus.yml");
            saveResourceIfNotExists("cigarette.yml");
            saveResourceIfNotExists("addons.yml");

            cfg = getConfig();
            loadLang();
            loadCigaretteConfig();
            loadAddonsConfig();

            this.cigaretteManager = new CigaretteManager(this);
            this.snusManager = new SnusManager(this);
            this.snusManager.loadSnusConfig();
            this.addonsManager = new AddonsManager(this);

            cigaretteManager.startIntoxicationTask();

            NpCommand npCmd = new NpCommand(this);
            if (getCommand("np") != null) {
                getCommand("np").setExecutor(npCmd);
                getCommand("np").setTabCompleter(npCmd);
            }
            if (getCommand("wypluj") != null) {
                getCommand("wypluj").setExecutor(new BelchCommand(this));
            }

            getServer().getPluginManager().registerEvents(new CigaretteCoreListener(cigaretteManager), this);
            getServer().getPluginManager().registerEvents(new CigaretteReviveListener(this, cigaretteManager), this);
            getServer().getPluginManager().registerEvents(new SnusListener(snusManager), this);

            getLogger().info("NicotinePremium enabled.");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Error during onEnable. Plugin will be disabled.", t);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (cigaretteManager != null) cigaretteManager.onDisable();
        if (snusManager != null) snusManager.onDisable();
        getLogger().info("NicotinePremium disabled.");
    }

    private void saveResourceIfNotExists(String internalPath) {
        File out = new File(getDataFolder(), internalPath);
        if (!out.exists()) {
            out.getParentFile().mkdirs();
            try {
                saveResource(internalPath, false);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }


    private void ensureLangFiles() {
        File dir = new File(getDataFolder(), "lang");
        if (!dir.exists()) dir.mkdirs();
        saveResourceIfNotExists("lang/lang_pl.yml");
        saveResourceIfNotExists("lang/lang_en.yml");
    }

    public void loadLang() {
        ensureLangFiles();

        String selected = getConfig().getString("language.selected", "pl");
        if (selected == null) selected = "pl";
        selected = selected.trim().toLowerCase();

        String fileName = selected.equals("en") ? "lang_en.yml" : "lang_pl.yml";
        this.langFile = new File(getDataFolder(), "lang/" + fileName);
        this.lang = YamlConfiguration.loadConfiguration(this.langFile);

        File legacy = new File(getDataFolder(), "lang.yml");
        if (legacy.exists()) {
            this.langFallback = YamlConfiguration.loadConfiguration(legacy);
        } else {
            this.langFallback = null;
        }

        getLogger().info("[Nicotine] Loaded language file: " + fileName);
    }

    public void loadCigaretteConfig() {
        File f = new File(getDataFolder(), "cigarette.yml");
        if (!f.exists()) saveResource("cigarette.yml", false);
        cigaretteCfg = YamlConfiguration.loadConfiguration(f);
    }

    public void loadAddonsConfig() {
        File f = new File(getDataFolder(), "addons.yml");
        if (!f.exists()) {
            saveResource("addons.yml", false);
        }
        addonsCfg = YamlConfiguration.loadConfiguration(f);
    }

    private String getLangString(String key, String def) {
        String v = (lang != null) ? lang.getString(key, null) : null;
        if (v == null && langFallback != null) {
            v = langFallback.getString(key, null);
        }
        return v != null ? v : def;
    }

    public String getMessage(String key) {
        String msg = getLangString(key, key);
        return color(getPrefix() + msg);
    }

    public String getRawMessage(String key) {
        return color(getLangString(key, ""));
    }

    public List<String> getLangList(String path) {
        if (lang == null) return new ArrayList<>();
        List<String> list = lang.getStringList(path);
        if ((list == null || list.isEmpty()) && langFallback != null) {
            list = langFallback.getStringList(path);
        }
        return colorList(list);
    }

    public String getPrefix() {
        return color(getLangString("prefix", ""));
    }

    public String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg == null ? "" : msg);
    }

    public List<String> colorList(List<String> list) {
        if (list == null) return new ArrayList<>();
        return list.stream().map(this::color).collect(Collectors.toList());
    }

    public List<String> getMessageList(String key) {
        List<String> raw = (lang != null) ? lang.getStringList(key) : java.util.Collections.emptyList();
        if ((raw == null || raw.isEmpty()) && langFallback != null) {
            raw = langFallback.getStringList(key);
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) out.add(color(s));
        return out;
    }

    public String getActionBarText(String key, String def) {
        String fromCfg = getConfig().getString("actionbar." + key, null);
        if (fromCfg != null && !fromCfg.isEmpty()) return color(fromCfg);

        String fromLang = getRawMessage("actionbar." + key);
        if (fromLang == null || fromLang.equals("actionbar." + key)) {
            fromLang = getRawMessage("actionbar_" + key);
        }
        if (fromLang != null && !fromLang.isEmpty()) return color(fromLang);

        return color(def);
    }

    public String sendActionBar(Player p, String msgColoredAmpersand) {
        if (p == null) return msgColoredAmpersand;
        String colored = color(msgColoredAmpersand == null ? "" : msgColoredAmpersand);
        try {
            p.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(colored)
            );
        } catch (Throwable ignored) {
            p.sendMessage(colored);
        }
        return msgColoredAmpersand;
    }

    public String getActionBarFromLang(String key, String def, String... kv) {
        String base = getRawMessage("actionbar." + key);
        if (base == null || base.isEmpty() || ("actionbar."+key).equals(base)) {
            base = getRawMessage("actionbar_" + key);
        }
        if (base == null || base.isEmpty()) base = def;
        if (kv != null && kv.length % 2 == 0) {
            for (int i = 0; i < kv.length; i += 2) base = base.replace(kv[i], kv[i+1]);
        }
        return color(base);
    }

    public void sendActionBar(Player p, String langKey, String def, String... kv) {
        String msg = getActionBarFromLang(langKey, def, kv);
        try {
            p.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(msg)
            );
        } catch (Throwable t) {
            p.sendMessage(msg);
        }
    }
}
