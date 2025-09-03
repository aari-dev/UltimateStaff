package dev.aari.ultimatestaff;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class UltimateStaff extends JavaPlugin implements Listener {

    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> godPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mutedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, BanInfo> bannedPlayers = new ConcurrentHashMap<>();
    private final Set<String> bannedIPs = ConcurrentHashMap.newKeySet();

    private Connection database;
    private LuckPerms luckPerms;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            getLogger().severe("LuckPerms not found! Plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (config.getBoolean("database.enabled", true)) {
            initDatabase();
        }

        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredPunishments();
            }
        }.runTaskTimer(this, 20L, 1200L);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : vanishedPlayers) {
                    Player player = getServer().getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        player.sendActionBar(Component.text("You are invisible to all other players!", NamedTextColor.GREEN));
                    }
                }
            }
        }.runTaskTimer(this, 0L, 60L);
    }

    @Override
    public void onDisable() {
        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().warning("Error closing database: " + e.getMessage());
            }
        }
    }

    private void initDatabase() {
        try {
            File dataFolder = getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            database = DriverManager.getConnection("jdbc:sqlite:" + dataFolder + "/data.db");

            String createTables = """
                CREATE TABLE IF NOT EXISTS punishments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    username TEXT NOT NULL,
                    type TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    duration TEXT,
                    staff TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                );
                
                CREATE TABLE IF NOT EXISTS player_ips (
                    uuid TEXT NOT NULL,
                    username TEXT NOT NULL,
                    ip TEXT NOT NULL,
                    last_seen DATETIME DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (uuid, ip)
                );
                """;

            try (Statement stmt = database.createStatement()) {
                stmt.executeUpdate(createTables);
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "gmc" -> handleGamemode(player, GameMode.CREATIVE, args);
            case "gmsp" -> handleGamemode(player, GameMode.SPECTATOR, args);
            case "gms" -> handleGamemode(player, GameMode.SURVIVAL, args);
            case "fly" -> handleFly(player, args);
            case "god" -> handleGod(player, args, true);
            case "ungod" -> handleGod(player, args, false);
            case "v" -> handleVanish(player, args, true);
            case "vanish" -> handleVanish(player, args, false);
            case "tp" -> handleTeleport(player, args);
            case "tphere" -> handleTeleportHere(player, args);
            case "otp" -> handleOfflineTeleport(player, args);
            case "history" -> handleHistory(player, args);
            case "tempmute" -> handleTempMute(player, args);
            case "tempban" -> handleTempBan(player, args);
            case "alts" -> handleAlts(player, args);
            case "invsee" -> handleInvSee(player, args);
            case "media" -> handleMedia(player, args);
            case "staff" -> handleStaff(player, args);
            case "unban" -> handleUnban(player, args);
            case "unmute" -> handleUnmute(player, args);
            case "banip" -> handleBanIP(player, args);
            case "punish" -> handlePunish(player, args);
        }
        return true;
    }

    private void handleGamemode(Player player, GameMode mode, String[] args) {
        if (args.length == 0) {
            player.setGameMode(mode);
            String key = mode.name().toLowerCase().startsWith("c") ? "gmc_self" :
                    mode.name().toLowerCase().startsWith("sp") ? "gmsp_self" : "gms_self";
            sendMessage(player, key);
        } else {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) {
                sendMessage(player, "player_not_found");
                return;
            }
            target.setGameMode(mode);
            String key = mode.name().toLowerCase().startsWith("c") ? "gmc_other" :
                    mode.name().toLowerCase().startsWith("sp") ? "gmsp_other" : "gms_other";
            sendMessage(player, key, target.getName());
        }
    }

    private void handleFly(Player player, String[] args) {
        if (args.length == 0) {
            boolean flying = !player.getAllowFlight();
            player.setAllowFlight(flying);
            player.setFlying(flying);
            sendMessage(player, flying ? "fly_enabled" : "fly_disabled");
        } else {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) {
                sendMessage(player, "player_not_found");
                return;
            }
            boolean flying = !target.getAllowFlight();
            target.setAllowFlight(flying);
            target.setFlying(flying);
            sendMessage(player, flying ? "fly_enabled_other" : "fly_disabled_other", target.getName());
        }
    }

    private void handleGod(Player player, String[] args, boolean enable) {
        if (args.length == 0) {
            if (enable) {
                godPlayers.add(player.getUniqueId());
                sendMessage(player, "god_enabled");
            } else {
                godPlayers.remove(player.getUniqueId());
                sendMessage(player, "god_disabled");
            }
        } else {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) {
                sendMessage(player, "player_not_found");
                return;
            }
            if (enable) {
                godPlayers.add(target.getUniqueId());
                sendMessage(player, "god_enabled_other", target.getName());
            } else {
                godPlayers.remove(target.getUniqueId());
                sendMessage(player, "god_disabled_other", target.getName());
            }
        }
    }

    private void handleVanish(Player player, String[] args, boolean spectator) {
        if (args.length == 0) {
            boolean vanished = vanishedPlayers.contains(player.getUniqueId());
            if (vanished) {
                vanishedPlayers.remove(player.getUniqueId());
                for (Player p : getServer().getOnlinePlayers()) {
                    p.showPlayer(this, player);
                }
                sendMessage(player, "vanish_disabled");
            } else {
                vanishedPlayers.add(player.getUniqueId());
                if (spectator) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                for (Player p : getServer().getOnlinePlayers()) {
                    p.hidePlayer(this, player);
                }
                sendMessage(player, "vanish_enabled");
            }
        } else {
            Player target = getServer().getPlayer(args[0]);
            if (target == null) {
                sendMessage(player, "player_not_found");
                return;
            }
            boolean vanished = vanishedPlayers.contains(target.getUniqueId());
            if (vanished) {
                vanishedPlayers.remove(target.getUniqueId());
                for (Player p : getServer().getOnlinePlayers()) {
                    p.showPlayer(this, target);
                }
                sendMessage(player, "vanish_disabled_other", target.getName());
            } else {
                vanishedPlayers.add(target.getUniqueId());
                if (spectator) {
                    target.setGameMode(GameMode.SPECTATOR);
                }
                for (Player p : getServer().getOnlinePlayers()) {
                    p.hidePlayer(this, target);
                }
                sendMessage(player, "vanish_enabled_other", target.getName());
            }
        }
    }

    private void handleTeleport(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/tp <player>");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            sendMessage(player, "player_not_found");
            return;
        }
        player.teleport(target.getLocation());
        sendMessage(player, "tp_success", target.getName());
    }

    private void handleTeleportHere(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/tphere <player>");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            sendMessage(player, "player_not_found");
            return;
        }
        target.teleport(player.getLocation());
        sendMessage(player, "tphere_success", target.getName());
    }

    private void handleOfflineTeleport(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/otp <player>");
            return;
        }
        OfflinePlayer target = getServer().getOfflinePlayer(args[0]);
        Location loc = lastLocations.get(target.getUniqueId());
        if (loc == null) {
            sendMessage(player, "otp_no_data", target.getName());
            return;
        }
        player.teleport(loc);
        sendMessage(player, "otp_success", target.getName());
    }

    private void handleTempMute(Player player, String[] args) {
        if (args.length < 3) {
            sendMessage(player, "command_usage", "/tempmute <player> <time> <reason>");
            return;
        }

        OfflinePlayer target = getServer().getOfflinePlayer(args[0]);
        long duration = parseDuration(args[1]);
        if (duration == -1) {
            sendMessage(player, "invalid_duration");
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        long expiry = System.currentTimeMillis() + duration;

        mutedPlayers.put(target.getUniqueId(), expiry);
        logPunishment(target.getUniqueId(), target.getName(), "TEMPMUTE", reason, formatDuration(duration), player.getName());

        sendMessage(player, "tempmute_success", target.getName(), formatDuration(duration), reason);
    }

    private void handleTempBan(Player player, String[] args) {
        if (args.length < 3) {
            sendMessage(player, "command_usage", "/tempban <player> <time> <reason>");
            return;
        }

        OfflinePlayer target = getServer().getOfflinePlayer(args[0]);
        long duration = parseDuration(args[1]);
        if (duration == -1) {
            sendMessage(player, "invalid_duration");
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        long expiry = System.currentTimeMillis() + duration;

        bannedPlayers.put(target.getUniqueId(), new BanInfo(reason, expiry, false));
        logPunishment(target.getUniqueId(), target.getName(), "TEMPBAN", reason, formatDuration(duration), player.getName());

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null) {
            dropItemsAndKick(onlineTarget, reason, expiry);
        }

        sendMessage(player, "tempban_success", target.getName(), formatDuration(duration), reason);
    }

    private void handleHistory(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/history <player>");
            return;
        }

        if (database == null) {
            player.sendMessage("§cDatabase is disabled.");
            return;
        }

        String targetName = args[0];
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement stmt = database.prepareStatement(
                    "SELECT type, reason, duration, staff, timestamp FROM punishments WHERE username = ? ORDER BY timestamp DESC LIMIT 10")) {
                stmt.setString(1, targetName);
                ResultSet rs = stmt.executeQuery();

                List<String> history = new ArrayList<>();
                while (rs.next()) {
                    String entry = config.getString("messages.history_entry", "")
                            .replace("{date}", rs.getString("timestamp"))
                            .replace("{type}", rs.getString("type"))
                            .replace("{duration}", rs.getString("duration") != null ? rs.getString("duration") : "Permanent")
                            .replace("{reason}", rs.getString("reason"));
                    history.add(entry);
                }

                getServer().getScheduler().runTask(this, () -> {
                    String header = config.getString("messages.history_header", "").replace("{player}", targetName);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(header));

                    if (history.isEmpty()) {
                        String empty = config.getString("messages.history_empty", "").replace("{player}", targetName);
                        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(empty));
                    } else {
                        for (String entry : history) {
                            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(entry));
                        }
                    }
                });
            } catch (SQLException e) {
                getLogger().warning("Error fetching punishment history: " + e.getMessage());
            }
        });
    }

    private void handleAlts(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/alts <player>");
            return;
        }

        if (database == null) {
            player.sendMessage("§cDatabase is disabled.");
            return;
        }

        String targetName = args[0];
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String targetUUID = null;
                String targetIP = null;

                try (PreparedStatement stmt = database.prepareStatement("SELECT uuid, ip FROM player_ips WHERE username = ? LIMIT 1")) {
                    stmt.setString(1, targetName);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        targetUUID = rs.getString("uuid");
                        targetIP = rs.getString("ip");
                    }
                }

                if (targetUUID == null) {
                    getServer().getScheduler().runTask(this, () -> sendMessage(player, "player_not_found"));
                    return;
                }

                List<String> alts = new ArrayList<>();
                try (PreparedStatement stmt = database.prepareStatement(
                        "SELECT DISTINCT username, ip FROM player_ips WHERE ip = ? AND uuid != ?")) {
                    stmt.setString(1, targetIP);
                    stmt.setString(2, targetUUID);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        String entry = config.getString("messages.alts_entry", "")
                                .replace("{name}", rs.getString("username"))
                                .replace("{ip}", rs.getString("ip"));
                        alts.add(entry);
                    }
                }

                getServer().getScheduler().runTask(this, () -> {
                    String header = config.getString("messages.alts_header", "").replace("{player}", targetName);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(header));

                    if (alts.isEmpty()) {
                        String none = config.getString("messages.alts_none", "").replace("{player}", targetName);
                        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(none));
                    } else {
                        for (String alt : alts) {
                            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(alt));
                        }
                    }
                });
            } catch (SQLException e) {
                getLogger().warning("Error fetching alt accounts: " + e.getMessage());
            }
        });
    }

    private void handleInvSee(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/invsee <player>");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            sendMessage(player, "player_not_found");
            return;
        }
        player.openInventory(target.getInventory());
        sendMessage(player, "invsee_opened", target.getName());
    }

    private void handleMedia(Player player, String[] args) {
        if (args.length < 2) {
            sendMessage(player, "command_usage", "/media <add/remove> <player>");
            return;
        }

        String action = args[0].toLowerCase();
        OfflinePlayer target = getServer().getOfflinePlayer(args[1]);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            User user = luckPerms.getUserManager().loadUser(target.getUniqueId()).join();

            if ("add".equals(action)) {
                Node mediaNode = Node.builder("group.media")
                        .expiry(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(56))
                        .build();
                user.data().add(mediaNode);
                luckPerms.getUserManager().saveUser(user);

                getServer().getScheduler().runTask(this, () ->
                        sendMessage(player, "media_added", target.getName()));
            } else if ("remove".equals(action)) {
                user.data().clear(node -> node.getKey().equals("group.media"));
                luckPerms.getUserManager().saveUser(user);

                getServer().getScheduler().runTask(this, () ->
                        sendMessage(player, "media_removed", target.getName()));
            }
        });
    }

    private void handleStaff(Player player, String[] args) {
        if (args.length < 2) {
            sendMessage(player, "command_usage", "/staff <add/promote/remove> <player>");
            return;
        }

        String action = args[0].toLowerCase();
        OfflinePlayer target = getServer().getOfflinePlayer(args[1]);
        List<String> hierarchy = config.getStringList("staff_ranks.hierarchy");

        if (hierarchy.isEmpty()) {
            player.sendMessage("§cNo staff hierarchy configured.");
            return;
        }

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            User user = luckPerms.getUserManager().loadUser(target.getUniqueId()).join();

            switch (action) {
                case "add" -> {
                    String lowestRank = hierarchy.get(0);
                    Node rankNode = Node.builder("group." + lowestRank).build();
                    user.data().add(rankNode);
                    luckPerms.getUserManager().saveUser(user);

                    getServer().getScheduler().runTask(this, () -> {
                        String message = config.getString("staff_ranks.add_message", "")
                                .replace("{player}", target.getName())
                                .replace("{rank}", lowestRank);
                        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
                    });
                }
                case "promote" -> {
                    String currentRank = null;
                    for (String rank : hierarchy) {
                        if (user.getInheritedGroups(user.getQueryOptions()).contains(luckPerms.getGroupManager().getGroup(rank))) {
                            currentRank = rank;
                            break;
                        }
                    }

                    if (currentRank == null) {
                        getServer().getScheduler().runTask(this, () ->
                                player.sendMessage("§c" + target.getName() + " is not staff."));
                        return;
                    }

                    int currentIndex = hierarchy.indexOf(currentRank);
                    if (currentIndex == hierarchy.size() - 1) {
                        getServer().getScheduler().runTask(this, () ->
                                player.sendMessage("§c" + target.getName() + " is already at the highest rank."));
                        return;
                    }

                    final String oldRank = currentRank;
                    String newRank = hierarchy.get(currentIndex + 1);
                    user.data().clear(node -> node.getKey().equals("group." + oldRank));
                    user.data().add(Node.builder("group." + newRank).build());
                    luckPerms.getUserManager().saveUser(user);

                    getServer().getScheduler().runTask(this, () -> {
                        String message = config.getString("staff_ranks.promote_message", "")
                                .replace("{player}", target.getName())
                                .replace("{rank}", newRank);
                        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
                    });
                }
                case "remove" -> {
                    for (String rank : hierarchy) {
                        user.data().clear(node -> node.getKey().equals("group." + rank));
                    }
                    luckPerms.getUserManager().saveUser(user);

                    getServer().getScheduler().runTask(this, () -> {
                        String message = config.getString("staff_ranks.remove_message", "")
                                .replace("{player}", target.getName());
                        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
                    });
                }
            }
        });
    }

    private void handleUnban(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/unban <player>");
            return;
        }

        OfflinePlayer target = getServer().getOfflinePlayer(args[0]);
        bannedPlayers.remove(target.getUniqueId());
        sendMessage(player, "unban_success", target.getName());
    }

    private void handleUnmute(Player player, String[] args) {
        if (args.length == 0) {
            sendMessage(player, "command_usage", "/unmute <player>");
            return;
        }

        OfflinePlayer target = getServer().getOfflinePlayer(args[0]);
        mutedPlayers.remove(target.getUniqueId());
        sendMessage(player, "unmute_success", target.getName());
    }

    private void handleBanIP(Player player, String[] args) {
        if (args.length < 2) {
            sendMessage(player, "command_usage", "/banip <player> <reason>");
            return;
        }

        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            sendMessage(player, "player_not_found");
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String ip = target.getAddress().getAddress().getHostAddress();

        bannedIPs.add(ip);
        bannedPlayers.put(target.getUniqueId(), new BanInfo(reason, -1, true));

        logPunishment(target.getUniqueId(), target.getName(), "IPBAN", reason, "Permanent", player.getName());
        dropItemsAndKick(target, reason, -1);

        sendMessage(player, "banip_success", target.getName(), reason);
    }

    private void handlePunish(Player player, String[] args) {
        if (args.length < 2) {
            sendMessage(player, "command_usage", "/punish <player> <preset>");
            return;
        }

        OfflinePlayer target = getServer().getOfflinePlayer(args[0]);
        String presetKey = args[1];

        String presetPath = "punish_presets." + presetKey;
        if (!config.contains(presetPath)) {
            sendMessage(player, "invalid_preset");
            return;
        }

        String reason = config.getString(presetPath + ".reason");
        String type = config.getString(presetPath + ".type");
        String duration = config.getString(presetPath + ".duration");

        if ("tempmute".equals(type)) {
            long durationMs = parseDuration(duration);
            if (durationMs != -1) {
                mutedPlayers.put(target.getUniqueId(), System.currentTimeMillis() + durationMs);
                logPunishment(target.getUniqueId(), target.getName(), "TEMPMUTE", reason, duration, player.getName());
                sendMessage(player, "tempmute_success", target.getName(), duration, reason);
            }
        } else if ("tempban".equals(type)) {
            long durationMs = parseDuration(duration);
            if (durationMs != -1) {
                bannedPlayers.put(target.getUniqueId(), new BanInfo(reason, System.currentTimeMillis() + durationMs, false));
                logPunishment(target.getUniqueId(), target.getName(), "TEMPBAN", reason, duration, player.getName());

                Player onlineTarget = target.getPlayer();
                if (onlineTarget != null) {
                    dropItemsAndKick(onlineTarget, reason, System.currentTimeMillis() + durationMs);
                }

                sendMessage(player, "tempban_success", target.getName(), duration, reason);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress().getAddress().getHostAddress();

        if (database != null) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try (PreparedStatement stmt = database.prepareStatement(
                        "INSERT OR REPLACE INTO player_ips (uuid, username, ip, last_seen) VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, player.getName());
                    stmt.setString(3, ip);
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    getLogger().warning("Error saving player IP: " + e.getMessage());
                }
            });
        }

        for (UUID vanished : vanishedPlayers) {
            Player vanishedPlayer = getServer().getPlayer(vanished);
            if (vanishedPlayer != null) {
                player.hidePlayer(this, vanishedPlayer);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastLocations.put(player.getUniqueId(), player.getLocation());
    }

    @EventHandler
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        if (bannedIPs.contains(ip)) {
            BanInfo info = new BanInfo("IP Ban", -1, true);
            String message = formatBanMessage(info, event.getName());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);
            return;
        }

        BanInfo banInfo = bannedPlayers.get(uuid);
        if (banInfo != null) {
            if (banInfo.expiry == -1 || System.currentTimeMillis() < banInfo.expiry) {
                String message = formatBanMessage(banInfo, event.getName());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);

                getServer().getScheduler().runTask(this, () -> {
                    String alertMessage = config.getString("ban_messages.ban_attempt", "")
                            .replace("{player}", event.getName())
                            .replace("{reason}", banInfo.reason);

                    for (Player staff : getServer().getOnlinePlayers()) {
                        if (staff.hasPermission("ultimatestaff.alerts")) {
                            staff.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(alertMessage));
                        }
                    }
                });
            } else {
                bannedPlayers.remove(uuid);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (godPlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    private void dropItemsAndKick(Player player, String reason, long expiry) {
        Location loc = player.getLocation();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                loc.getWorld().dropItemNaturally(loc, item);
            }
        }
        player.getInventory().clear();
        player.setHealth(0);

        getServer().getScheduler().runTaskLater(this, () -> {
            String message = formatBanMessage(new BanInfo(reason, expiry, false), player.getName());
            player.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        }, 1L);
    }

    private String formatBanMessage(BanInfo info, String playerName) {
        String messageKey = info.expiry == -1 ? "permanent_ban" : "temp_ban";
        if (info.isIPBan) messageKey = "ip_ban";

        String message = config.getString("ban_messages." + messageKey, "You are banned!");
        message = message.replace("{reason}", info.reason);

        if (info.expiry != -1) {
            LocalDateTime expiry = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(info.expiry),
                    java.time.ZoneId.systemDefault()
            );
            message = message.replace("{expires}", expiry.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }

        return message;
    }

    private void checkExpiredPunishments() {
        long now = System.currentTimeMillis();

        mutedPlayers.entrySet().removeIf(entry -> entry.getValue() < now);
        bannedPlayers.entrySet().removeIf(entry -> {
            BanInfo info = entry.getValue();
            return info.expiry != -1 && info.expiry < now;
        });
    }

    private long parseDuration(String input) {
        if (input == null || input.isEmpty()) return -1;

        try {
            char unit = input.charAt(input.length() - 1);
            int amount = Integer.parseInt(input.substring(0, input.length() - 1));

            return switch (unit) {
                case 's' -> amount * 1000L;
                case 'm' -> amount * 60 * 1000L;
                case 'h' -> amount * 60 * 60 * 1000L;
                case 'd' -> amount * 24 * 60 * 60 * 1000L;
                case 'w' -> amount * 7 * 24 * 60 * 60 * 1000L;
                default -> -1;
            };
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long weeks = days / 7;

        if (weeks > 0) return weeks + "w";
        if (days > 0) return days + "d";
        if (hours > 0) return hours + "h";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }

    private void logPunishment(UUID uuid, String username, String type, String reason, String duration, String staff) {
        if (database == null) return;

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement stmt = database.prepareStatement(
                    "INSERT INTO punishments (uuid, username, type, reason, duration, staff) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, username);
                stmt.setString(3, type);
                stmt.setString(4, reason);
                stmt.setString(5, duration);
                stmt.setString(6, staff);
                stmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("Error logging punishment: " + e.getMessage());
            }
        });
    }

    private void sendMessage(Player player, String key, String... replacements) {
        String message = config.getString("messages." + key, "§cMessage not found: " + key);

        for (int i = 0; i < replacements.length; i++) {
            String placeholder = switch (i) {
                case 0 -> "{player}";
                case 1 -> "{duration}";
                case 2 -> "{reason}";
                case 3 -> "{usage}";
                default -> "{arg" + i + "}";
            };
            message = message.replace(placeholder, replacements[i]);
        }

        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    private static class BanInfo {
        final String reason;
        final long expiry;
        final boolean isIPBan;

        BanInfo(String reason, long expiry, boolean isIPBan) {
            this.reason = reason;
            this.expiry = expiry;
            this.isIPBan = isIPBan;
        }
    }}
