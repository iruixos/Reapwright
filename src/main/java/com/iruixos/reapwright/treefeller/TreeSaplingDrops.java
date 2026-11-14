package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Per-harvested-leaf planting-item drops, independent of per-tree bonus loot. */
final class TreeSaplingDrops {
    private static final String ROOT = "tree-feller.sapling-drops";
    private final JavaPlugin plugin;

    TreeSaplingDrops(JavaPlugin plugin) { this.plugin = plugin; }

    /**
     * Replace only the matching planting item in vanilla leaf loot; preserve sticks,
     * apples, and any other ordinary drops. Missing species entries use vanilla loot.
     * Mangrove has no vanilla propagule leaf loot and is treated as an additional roll.
     */
    Collection<ItemStack> adjust(FileConfiguration config, String species, Material leafType,
                                 ItemStack axe, Collection<ItemStack> vanilla) {
        List<ItemStack> drops = new ArrayList<>(vanilla);
        if (!config.getBoolean(ROOT + ".enabled", true)) return drops;
        if (axe.containsEnchantment(Enchantment.SILK_TOUCH)) return drops;
        Material planting = plantingItem(species, leafType);
        if (planting == null) return drops;
        String key = species.equals("AZALEA") && leafType == Material.FLOWERING_AZALEA_LEAVES
                ? "FLOWERING_AZALEA" : species;
        String path = ROOT + ".rates." + key;
        if (!config.contains(path)) return drops;
        Object configured = config.get(path);
        if (!(configured instanceof Number number)) {
            plugin.getLogger().warning("Invalid sapling chance at " + path + "; using vanilla drops.");
            return drops;
        }
        double rate = number.doubleValue();
        if (!Double.isFinite(rate) || rate < 0.0 || rate > 100.0) {
            plugin.getLogger().warning("Sapling chance must be 0..100 at " + path + "; using vanilla drops.");
            return drops;
        }
        // Do not change natural drops of unrelated foliage.
        if (!matches(species, leafType)) return drops;
        // Vanilla normal saplings: 1/20 -> 1/16 -> 1/12 -> 1/10.
        // Preserve the *relative* Fortune progression for configured baseline rates.
        int fortune = Math.min(3, Math.max(0, axe.getEnchantmentLevel(Enchantment.FORTUNE)));
        double[] multipliers = {1.0, 1.25, 20.0 / 12.0, 2.0};
        double adjustedChance = Math.min(100.0, rate * multipliers[fortune]);
        drops.removeIf(item -> item.getType() == planting);
        if (ThreadLocalRandom.current().nextDouble(100.0) < adjustedChance)
            drops.add(new ItemStack(planting, 1));
        return drops;
    }

    private static boolean matches(String species, Material foliage) {
        if (species.equals("AZALEA")) return foliage == Material.AZALEA_LEAVES
                || foliage == Material.FLOWERING_AZALEA_LEAVES;
        if (species.equals("POPLAR")) return foliage.name().endsWith("_POPLAR_LEAVES");
        return foliage.name().equals(species + "_LEAVES");
    }

    private static Material plantingItem(String species, Material foliage) {
        if (species.equals("AZALEA"))
            return foliage == Material.FLOWERING_AZALEA_LEAVES ? Material.FLOWERING_AZALEA : Material.AZALEA;
        if (species.equals("MANGROVE")) return Material.MANGROVE_PROPAGULE;
        Material result = Material.matchMaterial(species + "_SAPLING");
        return result != null && result.isItem() ? result : null;
    }
}
