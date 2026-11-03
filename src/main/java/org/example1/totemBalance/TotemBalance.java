package org.example1.totemBalance;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class TotemBalance extends JavaPlugin implements Listener {

    private double dropChance;
    private double raidDropChance;
    private long cooldownMs;
    private final Map<String, Long> worldCooldownsMs = new HashMap<>();
    private String reloadMessage;
    private String usageMessage;
    private String cooldownMessage;
    private NamespacedKey cooldownUntilKey;
    private NamespacedKey cooldownGenerationKey;
    private File cooldownStateFile;
    private long cooldownGeneration;
    private boolean cooldownsWereEnabled = true;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> materialCooldownUntilTick = new HashMap<>();
    private final Set<UUID> pendingResurrections = new HashSet<>();
    private final Map<EntityDeathEvent, CapturedEvokerDrops> vanillaEvokerTotems = new IdentityHashMap<>();

    private record CapturedEvokerDrops(Set<ItemStack> items, double chance) {}

    @Override
    public void onEnable() {
        cooldownUntilKey = new NamespacedKey(this, "totem-cooldown-until");
        cooldownGenerationKey = new NamespacedKey(this, "totem-cooldown-generation");
        saveDefaultConfig();
        reloadConfig();
        loadConfig();
        loadCooldownState();
        applyCooldownConfigTransition();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers()) {
            restoreCooldown(player);
        }
        getServer().getScheduler().runTaskTimer(this, this::purgeExpiredCooldowns, 200L, 200L);
    }

    private void loadConfig() {
        dropChance = loadChance("drop-chance", 0.05);
        raidDropChance = loadChance("raid-drop-chance", dropChance);

        long configuredSeconds = Math.max(0L, getConfig().getLong("cooldown-seconds", 60L));
        cooldownMs = secondsToMillis(configuredSeconds);
        worldCooldownsMs.clear();
        ConfigurationSection worldSection = getConfig().getConfigurationSection("world-cooldowns");
        if (worldSection != null) {
            for (Map.Entry<String, Object> entry : worldSection.getValues(true).entrySet()) {
                if (entry.getValue() instanceof ConfigurationSection) continue;
                if (!(entry.getValue() instanceof Number number)
                        || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
                    getLogger().warning("Некорректная длительность в world-cooldowns для мира '"
                            + entry.getKey() + "'; настройка пропущена");
                    continue;
                }
                worldCooldownsMs.put(entry.getKey(), secondsToMillis(number.longValue()));
            }
        }
        reloadMessage = color(getConfig().getString("messages.reload", "&aКонфиг TotemBalance перезагружен."));
        usageMessage = color(getConfig().getString("messages.usage", "&6Использование: /%label% reload"));
        cooldownMessage = color(getConfig().getString("messages.cooldown", ""));
    }

    private double loadChance(String path, double fallback) {
        double configuredChance = getConfig().getDouble(path, fallback);
        if (!Double.isFinite(configuredChance)) {
            getLogger().warning(path + " должен быть конечным числом; используется значение " + fallback);
            configuredChance = fallback;
        }
        return Math.max(0.0, Math.min(1.0, configuredChance));
    }

    private static long secondsToMillis(long seconds) {
        return Math.min(seconds, Long.MAX_VALUE / 1000L) * 1000L;
    }

    private void loadCooldownState() {
        cooldownStateFile = new File(getDataFolder(), "cooldown-state.yml");
        YamlConfiguration state = YamlConfiguration.loadConfiguration(cooldownStateFile);
        cooldownGeneration = Math.max(0L, state.getLong("generation", 0L));
        // Default to enabled so upgrading from a version without this state file
        // correctly invalidates old timers if the configured default is zero.
        cooldownsWereEnabled = state.getBoolean("default-cooldown-enabled", true);
    }

    private void applyCooldownConfigTransition() {
        boolean cooldownEnabled = cooldownMs > 0;
        if (cooldownsWereEnabled && !cooldownEnabled) {
            cooldownGeneration = cooldownGeneration == Long.MAX_VALUE ? 0L : cooldownGeneration + 1L;
            for (Player player : getServer().getOnlinePlayers()) {
                clearCooldown(player);
            }
        }
        cooldownsWereEnabled = cooldownEnabled;
        saveCooldownState();
    }

    private void saveCooldownState() {
        YamlConfiguration state = new YamlConfiguration();
        state.set("generation", cooldownGeneration);
        state.set("default-cooldown-enabled", cooldownsWereEnabled);
        try {
            state.save(cooldownStateFile);
        } catch (IOException exception) {
            getLogger().severe("Не удалось сохранить состояние перезарядок: " + exception.getMessage());
        }
    }

    private long cooldownForWorld(String worldName) {
        return worldCooldownsMs.getOrDefault(worldName, cooldownMs);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000L + (ms % 1000L == 0 ? 0 : 1);
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static long deadlineAfter(long now, long duration) {
        return Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration;
    }

    private static int cooldownTicks(long ms) {
        long ticks = ms / 50L + (ms % 50L == 0 ? 0 : 1);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ticks));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfig();
            applyCooldownConfigTransition();
            for (Player player : getServer().getOnlinePlayers()) {
                restoreCooldown(player);
            }
            sender.sendMessage(reloadMessage);
            return true;
        }
        sender.sendMessage(usageMessage.replace("%label%", label));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureVanillaEvokerDrops(EntityDeathEvent event) {
        if (event.getEntity().getType() != EntityType.EVOKER) return;
        Set<ItemStack> originalTotems = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ItemStack item : event.getDrops()) {
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) originalTotems.add(item);
        }
        if (!originalTotems.isEmpty()) {
            boolean fromRaidWave = event.getEntity() instanceof Raider raider && raider.getWave() > 0;
            double chance = fromRaidWave
                    ? raidDropChance
                    : dropChance;
            vanillaEvokerTotems.put(event, new CapturedEvokerDrops(originalTotems, chance));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        CapturedEvokerDrops captured = vanillaEvokerTotems.remove(event);
        if (captured != null && ThreadLocalRandom.current().nextDouble() >= captured.chance()) {
            // Filter only evoker totems that were already in the vanilla drop list;
            // preserve totems added by other plugins while this event is dispatched.
            event.getDrops().removeIf(item -> item != null && item.getType() == Material.TOTEM_OF_UNDYING
                    && captured.items().contains(item));
        }

    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        restoreCooldown(event.getPlayer());
    }

    private void restoreCooldown(Player player) {
        UUID id = player.getUniqueId();
        Long until = player.getPersistentDataContainer().get(cooldownUntilKey, PersistentDataType.LONG);
        Long generation = player.getPersistentDataContainer().get(cooldownGenerationKey, PersistentDataType.LONG);
        if (generation == null) generation = 0L;
        long now = System.currentTimeMillis();
        if (until != null && until > now && generation == cooldownGeneration) {
            cooldowns.put(id, until);
            extendMaterialCooldown(player, cooldownTicks(until - now));
        } else {
            clearCooldown(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long until = cooldowns.remove(id);
        pendingResurrections.remove(id);
        if (until != null && until <= System.currentTimeMillis()) {
            clearCooldown(event.getPlayer());
        } else {
            materialCooldownUntilTick.remove(id);
        }
    }

    private void purgeExpiredCooldowns() {
        long now = System.currentTimeMillis();
        var iterator = cooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                Player player = getServer().getPlayer(entry.getKey());
                if (player != null) {
                    player.getPersistentDataContainer().remove(cooldownUntilKey);
                    player.getPersistentDataContainer().remove(cooldownGenerationKey);
                    clearOwnedMaterialCooldown(player, entry.getKey());
                } else {
                    materialCooldownUntilTick.remove(entry.getKey());
                }
                iterator.remove();
            }
        }
    }

    private Long cooldownUntil(Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        if (until == null) {
            until = player.getPersistentDataContainer().get(cooldownUntilKey, PersistentDataType.LONG);
            Long generation = player.getPersistentDataContainer().get(cooldownGenerationKey,
                    PersistentDataType.LONG);
            if (generation == null) generation = 0L;
            if (until != null && generation != cooldownGeneration) {
                clearCooldown(player);
                return null;
            }
            if (until != null) cooldowns.put(player.getUniqueId(), until);
        }
        return until;
    }

    private void clearCooldown(Player player) {
        UUID id = player.getUniqueId();
        cooldowns.remove(id);
        pendingResurrections.remove(id);
        player.getPersistentDataContainer().remove(cooldownUntilKey);
        player.getPersistentDataContainer().remove(cooldownGenerationKey);
        clearOwnedMaterialCooldown(player, id);
    }

    private void clearOwnedMaterialCooldown(Player player, UUID id) {
        Long ownedUntilTick = materialCooldownUntilTick.remove(id);
        if (ownedUntilTick == null) return;

        // Item cooldowns are shared by Material across plugins. Only clear the
        // indicator when it does not outlast this plugin's own tick-based marker.
        long ownRemainingTicks = Math.max(0L, ownedUntilTick - getServer().getCurrentTick());
        int currentMaterialTicks = player.getCooldown(Material.TOTEM_OF_UNDYING);
        if (currentMaterialTicks <= ownRemainingTicks + 2L) {
            player.setCooldown(Material.TOTEM_OF_UNDYING, 0);
        }
    }

    private void extendMaterialCooldown(Player player, int ticks) {
        if (ticks > player.getCooldown(Material.TOTEM_OF_UNDYING)) {
            player.setCooldown(Material.TOTEM_OF_UNDYING, ticks);
            materialCooldownUntilTick.put(player.getUniqueId(),
                    (long) getServer().getCurrentTick() + ticks);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        clearCooldown(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        UUID id = player.getUniqueId();
        if (pendingResurrections.contains(id)) {
            event.setCancelled(true);
            return;
        }

        long now = System.currentTimeMillis();
        Long until = cooldownUntil(player);
        if (until != null && until > now) {
            event.setCancelled(true);
            ProgressionAdvancement.award(player, "totem_balance_cooldown");
            if (!cooldownMessage.isEmpty()) {
                player.sendMessage(cooldownMessage.replace("%time%", formatTime(until - now)));
            }
            return;
        }
        if (until != null) clearCooldown(player);
        long duration = cooldownForWorld(player.getWorld().getName());
        if (duration <= 0) return;
        long generationWhenResurrected = cooldownGeneration;

        // Commit after event dispatch. A later plugin can still cancel the event;
        // that must not consume the player's cooldown.
        pendingResurrections.add(id);
        getServer().getScheduler().runTask(this, () -> {
            pendingResurrections.remove(id);
            if (!isEnabled() || event.isCancelled() || !player.isOnline() || player.isDead()
                    || generationWhenResurrected != cooldownGeneration) return;

            long expiry = deadlineAfter(System.currentTimeMillis(), duration);
            cooldowns.put(id, expiry);
            player.getPersistentDataContainer().set(cooldownUntilKey, PersistentDataType.LONG, expiry);
            player.getPersistentDataContainer().set(cooldownGenerationKey, PersistentDataType.LONG,
                    cooldownGeneration);
            extendMaterialCooldown(player, cooldownTicks(duration));
            ProgressionAdvancement.award(player);
        });
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            clearOwnedMaterialCooldown(player, player.getUniqueId());
        }
        pendingResurrections.clear();
        cooldowns.clear();
        materialCooldownUntilTick.clear();
        vanillaEvokerTotems.clear();
    }
}
