package com.htlogin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class HTLogin extends JavaPlugin implements Listener, CommandExecutor {

    private File dataFile;
    private FileConfiguration dataConfig;
    private File langFile;
    private FileConfiguration langConfig;

    private final Map<UUID, Boolean> loggedInPlayers = new HashMap<>();
    private final Map<UUID, Integer> loginAttempts = new HashMap<>();
    private final Map<UUID, Long> joinTime = new HashMap<>();
    private final Map<UUID, String> sessionIPs = new HashMap<>();
    private final Map<UUID, Long> sessionTimes = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        loadLanguage(); // Dil sistemini başlat

        getServer().getPluginManager().registerEvents(this, this);

        String[] cmds = {"gir", "login", "kayıt", "register", "logout", "changepassword"};
        for (String cmd : cmds) {
            if (getCommand(cmd) != null) getCommand(cmd).setExecutor(this);
        }

        startActionBarTask();
        getLogger().info("HT-Login aktif edildi! Sürüm: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void startActionBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isLoggedIn(p)) {
                        long joined = joinTime.getOrDefault(p.getUniqueId(), System.currentTimeMillis());
                        long timePassed = (System.currentTimeMillis() - joined) / 1000;
                        int timeout = getConfig().getInt("settings.login-timeout-seconds", 60);
                        long timeLeft = timeout - timePassed;

                        if (timeLeft <= 0) {
                            Bukkit.getScheduler().runTask(HTLogin.this, () -> p.kickPlayer(msg("kick-timeout")));
                            continue;
                        }

                        // Geri sayım sesi
                        try {
                            String s = getConfig().getString("sounds.count-down");
                            if (s != null && !s.isEmpty()) p.playSound(p.getLocation(), Sound.valueOf(s), 0.5f, 2f);
                        } catch (Exception ignored) {}

                        boolean isRegistered = dataConfig.contains("passwords." + p.getUniqueId());
                        String msgKey = isRegistered ? "action-bar-login" : "action-bar-register";
                        String message = msg(msgKey).replace("%time%", String.valueOf(timeLeft));

                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private boolean isLoggedIn(Player p) {
        return loggedInPlayers.getOrDefault(p.getUniqueId(), false);
    }

    private void setLoggedIn(Player p, boolean status) {
        loggedInPlayers.put(p.getUniqueId(), status);
        if (status) {
            joinTime.remove(p.getUniqueId());
            sessionIPs.put(p.getUniqueId(), p.getAddress().getAddress().getHostAddress());
            sessionTimes.put(p.getUniqueId(), System.currentTimeMillis() + (getConfig().getInt("settings.session-duration-minutes") * 60000L));
        }
    }

    private String msg(String key) {
        if (langConfig == null) return "Lang Error: " + key;
        String val = langConfig.getString(key);
        return val != null ? ChatColor.translateAlternateColorCodes('&', val) : key;
    }

    private String hash(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return password;
        }
    }

    private void loadLanguage() {
        File langFolder = new File(getDataFolder(), "languages");
        if (!langFolder.exists()) langFolder.mkdirs();

        // Tüm dilleri dışarı aktar
        String[] langs = {"tr", "en", "de", "es", "ru", "ja", "ar", "fr", "pt", "zh", "it", "az"};
        for (String l : langs) {
            File f = new File(langFolder, l + ".yml");
            if (!f.exists()) saveResource("languages/" + l + ".yml", false);
        }

        String selected = getConfig().getString("settings.language", "tr");
        langFile = new File(langFolder, selected + ".yml");
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    // --- Events (Katı Kilit Sistemi) ---

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loggedInPlayers.put(p.getUniqueId(), false);
        joinTime.put(p.getUniqueId(), System.currentTimeMillis());
        loginAttempts.put(p.getUniqueId(), 0);

        // Bedrock Auto Login
        if (getConfig().getBoolean("settings.bedrock-auto-login")) {
            for (String prefix : getConfig().getStringList("settings.bedrock-prefixes")) {
                if (p.getName().startsWith(prefix)) {
                    setLoggedIn(p, true);
                    p.sendMessage(msg("bedrock-auto"));
                    return;
                }
            }
        }

        // IP Session Auto Login
        if (sessionIPs.containsKey(p.getUniqueId())) {
            String currentIP = p.getAddress().getAddress().getHostAddress();
            if (sessionIPs.get(p.getUniqueId()).equals(currentIP) && System.currentTimeMillis() < sessionTimes.getOrDefault(p.getUniqueId(), 0L)) {
                setLoggedIn(p, true);
                p.sendMessage(msg("login-success"));
                return;
            }
        }
        playSound(p, "sounds.join");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!isLoggedIn(e.getPlayer())) {
            // Sadece titremeyi değil, kafa çevirmeyi de engeller
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ() ||
                e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()) {
                e.setTo(e.getFrom());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!isLoggedIn(e.getPlayer())) {
            String cmd = e.getMessage().toLowerCase().split(" ")[0];
            List<String> allowed = Arrays.asList("/gir", "/login", "/l", "/kayıt", "/register", "/reg");
            if (!allowed.contains(cmd)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(msg("must-login"));
            }
        }
    }

    // Engelleyici Eventler
    @EventHandler public void onChat(AsyncPlayerChatEvent e) { if (!isLoggedIn(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { if (!isLoggedIn(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!isLoggedIn(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!isLoggedIn(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player && !isLoggedIn((Player) e.getEntity())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player && !isLoggedIn((Player) e.getDamager())) e.setCancelled(true); }

    // --- Commands ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("gir") || cmd.equals("login")) {
            if (isLoggedIn(p)) { p.sendMessage(msg("already-logged-in")); return true; }
            if (args.length != 1) { p.sendMessage(msg("usage-login")); return true; }
            if (!dataConfig.contains("passwords." + p.getUniqueId())) { p.sendMessage(msg("not-registered")); return true; }

            if (hash(args[0]).equals(dataConfig.getString("passwords." + p.getUniqueId()))) {
                setLoggedIn(p, true);
                p.sendMessage(msg("login-success"));
                playSound(p, "sounds.login-success");
            } else {
                handleFail(p);
            }
            return true;
        }

        if (cmd.equals("kayıt") || cmd.equals("register")) {
            if (isLoggedIn(p)) { p.sendMessage(msg("already-logged-in")); return true; }
            if (dataConfig.contains("passwords." + p.getUniqueId())) { p.sendMessage(msg("already-registered")); return true; }
            if (args.length != 2) { p.sendMessage(msg("usage-register")); return true; }
            if (!args[0].equals(args[1])) { p.sendMessage(msg("passwords-dont-match")); return true; }
            
            int min = getConfig().getInt("settings.min-password-length", 4);
            int max = getConfig().getInt("settings.max-password-length", 16);
            if (args[0].length() < min) { p.sendMessage(msg("password-too-short")); return true; }
            if (args[0].length() > max) { p.sendMessage(msg("password-too-long")); return true; }
            if (getConfig().getStringList("settings.banned-passwords").contains(args[0])) { p.sendMessage(msg("banned-password")); return true; }

            dataConfig.set("passwords." + p.getUniqueId(), hash(args[0]));
            saveData();
            setLoggedIn(p, true);
            p.sendMessage(msg("register-success"));
            playSound(p, "sounds.login-success");
            return true;
        }

        if (cmd.equals("logout")) {
            if (!isLoggedIn(p)) return true;
            loggedInPlayers.put(p.getUniqueId(), false);
            sessionIPs.remove(p.getUniqueId());
            joinTime.put(p.getUniqueId(), System.currentTimeMillis());
            p.sendMessage(msg("logout-success"));
            return true;
        }

        if (cmd.equals("changepassword")) {
            if (!isLoggedIn(p)) { p.sendMessage(msg("must-login")); return true; }
            if (args.length != 2) { p.sendMessage(msg("usage-changepass")); return true; }
            if (!hash(args[0]).equals(dataConfig.getString("passwords." + p.getUniqueId()))) { p.sendMessage(msg("wrong-password-generic")); return true; }
            
            dataConfig.set("passwords." + p.getUniqueId(), hash(args[1]));
            saveData();
            p.sendMessage(msg("changed-password"));
            return true;
        }
        return true;
    }

    private void handleFail(Player p) {
        int attempts = loginAttempts.getOrDefault(p.getUniqueId(), 0) + 1;
        loginAttempts.put(p.getUniqueId(), attempts);
        int max = getConfig().getInt("settings.max-password-attempts", 3);
        if (attempts >= max) {
            p.kickPlayer(msg("kick-max-attempts"));
        } else {
            p.sendMessage(msg("wrong-password").replace("%attempts%", String.valueOf(max - attempts)));
            playSound(p, "sounds.login-fail");
        }
    }

    private void playSound(Player p, String path) {
        try {
            String s = getConfig().getString(path);
            if (s != null && !s.isEmpty()) p.playSound(p.getLocation(), Sound.valueOf(s), 1f, 1f);
        } catch (Exception ignored) {}
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }
}
