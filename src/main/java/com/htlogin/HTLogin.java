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

    private File dataFile, langFile;
    private FileConfiguration dataConfig, langConfig;
    private final Map<UUID, Boolean> loggedIn = new HashMap<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, Long> joinTime = new HashMap<>();
    private final Map<UUID, String> sessions = new HashMap<>();
    private final Map<UUID, Long> sessionExpiries = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        loadLanguage();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("gir").setExecutor(this);
        getCommand("kayıt").setExecutor(this);
        getCommand("logout").setExecutor(this);
        getCommand("changepassword").setExecutor(this);
        startActionBar();
    }

    private void loadLanguage() {
        File folder = new File(getDataFolder(), "languages");
        if (!folder.exists()) folder.mkdirs();
        String[] langs = {"tr", "en", "de", "es", "ru", "ja", "ar", "fr", "pt", "zh", "it", "az"};
        for (String l : langs) {
            File f = new File(folder, l + ".yml");
            if (!f.exists()) saveResource("languages/" + l + ".yml", false);
        }
        String sel = getConfig().getString("settings.language", "tr");
        langFile = new File(folder, sel + ".yml");
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    private String msg(String k) {
        String s = langConfig.getString(k);
        return s != null ? ChatColor.translateAlternateColorCodes('&', s) : k;
    }

    private void startActionBar() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (loggedIn.getOrDefault(p.getUniqueId(), false)) continue;
                    long left = getConfig().getInt("settings.login-timeout-seconds") - ((System.currentTimeMillis() - joinTime.getOrDefault(p.getUniqueId(), System.currentTimeMillis())) / 1000);
                    if (left <= 0) { p.kickPlayer(msg("kick-timeout")); continue; }
                    String m = msg(dataConfig.contains("passwords." + p.getUniqueId()) ? "action-bar-login" : "action-bar-register").replace("%time%", String.valueOf(left));
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(m));
                }
            }
        }.runTaskTimer(this, 0, 20);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinTime.put(p.getUniqueId(), System.currentTimeMillis());
        loggedIn.put(p.getUniqueId(), false);
        // Session Check
        if (sessions.getOrDefault(p.getUniqueId(), "").equals(p.getAddress().getAddress().getHostAddress()) && System.currentTimeMillis() < sessionExpiries.getOrDefault(p.getUniqueId(), 0L)) {
            loggedIn.put(p.getUniqueId(), true);
            p.sendMessage(msg("login-success"));
        }
    }

    @EventHandler public void onMove(PlayerMoveEvent e) { if (!loggedIn.getOrDefault(e.getPlayer().getUniqueId(), false)) e.setTo(e.getFrom()); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e) { if (!loggedIn.getOrDefault(e.getPlayer().getUniqueId(), false)) e.setCancelled(true); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        if (c.getName().equalsIgnoreCase("gir")) {
            if (a.length == 1 && hash(a[0]).equals(dataConfig.getString("passwords." + p.getUniqueId()))) {
                loggedIn.put(p.getUniqueId(), true);
                sessions.put(p.getUniqueId(), p.getAddress().getAddress().getHostAddress());
                sessionExpiries.put(p.getUniqueId(), System.currentTimeMillis() + 3600000);
                p.sendMessage(msg("login-success"));
            } else { p.sendMessage(msg("wrong-password-generic")); }
        }
        if (c.getName().equalsIgnoreCase("kayıt") && a.length == 2 && a[0].equals(a[1])) {
            dataConfig.set("passwords." + p.getUniqueId(), hash(a[0]));
            saveData();
            loggedIn.put(p.getUniqueId(), true);
            p.sendMessage(msg("register-success"));
        }
        return true;
    }

    private String hash(String p) {
        try {
            MessageDigest m = MessageDigest.getInstance("SHA-256");
            byte[] h = m.digest(p.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return p; }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) { try { dataFile.createNewFile(); } catch (IOException ignored) {} }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() { try { dataConfig.save(dataFile); } catch (IOException ignored) {} }
                  }
