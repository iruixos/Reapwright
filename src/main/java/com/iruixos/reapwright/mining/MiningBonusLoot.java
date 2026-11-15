package com.iruixos.reapwright.mining;

import com.iruixos.reapwright.ConfigVersionComments;
import com.iruixos.reapwright.ModuleConfigFiles;
import com.iruixos.reapwright.ReapwrightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Bonus items are generated from the vanilla and Veinminer drop events.
 * IMPORTANT: this does not establish whether a block is world-generated.
 */
public final class MiningBonusLoot implements Listener {
    private static final String CONFIG_FILE = "config-mining.yml";
    private static final String LEGACY_FORTUNE_KEY = "fortune-quantity-bonus-per-level";
    private static final List<String> FORTUNE_TIERS = List.of("I", "II", "III");
    private static final List<Double> DEFAULT_FORTUNE_MULTIPLIERS = List.of(1.0, 1.25, 1.667, 2.0);
    private final ReapwrightPlugin plugin;
    private boolean enabled;
    private boolean bonusLootEnabled;
    private boolean diamond;
    private boolean netherite;
    private List<Double> fortuneMultipliers = DEFAULT_FORTUNE_MULTIPLIERS;
    private Map<World.Environment, Map<Material, List<Reward>>> environmentRewards = Map.of();
    private Map<String, Map<Material, List<Reward>>> worldRewards = Map.of();

    private final Map<ManualBreak, MiningTool> manualTools = new HashMap<>();
    private int manualToolsTick;

    private record ManualBreak(UUID player, Location block) {}

    record MiningTool(Material material, int fortune, boolean silkTouch) {
        static MiningTool capture(ItemStack item) {
            return new MiningTool(item.getType(), Math.clamp(item.getEnchantmentLevel(Enchantment.FORTUNE),
                    0, FORTUNE_TIERS.size()), item.containsEnchantment(Enchantment.SILK_TOUCH));
        }
    }

    private record Reward(Material item, double chancePercent, int min, int max) {}

    public MiningBonusLoot(ReapwrightPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), CONFIG_FILE);
        YamlConfiguration config = new YamlConfiguration();
        // Preserve literal world names containing dots in the YAML mapping.
        config.options().pathSeparator((char) 31);
        try {
            if (!ModuleConfigFiles.useExisting(plugin, file, "reapwright-module-mining.yml", "mining-drops.yml")) {
                plugin.saveResource(CONFIG_FILE, false);
            }
            config.load(file);
            ConfigVersionComments.ensure(file.toPath(), plugin.getPluginMeta().getVersion(), plugin.getLogger());
        } catch (IOException | InvalidConfigurationException error) {
            enabled = false;
            plugin.getLogger().severe("Mining disabled because " + CONFIG_FILE + " could not be loaded: " + error.getMessage());
            logStatus(0);
            return;
        }
        boolean nextEnabled = config.getBoolean("enabled", false);
        ConfigurationSection bonusLoot = config.getConfigurationSection("bonus-loot");
        boolean nextBonusLootEnabled = bonusLoot == null || bonusLoot.getBoolean("enabled", true);
        ConfigurationSection tools = config.getConfigurationSection("eligible-tools");
        boolean nextDiamond = tools == null || tools.getBoolean("diamond", true);
        boolean nextNetherite = tools == null || tools.getBoolean("netherite", true);
        Set<Material> nextBlocks = EnumSet.noneOf(Material.class);
        for (String key : config.getStringList("enabled-blocks")) {
            Material block = Material.matchMaterial(key);
            if (block == Material.ANCIENT_DEBRIS) continue;
            if (block != null && (ALLOWED_ROCKS.contains(block) || OVERWORLD_ORES.contains(block)
                    || NETHER_ORES.contains(block))) {
                nextBlocks.add(block);
            } else {
                plugin.getLogger().warning("Mining: unknown or unsupported block material: " + key);
            }
        }
        Set<Material> rockBlocks = EnumSet.copyOf(ALLOWED_ROCKS);
        rockBlocks.retainAll(nextBlocks);
        Map<World.Environment, Map<Material, List<Reward>>> nextEnvironments = new EnumMap<>(World.Environment.class);
        ConfigurationSection drops = config.getConfigurationSection("drops");
        if (drops != null && (drops.contains("overworld") || drops.contains("nether") || drops.contains("end"))) {
            nextEnvironments.put(World.Environment.NORMAL, readTable(drops.getConfigurationSection("overworld"), rockBlocks));
            nextEnvironments.put(World.Environment.NETHER, readTable(drops.getConfigurationSection("nether"), rockBlocks));
            nextEnvironments.put(World.Environment.THE_END, readTable(drops.getConfigurationSection("end"), rockBlocks));
        } else {
            // Older configs stored every rock in a flat drops section.
            readTable(drops, rockBlocks).forEach((rock, entries) -> {
                World.Environment environment = switch (rock) {
                    case BASALT, BLACKSTONE, NETHERRACK -> World.Environment.NETHER;
                    case END_STONE -> World.Environment.THE_END;
                    default -> World.Environment.NORMAL;
                };
                nextEnvironments.computeIfAbsent(environment, ignored -> new EnumMap<>(Material.class))
                        .put(rock, entries);
            });
        }
        ConfigurationSection oreDrops = config.getConfigurationSection("ore-drops");
        if (oreDrops != null) {
            addOreTable(nextEnvironments, World.Environment.NORMAL, oreDrops.getConfigurationSection("overworld"),
                    OVERWORLD_ORES, nextBlocks);
            addOreTable(nextEnvironments, World.Environment.NETHER, oreDrops.getConfigurationSection("nether"),
                    NETHER_ORES, nextBlocks);
        }
        Map<String, Map<Material, List<Reward>>> nextWorlds = new HashMap<>();
        ConfigurationSection worlds = config.getConfigurationSection("world-drops");
        if (worlds != null) for (var entry : worlds.getValues(false).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection table) {
                nextWorlds.put(entry.getKey(), readTable(table, nextBlocks));
            } else {
                plugin.getLogger().warning("Mining: expected a drop table for world " + entry.getKey());
            }
        }
        diamond = nextDiamond;
        netherite = nextNetherite;
        fortuneMultipliers = readFortuneMultipliers(config);
        environmentRewards = nextEnvironments;
        worldRewards = nextWorlds;
        enabled = nextEnabled;
        bonusLootEnabled = nextBonusLootEnabled;
        logStatus(nextBlocks.size());
    }

    private void logStatus(int allowedBlockTypes) {
        plugin.getLogger().info("Mining: module " + (enabled ? "enabled" : "disabled")
                + ", bonus loot " + (isBonusLootActive() ? "enabled" : "disabled")
                + ", " + allowedBlockTypes + " allowed block types.");
    }

    private void addOreTable(Map<World.Environment, Map<Material, List<Reward>>> environments,
                             World.Environment environment, ConfigurationSection table,
                             EnumSet<Material> dimensionOres, Set<Material> enabledBlocks) {
        if (table == null) return;
        EnumSet<Material> eligibleOres = EnumSet.copyOf(dimensionOres);
        eligibleOres.retainAll(enabledBlocks);
        environments.computeIfAbsent(environment, ignored -> new EnumMap<>(Material.class))
                .putAll(readTable(table, eligibleOres));
    }

    private List<Double> readFortuneMultipliers(YamlConfiguration config) {
        ConfigurationSection tiers = config.getConfigurationSection("fortune-quantity-multipliers");
        if (tiers == null && config.contains(LEGACY_FORTUNE_KEY)) {
            double bonus = config.getDouble(LEGACY_FORTUNE_KEY, 0.25);
            if (!Double.isFinite(bonus) || bonus < 0 || bonus > 1) {
                plugin.getLogger().warning("Mining: invalid legacy Fortune multiplier; using 0.25 per level.");
                bonus = 0.25;
            }
            return List.of(1.0, 1.0 + bonus, 1.0 + 2 * bonus, 1.0 + 3 * bonus);
        }
        if (tiers == null) return DEFAULT_FORTUNE_MULTIPLIERS;

        List<Double> multipliers = new ArrayList<>(DEFAULT_FORTUNE_MULTIPLIERS);
        for (int index = 0; index < FORTUNE_TIERS.size(); index++) {
            String tier = FORTUNE_TIERS.get(index);
            double multiplier = tiers.getDouble(tier, multipliers.get(index + 1));
            if (!Double.isFinite(multiplier) || multiplier < 1 || multiplier > 4) {
                plugin.getLogger().warning("Mining: Fortune " + tier + " multiplier must be between 1.0 and 4.0.");
                continue;
            }
            multipliers.set(index + 1, multiplier);
        }
        return List.copyOf(multipliers);
    }

    private Map<Material, List<Reward>> readTable(ConfigurationSection tables, Set<Material> eligibleBlocks) {
        Map<Material, List<Reward>> rewards = new EnumMap<>(Material.class);
        if (tables == null) return rewards;
        for (String key : tables.getKeys(false)) {
            Material block = Material.matchMaterial(key);
            if (block == null || !eligibleBlocks.contains(block)) continue;
            ConfigurationSection table = tables.getConfigurationSection(key);
            if (table == null) continue;
            List<Reward> entries = new ArrayList<>();
            for (String name : table.getKeys(false)) {
                ConfigurationSection entry = table.getConfigurationSection(name);
                Material item = Material.matchMaterial(name);
                if (entry == null || item == null || !item.isItem() || item.isAir()) {
                    plugin.getLogger().warning("Mining: invalid item: " + table.getCurrentPath() + "." + name);
                    continue;
                }
                double chance = entry.getDouble("chance", -1);
                int min = entry.getInt("min", 0), max = entry.getInt("max", 0);
                if (!Double.isFinite(chance) || chance < 0 || chance > 100 || min < 1 || max < min || max > 2304) {
                    plugin.getLogger().warning("Mining: invalid chance/quantity: " + table.getCurrentPath() + "." + name);
                    continue;
                }
                entries.add(new Reward(item, chance, min, max));
            }
            rewards.put(block, List.copyOf(entries));
        }
        return rewards;
    }

    private static final Set<Material> ALLOWED_ROCKS = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.CALCITE, Material.DRIPSTONE_BLOCK,
            Material.SMOOTH_BASALT, Material.SULFUR, Material.CINNABAR,
            Material.SANDSTONE, Material.RED_SANDSTONE, Material.BASALT,
            Material.BLACKSTONE, Material.NETHERRACK, Material.END_STONE);

    private static final EnumSet<Material> OVERWORLD_ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private static final EnumSet<Material> NETHER_ORES = EnumSet.of(
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS);

    boolean isBonusLootActive() {
        return enabled && bonusLootEnabled;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void rememberManualTool(BlockBreakEvent event) {
        if (!isBonusLootActive() || !event.isDropItems() || event.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        int tick = Bukkit.getCurrentTick();
        if (manualToolsTick != tick) {
            manualTools.clear();
            manualToolsTick = tick;
        }
        Player player = event.getPlayer();
        manualTools.put(new ManualBreak(player.getUniqueId(), event.getBlock().getLocation()),
                MiningTool.capture(player.getInventory().getItemInMainHand()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        if (!isBonusLootActive() || manualToolsTick != Bukkit.getCurrentTick()) return;
        // Paper has already applied durability by the time the drop event fires.
        MiningTool tool = manualTools.remove(new ManualBreak(event.getPlayer().getUniqueId(),
                event.getBlock().getLocation()));
        if (tool == null || event.getItems().isEmpty()) return;
        addRewards(event.getPlayer(), event.getBlock().getWorld(), event.getBlockState().getType(),
                tool, reward -> event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), reward));
    }

    void addRewards(Player player, World world, Material material, MiningTool tool, Consumer<ItemStack> addItem) {
        // Ancient Debris drops itself even without Silk Touch and can be placed and mined repeatedly.
        if (!isBonusLootActive() || tool.silkTouch() || material == Material.ANCIENT_DEBRIS) return;
        if (player.getGameMode() != GameMode.SURVIVAL || !player.hasPermission("reapwright.mining")) return;
        Map<Material, List<Reward>> tableByBlock = worldRewards.get(world.getName());
        if (tableByBlock == null) tableByBlock = environmentRewards.get(world.getEnvironment());
        if (tableByBlock == null) return;
        List<Reward> table = tableByBlock.get(material);
        if (table == null || table.isEmpty()) return;
        if (!(tool.material() == Material.DIAMOND_PICKAXE && diamond
                || tool.material() == Material.NETHERITE_PICKAXE && netherite)) return;
        double multiplier = fortuneMultipliers.get(tool.fortune());
        for (Reward reward : table) {
            if (ThreadLocalRandom.current().nextDouble(100.0) >= reward.chancePercent()) continue;
            int base = ThreadLocalRandom.current().nextInt(reward.min(), reward.max() + 1);
            double scaled = base * multiplier;
            int quantity = Math.min(4096, (int) Math.floor(scaled));
            if (quantity < 4096 && ThreadLocalRandom.current().nextDouble() < scaled - Math.floor(scaled)) quantity++;
            while (quantity > 0) {
                int amount = Math.min(quantity, reward.item().getMaxStackSize());
                addItem.accept(new ItemStack(reward.item(), amount));
                quantity -= amount;
            }
        }
    }
}
