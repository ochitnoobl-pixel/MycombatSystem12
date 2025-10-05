package me.yourname.customabilities;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.UUID;

public class CustomAbilities extends JavaPlugin implements Listener {

    private final HashMap<UUID, Integer> swordHits = new HashMap<>();
    private final HashMap<UUID, Integer> axeHits = new HashMap<>();
    private final HashMap<UUID, Integer> bowHits = new HashMap<>();
    private final HashMap<UUID, UUID> lastTarget = new HashMap<>();
    private final HashMap<UUID, Long> stunnedUntil = new HashMap<>();
    private final HashMap<UUID, Long> lastHitTime = new HashMap<>();

    // Sword crit mode state and expire tasks
    private final HashMap<UUID, Boolean> swordCrit = new HashMap<>();
    private final HashMap<UUID, BukkitTask> swordCritExpireTask = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig(); // load config.yml
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MyCombatSystem enabled!");
    }

    @Override
    public void onDisable() {
        // cancel any scheduled tasks we've stored
        for (BukkitTask t : swordCritExpireTask.values()) {
            if (t != null) t.cancel();
        }
        getLogger().info("MyCombatSystem disabled!");
    }

    // Handle all damage events
    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity target = event.getEntity();

        if (!(target instanceof LivingEntity victim)) return;

        long now = System.currentTimeMillis();

        // Melee hits
        if (damager instanceof Player player) {
            // Update last hit time for miss-detection
            lastHitTime.put(player.getUniqueId(), now);

            Material weapon = player.getInventory().getItemInMainHand().getType();

            if (weapon.name().endsWith("_SWORD")) {
                handleSwordHit(player, victim, event);
            } else if (weapon.name().endsWith("_AXE")) {
                handleAxeHit(player, victim, event);
            }
        }

        // Bow hits (arrow)
        if (damager instanceof Arrow arrow && arrow.getShooter() instanceof Player player) {
            // Update last hit time for player (counts as a successful shot)
            lastHitTime.put(player.getUniqueId(), now);
            handleBowHit(player, victim, event);
        }
    }

    private void handleSwordHit(Player player, LivingEntity target, EntityDamageByEntityEvent event) {
        UUID id = player.getUniqueId();
        UUID targetId = target.getUniqueId();

        int inactivitySec = getConfig().getInt("sword.inactivity_time", 10);

        // If target changed, reset combo (but don't exit crit mode automatically)
        if (lastTarget.containsKey(id) && !lastTarget.get(id).equals(targetId)) {
            swordHits.put(id, 0);
        }

        // If already in crit mode, apply crit and extend expire timer
        if (swordCrit.getOrDefault(id, false)) {
            double mult = getConfig().getDouble("sword.crit_multiplier", 1.5);
            event.setDamage(event.getDamage() * mult);
            player.sendMessage(ChatColor.GOLD + "⚔ Critical Hit (combo)!");
            // reschedule expire
            scheduleSwordCritExpire(id, inactivitySec);
            return;
        }

        int count = swordHits.getOrDefault(id, 0) + 1;
        swordHits.put(id, count);
        lastTarget.put(id, targetId);

        if (count >= 3) {
            // Enter crit mode: from now on hits are critical until miss or inactivity
            swordCrit.put(id, true);
            double mult = getConfig().getDouble("sword.crit_multiplier", 1.5);
            event.setDamage(event.getDamage() * mult);
            player.sendMessage(ChatColor.GOLD + "⚔ Critical Mode Activated!");
            scheduleSwordCritExpire(id, inactivitySec);
            // reset combo counter (we keep crit mode on)
            swordHits.put(id, 0);
        }
    }

    private void scheduleSwordCritExpire(UUID playerId, int inactivitySec) {
        // cancel old task
        BukkitTask old = swordCritExpireTask.get(playerId);
        if (old != null) old.cancel();

        // schedule new expire in inactivitySec*20 ticks
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            swordCrit.remove(playerId);
            swordCritExpireTask.remove(playerId);
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                p.sendMessage(ChatColor.GRAY + "Critical mode expired due to inactivity.");
            }
        }, inactivitySec * 20L);

        swordCritExpireTask.put(playerId, task);
    }

    private void handleAxeHit(Player player, LivingEntity target, EntityDamageByEntityEvent event) {
        UUID id = player.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (lastTarget.containsKey(id) && !lastTarget.get(id).equals(targetId))
            axeHits.put(id, 0);

        int count = axeHits.getOrDefault(id, 0) + 1;
        axeHits.put(id, count);
        lastTarget.put(id, targetId);

        if (count >= 3) {
            int stunSec = getConfig().getInt("axe.stun_time", 2);
            player.sendMessage(ChatColor.RED + "🪓 Stun Strike!");
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, stunSec * 20, 255)); // no movement
            target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, stunSec * 20, 200)); // can't jump

            if (target instanceof Player stunnedPlayer) {
                stunnedUntil.put(stunnedPlayer.getUniqueId(), System.currentTimeMillis() + (stunSec * 1000));
                stunnedPlayer.sendMessage(ChatColor.GRAY + "You are stunned!");
            }

            // Visual cue
            target.getWorld().strikeLightningEffect(target.getLocation());

            axeHits.put(id, 0);
        }
    }

    private void handleBowHit(Player player, LivingEntity target, EntityDamageByEntityEvent event) {
        UUID id = player.getUniqueId();
        int count = bowHits.getOrDefault(id, 0) + 1;
        bowHits.put(id, count);

        if (count >= 3) {
            double mult = getConfig().getDouble("bow.damage_multiplier", 2.0);
            int buffDur = getConfig().getInt("bow.buff_duration", 3);
            int speedLvl = getConfig().getInt("bow.speed_level", 3);
            int strLvl = getConfig().getInt("bow.strength_level", 2);

            event.setDamage(event.getDamage() * mult);
            // Give short temporary buffs: Speed (amplifier speedLvl-1) and Strength (amplifier strLvl-1)
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, buffDur * 20, Math.max(0, speedLvl-1)));
            player.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, buffDur * 20, Math.max(0, strLvl-1)));
            player.sendMessage(ChatColor.AQUA + "🏹 Power Shot! Double damage + short buff");
            bowHits.put(id, 0);
        }
    }

    // Prevent stunned players from eating or using items
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Long stunTime = stunnedUntil.get(player.getUniqueId());
        if (stunTime != null && System.currentTimeMillis() < stunTime) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.DARK_GRAY + "You are stunned!");
        }
    }

    // Detect swings that don't hit (misses) and reset combos / crit mode
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Schedule a short delay to see if a hit was registered
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Long lastHit = lastHitTime.get(id);
            int missMs = getConfig().getInt("general.combo_reset_on_miss_ms", 200);
            // if no hit in the last missMs, consider it a miss
            if (lastHit == null || (System.currentTimeMillis() - lastHit) > missMs) {
                swordHits.put(id, 0);
                axeHits.put(id, 0);
                bowHits.put(id, 0);
                // exiting critical mode on miss
                if (swordCrit.getOrDefault(id, false)) {
                    swordCrit.remove(id);
                    BukkitTask t = swordCritExpireTask.remove(id);
                    if (t != null) t.cancel();
                    player.sendMessage(ChatColor.GRAY + "Critical mode ended (you missed).");
                }
            } else {
                // successful hit recently — if in crit mode, reschedule expire to full inactivitySec
                if (swordCrit.getOrDefault(id, false)) {
                    int inactivitySec = getConfig().getInt("sword.inactivity_time", 10);
                    scheduleSwordCritExpire(id, inactivitySec);
                }
            }
        }, 2L);
    }

    // Cleanup on quit
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        swordHits.remove(id);
        axeHits.remove(id);
        bowHits.remove(id);
        lastTarget.remove(id);
        stunnedUntil.remove(id);
        lastHitTime.remove(id);

        BukkitTask t = swordCritExpireTask.remove(id);
        if (t != null) t.cancel();
        swordCrit.remove(id);
    }
}
