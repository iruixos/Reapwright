package com.iruixos.reapwright;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class ReapwrightPlugin extends JavaPlugin implements Listener {
    private Set<Material> allowedTools;
    private Set<Material> enabledCrops;
    private int harvestWidth;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Reapwright enabled.");
    }

    private Set<Material> materials(String key) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList(key)) {
            Material material = Material.matchMaterial(name);
            if (material == null) getLogger().warning("Unknown material in " + key + ": " + name);
            else result.add(material);
        }
        return result;
    }

    private void loadSettings() {
        reloadConfig();
        allowedTools = materials("allowed-tools");
        enabledCrops = materials("enabled-crops");
        harvestWidth = Math.max(1, Math.min(9, getConfig().getInt("harvest-area-size", 1)));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /" + label + " reload");
            return true;
        }
        if (!sender.hasPermission("reapwright.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        loadSettings();
        sender.sendMessage("Reapwright configuration reloaded.");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("reapwright.use") || player.getGameMode() == GameMode.SPECTATOR) return;
        Block block = event.getClickedBlock();
        if (block == null || !enabledCrops.contains(block.getType())) return;
        if (!(block.getBlockData() instanceof Ageable crop) || crop.getAge() < crop.getMaximumAge()) return;

        PlayerInventory inventory = player.getInventory();
        ItemStack hoe = inventory.getItemInMainHand();
        if (!allowedTools.contains(hoe.getType())) return;

        // Select a square independently of the player's facing direction.
        // For even widths, evaluate all possible offsets containing the clicked crop
        // and choose the square with the most mature, connected crops of the same type.
        // The clicked crop is always the first harvested.
        List<Block> candidates = selectConnectedArea(block, harvestWidth);
        int harvested = 0;
        for (Block candidate : candidates) {
            if (!allowedTools.contains(inventory.getItemInMainHand().getType())) break;
            if (!(candidate.getBlockData() instanceof Ageable candidateAge)
                    || candidateAge.getAge() < candidateAge.getMaximumAge()) continue;
            if (harvestOne(player, inventory, hoe, candidate, candidateAge)) harvested++;
        }
        if (harvested > 0) event.setCancelled(true);
    }

    /**
     * Find the size-by-size square containing the clicked crop that captures the
     * greatest number of orthogonally connected, mature crops of its type.
     * Every possible square origin is considered, for both odd and even sizes.
     * Ties prefer the square whose center is nearest the clicked crop, followed
     * by a deterministic offset order. The clicked crop is always first.
     */
    private List<Block> selectConnectedArea(Block origin, int size) {
        Material type = origin.getType();
        List<Block> best = List.of(origin);
        int bestCenterDistance = Integer.MAX_VALUE;
        org.bukkit.block.BlockFace[] directions = {
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST
        };

        // For a square to contain the clicked crop, its lower offset along each
        // axis must be in [1 - size, 0]. This applies equally to odd/even sizes.
        for (int startX = 1 - size; startX <= 0; startX++) {
            for (int startZ = 1 - size; startZ <= 0; startZ++) {
                Set<Block> eligible = new java.util.HashSet<>();
                for (int x = startX; x < startX + size; x++) {
                    for (int z = startZ; z < startZ + size; z++) {
                        Block candidate = origin.getRelative(x, 0, z);
                        if (candidate.getType() != type) continue;
                        if (candidate.getBlockData() instanceof Ageable age
                                && age.getAge() == age.getMaximumAge()) eligible.add(candidate);
                    }
                }

                if (!eligible.remove(origin)) continue;
                List<Block> connected = new ArrayList<>();
                java.util.ArrayDeque<Block> queue = new java.util.ArrayDeque<>();
                queue.add(origin);
                while (!queue.isEmpty()) {
                    Block current = queue.removeFirst();
                    connected.add(current);
                    for (org.bukkit.block.BlockFace direction : directions) {
                        Block neighbor = current.getRelative(direction);
                        if (eligible.remove(neighbor)) queue.addLast(neighbor);
                    }
                }

                // Doubled coordinates avoid floating-point arithmetic for even sizes.
                int centerX2 = 2 * startX + size - 1;
                int centerZ2 = 2 * startZ + size - 1;
                int centerDistance = centerX2 * centerX2 + centerZ2 * centerZ2;
                if (connected.size() > best.size()
                        || (connected.size() == best.size() && centerDistance < bestCenterDistance)) {
                    best = connected;
                    bestCenterDistance = centerDistance;
                }
            }
        }
        return best;
    }

    private boolean harvestOne(Player player, PlayerInventory inventory, ItemStack hoe, Block block, Ageable crop) {
        Material replantItem = switch (block.getType()) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
        if (replantItem == null) return false;

        // Query the server's native block loot logic with the actual enchanted hoe.
        Collection<ItemStack> rawDrops = block.getDrops(hoe, player);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack drop : rawDrops) drops.add(drop.clone());

        // Vanilla Fortune boosts seeds for wheat and beetroot, not the edible crop.
        // Add 0..Fortune-level extra produce, preserving native seed drops.
        if (getConfig().getBoolean("fortune-boost-produce", true)) {
            Material produce = switch (block.getType()) {
                case WHEAT -> Material.WHEAT;
                case BEETROOTS -> Material.BEETROOT;
                default -> null;
            };
            int fortune = hoe.getEnchantmentLevel(Enchantment.FORTUNE);
            if (produce != null && fortune > 0) {
                int bonus = ThreadLocalRandom.current().nextInt(fortune + 1);
                if (bonus > 0) drops.add(new ItemStack(produce, bonus));
            }
        }

        // Reserve a replanting item from harvested drops, before using inventory.
        boolean paid = takeOne(drops, replantItem);
        boolean fromInventory = false;
        if (!paid && getConfig().getBoolean("use-inventory-for-replanting", true)) {
            fromInventory = inventory.containsAtLeast(new ItemStack(replantItem), 1);
            paid = fromInventory;
        }
        if (!paid) return false; // No free replanting; leave the crop untouched.
        if (fromInventory && !removeOne(inventory, replantItem)) return false;
        Ageable replanted = (Ageable) crop.clone();
        replanted.setAge(0);
        block.setBlockData(replanted, true);
        for (ItemStack drop : drops) {
            if (getConfig().getBoolean("insert-drops-into-inventory", true)) {
                Map<Integer, ItemStack> overflow = inventory.addItem(drop);
                for (ItemStack extra : overflow.values())
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), extra);
            } else {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
            }
        }
        if (getConfig().getBoolean("consume-durability", true) && player.getGameMode() != GameMode.CREATIVE)
            damageHoe(player, hoe);
        return true;
    }


    private boolean takeOne(List<ItemStack> drops, Material material) {
        for (int i = 0; i < drops.size(); i++) {
            ItemStack stack = drops.get(i);
            if (stack.getType() == material && stack.getAmount() > 0) {
                if (stack.getAmount() == 1) drops.remove(i);
                else stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private boolean removeOne(PlayerInventory inventory, Material material) {
        // Remove from storage only; don't accidentally consume the held hoe or armor.
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == material && item.getAmount() > 0) {
                if (item.getAmount() == 1) inventory.setItem(slot, null);
                else { item.setAmount(item.getAmount() - 1); inventory.setItem(slot, item); }
                return true;
            }
        }
        return false;
    }

    private void damageHoe(Player player, ItemStack hoe) {
        int unbreaking = hoe.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return;
        if (!(hoe.getItemMeta() instanceof Damageable damageable)) return;
        int newDamage = damageable.getDamage() + 1;
        if (newDamage >= hoe.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        damageable.setDamage(newDamage);
        hoe.setItemMeta(damageable);
        player.getInventory().setItemInMainHand(hoe);
    }
}
