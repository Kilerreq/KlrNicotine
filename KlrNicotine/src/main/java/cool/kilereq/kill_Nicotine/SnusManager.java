package cool.kilereq.kill_Nicotine;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

public class SnusManager {

    final MainNicotine plugin;
    private final NamespacedKey KEY_SNUS;

    private FileConfiguration snusCfg; // JEDNO pole – bez duplikatów
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> runningTasks = new HashMap<>();
    private final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> reminderTasks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, org.bukkit.scheduler.BukkitTask> stageTasks = new java.util.HashMap<>();

    public SnusManager(MainNicotine plugin) {
        this.plugin = plugin;
        this.KEY_SNUS = new NamespacedKey(plugin, "snus");
    }

    // -----------------------------
    // snus.yml
    // -----------------------------
    public void loadSnusConfig() {
        String fileName = "snus.yml";
        File f = new File(plugin.getDataFolder(), fileName);
        if (!f.exists()) {
            try { plugin.saveResource(fileName, false); } catch (IllegalArgumentException ignored) {}
        }
        snusCfg = YamlConfiguration.loadConfiguration(f);
        plugin.getLogger().info("[Kill_Nicotine] Loaded snus file: " + fileName);
    }

    public FileConfiguration getSnusCfg() {
        return snusCfg;
    }

    private boolean debug() {
        try {
            return plugin.getConfig().getBoolean("debug_snus", false);
        } catch (Throwable t) { return false; }
    }

    private void logDebug(String msg) {
        if (debug()) plugin.getLogger().info("[SNUS] " + msg);
    }

    public void handleJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        org.bukkit.entity.Player p = e.getPlayer();
        // Jeśli gracz miał snusa i efekty już się skończyły — wznowimy tylko przypominanie
        if (hasSnusInMouth(p) && isSnusEnded(p)) {
            scheduleReminder(p);
        }
    }

    // -----------------------------
    // Paczki snusów (config.yml -> snus_packs)
    // -----------------------------
    public boolean isSnusPack(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(plugin.KEY_SNUS_PACK_ID, PersistentDataType.STRING);
    }

    /** /np give snus <ID> */
    public boolean givePack(org.bukkit.entity.Player player, String packId, String ignoredPacksSectionParam) {
        // Główne źródło: snus.yml
        final org.bukkit.configuration.file.FileConfiguration snusFile = this.snusCfg;
        org.bukkit.configuration.ConfigurationSection sec = null;

        if (snusFile != null) {
            // Twoja struktura: snus.packs.<id>
            sec = snusFile.getConfigurationSection("snus.packs." + packId);
            // fallback: alternatywna nazwa snus_packs.<id>
            if (sec == null) sec = snusFile.getConfigurationSection("snus_packs." + packId);
        }

        // awaryjny fallback do config.yml (gdyby jednak ktoś tam trzymał)
        if (sec == null) {
            org.bukkit.configuration.file.FileConfiguration base = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
            sec = base.getConfigurationSection("snus.packs." + packId);
            if (sec == null) sec = base.getConfigurationSection("snus_packs." + packId);
        }

        if (sec == null) {
            player.sendMessage(plugin.getMessage("np_snus_pack_not_found").replace("%pack%", packId));
            plugin.getLogger().warning("[Nicotine] Snus pack not found: " + packId
                    + " (looked at snus.yml: snus.packs." + packId + " and snus_packs." + packId + ")");
            return false;
        }

        // Material
        String matName = sec.getString("item", "CLAY_BALL");
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = org.bukkit.Material.CLAY_BALL;

        // Rozmiar (domyślnie general.pack_size z config.yml)
        org.bukkit.configuration.file.FileConfiguration mainCfg = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
        int size = sec.getInt("size", mainCfg.getInt("general.pack_size", 20));

        // Nazwa + CMD
        String rawName = sec.getString("name", packId);
        String display = org.bukkit.ChatColor.translateAlternateColorCodes('&', rawName);
        int cmd = sec.getInt("custom_model_data", 0);

        // Item
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat, 1);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) { player.getInventory().addItem(item); return true; }

        meta.setDisplayName(display);
        if (cmd > 0) meta.setCustomModelData(cmd);

        // LORE – 1 linia: pełny licznik z CONFIGU (nie z lang!)
        String tplInitial = mainCfg.getString("snus_pack_lore", "&7&o%max%/%max%");
        String line = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                tplInitial.replace("%max%", String.valueOf(size)));
        meta.setLore(java.util.Collections.singletonList(line));

        // PDC
        org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.KEY_SNUS_PACK_ID, org.bukkit.persistence.PersistentDataType.STRING, packId);
        pdc.set(plugin.KEY_SNUS_PACK_REMAINING, org.bukkit.persistence.PersistentDataType.INTEGER, size);
        pdc.set(plugin.KEY_UNIQUE, org.bukkit.persistence.PersistentDataType.STRING, java.util.UUID.randomUUID().toString());

        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        return true;
    }

    /** PPM na paczce -> wyjmij 1 snusa + zaktualizuj 1-liniowe lore */
    public org.bukkit.inventory.ItemStack takeSnusFromPack(org.bukkit.inventory.ItemStack pack) {
        if (pack == null || !pack.hasItemMeta()) return null;

        org.bukkit.inventory.meta.ItemMeta meta = pack.getItemMeta();
        org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (!pdc.has(plugin.KEY_SNUS_PACK_REMAINING, org.bukkit.persistence.PersistentDataType.INTEGER)) return null;
        if (!pdc.has(plugin.KEY_SNUS_PACK_ID, org.bukkit.persistence.PersistentDataType.STRING)) return null;

        Integer remaining = pdc.get(plugin.KEY_SNUS_PACK_REMAINING, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (remaining == null || remaining <= 0) return null;

        // zmniejsz
        remaining = remaining - 1;
        pdc.set(plugin.KEY_SNUS_PACK_REMAINING, org.bukkit.persistence.PersistentDataType.INTEGER, remaining);

        // odczytaj packId
        String packId = pdc.get(plugin.KEY_SNUS_PACK_ID, org.bukkit.persistence.PersistentDataType.STRING);
        if (packId == null) packId = "unknown";

        // WYZNACZ MAX Z snus.yml (snus.packs.<id>.size -> snus_packs.<id>.size -> general.pack_size)
        int max = 0;
        if (this.snusCfg != null) {
            org.bukkit.configuration.ConfigurationSection s =
                    this.snusCfg.getConfigurationSection("snus.packs." + packId);
            if (s == null) {
                s = this.snusCfg.getConfigurationSection("snus_packs." + packId);
            }
            if (s != null) {
                max = s.getInt("size", 0);
            }
        }
        if (max <= 0) {
            org.bukkit.configuration.file.FileConfiguration base = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
            max = base.getInt("general.pack_size", 20);
        }

        // Uaktualnij LORE paczki snusa – dokładnie 1 linia licznika
        String tplCount = (plugin.getConfig() != null)
                ? plugin.getConfig().getString("snus_pack_lore_count", "&7&o%remaining%/%max%")
                : "&7&o%remaining%/%max%";
        String line = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                tplCount.replace("%remaining%", String.valueOf(remaining))
                        .replace("%max%", String.valueOf(max)));

        meta.setLore(java.util.Collections.singletonList(line));
        pack.setItemMeta(meta);

        // ZBUDUJ POJEDYNCZEGO SNUSA (jak dotychczas – czytaj wygląd i typ z snus.yml)
        // typ (jeśli jest zapisany w paczce; fallback do packId)
        String typeKey = packId;
        // display/taken item z snus.yml
        org.bukkit.configuration.ConfigurationSection typeSec = null;
        if (this.snusCfg != null) {
            typeSec = this.snusCfg.getConfigurationSection("snus_types." + typeKey);
            if (typeSec == null) {
                // jeżeli typy masz inaczej – dostosuj w razie potrzeby
            }
        }

        org.bukkit.Material snusMat = org.bukkit.Material.SUGAR;
        int sCmd = 0;
        String snusDisplayRaw = "&b&lSnus";
        if (typeSec != null) {
            org.bukkit.configuration.ConfigurationSection taken = typeSec.getConfigurationSection("taken_item");
            if (taken != null) {
                String sMat = taken.getString("material", "SUGAR");
                org.bukkit.Material tmp = org.bukkit.Material.matchMaterial(sMat.toUpperCase());
                if (tmp != null) snusMat = tmp;
                sCmd = taken.getInt("custom_model_data", 0);
            }
            snusDisplayRaw = typeSec.getString("display_name", snusDisplayRaw);
        }

        org.bukkit.inventory.ItemStack snus = new org.bukkit.inventory.ItemStack(snusMat, 1);
        org.bukkit.inventory.meta.ItemMeta sMeta = snus.getItemMeta();
        if (sMeta != null) {
            sMeta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&', snusDisplayRaw));
            if (sCmd > 0) sMeta.setCustomModelData(sCmd);
            // zapisz typ do PDC (żeby po PPM wiedzieć, jakie efekty uruchomić)
            sMeta.getPersistentDataContainer().set(plugin.KEY_SNUS_TYPE, org.bukkit.persistence.PersistentDataType.STRING, typeKey);
            snus.setItemMeta(sMeta);
        }

        return snus;
    }

    // -----------------------------
    // Snus (pojedynczy)
    // -----------------------------
    public boolean isSnus(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        // PDC – najpewniejsze
        if (item.getItemMeta().getPersistentDataContainer().has(plugin.KEY_SNUS_TYPE, PersistentDataType.STRING)) {
            return true;
        }
        // fallback – po nazwie z snus.yml
        String disp = item.getItemMeta().getDisplayName();
        if (disp == null || disp.isEmpty() || snusCfg == null) return false;
        ConfigurationSection sec = snusCfg.getConfigurationSection("snus_types");
        if (sec == null) return false;
        for (String key : sec.getKeys(false)) {
            String dnRaw = sec.getString(key + ".display_name", "");
            String dn = ChatColor.translateAlternateColorCodes('&', dnRaw);
            if (!dn.isEmpty() && dn.equals(disp)) return true;
        }
        return false;
    }

    public String getSnusTypeFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (pdc.has(plugin.KEY_SNUS_TYPE, PersistentDataType.STRING)) {
            return pdc.get(plugin.KEY_SNUS_TYPE, PersistentDataType.STRING);
        }
        String disp = item.getItemMeta().getDisplayName();
        if (disp == null || snusCfg == null) return null;
        ConfigurationSection sec = snusCfg.getConfigurationSection("snus_types");
        if (sec == null) return null;
        for (String key : sec.getKeys(false)) {
            String dnRaw = sec.getString(key + ".display_name", "");
            String dn = ChatColor.translateAlternateColorCodes('&', dnRaw);
            if (!dn.isEmpty() && dn.equals(disp)) return key;
        }
        return null;
    }

    /** PPM na snusie – zużyj 1 szt. i start efektów wg snus.yml */
    public boolean handlePlayerInteract(Player player, ItemStack item) {
        if (!isSnus(item)) return false;

        if (hasSnusInMouth(player)) {
            player.sendMessage(plugin.getMessage("snus_already_in_mouth"));
            return true;
        }

        String type = getSnusTypeFromItem(item);
        if (type == null) {
            player.sendMessage(ChatColor.RED + "Ten snus nie ma przypisanego typu (snus.yml / PDC).");
            return true;
        }

        // zużyj 1 sztukę z main-hand
        int amt = item.getAmount();
        if (amt <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(amt - 1);
            player.getInventory().setItemInMainHand(item);
        }

        // start efektów
        startSnusEffect(player, type);

        // ActionBar z lang: actionbar.snus_used
        plugin.sendActionBar(player, "snus_used", "&7&oWziąłeś snusa");
        return true;
    }

    // -----------------------------
    // Stan i czyszczenie
    // -----------------------------
    public boolean hasSnusInMouth(Player p) {
        return p.getPersistentDataContainer().getOrDefault(plugin.KEY_SNUS_IN_MOUTH, PersistentDataType.INTEGER, 0) == 1;
    }
    public void setSnusInMouth(Player p, boolean val) {
        p.getPersistentDataContainer().set(plugin.KEY_SNUS_IN_MOUTH, PersistentDataType.INTEGER, val ? 1 : 0);
    }
    public void setSnusEnded(Player p, boolean val) {
        p.getPersistentDataContainer().set(plugin.KEY_SNUS_ENDED, PersistentDataType.INTEGER, val ? 1 : 0);
    }
    public boolean isSnusEnded(Player p) {
        return p.getPersistentDataContainer().getOrDefault(plugin.KEY_SNUS_ENDED, PersistentDataType.INTEGER, 0) == 1;
    }

    /** /wypluj */
    public void wypluj(Player p) {
        if (!hasSnusInMouth(p)) {
            p.sendMessage(plugin.getMessage("snus_not_in_mouth"));
            return;
        }
        removeSnus(p, true);

        // WYŁĄCZNIE ActionBar:
        plugin.sendActionBar(p, "snus_spit", "&7&oWyplułeś snusa");
    }


    public void removeSnus(Player p, boolean manual) {
        // usuń wszystkie efekty
        for (org.bukkit.potion.PotionEffect ef : p.getActivePotionEffects()) {
            p.removePotionEffect(ef.getType());
        }
        cancelTasks(p);               // przerwij ewentualne timery/ przypomnienia
        setSnusInMouth(p, false);
        setSnusEnded(p, false);
    }

    // -----------------------------
    // Harmonogram efektów wg snus.yml
    // -----------------------------

    /** Parsuje łańcuch "TYPE:LEVEL:SECONDS" -> PotionEffect */
    private org.bukkit.potion.PotionEffect parseEffectSpec(String spec) {
        if (spec == null) return null;
        String[] parts = spec.split(":");
        if (parts.length != 3) return null;
        org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(parts[0]);
        if (type == null) return null;
        int level;
        int seconds;
        try {
            // Skriptowe LEVEL zaczyna się od 1 -> w Bukkit amplifier to level-1
            level = Math.max(1, Integer.parseInt(parts[1]));
            seconds = Math.max(1, Integer.parseInt(parts[2]));
        } catch (NumberFormatException nfe) {
            return null;
        }
        return new org.bukkit.potion.PotionEffect(type, seconds * 20, level - 1, true, true, true);
    }

    private void applyEffects(org.bukkit.entity.Player p, java.util.List<String> specs) {
        if (specs == null) return;
        for (String s : specs) {
            org.bukkit.potion.PotionEffect eff = parseEffectSpec(s);
            if (eff != null) {
                p.addPotionEffect(eff);
                logDebug("Applied " + s + " to " + p.getName());
            } else {
                logDebug("Skipped invalid effect spec: " + s);
            }
        }
    }

    /** Główna: odpal etapy wg snus.yml – bez zmiennej 'runner' */
    public void startSnusEffect(Player player, String snusType) {
        setSnusInMouth(player, true);
        setSnusEnded(player, false);

        // jeśli coś już leciało – przerwij
        cancelTasks(player);

        org.bukkit.configuration.ConfigurationSection tSec =
                (snusCfg != null) ? snusCfg.getConfigurationSection("snus_types." + snusType) : null;
        if (tSec == null) return;

        java.util.List<?> stages = tSec.getList("stages");
        if (stages == null || stages.isEmpty()) return;

        // Harmonogram etapów
        org.bukkit.scheduler.BukkitRunnable runner = new org.bukkit.scheduler.BukkitRunnable() {
            int stageIndex = 0;

            @Override
            public void run() {
                if (!player.isOnline()) return;

                if (stageIndex >= stages.size()) {
                    // skończyliśmy efekty
                    setSnusEnded(player, true);
                    scheduleReminder(player);
                    cancel();
                    runningTasks.remove(player.getUniqueId());
                    return;
                }

                Object o = stages.get(stageIndex);
                if (!(o instanceof org.bukkit.configuration.ConfigurationSection) && !(o instanceof java.util.Map)) {
                    stageIndex++;
                    return;
                }

                org.bukkit.configuration.ConfigurationSection s =
                        (o instanceof org.bukkit.configuration.ConfigurationSection)
                                ? (org.bukkit.configuration.ConfigurationSection) o
                                : new org.bukkit.configuration.MemoryConfiguration(); // fallback

                // --- Effects ---
                java.util.List<String> effects = s.getStringList("effects");
                if (effects != null) {
                    for (String spec : effects) {
                        // FORMAT: TYPE:LEVEL:DURATION_SECONDS
                        String[] parts = spec.split(":");
                        if (parts.length != 3) continue;

                        org.bukkit.potion.PotionEffectType type = resolveEffect(parts[0]);
                        if (type == null) continue;

                        int level, durSec;
                        try {
                            level = Integer.parseInt(parts[1]);
                            durSec = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ex) {
                            continue;
                        }

                        int amplifier = Math.max(0, level - 1);
                        int ticks = Math.max(1, durSec * 20);

                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks, amplifier));
                    }
                }

                // --- Extra after ---
                java.util.List<String> extra = s.getStringList("extra_after");
                int delayAfter = s.getInt("delay_after", 0);

                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) return;
                        if (extra != null && !extra.isEmpty() && hasSnusInMouth(player)) {
                            for (String spec : extra) {
                                String[] parts = spec.split(":");
                                if (parts.length != 3) continue;

                                org.bukkit.potion.PotionEffectType type = resolveEffect(parts[0]);
                                if (type == null) continue;

                                int level, durSec;
                                try {
                                    level = Integer.parseInt(parts[1]);
                                    durSec = Integer.parseInt(parts[2]);
                                } catch (NumberFormatException ex) {
                                    continue;
                                }

                                int amplifier = Math.max(0, level - 1);
                                int ticks = Math.max(1, durSec * 20);

                                player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks, amplifier));
                            }
                        }
                        stageIndex++;
                        run(); // wywołaj kolejny etap
                    }
                }.runTaskLater(plugin, Math.max(0, delayAfter) * 20L);
            }
        };

        org.bukkit.scheduler.BukkitTask task = runner.runTask(plugin);
        runningTasks.put(player.getUniqueId(), task);

        // odpal natychmiast pierwszy etap
        runner.run();
    }

    // pomocnik – wznowienie głównego run() z nowym stanem idx (poniżej prościej: przenieś harmonogram w jedną klasę, jeśli wolisz)
    private void runStageNext(org.bukkit.scheduler.BukkitRunnable inner, Player player) {
        // nic – metoda-pomocnik, tylko żeby zachować czytelność; realnie powyżej już robimy 'idx++' i znów 'run()' anonimowej klasy
    }

    /** Rekurencyjny łańcuch: aplikuje etap 'index', potem po delay_after planuje extra i przechodzi do 'index+1' */
    private void runSnusStages(final org.bukkit.entity.Player player,
                               final java.util.List<?> stages,
                               final int index) {

        // Przerwanie, jeśli gracz wyszedł/wypluł
        if (!player.isOnline() || !hasSnusInMouth(player)) {
            cancelTasks(player);
            return;
        }

        // Koniec wszystkich etapów: ustawiamy ended i przypominajkę co 3 min
        if (index >= stages.size()) {
            setSnusEnded(player, true);
            scheduleReminder(player);
            // nic już nie planujemy
            return;
        }

        // Odczyt bieżącego etapu jako mapa/sekcja
        java.util.Map<String, Object> stageMap = null;
        Object o = stages.get(index);
        if (o instanceof org.bukkit.configuration.ConfigurationSection) {
            stageMap = ((org.bukkit.configuration.ConfigurationSection) o).getValues(false);
        } else if (o instanceof java.util.Map) {
            stageMap = (java.util.Map<String, Object>) o;
        } else {
            // dziwny etap – przeskocz dalej
            runSnusStages(player, stages, index + 1);
            return;
        }

        // effects: ["TYPE:LEVEL:SECONDS", ...]
        java.util.List<String> effects = new java.util.ArrayList<>();
        Object effObj = stageMap.get("effects");
        if (effObj instanceof java.util.List) {
            for (Object eo : (java.util.List<?>) effObj) {
                if (eo != null) effects.add(String.valueOf(eo));
            }
        }

        // delay_after: int (sekundy)
        int delayAfter = 0;
        Object dObj = stageMap.get("delay_after");
        if (dObj != null) {
            try { delayAfter = Integer.parseInt(String.valueOf(dObj)); } catch (NumberFormatException ignored) {}
        }

        // extra_after: ["TYPE:LEVEL:SECONDS", ...]
        java.util.List<String> extraAfter = new java.util.ArrayList<>();
        Object exObj = stageMap.get("extra_after");
        if (exObj instanceof java.util.List) {
            for (Object xo : (java.util.List<?>) exObj) {
                if (xo != null) extraAfter.add(String.valueOf(xo));
            }
        }

        // 1) Nałóż główne efekty bieżącego etapu natychmiast
        applyEffects(player, effects);

        // 2) Po delay_after sekundach dołóż 'extra_after' i przejdź do kolejnego etapu
        org.bukkit.scheduler.BukkitTask t = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && hasSnusInMouth(player)) {
                    applyEffects(player, extraAfter);
                }
                // kolejny etap
                runSnusStages(player, stages, index + 1);
            }
        }.runTaskLater(plugin, Math.max(0, delayAfter) * 20L);

        // zapamiętaj aktualnie aktywne zadanie dla tego gracza – żeby /wypluj i ondisable mogły anulować
        runningTasks.put(player.getUniqueId(), t);
    }

    private void scheduleReminder(org.bukkit.entity.Player p) {
        cancelReminder(p);

        boolean enabled = plugin.cfg.getBoolean("snus.reminder.enabled", true);
        int intervalSec = plugin.cfg.getInt("snus.reminder.interval_seconds", 180);
        if (!enabled) return;
        if (intervalSec < 1) intervalSec = 1;

        org.bukkit.scheduler.BukkitTask t = new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (!hasSnusInMouth(p) || !isSnusEnded(p)) {
                    cancelReminder(p);
                    return;
                }
                // ActionBar z lang: actionbar.snus_effects_end_reminder
                plugin.sendActionBar(p, "snus_effects_end_reminder",
                        "&7&oTwój snus stracił właściwości - użyj &f/wypluj");
            }
        }.runTaskTimer(plugin, 0L, 20L * intervalSec);

        reminderTasks.put(p.getUniqueId(), t);
    }

    private void cancelReminder(org.bukkit.entity.Player p) {
        org.bukkit.scheduler.BukkitTask t = reminderTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    private void startSnusVomiting(Player p, ConfigurationSection snusVomSec) {
        // jeśli brak sekcji snus_overdose.vomiting – fallback do overdose.vomiting
        ConfigurationSection use = snusVomSec;
        if (use == null) {
            FileConfiguration c = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
            use = c.getConfigurationSection("overdose.vomiting"); // global fallback
        }
        if (use == null || !use.getBoolean("enabled", true)) return;

        // seria: np. count/interval z Twoich stages (jeśli chcesz) — tu 1 epizod:
        doSnusVomit(p, use);
    }

    private void doSnusVomit(Player p, ConfigurationSection vom) {
        String mode = vom.getString("mode", "BLOCK").toUpperCase(); // BLOCK | ITEM | BASIC
        String blockMatName = vom.getString("block_material", "SOUL_SAND");
        String itemMatName  = vom.getString("item_material", "SOUL_SAND");
        int trailMaxTicks   = Math.max(5, vom.getInt("trail_max_ticks", 20));
        int trailCount      = Math.max(1, vom.getInt("trail_count", 6));
        int splashBursts    = Math.max(1, vom.getInt("splash_bursts", 6));
        int splashCount     = Math.max(1, vom.getInt("splash_count", 12));
        String soundName    = vom.getString("sound", "ENTITY_PLAYER_BURP");
        float vol           = (float) vom.getDouble("sound_volume", 1.0);
        float pit           = (float) vom.getDouble("sound_pitch", 1.0);

        // Particle payload
        org.bukkit.Particle particleType = org.bukkit.Particle.BLOCK;
        org.bukkit.block.data.BlockData blockData = null;
        org.bukkit.inventory.ItemStack itemData = null;

        switch (mode) {
            case "BLOCK" -> {
                var mat = org.bukkit.Material.matchMaterial(blockMatName.toUpperCase());
                if (mat == null) mat = org.bukkit.Material.SOUL_SAND;
                blockData = mat.createBlockData();
                particleType = org.bukkit.Particle.BLOCK;
            }
            case "ITEM" -> {
                var mat = org.bukkit.Material.matchMaterial(itemMatName.toUpperCase());
                if (mat == null) mat = org.bukkit.Material.SOUL_SAND;
                itemData = new org.bukkit.inventory.ItemStack(mat);
                particleType = org.bukkit.Particle.ITEM;
            }
            default -> particleType = Particle.CAMPFIRE_COSY_SMOKE;
        }

        try {
            var snd = org.bukkit.Sound.valueOf(soundName.toUpperCase());
            p.playSound(p.getLocation(), snd, vol, pit);
        } catch (IllegalArgumentException ignored) {}

        // finals do Runnable
        final String finalMode = mode;
        final org.bukkit.Particle finalParticleType = particleType;
        final org.bukkit.block.data.BlockData finalBlockData = blockData;
        final org.bukkit.inventory.ItemStack finalItemData = itemData;
        final int finalTrailMaxTicks = trailMaxTicks;
        final int finalTrailCount = trailCount;
        final int finalSplashBursts = splashBursts;
        final int finalSplashCount = splashCount;

        final org.bukkit.Location loc = p.getEyeLocation().clone()
                .add(p.getLocation().getDirection().normalize().multiply(0.45));
        final org.bukkit.util.Vector startDir = p.getLocation().getDirection().normalize();
        final org.bukkit.util.Vector vel = startDir.clone().multiply(0.25).add(new org.bukkit.util.Vector(0, 0.15, 0));
        final double gravity = 0.08;
        final org.bukkit.World world = p.getWorld();

        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!p.isOnline()) { cancel(); return; }

                loc.add(vel);
                vel.add(new org.bukkit.util.Vector(0, -gravity, 0));

                switch (finalMode) {
                    case "BLOCK" -> world.spawnParticle(finalParticleType, loc, finalTrailCount, 0.06, 0.04, 0.06, 0.0, finalBlockData);
                    case "ITEM"  -> world.spawnParticle(finalParticleType, loc, finalTrailCount, 0.06, 0.04, 0.06, 0.0, finalItemData);
                    default      -> world.spawnParticle(finalParticleType, loc, finalTrailCount, 0.06, 0.04, 0.06, 0.0);
                }

                boolean hitGround = isGrounded(loc);
                if (hitGround || ticks >= finalTrailMaxTicks) {
                    org.bukkit.Location ground = loc.clone();
                    ground.setY(Math.floor(ground.getY() - 0.01) + 0.01);

                    java.util.Random r = new java.util.Random();
                    for (int b = 0; b < finalSplashBursts; b++) {
                        double angle = r.nextDouble() * Math.PI * 2;
                        double radius = 0.25 + r.nextDouble() * 0.35;
                        double ox = Math.cos(angle) * radius;
                        double oz = Math.sin(angle) * radius;
                        org.bukkit.Location spot = ground.clone().add(ox, 0.02, oz);

                        switch (finalMode) {
                            case "BLOCK" -> world.spawnParticle(org.bukkit.Particle.BLOCK, spot, finalSplashCount, 0.06, 0.02, 0.06, 0.0, finalBlockData);
                            case "ITEM"  -> world.spawnParticle(org.bukkit.Particle.ITEM,  spot, finalSplashCount, 0.06, 0.02, 0.06, 0.0, finalItemData);
                            default      -> world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, spot, finalSplashCount, 0.06, 0.02, 0.06, 0.0);
                        }
                    }
                    cancel();
                } else {
                    ticks++;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // messages
        String ab = plugin.sendActionBar(p, plugin.getRawMessage("actionbar.snus_overdose_vomit_ab"));
        if (ab == null || ab.isEmpty()) ab = "&eVomiting...";
        p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', ab)));
        String msg = plugin.sendActionBar(p, plugin.getRawMessage("actionbar.snus_overdose_knockdown_ab"));
        if (msg != null && !msg.isEmpty()) p.sendMessage(msg);
    }

    private void startVomitingSequenceFromConfig(Player p) {
        org.bukkit.configuration.ConfigurationSection od = plugin.cfg.getConfigurationSection("overdose.vomiting");
        if (od == null || !od.getBoolean("enabled", true)) return;

        int count = Math.max(1, od.getInt("count", 5));
        int interval = Math.max(5, od.getInt("interval_ticks", 80));
        String sound = od.getString("sound", "ENTITY_PLAYER_BURP");

        for (int i = 0; i < count; i++) {
            int delay = i * interval;
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override public void run() {
                    if (!p.isOnline()) return;
                    try { p.playSound(p.getLocation(), org.bukkit.Sound.valueOf(sound), 1f, 1f); } catch (Throwable ignored) {}

                    // prosty „wyrzut” cząsteczek do przodu
                    org.bukkit.util.Vector dir = p.getEyeLocation().getDirection().normalize();
                    org.bukkit.Location loc = p.getEyeLocation().add(dir.clone().multiply(0.5));
                    p.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, loc, 20, 0.2, 0.1, 0.2, 0.01,
                            org.bukkit.Material.SOUL_SAND.createBlockData());
                }
            }.runTaskLater(plugin, delay);
        }
    }

    private boolean isGrounded(org.bukkit.Location loc) {
        org.bukkit.block.Block b = loc.clone().subtract(0, 0.2, 0).getBlock();
        return b.getType().isSolid();
    }

    private void doSnusKnockdown(Player p, ConfigurationSection kd) {
        String cmdTpl   = kd.getString("command", "reviveme down %player%");
        boolean console = kd.getBoolean("run_as_console", true);
        int delayTicks  = kd.getInt("delay_ticks", 0);

        Runnable runCmd = () -> {
            String cmd = cmdTpl.replace("%player%", p.getName());
            if (console) org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), cmd);
            else p.performCommand(cmd);
        };
        if (delayTicks > 0) {
            new org.bukkit.scheduler.BukkitRunnable(){ @Override public void run(){ runCmd.run(); } }
                    .runTaskLater(plugin, delayTicks);
        } else runCmd.run();

        // Opcjonalnie: wyświetl info
        String ab = plugin.getRawMessage("actionbar.snus_overdose_knockdown_ab");
        if (ab != null && !ab.isEmpty()) {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', ab)));
        }
        String msg = plugin.getMessage("snus_overdose_knockdown");
        if (msg != null && !msg.isEmpty()) p.sendMessage(msg);
    }

    private void cancelTasks(Player p) {
        org.bukkit.scheduler.BukkitTask t = runningTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
        cancelReminder(p);
    }

    public void onDisable() {
        // anuluj harmonogramy efektów
        for (org.bukkit.scheduler.BukkitTask t : runningTasks.values()) {
            if (t != null) t.cancel();
        }
        runningTasks.clear();

        // anuluj przypomnienia o wypluciu
        for (org.bukkit.scheduler.BukkitTask t : reminderTasks.values()) {
            if (t != null) t.cancel();
        }
        reminderTasks.clear();

        // (opcjonalnie) wyczyść flagi u online graczy
        try {
            for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
                // nie czyszczę efektów – przy restarcie i tak znikną,
                // ale porządkujemy nasz stan PDC, jeśli chcesz:
                setSnusInMouth(p, false);
                setSnusEnded(p, false);
            }
        } catch (Throwable ignored) { }
    }

    private int getNicotineMgForType(String typeKey) {
        if (snusCfg == null || typeKey == null) return 0;
        ConfigurationSection sec = snusCfg.getConfigurationSection("snus_types." + typeKey);
        return (sec != null) ? sec.getInt("nicotine_mg", 0) : 0;
    }

    private void triggerSnusOverdoseIfNeeded(Player p, int mg) {
        FileConfiguration c = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
        ConfigurationSection root = c.getConfigurationSection("snus_overdose");
        if (root == null || !root.getBoolean("enabled", true)) return;

        int thNausea   = root.getInt("nausea_threshold_mg", 50);
        int thNB       = root.getInt("nausea_blindness_threshold_mg", 66);
        int thVomit    = root.getInt("vomiting_threshold_mg", 90);

        // apply once, from the highest matched stage down:
        if (mg >= thVomit) {
            // nausea + blindness + vomiting
            applyEffect(p, org.bukkit.potion.PotionEffectType.NAUSEA, 10, 2);     // 10s, amp 2
            applyEffect(p, org.bukkit.potion.PotionEffectType.BLINDNESS, 6, 2);   // 6s, amp 2
            startSnusVomiting(p, root.getConfigurationSection("vomiting"));

            // optional knockdown
            ConfigurationSection kd = root.getConfigurationSection("knockdown");
            if (kd != null && kd.getBoolean("enabled", false)) {
                int thKD = kd.getInt("threshold_mg", 120);
                if (mg >= thKD) {
                    doSnusKnockdown(p, kd);
                }
            }
            return;
        }

        if (mg >= thNB) {
            // nausea + blindness
            applyEffect(p, org.bukkit.potion.PotionEffectType.NAUSEA, 10, 2);
            applyEffect(p, org.bukkit.potion.PotionEffectType.BLINDNESS, 6, 1);
            return;
        }

        if (mg >= thNausea) {
            // nausea only
            applyEffect(p, org.bukkit.potion.PotionEffectType.NAUSEA, 8, 1);
        }
    }

    private void applyEffect(Player p, org.bukkit.potion.PotionEffectType type, int durationSeconds, int amplifier) {
        if (type == null) return;
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(type, durationSeconds * 20, Math.max(0, amplifier - 1)));
    }

    // ===== helper: definicja etapu =====
    private static class StageDef {
        java.util.List<String> effects = java.util.Collections.emptyList();     // "TYPE:LEVEL:DURATION_S"
        java.util.List<String> extraAfter = java.util.Collections.emptyList();  // po delay_after
        int delayAfter = 0;                                                     // sekundy do kolejnego etapu
    }

    private java.util.List<StageDef> parseStages(org.bukkit.configuration.ConfigurationSection typeSec) {
        java.util.List<StageDef> out = new java.util.ArrayList<>();
        if (typeSec == null) return out;

        java.util.List<?> raw = typeSec.getList("stages");
        if (raw == null) return out;

        for (Object o : raw) {
            StageDef sd = new StageDef();
            if (o instanceof org.bukkit.configuration.ConfigurationSection s) {
                sd.effects    = s.getStringList("effects");
                sd.extraAfter = s.getStringList("extra_after");
                sd.delayAfter = Math.max(0, s.getInt("delay_after", 0));
            } else if (o instanceof java.util.Map<?,?> map) {
                Object e  = map.get("effects");
                Object ex = map.get("extra_after");
                Object d  = map.get("delay_after");
                if (e instanceof java.util.List<?> l) {
                    sd.effects = l.stream().map(String::valueOf).toList();
                }
                if (ex instanceof java.util.List<?> l2) {
                    sd.extraAfter = l2.stream().map(String::valueOf).toList();
                }
                if (d != null) {
                    try { sd.delayAfter = Math.max(0, Integer.parseInt(String.valueOf(d))); } catch (Exception ignored) {}
                }
            }
            out.add(sd);
        }
        return out;
    }

    private void applyEffectSpec(org.bukkit.entity.Player p, String spec) {
        // "TYPE:LEVEL:DURATION_S"
        if (spec == null || spec.isEmpty()) return;
        String[] parts = spec.split(":");
        if (parts.length != 3) return;
        org.bukkit.potion.PotionEffectType t = org.bukkit.potion.PotionEffectType.getByName(parts[0]);
        if (t == null) return;
        int level, durS;
        try {
            level = Math.max(1, Integer.parseInt(parts[1])) - 1; // Bukkit levels 0-based
            durS  = Math.max(1, Integer.parseInt(parts[2]));
        } catch (Exception e) { return; }
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(t, durS * 20, level));
    }

    // ===== helper: zestaw ustawień overdose dla danego typu snusa (per-type override + global defaults) =====
    private static class OverdoseSpec {
        int triggerStage;                      // od którego etapu (1-based)
        boolean vomitingEnabled;
        java.util.List<String> extraEffects;   // np. ["NAUSEA:2:45","BLINDNESS:1:20"]
    }

    private OverdoseSpec readOverdoseSpec(String snusType, org.bukkit.configuration.ConfigurationSection typeSec) {
        OverdoseSpec spec = new OverdoseSpec();

        // GLOBALNE DOMYŚLNE z config.yml
        int defStage = Math.max(1, plugin.cfg.getInt("snus.overdose.default_trigger_stage", 3));
        boolean defVom = plugin.cfg.getBoolean("snus.overdose.default_vomiting", true);
        java.util.List<String> defFx = plugin.cfg.getStringList("snus.overdose.default_effects");

        // PER-TYPE (snus.yml -> snus_types.<type>.overdose.*)
        org.bukkit.configuration.ConfigurationSection od = (typeSec != null) ? typeSec.getConfigurationSection("overdose") : null;

        spec.triggerStage    = (od != null) ? Math.max(1, od.getInt("trigger_stage", defStage)) : defStage;
        spec.vomitingEnabled = (od != null) ? od.getBoolean("vomiting", defVom) : defVom;
        spec.extraEffects    = (od != null && od.isList("effects")) ? od.getStringList("effects") : defFx;

        return spec;
    }

    // --- Effect resolver: toleruje aliasy i nazwy z configów ---
    private org.bukkit.potion.PotionEffectType resolveEffect(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');

        // Najczęstsze aliasy
        switch (key) {
            case "JUMP_BOOST":
            case "JUMPBOOST":
            case "LEAPING":
                key = "JUMP"; break;

            case "NAUSEA":
                // W starszych wersjach Bukkit to CONFUSION
                if (org.bukkit.potion.PotionEffectType.getByName("NAUSEA") == null) {
                    key = "CONFUSION";
                }
                break;

            case "HASTE":
                key = "FAST_DIGGING"; break;

            case "MINING_FATIGUE":
                key = "SLOW_DIGGING"; break;

            case "HUNGER":
                key = "HUNGER"; break;

            case "DOLPHINS_GRACE":
                key = "DOLPHINS_GRACE"; break;

            // możesz dodać inne, jeśli używasz
        }

        org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(key);
        if (type != null) return type;

        // Próba po namespace (Paper/1.20+)
        try {
            org.bukkit.NamespacedKey nsk = org.bukkit.NamespacedKey.minecraft(key.toLowerCase());
            type = org.bukkit.Registry.EFFECT.get(nsk);
        } catch (Throwable ignored) {}

        return type;
    }

    private void finishSnusEffects(org.bukkit.entity.Player p) {
        setSnusEnded(p, true);
        scheduleReminder(p);
    }

    private void cancelStageTask(org.bukkit.entity.Player p) {
        org.bukkit.scheduler.BukkitTask t = stageTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    private void cancelAllTasksFor(org.bukkit.entity.Player p) {
        cancelStageTask(p);
        cancelReminder(p);
    }

}
