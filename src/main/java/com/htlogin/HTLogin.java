package com.htlogin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class HTLogin extends JavaPlugin implements Listener, CommandExecutor {

    private File dataFile;
    private FileConfiguration dataConfig;
    private FileConfiguration langConfig;
    private final Map<UUID, Boolean> loggedIn = new HashMap<>();
    private final Map<UUID, Long> joinTime = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        loadLanguage();
        getServer().getPluginManager().registerEvents(this, this);
        
        String[] cmds = {"gir", "kayıt", "logout", "changepassword"};
        for (String label : cmds) {
            if (getCommand(label) != null) getCommand(label).setExecutor(this);
        }
        startActionBar();
    }

    private void loadLanguage() {
        File folder = new File(getDataFolder(), "languages");
        if (!folder.exists()) folder.mkdirs();
        String sel = getConfig().getString("settings.language", "tr");
        File f = new File(folder, sel + ".yml");
        if (!f.exists()) saveResource("languages/" + sel + ".yml", false);
        langConfig = YamlConfiguration.loadConfiguration(f);
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
                    int timeout = getConfig().getInt("settings.login-timeout-seconds", 60);
                    long left = timeout - ((System.currentTimeMillis() - joinTime.getOrDefault(p.getUniqueId(), System.currentTimeMillis())) / 1000);
                    if (left <= 0) {
                        Bukkit.getScheduler().runTask(HTLogin.this, () -> p.kickPlayer(msg("kick-timeout")));
                        continue;
                    }
                    boolean reg = dataConfig.contains("passwords." + p.getUniqueId());
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg(reg ? "action-bar-login" : "action-bar-register").replace("%time%", String.valueOf(left))));
                }
            }
        }.runTaskTimer(this, 0, 20);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        joinTime.put(p.getUniqueId(), System.currentTimeMillis());
        loggedIn.put(p.getUniqueId(), false);

        // --- GELİŞMİŞ AUTO LOGIN (Kalıcı IP Kontrolü) ---
        String currentIP = p.getAddress().getAddress().getHostAddress();
        String savedIP = dataConfig.getString("sessions." + p.getUniqueId());
        
        if (savedIP != null && savedIP.equals(currentIP)) {
            loggedIn.put(p.getUniqueId(), true);
            p.sendMessage(msg("login-success"));
        }
    }

    // --- TÜM KOMUTLARI ENGELLEME (Giriş yapmadan komut yasak) ---
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandProcess(PlayerCommandPreprocessEvent e) {
        if (!loggedIn.getOrDefault(e.getPlayer().getUniqueId(), false)) {
            String cmd = e.getMessage().toLowerCase();
            if (!cmd.startsWith("/gir") && !cmd.startsWith("/login") && !cmd.startsWith("/kayıt") && !cmd.startsWith("/register")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(msg("must-login"));
            }
        }
    }

    @EventHandler public void onMove(PlayerMoveEvent e) { 
        if (!loggedIn.getOrDefault(e.getPlayer().getUniqueId(), false)) {
            // Sadece kafa çevirmeyi değil, milimetrik her hareketi bloklar
            if (e.getFrom().getX() != e.getTo().getX() || e.getFrom().getY() != e.getTo().getY() || e.getFrom().getZ() != e.getTo().getZ() || e.getFrom().getYaw() != e.getTo().getYaw()) {
                e.setTo(e.getFrom()); 
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        UUID id = p.getUniqueId();

        if (c.getName().equalsIgnoreCase("gir")) {
            String pass = dataConfig.getString("passwords." + id);
            if (pass != null && a.length > 0 && pass.equals(hash(a[0]))) {
                loggedIn.put(id, true);
                dataConfig.set("sessions." + id, p.getAddress().getAddress().getHostAddress()); // IP'yi kaydet
                saveData();
                p.sendMessage(msg("login-success"));
            } else p.sendMessage(msg("wrong-password-generic"));
        }
        
        if (c.getName().equalsIgnoreCase("kayıt") && a.length > 1 && a[0].equals(a[1])) {
            dataConfig.set("passwords." + id, hash(a[0]));
            dataConfig.set("sessions." + id, p.getAddress().getAddress().getHostAddress()); // IP'yi kaydet
            saveData();
            loggedIn.put(id, true);
            p.sendMessage(msg("register-success"));
        }
        
        if (c.getName().equalsIgnoreCase("logout")) {
            loggedIn.put(id, false);
            dataConfig.set("sessions." + id, null); // IP oturumunu sil
            saveData();
            p.sendMessage(msg("logout-success"));
        }
        return true;
    }

    private String hash(String p) {
        try {
            MessageDigest m = MessageDigest.getInstance("SHA-256");
            byte[] h = m.digest(p.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return p; }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) { try { dataFile.createNewFile(); } catch (IOException ignored) {} }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() { try { dataConfig.save(dataFile); } catch (IOException ignored) {} }
    }
