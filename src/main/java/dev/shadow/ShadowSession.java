package dev.shadow;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** One running /shadow effect for one player. */
public final class ShadowSession extends BukkitRunnable {

    private static final class Decoy {
        final Mannequin entity;
        Vector offset; // where it is heading, relative to the player (roaming mode)

        Decoy(Mannequin entity, Vector offset) {
            this.entity = entity;
            this.offset = offset;
        }
    }

    private final ShadowPlugin plugin;
    private final Player player;
    private final List<Decoy> decoys = new ArrayList<>();

    // settings
    private final int count;
    private final double radius;
    private final double speed;
    private final int particlesPerTick;
    private final boolean darkness;
    private final double darknessRadius;
    private final double trapRadius;
    private final int trapTicks;
    private final double trapSpin;
    private final double trapPush;

    // state
    private int ticksLeft;
    private int tick;

    // target trap state
    private Player trapTarget;
    private Location trapCenter;
    private int trapTicksLeft;
    private double ringAngle;

    private final Particle.DustOptions dust = new Particle.DustOptions(Color.BLACK, 1.8f);

    public ShadowSession(ShadowPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        var cfg = plugin.getConfig();
        this.count = Math.max(1, cfg.getInt("count", 25));
        this.radius = Math.max(1.0, cfg.getDouble("radius", 7.0));
        this.speed = Math.max(0.1, cfg.getDouble("speed", 1.0));
        this.particlesPerTick = Math.max(0, cfg.getInt("particles-per-tick", 60));
        this.darkness = cfg.getBoolean("darkness", true);
        this.darknessRadius = Math.max(1.0, cfg.getDouble("darkness-radius", 12.0));
        this.trapRadius = Math.max(2.0, cfg.getDouble("trap-radius", 5.0));
        this.trapTicks = Math.max(1, cfg.getInt("trap-duration-seconds", 8)) * 20;
        this.trapSpin = cfg.getDouble("trap-spin", 0.04);
        this.trapPush = Math.max(0.2, cfg.getDouble("trap-push", 0.9));
        this.ticksLeft = Math.max(1, cfg.getInt("duration-seconds", 12)) * 20;
    }

    // ------------------------------------------------------------ lifecycle

    public void begin() {
        Location base = player.getLocation();
        World world = base.getWorld();
        for (int i = 0; i < count; i++) {
            Location spawnAt = spotFor(base, randomOffset());
            decoys.add(new Decoy(spawnDecoy(spawnAt), randomOffset()));
        }
        shockwave(base, 3);
        world.playSound(base, Sound.ENTITY_WARDEN_EMERGE, 1.0f, 1.3f);
        world.playSound(base, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        applyDarkness(base, world);
        runTaskTimer(plugin, 1L, 1L);
    }

    public void end() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // task was never scheduled
        }
        if (trapTarget != null) {
            trapTarget.removePotionEffect(PotionEffectType.DARKNESS);
            trapTarget = null;
            trapCenter = null;
        }
        for (Decoy d : decoys) {
            if (d.entity.isValid()) {
                puff(d.entity.getLocation());
                d.entity.remove();
            }
        }
        decoys.clear();
    }

    public boolean isTrapActive() {
        return trapTarget != null;
    }

    // ------------------------------------------------------------ main loop

    @Override
    public void run() {
        tick++;
        if (!player.isOnline() || player.isDead() || --ticksLeft < 0) {
            plugin.endSession(player.getUniqueId());
            return;
        }

        decoys.removeIf(d -> !d.entity.isValid());

        Location base = player.getLocation();
        World world = base.getWorld();

        if (trapTarget != null && !trapStillValid()) {
            endTrap();
        }

        if (trapTarget != null) {
            trapStep();
        } else {
            roamStep(base, world);
        }

        auraParticles(base, world);

        if (tick % 20 == 0) {
            applyDarkness(base, world);
            world.playSound(base, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.8f);
        }
    }

    // ------------------------------------------------------------ roaming

    private void roamStep(Location base, World world) {
        for (Decoy d : decoys) {
            if (!d.entity.getWorld().equals(world)) {
                d.entity.teleport(base);
            }

            Location goal = spotFor(base, d.offset);
            // Reached its spot: pick a new one and keep going
            if (d.entity.getLocation().distanceSquared(goal) < 1.0) {
                d.offset = randomOffset();
                goal = spotFor(base, d.offset);
            }
            step(d, goal, null);
            decoyParticles(world, d.entity.getLocation());
        }
    }

    // ------------------------------------------------------------ shadow step

    /** Swap places with a random decoy. Returns false if there is nothing to swap with. */
    public boolean shadowStep() {
        List<Decoy> alive = new ArrayList<>();
        for (Decoy d : decoys) {
            if (d.entity.isValid()) {
                alive.add(d);
            }
        }
        if (alive.isEmpty()) {
            return false;
        }
        Decoy d = alive.get(ThreadLocalRandom.current().nextInt(alive.size()));

        Location from = player.getLocation();
        Location dest = d.entity.getLocation().clone();
        dest.setYaw(from.getYaw());
        dest.setPitch(from.getPitch());

        Location swapped = from.clone();
        swapped.setPitch(0f);

        burst(from);
        player.teleport(dest);
        d.entity.teleport(swapped);
        burst(dest);
        return true;
    }

    // ------------------------------------------------------------ target trap

    /** Called when the owner hits another player while shadows are active. */
    public boolean trap(Player target) {
        if (trapTarget != null || !target.isOnline() || target.isDead()) {
            return false;
        }
        trapTarget = target;
        trapCenter = target.getLocation().clone();
        trapCenter.setY(groundY(trapCenter, trapCenter.getY()));
        trapTicksLeft = trapTicks;
        ringAngle = 0;
        // make sure the session lasts long enough for the trap to play out
        ticksLeft = Math.max(ticksLeft, trapTicks + 20);

        target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, trapTicks + 40, 0, false, false, true));

        player.showTitle(Title.title(
                Component.text("Target: " + target.getName(), NamedTextColor.DARK_RED),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(400))));
        player.sendMessage(Component.text("Target: " + target.getName(), NamedTextColor.DARK_RED));

        World tw = trapCenter.getWorld();
        shockwave(trapCenter, 4);
        tw.playSound(trapCenter, Sound.ENTITY_WARDEN_ROAR, 1.5f, 0.9f);
        return true;
    }

    private boolean trapStillValid() {
        trapTicksLeft--;
        return trapTarget.isOnline()
                && !trapTarget.isDead()
                && trapTicksLeft >= 0
                && trapTarget.getWorld().equals(trapCenter.getWorld());
    }

    private void endTrap() {
        if (trapTarget != null) {
            trapTarget.removePotionEffect(PotionEffectType.DARKNESS);
        }
        if (trapCenter != null) {
            puff(trapCenter);
            trapCenter.getWorld().playSound(trapCenter, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        }
        trapTarget = null;
        trapCenter = null;
        for (Decoy d : decoys) {
            d.offset = randomOffset();
        }
    }

    private void trapStep() {
        World tw = trapCenter.getWorld();
        ringAngle += trapSpin;

        int n = Math.max(1, decoys.size());
        for (int i = 0; i < decoys.size(); i++) {
            Decoy d = decoys.get(i);
            if (!d.entity.getWorld().equals(tw)) {
                d.entity.teleport(trapCenter);
            }
            double a = ringAngle + i * (Math.PI * 2.0 / n);
            Location slot = trapCenter.clone().add(Math.cos(a) * trapRadius, 0, Math.sin(a) * trapRadius);
            slot.setY(groundY(slot, trapCenter.getY()));
            step(d, slot, trapCenter);
            decoyParticles(tw, d.entity.getLocation());
        }

        contain(tw);
        ringWallParticles(tw);
    }

    /** Pushes the target back towards the middle if it tries to leave the ring. */
    private void contain(World tw) {
        Location tl = trapTarget.getLocation();
        Vector flat = tl.toVector().subtract(trapCenter.toVector());
        flat.setY(0);
        double dist = flat.length();
        double limit = trapRadius - 0.6;
        if (dist <= limit) {
            return;
        }

        Vector outward = flat.clone().normalize();

        if (dist > trapRadius + 1.0) {
            // Got through (ender pearl, lag, etc): put them back inside
            Location back = trapCenter.clone().add(outward.clone().multiply(trapRadius - 1.5));
            back.setY(tl.getY());
            back.setYaw(tl.getYaw());
            back.setPitch(tl.getPitch());
            trapTarget.teleport(back);
            tw.spawnParticle(Particle.SQUID_INK, back.getX(), back.getY() + 1.0, back.getZ(),
                    20, 0.4, 0.6, 0.4, 0.05);
        } else {
            Vector push = outward.multiply(-trapPush);
            push.setY(Math.max(0.12, trapTarget.getVelocity().getY()));
            trapTarget.setVelocity(push);
            tw.spawnParticle(Particle.SQUID_INK, tl.getX(), tl.getY() + 1.0, tl.getZ(),
                    8, 0.3, 0.5, 0.3, 0.05);
        }

        if (tick % 6 == 0) {
            tw.playSound(tl, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 0.7f, 1.2f);
        }
    }

    private void ringWallParticles(World tw) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 14; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            double h = rnd.nextDouble() * 3.0;
            tw.spawnParticle(Particle.DUST,
                    trapCenter.getX() + Math.cos(a) * trapRadius,
                    trapCenter.getY() + h,
                    trapCenter.getZ() + Math.sin(a) * trapRadius,
                    1, 0.05, 0.05, 0.05, 0, dust);
        }
        Location tl = trapTarget.getLocation();
        tw.spawnParticle(Particle.SCULK_SOUL, tl.getX(), tl.getY() + 1.0, tl.getZ(), 2, 0.3, 0.6, 0.3, 0.01);
        tw.spawnParticle(Particle.DUST, tl.getX(), tl.getY() + 1.0, tl.getZ(), 6, 0.4, 0.8, 0.4, 0, dust);
    }

    // ------------------------------------------------------------ darkness

    private void applyDarkness(Location base, World world) {
        if (!darkness) {
            return;
        }
        PotionEffect effect = new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false, true);
        for (Player p : world.getNearbyPlayers(base, darknessRadius)) {
            if (p.equals(player) || p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            p.addPotionEffect(effect);
        }
    }

    // ------------------------------------------------------------ particles

    private void auraParticles(Location base, World world) {
        if (particlesPerTick > 0) {
            world.spawnParticle(Particle.DUST, base.getX(), base.getY() + 1.0, base.getZ(),
                    particlesPerTick, radius / 2.5, 1.0, radius / 2.5, 0, dust);
        }

        // spinning helix of shadow around the player
        for (int i = 0; i < 4; i++) {
            double a = tick * 0.35 + i * (Math.PI / 2.0);
            double y = ((tick * 2 + i * 12) % 48) / 48.0 * 2.4;
            world.spawnParticle(Particle.DUST,
                    base.getX() + Math.cos(a) * 1.1, base.getY() + y, base.getZ() + Math.sin(a) * 1.1,
                    2, 0.02, 0.02, 0.02, 0, dust);
        }

        if (tick % 3 == 0) {
            world.spawnParticle(Particle.LARGE_SMOKE, base.getX(), base.getY() + 1.0, base.getZ(),
                    6, radius / 3.0, 0.8, radius / 3.0, 0.01);
            world.spawnParticle(Particle.SQUID_INK, base.getX(), base.getY() + 1.0, base.getZ(),
                    6, radius / 3.0, 0.8, radius / 3.0, 0.05);
        }
        if (tick % 5 == 0) {
            world.spawnParticle(Particle.SCULK_SOUL, base.getX(), base.getY() + 1.0, base.getZ(),
                    3, radius / 3.0, 0.8, radius / 3.0, 0.02);
        }
    }

    private void decoyParticles(World w, Location l) {
        w.spawnParticle(Particle.DUST, l.getX(), l.getY() + 1.0, l.getZ(), 5, 0.3, 0.6, 0.3, 0, dust);
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.12) {
            w.spawnParticle(Particle.SQUID_INK, l.getX(), l.getY() + 1.0, l.getZ(), 1, 0.2, 0.4, 0.2, 0.03);
        } else if (r < 0.2) {
            w.spawnParticle(Particle.SCULK_SOUL, l.getX(), l.getY() + 1.0, l.getZ(), 1, 0.2, 0.4, 0.2, 0.02);
        } else if (r < 0.28) {
            w.spawnParticle(Particle.ASH, l.getX(), l.getY() + 1.0, l.getZ(), 6, 0.4, 0.6, 0.4, 0);
        }
    }

    private void shockwave(Location c, int rings) {
        World w = c.getWorld();
        for (int ring = 1; ring <= rings; ring++) {
            double r = ring * 1.5;
            for (int a = 0; a < 360; a += 15) {
                double rad = Math.toRadians(a);
                w.spawnParticle(Particle.DUST,
                        c.getX() + Math.cos(rad) * r, c.getY() + 0.2, c.getZ() + Math.sin(rad) * r,
                        2, 0.05, 0.05, 0.05, 0, dust);
            }
        }
        w.spawnParticle(Particle.LARGE_SMOKE, c.getX(), c.getY() + 1.0, c.getZ(), 40, 1.5, 1.0, 1.5, 0.05);
        w.spawnParticle(Particle.SQUID_INK, c.getX(), c.getY() + 1.0, c.getZ(), 30, 1.5, 1.0, 1.5, 0.1);
    }

    /** Short burst used for shadow step and decoys disappearing. */
    private void burst(Location l) {
        World w = l.getWorld();
        w.spawnParticle(Particle.SQUID_INK, l.getX(), l.getY() + 1.0, l.getZ(), 25, 0.4, 0.8, 0.4, 0.08);
        w.spawnParticle(Particle.LARGE_SMOKE, l.getX(), l.getY() + 1.0, l.getZ(), 15, 0.4, 0.8, 0.4, 0.03);
        w.spawnParticle(Particle.DUST, l.getX(), l.getY() + 1.0, l.getZ(), 30, 0.5, 0.9, 0.5, 0, dust);
        w.playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.7f);
        w.playSound(l, Sound.ENTITY_WARDEN_TENDRIL_CLICKS, 1.0f, 1.0f);
    }

    private void puff(Location loc) {
        loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc.getX(), loc.getY() + 1.0, loc.getZ(),
                8, 0.3, 0.5, 0.3, 0.02);
    }

    // ------------------------------------------------------------ helpers

    /** Moves a decoy up to {@code speed} blocks towards goal. Faces the movement, or {@code facing} if given. */
    private void step(Decoy d, Location goal, Location facing) {
        Location current = d.entity.getLocation();
        Vector delta = goal.toVector().subtract(current.toVector());
        double dist = delta.length();
        if (dist < 0.0001) {
            return;
        }
        if (dist > speed) {
            delta.multiply(speed / dist);
        }

        Location next = current.clone().add(delta);
        if (facing != null) {
            Vector f = facing.toVector().subtract(next.toVector());
            next.setYaw((float) Math.toDegrees(Math.atan2(-f.getX(), f.getZ())));
        } else {
            next.setYaw((float) Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ())));
        }
        next.setPitch(0f);
        d.entity.teleport(next);
    }

    private Mannequin spawnDecoy(Location loc) {
        loc.setYaw(ThreadLocalRandom.current().nextFloat() * 360f);
        World world = loc.getWorld();

        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null) {
                armor[i] = armor[i].clone();
            }
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand().clone();
        ItemStack offHand = player.getInventory().getItemInOffHand().clone();

        return world.spawn(loc, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            m.setDescription(null);           // removes the default "NPC" text
            m.customName(player.displayName());
            m.setCustomNameVisible(true);     // matches the real player's nameplate
            m.setImmovable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setCollidable(false);
            m.setPersistent(false);

            EntityEquipment eq = m.getEquipment();
            eq.setArmorContents(armor);
            eq.setItemInMainHand(mainHand);
            eq.setItemInOffHand(offHand);

            // tag the decoy with its owner so the plugin knows whose it is
            m.getPersistentDataContainer().set(plugin.getDecoyKey(), PersistentDataType.STRING,
                    player.getUniqueId().toString());
        });
    }

    /** Random point inside a circle of the configured radius, relative to the player. */
    private Vector randomOffset() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2.0;
        double dist = radius * Math.sqrt(rnd.nextDouble());
        return new Vector(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
    }

    private Location spotFor(Location base, Vector offset) {
        Location l = base.clone().add(offset);
        l.setY(groundY(l, base.getY()));
        return l;
    }

    /** Finds standing height near refY so decoys follow the terrain instead of floating or sinking. */
    private double groundY(Location l, double refY) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return refY;
        }
        int startY = (int) Math.floor(refY) + 3;
        for (int y = startY; y >= (int) Math.floor(refY) - 4; y--) {
            boolean feetFree = w.getBlockAt(x, y, z).isPassable();
            boolean headFree = w.getBlockAt(x, y + 1, z).isPassable();
            boolean groundSolid = w.getBlockAt(x, y - 1, z).isSolid();
            if (feetFree && headFree && groundSolid) {
                return y;
            }
        }
        return refY;
    }
}
