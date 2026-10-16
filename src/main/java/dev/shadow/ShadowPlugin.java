package dev.shadow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ShadowPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, ShadowSession> sessions = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> stepCooldownUntil = new HashMap<>();
    private NamespacedKey decoyKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // add any new settings from this version to an existing config.yml
        getConfig().options().copyDefaults(true);
        saveConfig();

        decoyKey = new NamespacedKey(this, "shadow_decoy");
        getServer().getPluginManager().registerEvents(this, this);

        for (String name : List.of("shadow", "shadowstep")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
            }
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            endSession(id);
        }
    }

    public NamespacedKey getDecoyKey() {
        return decoyKey;
    }

    /** Stops a player's session (if any), removes their decoys and starts the cooldown. */
    public void endSession(UUID id) {
        ShadowSession session = sessions.remove(id);
        if (session == null) {
            return;
        }
        session.end();
        long cooldownMs = getConfig().getLong("cooldown-seconds", 30) * 1000L;
        cooldownUntil.put(id, System.currentTimeMillis() + cooldownMs);
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("shadowstep")) {
            return handleStep(player);
        }
        return handleShadow(player);
    }

    private boolean handleShadow(Player player) {
        UUID id = player.getUniqueId();

        // Running the command again dismisses the shadows early
        if (sessions.containsKey(id)) {
            endSession(id);
            player.sendMessage(Component.text("Your shadows fade away.", NamedTextColor.GRAY));
            return true;
        }

        long remainingMs = cooldownUntil.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (remainingMs > 0) {
            long seconds = (remainingMs + 999) / 1000;
            player.sendMessage(Component.text("Shadows are recharging (" + seconds + "s).", NamedTextColor.RED));
            return true;
        }

        ShadowSession session = new ShadowSession(this, player);
        sessions.put(id, session);
        session.begin();
        player.sendMessage(Component.text("Shadows rise around you...", NamedTextColor.DARK_GRAY));
        return true;
    }

    private boolean handleStep(Player player) {
        UUID id = player.getUniqueId();
        ShadowSession session = sessions.get(id);
        if (session == null) {
            player.sendMessage(Component.text("Summon your shadows first with /shadow.", NamedTextColor.RED));
            return true;
        }

        long remainingMs = stepCooldownUntil.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (remainingMs > 0) {
            long seconds = (remainingMs + 999) / 1000;
            player.sendMessage(Component.text("Shadow Step is recharging (" + seconds + "s).", NamedTextColor.RED));
            return true;
        }

        if (session.shadowStep()) {
            long cooldownMs = getConfig().getLong("step-cooldown-seconds", 2) * 1000L;
            stepCooldownUntil.put(id, System.currentTimeMillis() + cooldownMs);
        } else {
            player.sendMessage(Component.text("You have no shadows to swap with.", NamedTextColor.RED));
        }
        return true;
    }

    // ------------------------------------------------------------ events

    /** Decoys never take real damage, and pop when hit (unless they are holding a trap ring). */
    @EventHandler
    public void onDecoyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Mannequin decoy)) {
            return;
        }
        String owner = decoy.getPersistentDataContainer().get(decoyKey, PersistentDataType.STRING);
        if (owner == null) {
            return;
        }

        event.setCancelled(true);

        if (!(event instanceof EntityDamageByEntityEvent)) {
            return;
        }
        if (!getConfig().getBoolean("pop-on-hit", true)) {
            return;
        }

        // While the ring is closed around a target, it can't be broken
        ShadowSession session = sessions.get(UUID.fromString(owner));
        if (session != null && session.isTrapActive()) {
            return;
        }

        Location loc = decoy.getLocation().add(0, 1, 0);
        loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 10, 0.3, 0.5, 0.3, 0.02);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.6f, 1.4f);
        decoy.remove();
    }

    /** Hitting a player while your shadows are active marks them as the target. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitPlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = null;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }

        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (victim.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        ShadowSession session = sessions.get(attacker.getUniqueId());
        if (session != null) {
            session.trap(victim);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        endSession(event.getEntity().getUniqueId());
    }
}
