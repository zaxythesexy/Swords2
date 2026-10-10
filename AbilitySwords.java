package com.example.abilityswords;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class AbilitySwords extends JavaPlugin implements Listener, TabExecutor {

    private static final String DASH = "dash";
    private static final String STRIKE = "strike";
    private static final String REGEN = "regen";

    private NamespacedKey typeKey;

    private final Map<UUID, Long> dashCooldowns = new HashMap<>();
    private final Map<UUID, Long> regenCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "item_type");
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("swords", "dashsword", "strikesword", "regenaxe")) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    private ItemStack createItem(String type) {
        Material material;
        Component name;
        List<Component> lore = new ArrayList<>();

        switch (type) {
            case DASH -> {
                material = Material.DIAMOND_SWORD;
                name = Component.text("Dash Sword", NamedTextColor.AQUA);
                lore.add(plain("Shift + Right Click to dash forward", NamedTextColor.GRAY));
                lore.add(plain(getConfig().getInt("dash.distance", 10) + " blocks, "
                        + formatTime(getConfig().getLong("dash.cooldown", 20)) + " cooldown", NamedTextColor.DARK_GRAY));
            }
            case STRIKE -> {
                material = Material.NETHERITE_SWORD;
                name = Component.text("Strike Sword", NamedTextColor.YELLOW);
                lore.add(plain(Math.round(getConfig().getDouble("strike.chance", 0.20) * 100)
                        + "% chance to strike lightning on hit", NamedTextColor.GRAY));
            }
            case REGEN -> {
                material = Material.DIAMOND_AXE;
                name = Component.text("Regeneration Axe", NamedTextColor.GREEN);
                lore.add(plain("Shift + Right Click to heal to full health", NamedTextColor.GRAY));
                lore.add(plain(formatTime(getConfig().getLong("regen.cooldown", 3660)) + " cooldown",
                        NamedTextColor.DARK_GRAY));
            }
            default -> {
                return null;
            }
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(lore);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** Returns "dash", "strike", "regen" or null if the item is not one of ours. */
    private String getType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------
    // Abilities
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        String type = getType(player.getInventory().getItemInMainHand());
        if (type == null) return;

        if (type.equals(DASH)) {
            event.setCancelled(true);
            useDash(player);
        } else if (type.equals(REGEN)) {
            event.setCancelled(true);
            useRegen(player);
        }
    }

    private void useDash(Player player) {
        long cooldown = getConfig().getLong("dash.cooldown", 20);
        long remaining = remaining(dashCooldowns, player, cooldown);
        if (remaining > 0) {
            player.sendMessage(Component.text("Dash is on cooldown for " + formatTime(remaining) + ".",
                    NamedTextColor.RED));
            return;
        }

        double maxDistance = getConfig().getDouble("dash.distance", 10);
        Location start = player.getLocation();
        Vector dir = start.getDirection().normalize();

        // Walk forward in half-block steps and stop before hitting a wall.
        Location lastSafe = start.clone();
        for (double d = 0.5; d <= maxDistance; d += 0.5) {
            Location test = start.clone().add(dir.clone().multiply(d));
            if (!test.getBlock().isPassable()
                    || !test.clone().add(0, 1, 0).getBlock().isPassable()) {
                break;
            }
            lastSafe = test;
        }

        if (lastSafe.distanceSquared(start) < 1.0) {
            player.sendMessage(Component.text("Something is blocking your dash.", NamedTextColor.RED));
            return;
        }

        player.getWorld().spawnParticle(Particle.CLOUD, start.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
        player.teleport(lastSafe);
        player.setFallDistance(0f);
        player.getWorld().playSound(lastSafe, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.5f);
        player.getWorld().spawnParticle(Particle.CLOUD, lastSafe.clone().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);

        dashCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.sendMessage(Component.text("You can use this ability again in " + formatTime(cooldown) + ".",
                NamedTextColor.AQUA));
    }

    private void useRegen(Player player) {
        long cooldown = getConfig().getLong("regen.cooldown", 3660);
        long remaining = remaining(regenCooldowns, player, cooldown);
        if (remaining > 0) {
            player.sendMessage(Component.text("Regeneration is on cooldown for " + formatTime(remaining) + ".",
                    NamedTextColor.RED));
            return;
        }

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth != null ? maxHealth.getValue() : 20.0;
        if (player.getHealth() >= max) {
            player.sendMessage(Component.text("You are already at full health.", NamedTextColor.YELLOW));
            return;
        }

        player.setHealth(max);
        player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.5, 0), 12, 0.5, 0.5, 0.5);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);

        regenCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.sendMessage(Component.text("Healed to full! You can use this ability again in "
                + formatTime(cooldown) + ".", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        if (!STRIKE.equals(getType(player.getInventory().getItemInMainHand()))) return;

        double chance = getConfig().getDouble("strike.chance", 0.20);
        if (Math.random() < chance) {
            target.getWorld().strikeLightning(target.getLocation());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Cooldowns intentionally kept so players can't reset them by relogging.
    }

    // ------------------------------------------------------------------
    // Cooldown helpers
    // ------------------------------------------------------------------

    /** Seconds left on the cooldown, or 0 if ready. */
    private long remaining(Map<UUID, Long> map, Player player, long cooldownSeconds) {
        Long last = map.get(player.getUniqueId());
        if (last == null) return 0;
        long left = cooldownSeconds - (System.currentTimeMillis() - last) / 1000;
        return Math.max(0, left);
    }

    private String formatTime(long seconds) {
        if (seconds >= 120) {
            long minutes = (seconds + 59) / 60;
            return minutes + " minutes";
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    // ------------------------------------------------------------------
    // Command: /swords give <dash|strike|regen> [player]
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String type;
        String playerArg;

        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "dashsword" -> { type = DASH; playerArg = args.length >= 1 ? args[0] : null; }
            case "strikesword" -> { type = STRIKE; playerArg = args.length >= 1 ? args[0] : null; }
            case "regenaxe" -> { type = REGEN; playerArg = args.length >= 1 ? args[0] : null; }
            default -> {
                if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
                    sender.sendMessage(Component.text("Usage: /swords give <dash|strike|regen> [player]",
                            NamedTextColor.RED));
                    return true;
                }
                type = args[1].toLowerCase(Locale.ROOT);
                playerArg = args.length >= 3 ? args[2] : null;
            }
        }

        ItemStack item = createItem(type);
        if (item == null) {
            sender.sendMessage(Component.text("Unknown item. Use dash, strike or regen.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (playerArg != null) {
            target = getServer().getPlayerExact(playerArg);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(item).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Gave " + type + " to " + target.getName() + ".",
                NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("swords")) return null; // shortcut commands: suggest player names
        if (args.length == 1) return List.of("give");
        if (args.length == 2) return List.of(DASH, STRIKE, REGEN);
        return List.of();
    }
}
