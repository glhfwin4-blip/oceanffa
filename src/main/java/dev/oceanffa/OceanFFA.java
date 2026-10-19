package dev.oceanffa;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Display;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class OceanFFA extends JavaPlugin implements Listener, CommandExecutor {
    private File dataFile;
    private YamlConfiguration data;
    private final Map<String, BukkitTask> decayTasks = new HashMap<>();
    private final Map<UUID, MenuHolder> openMenus = new HashMap<>();
    private BukkitTask leaderboardTask;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { getLogger().severe(e.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : List.of("spawn","kits","stats","kills","balance","shop","daily","rewards","ping","settings","oceanadmin")) {
            Objects.requireNonNull(getCommand(name), "Missing command " + name).setExecutor(this);
        }
        long refreshTicks = Math.max(10, getConfig().getLong("leaderboard.refresh-seconds", 60)) * 20L;
        leaderboardTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshLeaderboards, refreshTicks, refreshTicks);
        Bukkit.getScheduler().runTaskLater(this, this::refreshLeaderboards, 40L);
        getLogger().info("OceanFFA enabled.");
    }

    @Override public void onDisable() {
        decayTasks.values().forEach(BukkitTask::cancel);
        if (leaderboardTask != null) leaderboardTask.cancel();
        saveData();
    }

    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save players.yml: " + e.getMessage()); }
    }
    private String path(UUID id) { return "players." + id; }
    private int tokens(UUID id) { return Math.max(0, data.getInt(path(id) + ".tokens", 0)); }
    private int kills(UUID id) { return Math.max(0, data.getInt(path(id) + ".kills", 0)); }
    private int deaths(UUID id) { return Math.max(0, data.getInt(path(id) + ".deaths", 0)); }
    private int points(UUID id) { return Math.max(0, data.getInt(path(id) + ".points", 0)); }
    private void setTokens(UUID id, int amount) { data.set(path(id) + ".tokens", Math.max(0, amount)); saveData(); }
    private void addTokens(UUID id, int amount) { setTokens(id, Math.max(0, tokens(id) + amount)); }
    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String msg(String key) { return c(getConfig().getString("prefix", "&3&lOCEAN FFA &8» ")) + c(getConfig().getString("messages." + key, "")); }
    private void tell(CommandSender s, String key) { s.sendMessage(msg(key)); }
    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(c(name));
            if (lore != null) meta.setLore(lore.stream().map(this::c).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("oceanadmin")) return admin(sender, args);
        if (!(sender instanceof Player player)) { tell(sender, "player-only"); return true; }

        switch (cmd) {
            case "spawn" -> {
                Location loc = spawnLocation();
                if (loc == null) { player.sendMessage(c("&cSpawn world is unavailable. Set spawn.world in config.yml or leave it blank to use the default world.")); return true; }
                player.teleport(loc);
                player.sendMessage(c("&3Teleported to spawn."));
            }
            case "kits" -> openKits(player);
            case "stats" -> {
                if (args.length == 0) openStats(player, player.getUniqueId(), player.getName());
                else {
                    Player target = Bukkit.getPlayerExact(args[0]);
                    if (target == null) player.sendMessage(c("&cThat player must be online to view their stats."));
                    else openStats(player, target.getUniqueId(), target.getName());
                }
            }
            case "kills" -> player.sendMessage(c("&3Your total kills: &b" + kills(player.getUniqueId())));
            case "balance" -> player.sendMessage(c("&3Your token balance: &e" + tokens(player.getUniqueId())));
            case "shop" -> openShop(player);
            case "daily" -> openDaily(player);
            case "rewards" -> openRewards(player);
            case "ping" -> player.sendMessage(c("&3Your ping: &b" + player.getPing() + "ms"));
            case "settings" -> openSettings(player);
            default -> { return false; }
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("oceanffa.admin")) { tell(sender, "no-permission"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); tell(sender, "reloaded"); return true;
        }
        if (sender instanceof Player p && args.length == 1 && (args[0].equalsIgnoreCase("pvp1") || args[0].equalsIgnoreCase("pvp2"))) {
            String corner = args[0].equalsIgnoreCase("pvp1") ? "pos1" : "pos2";
            Location l = p.getLocation();
            getConfig().set("pvp-region.world", l.getWorld().getName());
            getConfig().set("pvp-region." + corner, l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
            saveConfig();
            p.sendMessage(c("&aSaved PvP region corner " + corner + "."));
            if (!getConfig().getString("pvp-region.pos1", "").isBlank() && !getConfig().getString("pvp-region.pos2", "").isBlank()) p.sendMessage(c("&aPvP region configured. Blocks are allowed inside it except near spawn."));
            return true;
        }
        if (sender instanceof Player p && args.length == 1 && args[0].equalsIgnoreCase("leaderboard")) {
            Location l = p.getLocation();
            getConfig().set("leaderboard.location.world", l.getWorld().getName());
            getConfig().set("leaderboard.location.x", l.getX()); getConfig().set("leaderboard.location.y", l.getY()); getConfig().set("leaderboard.location.z", l.getZ());
            saveConfig(); spawnLeaderboardDisplays();
            p.sendMessage(c("&aCreated top kills, top points, and top deaths displays here."));
            return true;
        }
        if (args.length != 3 || !(args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("set"))) {
            tell(sender, "admin-usage"); return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { tell(sender, "invalid-player"); return true; }
        int amount;
        try { amount = Integer.parseInt(args[2]); if (amount < 0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { tell(sender, "invalid-number"); return true; }
        if (args[0].equalsIgnoreCase("give")) addTokens(target.getUniqueId(), amount);
        else setTokens(target.getUniqueId(), amount);
        target.sendMessage(c("&3Your token balance is now &e" + tokens(target.getUniqueId())));
        sender.sendMessage(c("&aUpdated " + target.getName() + "'s tokens."));
        return true;
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        data.set(path(victim.getUniqueId()) + ".deaths", deaths(victim.getUniqueId()) + 1);
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            data.set(path(killer.getUniqueId()) + ".kills", kills(killer.getUniqueId()) + 1);
            int reward = Math.max(0, getConfig().getInt("tokens.per-kill", 1));
            int pointsReward = Math.max(0, getConfig().getInt("tokens.points-per-kill", 1));
            data.set(path(killer.getUniqueId()) + ".points", points(killer.getUniqueId()) + pointsReward);
            addTokens(killer.getUniqueId(), reward);
            killer.sendMessage(msg("token-earned").replace("%player%", victim.getName()).replace("%amount%", String.valueOf(reward)).replace("+1", "+" + reward));
        }
        saveData();
    }

    private Location spawnLocation() {
        String worldName = getConfig().getString("spawn.world", "");
        World world = worldName == null || worldName.isBlank() ? Bukkit.getWorlds().stream().findFirst().orElse(null) : Bukkit.getWorld(worldName);
        if (world == null) return null;
        if (worldName == null || worldName.isBlank()) return world.getSpawnLocation();
        return new Location(world, getConfig().getDouble("spawn.x", .5), getConfig().getDouble("spawn.y", 100),
                getConfig().getDouble("spawn.z", .5), (float)getConfig().getDouble("spawn.yaw", 0),
                (float)getConfig().getDouble("spawn.pitch", 0));
    }

    private void openKits(Player p) {
        MenuHolder h = new MenuHolder("kits");
        Inventory inv = Bukkit.createInventory(h, 27, c("&0Ocean FFA - Kits")); h.inventory = inv;
        ConfigurationSection section = getConfig().getConfigurationSection("kits");
        if (section != null) for (String id : section.getKeys(false)) {
            ConfigurationSection kit = section.getConfigurationSection(id); if (kit == null) continue;
            int slot = kit.getInt("slot", 13); if (slot < 0 || slot >= inv.getSize()) continue;
            Material mat = Material.matchMaterial(kit.getString("material", "CHEST"));
            if (mat == null) mat = Material.CHEST;
            List<String> lore = new ArrayList<>(kit.getStringList("lore"));
            lore.add("&eClick to claim this kit");
            inv.setItem(slot, item(mat, kit.getString("name", id), lore)); h.actions.put(slot, id);
        }
        show(p, h, inv);
    }

    private void claimKit(Player p, String id) {
        ConfigurationSection kit = getConfig().getConfigurationSection("kits." + id);
        if (kit == null) return;
        long cooldown = Math.max(0, kit.getLong("cooldown-hours", 24)) * 3600_000L;
        String key = path(p.getUniqueId()) + ".kit-last." + id;
        long last = data.getLong(key, 0), now = System.currentTimeMillis();
        if (now - last < cooldown) {
            long left = (cooldown - (now-last)) / 60000;
            p.sendMessage(c("&cYou can claim this kit again in " + (left/60) + "h " + (left%60) + "m."));
            return;
        }
        for (String spec : kit.getStringList("items")) {
            String[] parts = spec.split(":");
            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null || mat.isAir()) continue;
            int amount = 1;
            if (parts.length > 1) try { amount = Math.max(1, Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) {}
            ItemStack stack = new ItemStack(mat, Math.min(amount, mat.getMaxStackSize()));
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(stack);
            leftover.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        }
        data.set(key, now); saveData();
        p.sendMessage(c("&aKit claimed: &f" + kit.getString("name", id)));
        p.closeInventory();
    }

    private void openStats(Player viewer, UUID id, String name) {
        MenuHolder h = new MenuHolder("stats");
        Inventory inv = Bukkit.createInventory(h, 27, c("&0FFA Stats: " + name)); h.inventory = inv;
        inv.setItem(11, item(Material.IRON_SWORD, "&cTotal Kills", List.of("&7" + kills(id))));
        inv.setItem(13, item(Material.GOLD_INGOT, "&ePoints", List.of("&7" + points(id))));
        inv.setItem(15, item(Material.SKELETON_SKULL, "&7Deaths", List.of("&7" + deaths(id))));
        show(viewer, h, inv);
    }

    private void openShop(Player p) {
        int rows = Math.max(1, Math.min(6, getConfig().getInt("shop.rows", 3)));
        MenuHolder h = new MenuHolder("shop");
        Inventory inv = Bukkit.createInventory(h, rows*9, c(getConfig().getString("shop.title", "&0Ocean FFA - Token Shop"))); h.inventory = inv;
        ConfigurationSection items = getConfig().getConfigurationSection("shop.items");
        if (items != null) for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id); if (s == null) continue;
            int slot = s.getInt("slot", -1); if (slot < 0 || slot >= inv.getSize()) continue;
            ItemStack stack = makeShopItem(s);
            if (stack != null) { inv.setItem(slot, stack); h.actions.put(slot, id); }
        }
        show(p, h, inv);
    }

    private ItemStack makeShopItem(ConfigurationSection s) {
        Material mat = Material.matchMaterial(s.getString("material", "DIAMOND_SWORD"));
        if (mat == null || mat.isAir()) return null;
        ItemStack stack = new ItemStack(mat, Math.max(1, Math.min(mat.getMaxStackSize(), s.getInt("amount", 1))));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(c(s.getString("name", mat.name())));
            meta.setLore(s.getStringList("lore").stream().map(this::c).toList());
            stack.setItemMeta(meta);
        }
        ConfigurationSection enchants = s.getConfigurationSection("enchantments");
        if (enchants != null) for (String key : enchants.getKeys(false)) {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
            if (ench != null) stack.addUnsafeEnchantment(ench, Math.max(1, enchants.getInt(key, 1)));
        }
        return stack;
    }

    private void buy(Player p, String id) {
        ConfigurationSection s = getConfig().getConfigurationSection("shop.items." + id);
        if (s == null) return;
        int cost = Math.max(0, s.getInt("cost", 0));
        if (tokens(p.getUniqueId()) < cost) { p.sendMessage(msg("not-enough").replace("%cost%", String.valueOf(cost))); return; }
        ItemStack purchase = makeShopItem(s); if (purchase == null) return;
        if (p.getInventory().firstEmpty() == -1) { p.sendMessage(c("&cYour inventory is full.")); return; }
        setTokens(p.getUniqueId(), tokens(p.getUniqueId()) - cost);
        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(purchase);
        if (!leftovers.isEmpty()) {
            addTokens(p.getUniqueId(), cost);
            p.sendMessage(c("&cYour inventory is full."));
            return;
        }
        p.sendMessage(msg("purchased").replace("%item%", c(s.getString("name", id))).replace("%cost%", String.valueOf(cost)));
        p.closeInventory();
    }

    private void openDaily(Player p) {
        MenuHolder h = new MenuHolder("daily");
        Inventory inv = Bukkit.createInventory(h, 27, c("&0Daily Rewards")); h.inventory = inv;
        int reward = Math.max(0, getConfig().getInt("daily.token-reward", 10));
        long cooldown = Math.max(1, getConfig().getLong("daily.cooldown-hours", 24)) * 3600_000L;
        long last = data.getLong(path(p.getUniqueId()) + ".daily-last", 0);
        boolean ready = System.currentTimeMillis() - last >= cooldown;
        inv.setItem(13, item(Material.CHEST, ready ? "&aClaim Daily Reward" : "&cDaily Reward Claimed",
                List.of("&7Reward: &e" + reward + " tokens", ready ? "&aClick to claim" : "&7Come back later")));
        h.actions.put(13, "claim_daily"); show(p, h, inv);
    }

    private void claimDaily(Player p) {
        long cooldown = Math.max(1, getConfig().getLong("daily.cooldown-hours", 24)) * 3600_000L;
        String key = path(p.getUniqueId()) + ".daily-last";
        long last = data.getLong(key, 0), now = System.currentTimeMillis();
        if (now - last < cooldown) {
            long mins = (cooldown - (now-last))/60000;
            p.sendMessage(msg("daily-wait").replace("%time%", (mins/60) + "h " + (mins%60) + "m"));
            return;
        }
        int reward = Math.max(0, getConfig().getInt("daily.token-reward", 10));
        addTokens(p.getUniqueId(), reward); data.set(key, now); saveData();
        p.sendMessage(msg("daily-claimed").replace("%amount%", String.valueOf(reward)));
        openDaily(p);
    }

    private void openRewards(Player p) {
        MenuHolder h = new MenuHolder("rewards");
        Inventory inv = Bukkit.createInventory(h, 27, c("&0Rewards")); h.inventory = inv;
        inv.setItem(11, item(Material.CHEST, "&aDaily Rewards", List.of("&7View and claim your daily reward.")));
        inv.setItem(15, item(Material.IRON_SWORD, "&eKill Rewards", List.of("&7Your total kills: &f" + kills(p.getUniqueId()), "&7Earn &f1 token &7per player kill.")));
        h.actions.put(11, "daily"); h.actions.put(15, "kill_rewards"); show(p, h, inv);
    }

    private void openSettings(Player p) {
        MenuHolder h = new MenuHolder("settings");
        Inventory inv = Bukkit.createInventory(h, 27, c("&0Player Settings")); h.inventory = inv;
        UUID id = p.getUniqueId();
        boolean sounds = data.getBoolean(path(id) + ".settings.sounds", getConfig().getBoolean("settings.allow-sounds", true));
        boolean dms = data.getBoolean(path(id) + ".settings.private-messages", getConfig().getBoolean("settings.allow-private-messages", true));
        inv.setItem(11, item(sounds ? Material.NOTE_BLOCK : Material.BARRIER, "&bSounds: " + (sounds ? "&aEnabled" : "&cDisabled"), List.of("&7Click to toggle")));
        inv.setItem(15, item(dms ? Material.WRITABLE_BOOK : Material.BARRIER, "&bPrivate Messages: " + (dms ? "&aEnabled" : "&cDisabled"), List.of("&7Click to toggle")));
        h.actions.put(11, "sounds"); h.actions.put(15, "private_messages"); show(p, h, inv);
    }

    private void show(Player p, MenuHolder holder, Inventory inventory) {
        openMenus.put(p.getUniqueId(), holder);
        p.openInventory(inventory);
    }

    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        MenuHolder h = openMenus.get(p.getUniqueId());
        if (h == null || e.getView().getTopInventory().getHolder() != h) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        String action = h.actions.get(e.getSlot()); if (action == null) return;
        switch (h.type) {
            case "shop" -> buy(p, action);
            case "kits" -> claimKit(p, action);
            case "daily" -> { if (action.equals("claim_daily")) claimDaily(p); }
            case "rewards" -> { if (action.equals("daily")) openDaily(p); }
            case "settings" -> {
                String key = action.equals("sounds") ? ".settings.sounds" : ".settings.private-messages";
                String path = path(p.getUniqueId()) + key;
                data.set(path, !data.getBoolean(path, action.equals("sounds") ?
                        getConfig().getBoolean("settings.allow-sounds", true) :
                        getConfig().getBoolean("settings.allow-private-messages", true)));
                saveData(); openSettings(p);
            }
        }
    }

    @EventHandler public void onMenuClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            MenuHolder h = openMenus.get(p.getUniqueId());
            if (h != null && e.getView().getTopInventory().getHolder() == h) openMenus.remove(p.getUniqueId());
        }
    }

    @EventHandler public void onBlockPlace(BlockPlaceEvent e) {
        Location loc = e.getBlockPlaced().getLocation();
        if (nearSpawn(loc)) { e.setCancelled(true); e.getPlayer().sendMessage(c("&cYou cannot place blocks at spawn.")); return; }
        if (!insidePvpRegion(loc)) { e.setCancelled(true); e.getPlayer().sendMessage(c("&cYou can only place blocks inside the configured PvP area.")); return; }
        String key = blockKey(loc); BukkitTask old = decayTasks.remove(key); if (old != null) old.cancel();
        Material placed = e.getBlockPlaced().getType();
        long min = Math.max(1, getConfig().getLong("blocks.decay-min-seconds", 20));
        long max = Math.max(min, getConfig().getLong("blocks.decay-max-seconds", 30));
        long seconds = min == max ? min : min + new Random().nextInt((int)Math.min(Integer.MAX_VALUE, max - min + 1));
        UUID owner = e.getPlayer().getUniqueId();
        decayTasks.put(key, Bukkit.getScheduler().runTaskLater(this, () -> {
            decayTasks.remove(key); Block block = e.getBlockPlaced();
            if (block.getType() == placed) { block.setType(Material.AIR, false); Player p = Bukkit.getPlayer(owner); if (p != null) p.sendMessage(c("&7Your temporary block has decayed.")); }
        }, seconds * 20L));
    }
    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        BukkitTask task = decayTasks.remove(blockKey(e.getBlock().getLocation()));
        if (task != null) task.cancel();
    }
    private String blockKey(Location l) { return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }

    private boolean insidePvpRegion(Location l) {
        String world = getConfig().getString("pvp-region.world", "");
        int[] a = parsePos(getConfig().getString("pvp-region.pos1", ""));
        int[] b = parsePos(getConfig().getString("pvp-region.pos2", ""));
        if (world == null || world.isBlank() || a == null || b == null || !l.getWorld().getName().equals(world)) return false;
        return l.getBlockX() >= Math.min(a[0],b[0]) && l.getBlockX() <= Math.max(a[0],b[0]) && l.getBlockY() >= Math.min(a[1],b[1]) && l.getBlockY() <= Math.max(a[1],b[1]) && l.getBlockZ() >= Math.min(a[2],b[2]) && l.getBlockZ() <= Math.max(a[2],b[2]);
    }
    private int[] parsePos(String raw) { try { String[] v=raw.split(","); if(v.length!=3)return null; return new int[]{Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2])}; } catch(Exception ex){return null;} }
    private boolean nearSpawn(Location l) {
        String wn=getConfig().getString("spawn.world", ""); World w=(wn==null||wn.isBlank())?Bukkit.getWorlds().stream().findFirst().orElse(null):Bukkit.getWorld(wn);
        if(w==null||!l.getWorld().equals(w))return false;
        Location sp=(wn==null||wn.isBlank())?w.getSpawnLocation():new Location(w,getConfig().getDouble("spawn.x",.5),getConfig().getDouble("spawn.y",100),getConfig().getDouble("spawn.z",.5));
        double r=Math.max(0,getConfig().getDouble("spawn.protection-radius",12)), dx=l.getX()+.5-sp.getX(), dz=l.getZ()+.5-sp.getZ(); return dx*dx+dz*dz<=r*r;
    }
    private void spawnLeaderboardDisplays() {
        String wn=getConfig().getString("leaderboard.location.world", ""); World w=Bukkit.getWorld(wn==null?"":wn); if(w==null)return;
        double x=getConfig().getDouble("leaderboard.location.x"), y=getConfig().getDouble("leaderboard.location.y"), z=getConfig().getDouble("leaderboard.location.z"); removeOldDisplays();
        spawnBoard(w,new Location(w,x-4,y,z),"kills");
        spawnBoard(w,new Location(w,x,y,z),"points");
        spawnBoard(w,new Location(w,x+4,y,z),"deaths"); refreshLeaderboards();
    }
    private void removeOldDisplays() { for(String k:List.of("kills-entity","points-entity","deaths-entity")){String raw=getConfig().getString("leaderboard."+k,"");try{if(raw!=null&&!raw.isBlank()){Entity e=Bukkit.getEntity(UUID.fromString(raw));if(e!=null)e.remove();}}catch(IllegalArgumentException ignored){}getConfig().set("leaderboard."+k,null);} }
    private void spawnBoard(World w, Location loc, String type) { TextDisplay d=(TextDisplay)w.spawnEntity(loc,EntityType.TEXT_DISPLAY); d.setBillboard(Display.Billboard.CENTER); d.setAlignment(TextDisplay.TextAlignment.CENTER); d.setShadowed(true); d.setBackgroundColor(Color.fromARGB(160,0,30,50)); d.setPersistent(true); getConfig().set("leaderboard."+type+"-entity",d.getUniqueId().toString()); saveConfig(); }
    private void refreshLeaderboards() { refreshBoard("kills","TOP KILLS","kills"); refreshBoard("points","TOP POINTS","points"); refreshBoard("deaths","TOP DEATHS","deaths"); }
    private void refreshBoard(String board,String title,String stat) {
        String raw=getConfig().getString("leaderboard."+board+"-entity",""); if(raw==null||raw.isBlank())return; Entity e; try{e=Bukkit.getEntity(UUID.fromString(raw));}catch(IllegalArgumentException ex){return;} if(!(e instanceof TextDisplay d))return;
        ConfigurationSection section=data.getConfigurationSection("players"); List<String> ids=section==null?new ArrayList<>():new ArrayList<>(section.getKeys(false)); ids.sort((a,b)->Integer.compare(data.getInt("players."+b+"."+stat,0),data.getInt("players."+a+"."+stat,0)));
        int limit=Math.max(1,Math.min(15,getConfig().getInt("leaderboard.top-entries",10))); StringBuilder out=new StringBuilder("§b§lOCEAN FFA\n§f§l"+title+"\n§8────────────"); int rank=0;
        for(String id:ids){if(rank>=limit)break;UUID uuid;try{uuid=UUID.fromString(id);}catch(IllegalArgumentException ex){continue;} OfflinePlayer op=Bukkit.getOfflinePlayer(uuid);String name=op.getName()==null?id.substring(0,Math.min(8,id.length())):op.getName();out.append("\n§7").append(++rank).append(". §f").append(name).append(" §8» §b").append(data.getInt("players."+id+"."+stat,0));}
        if(rank==0)out.append("\n§7No stats yet"); d.setText(out.toString().replace("\\n","\n"));
    }

    private static final class MenuHolder implements InventoryHolder {
        final String type; final Map<Integer, String> actions = new HashMap<>(); Inventory inventory;
        MenuHolder(String type) { this.type = type; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
