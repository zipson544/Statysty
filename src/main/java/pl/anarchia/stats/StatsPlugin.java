package pl.anarchia.stats;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class StatsPlugin extends JavaPlugin implements Listener, TabExecutor {

    public enum Stat { KILLS, DEATHS, MINED, STONE, PLACED, PEARLS, GAPPLES, KOXY, CASES, SPENT, EARNED }

    private static StatsPlugin instance;
    private final Map<UUID, EnumMap<Stat, Double>> data = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private File file;
    private boolean dirty = false;
    private final Map<UUID, Long> shopUntil = new ConcurrentHashMap<>();
    private Object ecoProxy;
    private Class<?> ecoClass;
    private Pattern casePattern;
    private List<String> shopCommands = List.of("sklep");
    private long shopWindowMs = 30_000;

    /** API dla innych pluginow (sklep, getcase). */
    public static StatsPlugin get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        shopCommands = new ArrayList<>();
        for (String c : getConfig().getStringList("shop-commands")) shopCommands.add(c.toLowerCase(Locale.ROOT));
        shopWindowMs = getConfig().getLong("shop-window-seconds", 30) * 1000L;
        try { casePattern = Pattern.compile(getConfig().getString("case-message-regex", "Gracz\\s+(\\S+)\\s+otworzy")); }
        catch (Exception ex) { getLogger().warning("Zly case-message-regex: " + ex.getMessage()); }
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "stats.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("stats").setExecutor(this);
        getCommand("statsadmin").setExecutor(this);
        getCommand("statsadmin").setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 20L * 300, 20L * 300);
        Bukkit.getScheduler().runTask(this, this::hookEconomy);
    }

    @Override
    public void onDisable() {
        if (ecoProxy != null) Bukkit.getServicesManager().unregister(ecoProxy);
        save();
    }

    // ------------------------------------------------------------ dane

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                names.put(id, s.getString("name", key));
                EnumMap<Stat, Double> m = new EnumMap<>(Stat.class);
                for (Stat st : Stat.values()) m.put(st, s.getDouble(st.name(), 0));
                data.put(id, m);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, EnumMap<Stat, Double>> e : data.entrySet()) {
            String k = e.getKey().toString();
            y.set(k + ".name", names.getOrDefault(e.getKey(), k));
            for (Map.Entry<Stat, Double> s : e.getValue().entrySet()) y.set(k + "." + s.getKey().name(), s.getValue());
        }
        try { y.save(file); dirty = false; }
        catch (IOException ex) { getLogger().severe("Nie mozna zapisac stats.yml: " + ex.getMessage()); }
    }

    private EnumMap<Stat, Double> map(UUID id) {
        return data.computeIfAbsent(id, k -> {
            EnumMap<Stat, Double> m = new EnumMap<>(Stat.class);
            for (Stat s : Stat.values()) m.put(s, 0.0);
            return m;
        });
    }

    public synchronized void add(UUID id, Stat stat, double amount) {
        EnumMap<Stat, Double> m = map(id);
        m.put(stat, m.get(stat) + amount);
        Player p = Bukkit.getPlayer(id);
        if (p != null) names.put(id, p.getName());
        dirty = true;
    }

    public synchronized double getStat(UUID id, Stat stat) {
        EnumMap<Stat, Double> m = data.get(id);
        return m == null ? 0 : m.get(stat);
    }

    // ------------------------------------------------------------ zliczanie

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        add(id, Stat.MINED, 1);
        Material t = e.getBlock().getType();
        if (t == Material.STONE || t == Material.COBBLESTONE) add(id, Stat.STONE, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { add(e.getPlayer().getUniqueId(), Stat.PLACED, 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player p)
            add(p.getUniqueId(), Stat.PEARLS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Material t = e.getItem().getType();
        if (t == Material.GOLDEN_APPLE) add(e.getPlayer().getUniqueId(), Stat.GAPPLES, 1);
        else if (t == Material.ENCHANTED_GOLDEN_APPLE) add(e.getPlayer().getUniqueId(), Stat.KOXY, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        add(e.getEntity().getUniqueId(), Stat.DEATHS, 1);
        Player k = e.getEntity().getKiller();
        if (k != null && !k.equals(e.getEntity())) add(k.getUniqueId(), Stat.KILLS, 1);
    }

    // ------------------------------------------------------------ GUI

    private static class StatsHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static final int CLOSE_SLOT = 40;

    private Component line(String label, String value, TextColor color) {
        return Component.text(label + ": ", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
                .append(Component.text(value, color).decoration(TextDecoration.BOLD, true));
    }

    private ItemStack item(Material m, Component name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private String num(double v) { return String.format(Locale.US, "%,d", (long) v).replace(',', ' '); }

    private String money(double v) {
        if (v >= 1_000_000) return String.format(Locale.US, "%.2fM$", v / 1_000_000);
        if (v >= 1_000) return String.format(Locale.US, "%.2fk$", v / 1_000);
        return String.format(Locale.US, "%.2f$", v);
    }

    private String time(long ticks) {
        long s = ticks / 20, d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    private double balance(OfflinePlayer op) {
        try {
            Class<?> c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return 0;
            Object eco = rsp.getProvider();
            return (double) c.getMethod("getBalance", OfflinePlayer.class).invoke(eco, op);
        } catch (Throwable t) { return 0; }
    }

    private void open(Player viewer, OfflinePlayer target) {
        UUID id = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : names.getOrDefault(id, "?");
        Inventory inv = Bukkit.createInventory(new StatsHolder(), 45,
                Component.text("Statystyki " + name, NamedTextColor.DARK_GRAY));

        long playTicks = target.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);

        inv.setItem(10, item(Material.EMERALD, line("Stan konta", money(balance(target)), NamedTextColor.GREEN)));
        inv.setItem(11, item(Material.DIAMOND_SWORD, line("Zabójstw", num(getStat(id, Stat.KILLS)), NamedTextColor.RED)));
        inv.setItem(12, item(Material.SKELETON_SKULL, line("Śmierci", num(getStat(id, Stat.DEATHS)), NamedTextColor.RED)));
        inv.setItem(13, item(Material.CLOCK, line("Czas gry", time(playTicks), NamedTextColor.YELLOW)));
        inv.setItem(14, item(Material.COBBLESTONE, line("Wykopany kamień", num(getStat(id, Stat.STONE)), NamedTextColor.GRAY)));
        inv.setItem(15, item(Material.ENDER_PEARL, line("Rzucone perły", num(getStat(id, Stat.PEARLS)), NamedTextColor.DARK_AQUA)));
        inv.setItem(16, item(Material.GOLDEN_APPLE, line("Zjedzone refile", num(getStat(id, Stat.GAPPLES)), NamedTextColor.GOLD)));

        inv.setItem(19, item(Material.ENCHANTED_GOLDEN_APPLE, line("Zjedzone koxy", num(getStat(id, Stat.KOXY)), NamedTextColor.LIGHT_PURPLE)));
        inv.setItem(20, item(Material.TRIPWIRE_HOOK, line("Otwarte skrzynki", num(getStat(id, Stat.CASES)), NamedTextColor.AQUA)));
        inv.setItem(21, item(Material.GRASS_BLOCK, line("Postawione bloki", num(getStat(id, Stat.PLACED)), NamedTextColor.GREEN)));
        inv.setItem(22, item(Material.DIAMOND_PICKAXE, line("Wykopane bloki", num(getStat(id, Stat.MINED)), NamedTextColor.AQUA)));
        inv.setItem(23, item(Material.GOLD_NUGGET, line("Wydane pieniądze", money(getStat(id, Stat.SPENT)), NamedTextColor.RED)));
        inv.setItem(24, item(Material.IRON_NUGGET, line("Zarobione pieniądze", money(getStat(id, Stat.EARNED)), NamedTextColor.GREEN)));

        inv.setItem(CLOSE_SLOT, item(Material.BARRIER, Component.text("Zamknij", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)));
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Long until = shopUntil.get(e.getWhoClicked().getUniqueId());
        if (until != null && until > System.currentTimeMillis())
            shopUntil.put(e.getWhoClicked().getUniqueId(), System.currentTimeMillis() + shopWindowMs);
        if (e.getView().getTopInventory().getHolder() instanceof StatsHolder) {
            e.setCancelled(true);
            if (e.getRawSlot() == CLOSE_SLOT) e.getWhoClicked().closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof StatsHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ komendy

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("stats")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Tylko dla graczy."); return true; }
            OfflinePlayer target = p;
            if (args.length >= 1) {
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(args[0]);
                    if (off == null || !off.hasPlayedBefore()) {
                        p.sendMessage(color("&cNie znaleziono gracza."));
                        return true;
                    }
                    target = off;
                }
            }
            open(p, target);
            return true;
        }

        // /statsadmin <case|spent|earned> <nick> [ilosc]
        if (!sender.hasPermission("anarchiastats.admin")) { sender.sendMessage(color("&cBrak uprawnien.")); return true; }
        if (args.length < 2) { sender.sendMessage(color("&eUzycie: /statsadmin <case|spent|earned> <nick> [ilosc]")); return true; }
        Stat stat = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "case" -> Stat.CASES;
            case "spent" -> Stat.SPENT;
            case "earned" -> Stat.EARNED;
            default -> null;
        };
        if (stat == null) { sender.sendMessage(color("&cTyp: case / spent / earned")); return true; }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[1]);
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID id = online != null ? online.getUniqueId() : (t != null ? t.getUniqueId() : null);
        if (id == null) { sender.sendMessage(color("&cNie znaleziono gracza.")); return true; }
        double amount = 1;
        if (args.length >= 3) {
            try { amount = Double.parseDouble(args[2]); }
            catch (NumberFormatException ex) { sender.sendMessage(color("&cZla liczba.")); return true; }
        }
        add(id, stat, amount);
        sender.sendMessage(color("&aDodano &f" + amount + " &ado " + stat + " gracza " + args[1] + "."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (String x : List.of("case", "spent", "earned")) if (x.startsWith(a[0].toLowerCase())) out.add(x);
        if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
        return out;
    }

    // ------------------------------------------------------------ Vault (/sklep)

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void hookEconomy() {
        try {
            ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
            ServicesManager sm = Bukkit.getServicesManager();
            RegisteredServiceProvider<?> rsp = sm.getRegistration(ecoClass);
            if (rsp == null) { getLogger().warning("Brak ekonomii Vault - wydane/zarobione nie beda liczone."); return; }
            final Object real = rsp.getProvider();
            ecoProxy = Proxy.newProxyInstance(ecoClass.getClassLoader(), new Class<?>[]{ecoClass}, (proxy, m, a) -> {
                Object res;
                try { res = m.invoke(real, a); }
                catch (InvocationTargetException ex) { throw ex.getCause(); }
                try { trackEconomy(m.getName(), a, res); } catch (Throwable ignored) { }
                return res;
            });
            sm.register((Class) ecoClass, ecoProxy, this, ServicePriority.Highest);
            getLogger().info("Podpieto pod Vault (" + real.getClass().getSimpleName() + ").");
        } catch (Throwable t) {
            getLogger().warning("Nie udalo sie podpiac Vault: " + t);
        }
    }

    private void trackEconomy(String name, Object[] args, Object res) throws Exception {
        boolean w = name.startsWith("withdrawPlayer"), d = name.startsWith("depositPlayer");
        if ((!w && !d) || args == null || res == null) return;
        if (!(boolean) res.getClass().getMethod("transactionSuccess").invoke(res)) return;
        UUID id = null;
        double amount = 0;
        for (Object o : args) {
            if (o instanceof OfflinePlayer off) id = off.getUniqueId();
            else if (o instanceof String str && id == null) {
                Player pl = Bukkit.getPlayerExact(str);
                if (pl != null) id = pl.getUniqueId();
                else { OfflinePlayer off2 = Bukkit.getOfflinePlayerIfCached(str); if (off2 != null) id = off2.getUniqueId(); }
            } else if (o instanceof Double dd) amount = dd;
        }
        if (id == null || amount <= 0) return;
        Long until = shopUntil.get(id);
        if (until == null || until < System.currentTimeMillis()) return;
        add(id, w ? Stat.SPENT : Stat.EARNED, amount);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopCommand(PlayerCommandPreprocessEvent e) {
        String first = e.getMessage().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (first.startsWith("/")) first = first.substring(1);
        if (shopCommands.contains(first))
            shopUntil.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + shopWindowMs);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!shopUntil.containsKey(e.getPlayer().getUniqueId())) return;
        Player p = (Player) e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING)
                shopUntil.remove(p.getUniqueId());
        }, 2L);
    }

    // ------------------------------------------------------------ GetCase (komunikat na czacie)

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBroadcast(BroadcastMessageEvent e) {
        if (casePattern == null) return;
        String plain = e.getMessage().replaceAll("(?i)[\u00a7&][0-9a-fk-or]", "");
        Matcher m = casePattern.matcher(plain);
        if (!m.find()) return;
        Player p = Bukkit.getPlayerExact(m.group(1));
        if (p != null) add(p.getUniqueId(), Stat.CASES, 1);
    }

    // ------------------------------------------------------------ /klan info

    private static Component color(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onKlanCommand(PlayerCommandPreprocessEvent e) {
        String[] a = e.getMessage().trim().split("\\s+");
        if (a.length < 2) return;
        if (!a[0].equalsIgnoreCase("/klan") || !a[1].equalsIgnoreCase("info")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();

        File f = new File(getDataFolder().getParentFile(), "AnarchiaKlany/klany.yml");
        if (!f.exists()) { p.sendMessage(color("&8[&6Klan&8] &cBrak danych klanow.")); return; }
        ConfigurationSection clans = YamlConfiguration.loadConfiguration(f).getConfigurationSection("clans");
        if (clans == null) { p.sendMessage(color("&8[&6Klan&8] &cBrak klanow.")); return; }

        String found = null;
        for (String tag : clans.getKeys(false)) {
            if (a.length >= 3) { if (tag.equalsIgnoreCase(a[2])) { found = tag; break; } }
            else if (clans.contains(tag + ".members." + p.getUniqueId())) { found = tag; break; }
        }
        if (found == null) {
            p.sendMessage(color(a.length >= 3 ? "&8[&6Klan&8] &cNie ma klanu o takim skrocie."
                                              : "&8[&6Klan&8] &cNie jestes w klanie. Uzyj &f/klan info <skrot>&c."));
            return;
        }

        ConfigurationSection c = clans.getConfigurationSection(found);
        ConfigurationSection mem = c.getConfigurationSection("members");
        String leaderId = c.getString("leader", "");
        p.sendMessage(color("&8&m        &r &6Klan &7[&6" + found + "&7] &8&m        "));
        p.sendMessage(color("&eNazwa: &f" + c.getString("name", found)));
        String leaderName = mem != null ? mem.getString(leaderId, "?") : "?";
        p.sendMessage(color("&eLider: &f" + leaderName));
        int total = mem == null ? 0 : mem.getKeys(false).size();
        int online = 0;
        StringBuilder sb = new StringBuilder();
        if (mem != null) for (String uid : mem.getKeys(false)) {
            boolean on = false;
            try { on = Bukkit.getPlayer(UUID.fromString(uid)) != null; } catch (IllegalArgumentException ignored) { }
            if (on) online++;
            if (sb.length() > 0) sb.append("&7, ");
            sb.append(on ? "&a" : "&c").append(mem.getString(uid, "?"));
        }
        p.sendMessage(color("&eCzlonkowie &7(" + online + "/" + total + " online)&e: " + sb));
    }
}
