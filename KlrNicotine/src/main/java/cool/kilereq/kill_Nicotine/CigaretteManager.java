package cool.kilereq.kill_Nicotine;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.ChatMessageType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.ChatColor;

import java.util.*;

public class CigaretteManager {
    private final MainNicotine plugin;
    private final FileConfiguration cfg;

    public final Map<UUID, Long> smokingStart = new HashMap<>();

    private final Map<UUID, Deque<Long>> inhaleTimestamps = new HashMap<>();
    private final Map<UUID, Long> intoxEndTime = new HashMap<>();
    private final Map<UUID, Long> intoxAccum = new HashMap<>();

    private final Map<UUID, Deque<Long>> puffTimestamps = new HashMap<>();
    private final Map<UUID, Long> lastVomitTs = new HashMap<>();
    private final Map<UUID, Long> lastKnockdownTs = new HashMap<>();
    private final Map<UUID, Integer> lastStageTriggered = new HashMap<>();
    private final Map<UUID, Long> knockdownLockUntil = new HashMap<>();
    private final Set<UUID> lockNotifiedOnce = new HashSet<>();

    private final long INHALE_WINDOW_MS = 60_000L;
    private final int INHALE_THRESHOLD = 20;
    private final long INITIAL_INTOX_MS = 30_000L;
    private final long LETHAL_INTOX_ACCUM_MS = 300_000L;

    private final Map<UUID, BukkitTask> smokingTasks = new HashMap<>();

    public CigaretteManager(MainNicotine plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg != null ? plugin.cfg : plugin.getConfig();
    }

    public void startIntoxicationTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it = intoxEndTime.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Long> e = it.next();
                    UUID uuid = e.getKey();
                    long end = e.getValue();
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) {
                        it.remove();
                        intoxAccum.remove(uuid);
                        inhaleTimestamps.remove(uuid);
                        continue;
                    }
                    if (end > now) {
                        long accum = intoxAccum.getOrDefault(uuid, 0L);
                        if (accum >= LETHAL_INTOX_ACCUM_MS) {
                            p.sendMessage(plugin.getMessage("overdose"));
                            try { p.setHealth(0.0); } catch (Throwable ignored) {}
                            intoxAccum.remove(uuid);
                            it.remove();
                            inhaleTimestamps.remove(uuid);
                        }
                    } else {
                        it.remove();
                        intoxAccum.remove(uuid);
                        inhaleTimestamps.remove(uuid);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void onDisable() {
        smokingStart.clear();
        smokingTasks.values().forEach(BukkitTask::cancel);
        smokingTasks.clear();
        puffTimestamps.clear();
        lastVomitTs.clear();
        lastKnockdownTs.clear();
        lastStageTriggered.clear();
    }

    // -----------------------
    // Item creators
    // -----------------------

    public ItemStack createCigarettePackFromConfig(String packId) {
        FileConfiguration ccfg = plugin.getCigaretteCfg();
        ConfigurationSection sec = (ccfg != null)
                ? ccfg.getConfigurationSection("cigarette_packs." + packId)
                : null;

        if (sec == null) return null;

        String matName = sec.getString("item", "PAPER");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.PAPER;

        int size = sec.getInt("size", cfg.getInt("general.pack_size", 20));
        int cmd  = sec.getInt("custom_model_data", 0);
        String rawName = sec.getString("name", "&7Papieros");
        String name    = ChatColor.translateAlternateColorCodes('&', rawName);

        boolean warnEnabled = cfg.getBoolean("warnings.enabled", true);
        boolean warnLock    = cfg.getBoolean("warnings.lock_per_pack", true);

        ItemStack pack = new ItemStack(mat, 1);
        ItemMeta meta = pack.getItemMeta();
        if (meta == null) return pack;

        meta.setDisplayName(name);
        if (cmd > 0) meta.setCustomModelData(cmd);

        String warningLine = null;
        if (warnEnabled) {
            String stored = meta.getPersistentDataContainer().get(plugin.KEY_PACK_WARNING, PersistentDataType.STRING);
            if (stored != null && !stored.isEmpty()) {
                warningLine = stored;
            } else {
                List<String> pool = plugin.getMessageList("health_warnings");
                String picked;
                if (pool == null || pool.isEmpty()) {
                    picked = plugin.getRawMessage("pack_warning_missing");
                    if (picked == null || picked.isEmpty()) picked = "&cBrak ostrzeżenia";
                } else {
                    picked = pool.get(new Random().nextInt(pool.size()));
                }
                warningLine = ChatColor.translateAlternateColorCodes('&', picked);

                if (warnLock) {
                    meta.getPersistentDataContainer().set(plugin.KEY_PACK_WARNING, PersistentDataType.STRING, warningLine);
                }
            }
        }

        List<String> lore = new ArrayList<>();
        if (warnEnabled) {
            lore.add(warningLine);
            lore.add("");
        }

        String tpl = cfg.getString("cigarette_pack_lore", "&7&o%max%/%max%");
        String line = ChatColor.translateAlternateColorCodes('&',
                tpl.replace("%size%", String.valueOf(size))
                        .replace("%max%", String.valueOf(size))
                        .replace("%remaining%", String.valueOf(size))
                        .replace("%pack_name%", name));
        lore.add(line);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.KEY_PACK_ID,       PersistentDataType.STRING,  packId);
        pdc.set(plugin.KEY_PACK_REMAINING,PersistentDataType.INTEGER, size);
        pdc.set(plugin.KEY_UNIQUE,        PersistentDataType.STRING,  UUID.randomUUID().toString());

        meta.setLore(lore);
        pack.setItemMeta(meta);
        return pack;
    }

    public void updatePackLore(ItemStack pack, String packId, int remaining, int max) {
        if (pack == null || !pack.hasItemMeta()) return;
        ItemMeta meta = pack.getItemMeta();
        if (meta == null) return;

        String warning = getOrSetPackWarning(meta);

        FileConfiguration ccfg = plugin.getCigaretteCfg();
        ConfigurationSection sec = (ccfg != null)
                ? ccfg.getConfigurationSection("cigarette_packs." + packId)
                : null;
        List<String> rawLoreList = (sec != null) ? sec.getStringList("lore") : new ArrayList<>();
        if (rawLoreList.isEmpty()) rawLoreList.add("%remaining%/%max%");

        List<String> lore = new ArrayList<>();
        if (warning != null && !warning.isEmpty()) lore.add(warning);

        if (remaining <= 0) {
            String emptyText = cfg.getString("general.empty_pack_name", "&7&oPusta paczka");
            lore.add(ChatColor.translateAlternateColorCodes('&', emptyText));
        } else {
            for (String line : rawLoreList) {
                line = line.replace("%size%", String.valueOf(remaining))
                        .replace("%max%", String.valueOf(max))
                        .replace("%remaining%", String.valueOf(remaining))
                        .replace("%pack_name%", meta.getDisplayName());
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
        }

        meta.setLore(lore);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.KEY_PACK_REMAINING, PersistentDataType.INTEGER, remaining);

        pack.setItemMeta(meta);
    }

    public ItemStack createCigaretteDefault(int uses) {
        ConfigurationSection gen = cfg.getConfigurationSection("general.cigarette");
        if (gen == null) return null;

        String matName = gen.getString("material", "BLAZE_ROD");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.BLAZE_ROD;

        String name = gen.getString("name", "&7&lPapieros");
        String loreTemplate = gen.getString("lore", "&7&o%uses%/%max%");
        int maxUses = gen.getInt("max_uses", 30);
        int cmd = gen.getInt("model_data", 0);

        if (uses <= 0) uses = maxUses;

        ItemStack cig = new ItemStack(mat, 1);
        ItemMeta meta = cig.getItemMeta();
        if (meta == null) return cig;

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        String loreLine = loreTemplate
                .replace("%uses%", String.format("%.1f", (double) uses))
                .replace("%max%", String.valueOf(maxUses));
        meta.setLore(Collections.singletonList(ChatColor.translateAlternateColorCodes('&', loreLine)));

        if (cmd > 0) meta.setCustomModelData(cmd);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.KEY_CIG_ID, PersistentDataType.STRING, "default");
        pdc.set(plugin.KEY_CIG_USES, PersistentDataType.DOUBLE, (double) uses);
        pdc.set(plugin.KEY_CIG_MAX, PersistentDataType.DOUBLE, (double) maxUses);
        pdc.set(plugin.KEY_UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());

        cig.setItemMeta(meta);
        return cig;
    }

    public ItemStack createStubFromConfig() {
        ConfigurationSection stubConfig = cfg.getConfigurationSection("general.stub");
        if (stubConfig == null) return null;

        String matName = stubConfig.getString("material", "BLAZE_ROD");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.BLAZE_ROD;

        String rawName = stubConfig.getString("name", "&7&oKiep");
        String name = ChatColor.translateAlternateColorCodes('&', rawName);

        List<String> rawLore = stubConfig.getStringList("lore");
        List<String> lore = new ArrayList<>();
        for (String line : rawLore) lore.add(ChatColor.translateAlternateColorCodes('&', line));

        int cmd = stubConfig.getInt("model_data", 0);

        ItemStack stub = new ItemStack(mat, 1);
        ItemMeta meta = stub.getItemMeta();
        if (meta == null) return stub;

        meta.setDisplayName(name);
        if (!lore.isEmpty()) meta.setLore(lore);
        if (cmd > 0) meta.setCustomModelData(cmd);

        stub.setItemMeta(meta);
        return stub;
    }

    // -----------------------
    // Helpers
    // -----------------------
    private boolean isPack(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(plugin.KEY_PACK_ID, PersistentDataType.STRING)
                && !pdc.has(plugin.KEY_SNUS_PACK_ID, PersistentDataType.STRING);
    }

    private String getPackId(ItemStack item) {
        if (!isPack(item)) return null;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().get(plugin.KEY_PACK_ID, PersistentDataType.STRING);
    }

    private int getPackRemaining(ItemStack item) {
        if (!isPack(item)) return 0;
        ItemMeta meta = item.getItemMeta();
        Integer v = meta.getPersistentDataContainer().get(plugin.KEY_PACK_REMAINING, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private Deque<Long> getPuffQueue(Player p) {
        return puffTimestamps.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
    }
    private void pruneOld(Deque<Long> q, long nowMs, long windowMs) {
        while (!q.isEmpty() && nowMs - q.peekFirst() > windowMs) q.pollFirst();
    }

    private void doVomitSeries(Player p, int count, int intervalTicks) {
        if (count <= 0) return;
        new BukkitRunnable() {
            int left = count;
            @Override public void run() {
                if (!p.isOnline()) { cancel(); return; }
                doVomit(p);
                left--;
                if (left <= 0) cancel();
            }
        }.runTaskTimer(plugin, 0L, Math.max(1, intervalTicks));
    }

    private boolean isKnockdownLocked(Player p) {
        Long until = knockdownLockUntil.get(p.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() <= until) return true;
        knockdownLockUntil.remove(p.getUniqueId());
        lockNotifiedOnce.remove(p.getUniqueId());
        return false;
    }

    private boolean isGrounded(org.bukkit.Location loc) {
        org.bukkit.Location below = loc.clone().subtract(0, 0.2, 0);
        org.bukkit.block.Block b = below.getBlock();
        return b.getType().isSolid();
    }

    private void setPackRemaining(ItemStack item, int remaining) {
        if (item == null || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        int safeRemaining = Math.max(0, remaining);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.KEY_PACK_REMAINING, PersistentDataType.INTEGER, safeRemaining);

        String warning = getOrSetPackWarning(meta);
        String packId = pdc.get(plugin.KEY_PACK_ID, PersistentDataType.STRING);

        int max = 0;
        FileConfiguration cigCfg = plugin.getCigaretteCfg();
        if (cigCfg != null && packId != null) {
            ConfigurationSection csec = cigCfg.getConfigurationSection("cigarette_packs." + packId);
            if (csec != null) {
                max = csec.getInt("size", 0);
            }
        }
        if (max <= 0) {
            FileConfiguration base = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
            max = base.getInt("general.pack_size", 20);
        }

        String line2 = "";
        String line3;
        if (safeRemaining == 0) {
            String emptyText = (plugin.cfg != null ? plugin.cfg : plugin.getConfig())
                    .getString("general.empty_pack_name", "&7&oPusta paczka");
            line3 = ChatColor.translateAlternateColorCodes('&', emptyText);
        } else {
            String countTemplate = plugin.getConfig().getString("cigarette_pack_lore_count", "%remaining%/%max%");
            line3 = ChatColor.translateAlternateColorCodes('&',
                    countTemplate.replace("%remaining%", String.valueOf(safeRemaining))
                            .replace("%max%", String.valueOf(max)));
        }

        List<String> lore = new ArrayList<>();
        if (warning != null && !warning.isEmpty()) lore.add(warning);
        lore.add(line2);
        lore.add(line3);

        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private boolean isCigarette(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(plugin.KEY_CIG_ID, PersistentDataType.STRING);
    }

    private double getCigUses(ItemStack item) {
        if (!isCigarette(item)) return 0.0;
        Double v = item.getItemMeta().getPersistentDataContainer().get(plugin.KEY_CIG_USES, PersistentDataType.DOUBLE);
        return v == null ? 0.0 : v;
    }

    private double getCigMax(ItemStack item) {
        if (!isCigarette(item)) return (double) cfg.getInt("general.cigarette.max_uses", 30);
        Double v = item.getItemMeta().getPersistentDataContainer().get(plugin.KEY_CIG_MAX, PersistentDataType.DOUBLE);
        return v == null ? (double) cfg.getInt("general.cigarette.max_uses", 30) : v;
    }

    public void setCigUses(ItemStack item, double uses) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        pdc.set(plugin.KEY_CIG_USES, PersistentDataType.DOUBLE, uses);

        double max = pdc.getOrDefault(plugin.KEY_CIG_MAX, PersistentDataType.DOUBLE,
                (double) cfg.getInt("general.cigarette.max_uses", 30));

        String tpl = cfg.getString("general.cigarette.lore", "&7&o%uses%/%max%");
        String out = tpl
                .replace("%uses%", String.format("%.1f", uses))
                .replace("%max%", String.format("%.0f", max));

        List<String> newLore = Collections.singletonList(ChatColor.translateAlternateColorCodes('&', out));
        meta.setLore(newLore);
        item.setItemMeta(meta);
    }

    private void spawnSmoke(Player player, double seconds) {
        Vector dir = player.getLocation().getDirection().normalize();
        Vector base = player.getEyeLocation().toVector().add(dir.clone().multiply(1.2));

        player.playSound(player.getLocation(), Sound.BLOCK_CAMPFIRE_CRACKLE, 0.7f, 1f);

        int totalTicks = Math.max(1, (int) (seconds * 20));
        int particleCount = Math.max(2, (int) (seconds * 5));

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (ticks >= totalTicks) {
                    cancel();
                    return;
                }
                Vector forward = dir.clone().multiply(ticks * 0.05);
                Vector up = new Vector(0, ticks * 0.015, 0);
                Vector offset = forward.add(up);

                player.getWorld().spawnParticle(
                        Particle.CAMPFIRE_COSY_SMOKE,
                        base.clone().add(offset).toLocation(player.getWorld()),
                        particleCount,
                        0.15, 0.05, 0.15,
                        0.005
                );

                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void stopSmoking(Player player, ItemStack ignored, boolean spawnSmoke) {
        UUID uuid = player.getUniqueId();

        BukkitTask t = smokingTasks.remove(uuid);
        if (t != null) t.cancel();

        long now = System.currentTimeMillis();
        long start = smokingStart.getOrDefault(uuid, now);
        double seconds = Math.max(0.0, (now - start) / 1000.0);
        if (seconds > 4.1) seconds = 4.1;

        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || !isCigarette(inHand)) {
            smokingStart.remove(uuid);
            return;
        }

        double currentUses = getCigUses(inHand);
        double newUses = Math.max(0.0, currentUses - seconds);
        setCigUses(inHand, newUses);

        if (newUses <= 0.0) {
            ItemStack stub = createStubFromConfig();
            player.getInventory().setItemInMainHand(stub);
        }

        if (spawnSmoke && seconds > 0.0) {
            spawnSmoke(player, seconds);
        }

        if (seconds > 0.2) recordPuff(player);
        smokingStart.remove(uuid);

        plugin.sendActionBar(player, "cigarette_smoke_end", "&7&oZaciągnąłeś się", "%time%", String.format("%.1f", seconds));
    }

    // -----------------------
    // Event handlers
    // -----------------------
    public void handlePlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (!(a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK)) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        if (plugin.getSnusManager() != null && plugin.getSnusManager().isSnus(item)) return;
        if (plugin.getSnusManager() != null && plugin.getSnusManager().isSnusPack(item)) return;

        // Pack -> Taking out cigarette
        if (isPack(item)) {
            int rem = getPackRemaining(item);
            if (rem <= 0) {
                event.setCancelled(true);
                return;
            }
            setPackRemaining(item, rem - 1);
            ItemStack cig = createUnlitCigaretteFromConfig();
            player.getInventory().addItem(cig);
            event.setCancelled(true);
            return;
        }

        // Cigarette -> Start/Stop
        if (isCigarette(item)) {
            UUID id = player.getUniqueId();
            if (!isCigaretteLit(item)) {
                ItemStack off = player.getInventory().getItemInOffHand();
                if (isLighter(off)) {
                    lightCigarette(player, item);
                    event.setCancelled(true);
                    return;
                } else {
                    plugin.sendActionBar(player,
                            plugin.getActionBarFromLang("lighter_required",
                                    "&cPotrzebujesz zapalniczki w drugiej ręce!"));
                    event.setCancelled(true);
                    return;
                }
            }

            if (smokingTasks.containsKey(id)) {
                stopSmoking(player, item, true);
                event.setCancelled(true);
                return;
            }

            smokingStart.put(id, System.currentTimeMillis());

            BukkitTask task = new BukkitRunnable() {
                double time = 0.0;
                @Override
                public void run() {
                    if (!player.isOnline()) { cancel(); smokingTasks.remove(id); return; }

                    ItemStack inHand = player.getInventory().getItemInMainHand();
                    if (inHand == null || !isCigarette(inHand)) {
                        cancel();
                        smokingTasks.remove(id);
                        stopSmoking(player, item, true);
                        return;
                    }

                    time += 0.1;

                    if (time >= 4.1) {
                        cancel();
                        smokingTasks.remove(id);
                        stopSmoking(player, item, true);
                        return;
                    }

                    plugin.sendActionBar(player, "cigarette_smoke", "&7&oZaciągasz się... &e%time%s", "%time%", String.format("%.1f", time));
                }
            }.runTaskTimer(plugin, 0L, 2L);

            smokingTasks.put(id, task);
            event.setCancelled(true);
        }
    }

    public void handleUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;
        if (!isCigarette(item)) return;
        stopSmoking(player, item, true);
    }

    public void handleHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (smokingStart.containsKey(player.getUniqueId())) {
            stopSmoking(player, player.getInventory().getItem(event.getPreviousSlot()), true);
        }
    }

    public void handleQuit(PlayerQuitEvent event) {
        smokingStart.remove(event.getPlayer().getUniqueId());
    }

    // -----------------------
    // givePack (configurable)
    // -----------------------
    public boolean givePack(Player player, String packId, String packsSection) {
        final FileConfiguration packsCfg =
                (plugin.getCigaretteCfg() != null ? plugin.getCigaretteCfg()
                        : (plugin.cfg != null ? plugin.cfg : plugin.getConfig()));
        final ConfigurationSection sec = packsCfg.getConfigurationSection(packsSection + "." + packId);
        if (sec == null) {
            player.sendMessage(plugin.getMessage("np_cigarette_pack_not_found").replace("%pack%", packId));
            return false;
        }

        String matName = sec.getString("item", "PAPER");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.PAPER;

        final FileConfiguration baseCfg = (plugin.cfg != null ? plugin.cfg : plugin.getConfig());
        int size = sec.getInt("size", baseCfg.getInt("general.pack_size", 20));

        String rawName = sec.getString("name", packId);
        String display = ChatColor.translateAlternateColorCodes('&', rawName);
        int cmd = sec.getInt("custom_model_data", 0);

        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) { player.getInventory().addItem(item); return true; }

        meta.setDisplayName(display);
        if (cmd > 0) meta.setCustomModelData(cmd);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String warningLine = pdc.get(plugin.KEY_PACK_WARNING, PersistentDataType.STRING);
        if (warningLine == null || warningLine.isEmpty()) {
            List<String> warnings = plugin.getLangList("health_warnings");
            String picked = warnings.isEmpty()
                    ? plugin.getRawMessage("pack_warning_missing")
                    : warnings.get(new Random().nextInt(warnings.size()));
            warningLine = ChatColor.translateAlternateColorCodes('&',
                    (picked == null || picked.isEmpty()) ? "&cBrak ostrzeżenia" : picked);
            pdc.set(plugin.KEY_PACK_WARNING, PersistentDataType.STRING, warningLine);
        }

        List<String> lore = new ArrayList<>();
        if (warningLine != null && !warningLine.isEmpty()) lore.add(warningLine);
        lore.add("");

        String countLineTpl = baseCfg.getString("cigarette_pack_lore_count", "&7&o%remaining%/%max%");
        String countLine = ChatColor.translateAlternateColorCodes('&',
                countLineTpl.replace("%remaining%", String.valueOf(size))
                        .replace("%max%", String.valueOf(size)));
        lore.add(countLine);

        meta.setLore(lore);

        pdc.set(plugin.KEY_PACK_ID, PersistentDataType.STRING, packId);
        pdc.set(plugin.KEY_PACK_REMAINING, PersistentDataType.INTEGER, size);
        pdc.set(plugin.KEY_UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());

        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        return true;
    }

    private String getOrSetPackWarning(ItemMeta meta) {
        if (meta == null) return ChatColor.RED + "Brak ostrzeżenia";

        boolean warnEnabled = cfg.getBoolean("warnings.enabled", true);
        boolean warnLock    = cfg.getBoolean("warnings.lock_per_pack", true);

        if (!warnEnabled) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String stored = pdc.get(plugin.KEY_PACK_WARNING, PersistentDataType.STRING);
        if (stored != null && !stored.isEmpty()) return stored;

        List<String> pool = plugin.getMessageList("health_warnings");
        String picked;
        if (pool == null || pool.isEmpty()) {
            picked = plugin.getRawMessage("pack_warning_missing");
            if (picked == null || picked.isEmpty()) picked = "&cBrak ostrzeżenia";
        } else {
            picked = pool.get(new Random().nextInt(pool.size()));
        }
        String warning = ChatColor.translateAlternateColorCodes('&', picked);

        if (warnLock) {
            pdc.set(plugin.KEY_PACK_WARNING, PersistentDataType.STRING, warning);
        }
        return warning;
    }

    private void doVomit(Player p) {
        var od = cfg.getConfigurationSection("overdose");
        if (od == null || !od.getBoolean("enabled", true)) return;
        var vom = od.getConfigurationSection("vomiting");
        if (vom == null || !vom.getBoolean("enabled", true)) return;

        String mode = vom.getString("mode", "BLOCK").toUpperCase();
        String blockMatName = vom.getString("block_material", "SOUL_SAND");
        String itemMatName  = vom.getString("item_material", "SOUL_SAND");
        int trailMaxTicks   = Math.max(5, vom.getInt("trail_max_ticks", 20));
        int trailCount      = Math.max(1, vom.getInt("trail_count", 6));
        int splashBursts    = Math.max(1, vom.getInt("splash_bursts", 6));
        int splashCount     = Math.max(1, vom.getInt("splash_count", 12));

        String soundName    = vom.getString("sound", "ENTITY_PLAYER_BURP");
        float vol           = (float) vom.getDouble("sound_volume", 1.0);
        float pit           = (float) vom.getDouble("sound_pitch", 1.0);

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
            default -> {
                particleType = Particle.CAMPFIRE_COSY_SMOKE;
            }
        }

        try {
            var snd = org.bukkit.Sound.valueOf(soundName.toUpperCase());
            p.playSound(p.getLocation(), snd, vol, pit);
        } catch (IllegalArgumentException ignored) {}

        final String finalMode = mode;
        final org.bukkit.Particle finalParticleType = particleType;
        final org.bukkit.block.data.BlockData finalBlockData = blockData;
        final org.bukkit.inventory.ItemStack finalItemData = itemData;
        final int finalTrailMaxTicks = trailMaxTicks;
        final int finalTrailCount = trailCount;
        final int finalSplashBursts = splashBursts;
        final int finalSplashCount = splashCount;

        final org.bukkit.Location loc = p.getEyeLocation().clone().add(p.getLocation().getDirection().normalize().multiply(0.45));
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
                    return;
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        plugin.sendActionBar(p, "overdose_vomit_ab", "&7&oWymiotujesz...");
    }

    private void doKnockdown(Player p) {
        var odSec = cfg.getConfigurationSection("overdose");
        if (odSec == null || !odSec.getBoolean("enabled", true)) return;
        var kdSec = odSec.getConfigurationSection("knockdown");
        if (kdSec == null || !kdSec.getBoolean("enabled", true)) return;

        String cmdTpl   = kdSec.getString("command", "reviveme down %player%");
        boolean console = kdSec.getBoolean("run_as_console", true);
        int delayTicks  = kdSec.getInt("delay_ticks", 0);

        Runnable runCmd = () -> {
            String cmd = cmdTpl.replace("%player%", p.getName());
            if (console) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else {
                p.performCommand(cmd);
            }
        };

        if (delayTicks > 0) {
            new BukkitRunnable() { @Override public void run() { runCmd.run(); } }
                    .runTaskLater(plugin, delayTicks);
        } else {
            runCmd.run();
        }

        String ab = plugin.getRawMessage("actionbar.overdose_knockdown_ab");
        if (ab == null || ab.isEmpty()) ab = "&cKnockdown!";
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(ChatColor.translateAlternateColorCodes('&', ab)));

        UUID id = p.getUniqueId();
        puffTimestamps.remove(id);
        lastStageTriggered.remove(id);

        long lockMs = Math.max(0, kdSec.getInt("lock_after_ms", 180000));
        if (lockMs > 0) {
            knockdownLockUntil.put(id, System.currentTimeMillis() + lockMs);
            lockNotifiedOnce.remove(id);
        } else {
            knockdownLockUntil.remove(id);
            lockNotifiedOnce.remove(id);
        }
    }

    public void resetOverdose(Player p) {
        UUID id = p.getUniqueId();
        puffTimestamps.remove(id);
        lastStageTriggered.remove(id);
        knockdownLockUntil.remove(id);
        lockNotifiedOnce.remove(id);
    }

    private void recordPuff(Player p) {
        var od = cfg.getConfigurationSection("overdose");
        if (od == null || !od.getBoolean("enabled", true)) return;

        if (isKnockdownLocked(p)) {
            if (!lockNotifiedOnce.contains(p.getUniqueId())) {
                String ab = plugin.getRawMessage("overdose_locked_ab");
                if (ab != null && !ab.isEmpty()) {
                    p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            new net.md_5.bungee.api.chat.TextComponent(org.bukkit.ChatColor.translateAlternateColorCodes('&', ab)));
                }
                lockNotifiedOnce.add(p.getUniqueId());
            }
            return;
        }

        long now = System.currentTimeMillis();
        long windowMs = Math.max(1, od.getInt("window_seconds", 60)) * 1000L;

        Deque<Long> q = getPuffQueue(p);
        pruneOld(q, now, windowMs);
        q.addLast(now);

        int puffs = q.size();

        var stages = od.getConfigurationSection("stages");
        int t1 = stages != null ? Objects.requireNonNull(stages.getConfigurationSection("s1")).getInt("threshold", 15) : 15;
        int t2 = stages != null ? Objects.requireNonNull(stages.getConfigurationSection("s2")).getInt("threshold", 20) : 20;
        int t3 = stages != null ? Objects.requireNonNull(stages.getConfigurationSection("s3")).getInt("threshold", 25) : 25;
        int t4 = stages != null ? Objects.requireNonNull(stages.getConfigurationSection("s4")).getInt("threshold", 30) : 30;

        int stage = 0;
        if (puffs >= t4) stage = 4;
        else if (puffs >= t3) stage = 3;
        else if (puffs >= t2) stage = 2;
        else if (puffs >= t1) stage = 1;

        int last = lastStageTriggered.getOrDefault(p.getUniqueId(), 0);
        if (stage == 0 && last != 0) {
            lastStageTriggered.put(p.getUniqueId(), 0);
            return;
        }
        if (stage <= last) return;
        lastStageTriggered.put(p.getUniqueId(), stage);

        java.util.function.BiConsumer<String, org.bukkit.potion.PotionEffectType> applyEffect = (pathBase, type) -> {
            assert stages != null;
            var sec = stages.getConfigurationSection(pathBase);
            if (sec == null) return;
            int dur = Math.max(1, sec.getInt("duration_seconds", 5)) * 20;
            int amp = Math.max(0, sec.getInt("amplifier", 0));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(type, dur, amp, false, true, true));
        };

        switch (stage) {
            case 1 -> {
                if (stages != null) {
                    applyEffect.accept("s1.nausea", org.bukkit.potion.PotionEffectType.NAUSEA);
                } else {
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 6*20, 0, false, true, true));
                }
                plugin.sendActionBar(p, "overdose_nausea_applied", "&7Masz &e&lzawroty głowy&7.");
            }
            case 2 -> {
                if (stages != null) {
                    applyEffect.accept("s2.nausea", org.bukkit.potion.PotionEffectType.NAUSEA);
                    applyEffect.accept("s2.blindness", org.bukkit.potion.PotionEffectType.BLINDNESS);
                } else {
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 8*20, 1, false, true, true));
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 5*20, 0, false, true, true));
                }
                plugin.sendActionBar(p, "overdose_nausea_applied", "&7Masz &e&lzawroty głowy&7.");
            }
            case 3 -> {
                if (stages != null) {
                    applyEffect.accept("s3.nausea", org.bukkit.potion.PotionEffectType.NAUSEA);
                    applyEffect.accept("s3.blindness", org.bukkit.potion.PotionEffectType.BLINDNESS);
                    var v = stages.getConfigurationSection("s3.vomiting");
                    int count = v != null ? v.getInt("count", 3) : 3;
                    int interval = v != null ? v.getInt("interval_ticks", 20) : 20;
                    if (od.getConfigurationSection("vomiting").getBoolean("enabled", true) && count > 0) {
                        doVomitSeries(p, count, Math.max(1, interval));
                    }
                } else {
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 10*20, 1, false, true, true));
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 6*20, 1, false, true, true));
                    doVomitSeries(p, 3, 20);
                }
            }
            case 4 -> {
                var kdEnabled = od.getConfigurationSection("knockdown").getBoolean("enabled", true);
                var s4 = stages.getConfigurationSection("s4");
                boolean flag = s4 != null && s4.getBoolean("knockdown", true);
                if (kdEnabled && flag) {
                    doKnockdown(p);
                }
            }
        }
    }

    private String pickAndLockWarning(ItemMeta meta) {
        if (meta == null) return ChatColor.RED + "Brak ostrzeżenia";

        // POPRAWKA: właściwa gałąź "warnings.*"
        boolean enabled = plugin.cfg.getBoolean("warnings.enabled", true);
        if (!enabled) {
            return "";
        }
        boolean lockPerPack = plugin.cfg.getBoolean("warnings.lock_per_pack", true);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (lockPerPack && pdc.has(plugin.KEY_PACK_WARNING, PersistentDataType.STRING)) {
            String stored = pdc.get(plugin.KEY_PACK_WARNING, PersistentDataType.STRING);
            if (stored != null && !stored.isEmpty()) return stored;
        }

        List<String> warnings = plugin.getLangList("health_warnings");
        String picked;
        if (warnings == null || warnings.isEmpty()) {
            picked = plugin.getRawMessage("pack_warning_missing");
            if (picked == null || picked.isEmpty()) {
                picked = ChatColor.RED + "Brak ostrzeżenia";
            }
        } else {
            picked = warnings.get(new Random().nextInt(warnings.size()));
        }

        String colored = ChatColor.translateAlternateColorCodes('&', picked);

        if (lockPerPack) {
            pdc.set(plugin.KEY_PACK_WARNING, PersistentDataType.STRING, colored);
        } else {
            if (pdc.has(plugin.KEY_PACK_WARNING, PersistentDataType.STRING)) {
                try { pdc.remove(plugin.KEY_PACK_WARNING); } catch (Throwable ignored) {}
            }
        }

        return colored;
    }

    private boolean isCigaretteLit(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Integer v = item.getItemMeta().getPersistentDataContainer()
                .get(plugin.KEY_CIG_LIT, PersistentDataType.INTEGER);
        return v != null && v == 1;
    }

    private void setCigaretteLit(ItemStack item, boolean lit) {
        if (item == null || !item.hasItemMeta()) return;
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(plugin.KEY_CIG_LIT, PersistentDataType.INTEGER, lit ? 1 : 0);
        item.setItemMeta(meta);
    }

    private boolean isLighter(ItemStack item) {
        return plugin.getAddonsManager() != null && plugin.getAddonsManager().isLighter(item);
    }

    private ItemStack createUnlitCigaretteFromConfig() {
        var gen = cfg.getConfigurationSection("general.cigarette");
        String matName = (gen != null) ? gen.getString("material", "BLAZE_ROD") : "BLAZE_ROD";
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.BLAZE_ROD;

        String name = (gen != null) ? gen.getString("name", "&7&lPapieros") : "&7&lPapieros";
        int cmd = (gen != null) ? gen.getInt("model_data", 0) : 0;
        int maxUses = (gen != null) ? gen.getInt("max_uses", 30) : 30;

        ItemStack cig = new ItemStack(mat, 1);
        ItemMeta meta = cig.getItemMeta();
        if (meta == null) return cig;

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (cmd > 0) meta.setCustomModelData(cmd);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.KEY_CIG_ID, PersistentDataType.STRING, "default");
        pdc.set(plugin.KEY_CIG_MAX, PersistentDataType.DOUBLE, (double) maxUses);
        pdc.set(plugin.KEY_CIG_USES, PersistentDataType.DOUBLE, (double) maxUses);
        pdc.set(plugin.KEY_CIG_LIT, PersistentDataType.INTEGER, 0);
        pdc.set(plugin.KEY_UNIQUE, PersistentDataType.STRING, UUID.randomUUID().toString());

        meta.setLore(Collections.emptyList());
        cig.setItemMeta(meta);
        return cig;
    }

    private void lightCigarette(Player player, ItemStack cig) {
        setCigaretteLit(cig, true);

        var meta = cig.getItemMeta();
        if (meta != null) {
            double uses = meta.getPersistentDataContainer().getOrDefault(
                    plugin.KEY_CIG_USES, PersistentDataType.DOUBLE,
                    (double) cfg.getInt("general.cigarette.max_uses", 30)
            );
            double max = meta.getPersistentDataContainer().getOrDefault(
                    plugin.KEY_CIG_MAX, PersistentDataType.DOUBLE,
                    (double) cfg.getInt("general.cigarette.max_uses", 30)
            );

            String format = plugin.getRawMessage("cigarette_lore_format");
            if (format == null || format.isEmpty()) format = "&7&o%uses%/%max%";
            String line = ChatColor.translateAlternateColorCodes('&',
                    format.replace("%uses%", String.format("%.1f", uses))
                            .replace("%max%", String.format("%.0f", max))
            );
            meta.setLore(Collections.singletonList(line));
            cig.setItemMeta(meta);
        }

        plugin.sendActionBar(player,
                plugin.getActionBarFromLang("lighter_lighted",
                        "&7&oOdpaliłeś papierosa."));
    }
}
