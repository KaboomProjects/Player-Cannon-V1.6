package de.playercannon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class PlayerCannon extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey rodKey;
    private NamespacedKey pearlKey;
    private File dataFile;
    /** Besitzer der Angel -> verlinkte Spieler */
    private final Map<UUID, Set<UUID>> links = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    // ---------------------------------------------------------------- Lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rodKey = new NamespacedKey(this, "cannon_rod");
        pearlKey = new NamespacedKey(this, "cannon_pearl");
        dataFile = new File(getDataFolder(), "links.yml");
        loadLinks();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("playercannon");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveLinks();
    }

    // ---------------------------------------------------------------- Commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(Component.text("[PlayerCannon] Config neu geladen.", NamedTextColor.GREEN));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl nutzen.");
            return true;
        }
        if (args.length == 0) {
            msg(player, "/playercannon link <spieler|all> | unlink <spieler|all> | list | clear | rod | mode <crosshair|me|hook> | reload", NamedTextColor.GRAY);
            return true;
        }

        Set<UUID> set = links.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashSet<>());

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "rod" -> {
                player.getInventory().addItem(createRod());
                msg(player, "Du hast die Player Cannon Angel erhalten.", NamedTextColor.LIGHT_PURPLE);
            }
            case "link" -> {
                if (args.length < 2) {
                    msg(player, "Benutzung: /playercannon link <spieler|all>", NamedTextColor.RED);
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    int added = 0;
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.equals(player) && set.add(online.getUniqueId())) {
                            added++;
                            if (getConfig().getBoolean("buffs.apply-on-link", false)) applyBuffs(online);
                        }
                    }
                    saveLinks();
                    msg(player, added + " Spieler verlinkt.", NamedTextColor.GREEN);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    msg(player, "Spieler nicht gefunden (muss online sein).", NamedTextColor.RED);
                    return true;
                }
                if (set.add(target.getUniqueId())) {
                    saveLinks();
                    if (getConfig().getBoolean("buffs.apply-on-link", false)) applyBuffs(target);
                    msg(player, target.getName() + " wurde verlinkt.", NamedTextColor.GREEN);
                } else {
                    msg(player, target.getName() + " ist bereits verlinkt.", NamedTextColor.YELLOW);
                }
            }
            case "unlink" -> {
                if (args.length < 2) {
                    msg(player, "Benutzung: /playercannon unlink <spieler|all>", NamedTextColor.RED);
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    set.clear();
                    saveLinks();
                    msg(player, "Alle Links entfernt.", NamedTextColor.GREEN);
                    return true;
                }
                UUID id = resolveUuid(args[1], set);
                if (id != null && set.remove(id)) {
                    saveLinks();
                    msg(player, args[1] + " wurde entlinkt.", NamedTextColor.GREEN);
                } else {
                    msg(player, args[1] + " ist nicht verlinkt.", NamedTextColor.RED);
                }
            }
            case "mode" -> {
                if (args.length < 2) {
                    msg(player, "Aktueller Modus: " + targetMode() + " | /playercannon mode <crosshair|me|hook>", NamedTextColor.GRAY);
                    return true;
                }
                String m = args[1].toLowerCase(Locale.ROOT);
                if (m.equals("me") || m.equals("owner")) {
                    setMode("OWNER");
                    msg(player, "Modus: ME - verlinkte Spieler werden zu DIR gelauncht.", NamedTextColor.GREEN);
                } else if (m.equals("crosshair") || m.equals("cross")) {
                    setMode("CROSSHAIR");
                    msg(player, "Modus: CROSSHAIR - Pearls fallen aus der Höhe in einen Kreis um den Punkt, auf den du zielst.", NamedTextColor.GREEN);
                } else if (m.equals("hook")) {
                    setMode("HOOK");
                    msg(player, "Modus: HOOK - verlinkte Spieler werden zum Hook gelauncht.", NamedTextColor.GREEN);
                } else {
                    msg(player, "Benutzung: /playercannon mode <crosshair|me|hook>", NamedTextColor.RED);
                }
            }
            case "list" -> {
                if (set.isEmpty()) {
                    msg(player, "Keine Spieler verlinkt.", NamedTextColor.GRAY);
                } else {
                    String names = set.stream()
                            .map(u -> Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse(u.toString()))
                            .collect(Collectors.joining(", "));
                    msg(player, "Verlinkt (" + set.size() + "): " + names, NamedTextColor.AQUA);
                }
            }
            case "clear" -> {
                set.clear();
                saveLinks();
                msg(player, "Alle Links entfernt.", NamedTextColor.GREEN);
            }
            default -> msg(player, "Unbekannter Unterbefehl.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("link", "unlink", "list", "clear", "rod", "mode", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return filter(List.of("crosshair", "me", "hook"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("link") || args[0].equalsIgnoreCase("unlink"))) {
            List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            names.add("all");
            return filter(names, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private UUID resolveUuid(String name, Set<UUID> candidates) {
        for (UUID u : candidates) {
            String n = Bukkit.getOfflinePlayer(u).getName();
            if (n != null && n.equalsIgnoreCase(name)) return u;
        }
        return null;
    }

    // ---------------------------------------------------------------- Rod

    private ItemStack createRod() {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.displayName(LEGACY.deserialize(getConfig().getString("rod-name", "&d&lPlayer Cannon"))
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("rod-lore")) {
            lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) meta.lore(lore);
        meta.setUnbreakable(true);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
        rod.setItemMeta(meta);
        return rod;
    }

    private boolean isCannonRod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- Events

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        PlayerFishEvent.State state = e.getState();
        // Nur beim Einholen (Pullen) der Angel
        if (state != PlayerFishEvent.State.REEL_IN
                && state != PlayerFishEvent.State.IN_GROUND
                && state != PlayerFishEvent.State.CAUGHT_ENTITY
                && state != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player owner = e.getPlayer();
        if (!isCannonRod(owner.getInventory().getItemInMainHand())
                && !isCannonRod(owner.getInventory().getItemInOffHand())) return;

        if (state == PlayerFishEvent.State.CAUGHT_FISH) {
            if (e.getCaught() != null) e.getCaught().remove();
            e.setExpToDrop(0);
        }

        Set<UUID> set = links.get(owner.getUniqueId());
        if (set == null || set.isEmpty()) {
            owner.sendActionBar(Component.text("Keine Spieler verlinkt!", NamedTextColor.RED));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = getConfig().getLong("cooldown-ms", 1000);
        if (now - cooldowns.getOrDefault(owner.getUniqueId(), 0L) < cooldown) return;
        cooldowns.put(owner.getUniqueId(), now);

        Location hookLoc = e.getHook().getLocation().clone();
        String mode = targetMode();
        boolean hookMode = mode.equals("HOOK");
        boolean crosshairMode = mode.equals("CROSSHAIR");
        Location aim = crosshairMode ? crosshairPoint(owner) : null;
        Location target = hookMode ? hookLoc : (crosshairMode ? aim : ownerTarget(owner));

        double maxDist = getConfig().getDouble("max-distance", 150);
        int delay = Math.max(0, getConfig().getInt("launch-delay-ticks", 2));
        boolean effects = getConfig().getBoolean("effects", true);

        if (effects) targetEffect(target);

        // Alle Spieler sammeln, die abgefeuert werden
        List<Player> shooters = new ArrayList<>();
        for (UUID id : set) {
            Player t = Bukkit.getPlayer(id);
            if (t == null || !t.isOnline() || t.isDead() || t.equals(owner)) continue;
            if (hookMode) {
                if (!t.getWorld().equals(target.getWorld())) continue;
                if (t.getLocation().distance(target) > maxDist) continue;
            }
            shooters.add(t);
        }

        final int total = shooters.size();
        for (int i = 0; i < total; i++) {
            final Player t = shooters.get(i);
            final int idx = i;
            final int fixedDelay = i * delay;
            Runnable shot = () -> {
                if (!t.isOnline() || t.isDead() || !owner.isOnline()) return;
                if (crosshairMode) {
                    launchCrosshair(owner, t, aim, idx, total);
                } else if (hookMode) {
                    if (t.getWorld().equals(target.getWorld())) launchPearl(owner, t, target);
                } else {
                    launchSky(owner, t);
                }
            };
            if (fixedDelay == 0) {
                shot.run();
            } else {
                Bukkit.getScheduler().runTaskLater(this, shot, fixedDelay);
            }
        }
        owner.sendActionBar(Component.text(total + " Pearl(s) abgefeuert", NamedTextColor.LIGHT_PURPLE));
    }

    /** Teleport ohne Pearl-Schaden und mit Effekten. */
    @EventHandler
    public void onPearlHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!pearl.getPersistentDataContainer().has(pearlKey, PersistentDataType.BYTE)) return;
        if (!getConfig().getBoolean("no-pearl-damage", true)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;

        e.setCancelled(true);
        Location dest = destination(e, pearl);
        pearl.remove();
        if (!p.isOnline() || p.isDead()) return;

        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());
        boolean effects = getConfig().getBoolean("effects", true);
        if (effects) teleportEffect(p.getLocation());
        p.teleport(dest, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        p.setFallDistance(0);
        if (effects) teleportEffect(dest);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldowns.remove(e.getPlayer().getUniqueId());
    }

    private Location destination(ProjectileHitEvent e, EnderPearl pearl) {
        Block hb = e.getHitBlock();
        BlockFace face = e.getHitBlockFace();
        if (hb != null && face != null) {
            Location l = hb.getLocation().add(0.5, 0, 0.5)
                    .add(face.getModX(), face.getModY(), face.getModZ());
            if (face == BlockFace.DOWN) l.add(0, -1, 0);
            return l;
        }
        return pearl.getLocation().clone();
    }

    // ---------------------------------------------------------------- Launch & Effekte

    private String targetMode() {
        String m = getConfig().getString("target-mode", "CROSSHAIR");
        if (m == null) return "CROSSHAIR";
        m = m.toUpperCase(Locale.ROOT);
        if (m.equals("ME")) return "OWNER";
        if (m.equals("OWNER") || m.equals("HOOK") || m.equals("CROSSHAIR")) return m;
        return "CROSSHAIR";
    }

    /** Punkt, auf den das Crosshair des Besitzers zeigt (Block im Blick, sonst Boden unter dem Punkt in Reichweite). */
    private Location crosshairPoint(Player owner) {
        double range = Math.max(5, getConfig().getDouble("crosshair.range", 100.0));
        Location eye = owner.getEyeLocation();
        World w = eye.getWorld();
        Vector dir = eye.getDirection();
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        if (hit != null) return hit.getHitPosition().toLocation(w);
        Location far = eye.clone().add(dir.clone().multiply(range));
        RayTraceResult down = w.rayTraceBlocks(far, new Vector(0, -1, 0), 400, FluidCollisionMode.NEVER, true);
        if (down != null) return down.getHitPosition().toLocation(w);
        return far;
    }

    /** Oberfläche (Boden) an einer X/Z-Position nahe der angegebenen Höhe. */
    private Location groundAt(World w, double x, double y, double z) {
        Location from = new Location(w, x, y + 10, z);
        RayTraceResult r = w.rayTraceBlocks(from, new Vector(0, -1, 0), 60, FluidCollisionMode.NEVER, true);
        return r != null ? r.getHitPosition().toLocation(w) : new Location(w, x, y, z);
    }

    /** Spawnpunkt hoch über dem Besitzer (Höhe aus der Config, bei Decke darunter). */
    private Location highLocation(Player owner) {
        double height = Math.max(5, getConfig().getDouble("crosshair.spawn-height", 200.0));
        Location base = owner.getLocation();
        World w = base.getWorld();
        RayTraceResult up = w.rayTraceBlocks(base.clone().add(0, 1.8, 0), new Vector(0, 1, 0), height,
                FluidCollisionMode.NEVER, true);
        double h = up == null ? height : Math.max(3.0, up.getHitPosition().getY() - base.getY() - 0.8);
        return base.clone().add(0, h, 0);
    }

    /** CROSSHAIR-Modus: Pearl spawnt hoch über dem Besitzer und landet im Kreis um den Zielpunkt. */
    private void launchCrosshair(Player owner, Player shooter, Location aim, int index, int total) {
        double radius = Math.max(0, getConfig().getDouble("crosshair.radius", 5.0));
        boolean ring = !"RANDOM".equalsIgnoreCase(getConfig().getString("crosshair.pattern", "RING"));
        double angle;
        double r;
        if (ring) {
            angle = Math.PI * 2 * index / Math.max(1, total);
            r = radius;
        } else {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            angle = rnd.nextDouble() * Math.PI * 2;
            r = Math.sqrt(rnd.nextDouble()) * radius;
        }
        Location landing = groundAt(aim.getWorld(), aim.getX() + Math.cos(angle) * r, aim.getY(),
                aim.getZ() + Math.sin(angle) * r);
        launchPearlFrom(owner, shooter, highLocation(owner), landing);
    }

    private boolean isOwnerMode() {
        String m = getConfig().getString("target-mode", "OWNER");
        return m != null && (m.equalsIgnoreCase("OWNER") || m.equalsIgnoreCase("ME"));
    }

    private void setMode(String mode) {
        getConfig().set("target-mode", mode);
        saveConfig();
    }

    /** Zielpunkt auf Brusthöhe des Besitzers, damit die Pearl ihn trifft. */
    private Location ownerTarget(Player owner) {
        return owner.getLocation().clone().add(0, 1.0, 0);
    }

    private void launchPearl(Player owner, Player shooter, Location target) {
        launchPearlFrom(owner, shooter, shooter.getEyeLocation(), target);
    }

    /** OWNER-Modus: Die Pearl erscheint über dem Besitzer (im Radius) und fliegt zu ihm, egal wo der Spieler ist. */
    private void launchSky(Player owner, Player shooter) {
        launchPearlFrom(owner, shooter, skyLocation(owner), ownerTarget(owner));
    }

    private Location skyLocation(Player owner) {
        double radius = Math.max(0, getConfig().getDouble("sky-radius", 5.0));
        double height = Math.max(3, getConfig().getDouble("sky-height", 10.0));
        Location base = owner.getLocation();
        World w = base.getWorld();

        // Decke über dem Besitzer beachten
        RayTraceResult up = w.rayTraceBlocks(base.clone().add(0, 1.8, 0), new Vector(0, 1, 0), height,
                FluidCollisionMode.NEVER, true);
        double h = up == null ? height : Math.max(2.0, up.getHitPosition().getY() - base.getY() - 0.6);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rnd.nextDouble()) * radius;
            Location l = base.clone().add(Math.cos(angle) * r, h, Math.sin(angle) * r);
            if (l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable()) return l;
        }
        return base.clone().add(0, h, 0);
    }

    private void launchPearlFrom(Player owner, Player shooter, Location start, Location target) {
        applyBuffs(shooter);
        Vector velocity = solveVelocity(start, target);

        EnderPearl pearl = start.getWorld().spawn(start, EnderPearl.class, p -> {
            p.setShooter(shooter);
            p.setVelocity(velocity);
            p.getPersistentDataContainer().set(pearlKey, PersistentDataType.BYTE, (byte) 1);
        });

        if (getConfig().getBoolean("effects", true)) {
            start.getWorld().playSound(start, Sound.ENTITY_ENDER_PEARL_THROW, 1f, 0.8f);
            start.getWorld().spawnParticle(Particle.PORTAL, start, 30, 0.3, 0.5, 0.3, 0.6);
        }
        if (getConfig().getBoolean("notify-linked", true) && !shooter.equals(owner)) {
            shooter.sendActionBar(Component.text("Du wurdest von " + owner.getName() + " abgefeuert!", NamedTextColor.LIGHT_PURPLE));
        }
        if (getConfig().getBoolean("trail-particles", true)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!pearl.isValid()) {
                        cancel();
                        return;
                    }
                    Location l = pearl.getLocation();
                    l.getWorld().spawnParticle(Particle.PORTAL, l, 6, 0.1, 0.1, 0.1, 0.3);
                    l.getWorld().spawnParticle(Particle.END_ROD, l, 1, 0, 0, 0, 0.01);
                }
            }.runTaskTimer(this, 0L, 1L);
        }
    }

    /** Gibt dem verlinkten Spieler Strength, Speed und Fire Resistance (fight ready). */
    private void applyBuffs(Player target) {
        if (!getConfig().getBoolean("buffs.enabled", true)) return;
        int ticks = Math.max(1, getConfig().getInt("buffs.duration-seconds", 600)) * 20;
        int strength = getConfig().getInt("buffs.strength-level", 2);
        int speed = getConfig().getInt("buffs.speed-level", 2);
        if (strength > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, strength - 1, false, true, true));
        }
        if (speed > 0) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, speed - 1, false, true, true));
        }
        if (getConfig().getBoolean("buffs.fire-resistance", true)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, ticks, 0, false, true, true));
        }
    }

    private void targetEffect(Location target) {
        World w = target.getWorld();
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            w.spawnParticle(Particle.END_ROD, target.getX() + Math.cos(a), target.getY() + 0.1,
                    target.getZ() + Math.sin(a), 1, 0, 0, 0, 0.02);
        }
        w.spawnParticle(Particle.REVERSE_PORTAL, target, 40, 0.4, 0.4, 0.4, 0.4);
        w.playSound(target, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 2f);
    }

    private void teleportEffect(Location l) {
        World w = l.getWorld();
        w.spawnParticle(Particle.REVERSE_PORTAL, l.clone().add(0, 1, 0), 50, 0.4, 0.8, 0.4, 0.5);
        w.playSound(l, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
    }

    // ---------------------------------------------------------------- Ballistik

    private record Candidate(double score, Vector vel, int tick) {}

    /**
     * Berechnet die Abwurfgeschwindigkeit. Mehrere Flugbahnen (flach/hoch, verschiedene Geschwindigkeiten)
     * werden simuliert (Gravitation 0.03, Luftwiderstand 0.99) und per Raytrace auf Blöcke geprüft.
     */
    private Vector solveVelocity(Location start, Location target) {
        Vector s = start.toVector();
        Vector t = target.toVector();
        List<Candidate> candidates = candidates(s, t);
        if (candidates.isEmpty()) {
            Vector dir = t.clone().subtract(s).setY(0);
            if (dir.lengthSquared() == 0) dir = new Vector(1, 0, 0);
            return dir.normalize().multiply(1.5).setY(0.8);
        }
        int checked = 0;
        for (Candidate c : candidates) {
            if (checked++ >= 30) break;
            if (pathClear(start.getWorld(), s, c, t)) return c.vel();
        }
        return candidates.get(0).vel();
    }

    private List<Candidate> candidates(Vector s, Vector target) {
        double yaw = Math.atan2(target.getZ() - s.getZ(), target.getX() - s.getX());
        double hx = Math.cos(yaw), hz = Math.sin(yaw);
        List<Candidate> out = new ArrayList<>();

        for (double speed = 1.0; speed <= 5.0; speed += 0.25) {
            Candidate low = null, high = null;
            for (double pitch = -89; pitch <= 85; pitch += 0.5) {
                double rad = Math.toRadians(pitch);
                double sx = hx * Math.cos(rad) * speed;
                double sy = Math.sin(rad) * speed;
                double sz = hz * Math.cos(rad) * speed;

                double vx = sx, vy = sy, vz = sz;
                double x = s.getX(), y = s.getY(), z = s.getZ();
                double min = Double.MAX_VALUE;
                int minTick = 0;

                for (int tick = 0; tick < 300; tick++) {
                    x += vx; y += vy; z += vz;
                    vx *= 0.99; vy *= 0.99; vz *= 0.99;
                    vy -= 0.03;
                    double dx = x - target.getX(), dy = y - target.getY(), dz = z - target.getZ();
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < min) {
                        min = d;
                        minTick = tick;
                    }
                    if (vy < 0 && y < target.getY() - 30) break;
                }

                Candidate c = new Candidate(min + speed * 0.05, new Vector(sx, sy, sz), minTick);
                if (pitch < 35) {
                    if (low == null || c.score() < low.score()) low = c;
                } else {
                    if (high == null || c.score() < high.score()) high = c;
                }
            }
            if (low != null) out.add(low);
            if (high != null) out.add(high);
        }
        out.sort(Comparator.comparingDouble(Candidate::score));
        return out;
    }

    /** Prüft, ob die Flugbahn bis zum Zielpunkt frei von Blöcken ist. */
    private boolean pathClear(World w, Vector s, Candidate c, Vector target) {
        Vector pos = s.clone();
        Vector vel = c.vel().clone();
        for (int t = 0; t <= c.tick(); t++) {
            double len = vel.length();
            if (len > 0) {
                RayTraceResult r = w.rayTraceBlocks(pos.toLocation(w), vel.clone().normalize(), len,
                        FluidCollisionMode.NEVER, true);
                if (r != null) {
                    return r.getHitPosition().distance(target) <= 3.0;
                }
            }
            pos.add(vel);
            vel.multiply(0.99);
            vel.setY(vel.getY() - 0.03);
        }
        return true;
    }

    // ---------------------------------------------------------------- Persistenz

    private void loadLinks() {
        links.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        var section = cfg.getConfigurationSection("links");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                Set<UUID> set = new LinkedHashSet<>();
                for (String s : section.getStringList(key)) set.add(UUID.fromString(s));
                links.put(owner, set);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveLinks() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var entry : links.entrySet()) {
            cfg.set("links." + entry.getKey(), entry.getValue().stream().map(UUID::toString).toList());
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Konnte links.yml nicht speichern: " + ex.getMessage());
        }
    }

    private void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[PlayerCannon] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text(text, color)));
    }
}
