package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Independent per-entry percentage rolls, once per successfully felled tree. */
final class TreeBonusLoot {
    private final JavaPlugin plugin;

    TreeBonusLoot(JavaPlugin plugin) { this.plugin = plugin; }

    List<ItemStack> roll(FileConfiguration config, String species, ItemStack axe) {
        String root = "tree-feller.bonus-loot";
        if (!config.getBoolean(root + ".enabled", true)) return List.of();
        boolean eligible = axe.getType() == Material.NETHERITE_AXE
                && config.getBoolean(root + ".eligible-tools.netherite", true);
        // Keep the previous diamond-fortune-3 option as a migration fallback for
        // existing server config.yml files. Fortune is no longer an eligibility gate.
        boolean diamondEnabled = config.contains(root + ".eligible-tools.diamond")
                ? config.getBoolean(root + ".eligible-tools.diamond", true)
                : config.getBoolean(root + ".eligible-tools.diamond-fortune-3", true);
        eligible |= axe.getType() == Material.DIAMOND_AXE && diamondEnabled;
        if (!eligible) return List.of();
        ConfigurationSection table = config.getConfigurationSection(root + ".trees." + species);
        if (table == null) return List.of();
        // Scale successful bonus quantities, not their independently configured
        // probabilities. The fractional part is rounded probabilistically to
        // preserve the expected reward over repeated tree harvests.
        int fortune = Math.min(3, Math.max(0, axe.getEnchantmentLevel(Enchantment.FORTUNE)));
        double perLevel = config.getDouble(root + ".fortune-quantity-bonus-per-level", 0.25);
        if (!Double.isFinite(perLevel) || perLevel < 0.0 || perLevel > 1.0) {
            plugin.getLogger().warning("Invalid bonus-loot fortune-quantity-bonus-per-level; using 0.25");
            perLevel = 0.25;
        }
        double multiplier = 1.0 + fortune * perLevel;
        List<ItemStack> drops = new ArrayList<>();
        for (String key : table.getKeys(false)) {
            ConfigurationSection entry = table.getConfigurationSection(key);
            if (entry == null) { plugin.getLogger().warning("Invalid bonus loot entry: " + key); continue; }
            Material material = Material.matchMaterial(key);
            double chance = entry.getDouble("chance", -1);
            int min = entry.getInt("min", 0), max = entry.getInt("max", 0);
            if (material == null || !material.isItem() || material.isAir()
                    || !Double.isFinite(chance) || chance < 0 || chance > 100
                    || min < 1 || max < min || max > 2304) {
                plugin.getLogger().warning("Skipping invalid tree bonus loot entry: " + key);
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble(100.0) >= chance) continue;
            int baseQuantity = ThreadLocalRandom.current().nextInt(min, max + 1);
            double scaledQuantity = baseQuantity * multiplier;
            int quantity = (int) Math.floor(scaledQuantity);
            if (ThreadLocalRandom.current().nextDouble() < scaledQuantity - quantity) quantity++;
            int stackSize = material.getMaxStackSize();
            while (quantity > 0) {
                int size = Math.min(stackSize, quantity);
                drops.add(new ItemStack(material, size));
                quantity -= size;
            }
        }
        return drops;
    }
}
