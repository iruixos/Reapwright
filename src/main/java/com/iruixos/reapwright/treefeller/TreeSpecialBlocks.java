package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Beehive;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.CreakingHeart;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Bee;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BlockItemDataProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tree-owned generated structures: bee nests and natural Creaking Hearts.
 * All world interactions must run synchronously on the server thread.
 */
final class TreeSpecialBlocks {
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    record Selection(Set<Block> nests, Set<Block> hearts, Map<Block, String> originalData,
                     String reason) {
        Selection {
            nests = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(nests));
            hearts = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(hearts));
            originalData = Map.copyOf(originalData);
        }
        boolean valid() { return reason == null; }
        Set<Block> blocks() {
            Set<Block> all = new LinkedHashSet<>(nests);
            all.addAll(hearts);
            return all;
        }
    }

    private static boolean loaded(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }

    static Selection select(String species, Set<Block> logs, org.bukkit.World world) {
        Set<Block> nests = new LinkedHashSet<>(), hearts = new LinkedHashSet<>();
        Map<Block, String> snapshots = new LinkedHashMap<>();
        for (Block log : logs) for (BlockFace face : FACES) {
            Block b = log.getRelative(face);
            if (!loaded(b)) return new Selection(Set.of(), Set.of(), Map.of(), "UNLOADED_SPECIAL_BLOCK_CHUNK");
            if (b.getType() == Material.BEE_NEST || b.getType() == Material.BEEHIVE) {
                if (!(b.getState() instanceof Beehive))
                    return new Selection(Set.of(), Set.of(), Map.of(), "UNREADABLE_BEE_NEST");
                nests.add(b);
            }
            if (species.equals("PALE_OAK") && b.getType() == Material.CREAKING_HEART) {
                if (!(b.getBlockData() instanceof CreakingHeart heart) || !heart.isNatural())
                    return new Selection(Set.of(), Set.of(), Map.of(), "UNOWNED_OR_PLACED_CREAKING_HEART");
                // A natural Heart is embedded between two selected Pale Oak logs.
                // Never harvest a Heart that only borders a neighbouring tree.
                boolean opposed = (logs.contains(b.getRelative(BlockFace.UP)) && logs.contains(b.getRelative(BlockFace.DOWN)))
                        || (logs.contains(b.getRelative(BlockFace.NORTH)) && logs.contains(b.getRelative(BlockFace.SOUTH)))
                        || (logs.contains(b.getRelative(BlockFace.EAST)) && logs.contains(b.getRelative(BlockFace.WEST)));
                if (!opposed) return new Selection(Set.of(), Set.of(), Map.of(), "AMBIGUOUS_CREAKING_HEART_OWNERSHIP");
                hearts.add(b);
            }
        }
        if (nests.size() > 8 || hearts.size() > 8)
            return new Selection(Set.of(), Set.of(), Map.of(), "SPECIAL_BLOCK_LIMIT_EXCEEDED");
        for (Block b : nests) snapshots.put(b, b.getBlockData().getAsString());
        for (Block b : hearts) snapshots.put(b, b.getBlockData().getAsString());
        return new Selection(nests, hearts, snapshots, null);
    }

    static boolean unchanged(Selection selection) {
        for (var pair : selection.originalData().entrySet()) {
            Block b = pair.getKey();
            if (!loaded(b) || !b.getBlockData().getAsString().equals(pair.getValue())) return false;
        }
        return true;
    }

    static boolean harvest(Block b, ItemStack tool, Player player) {
        if (b.getType() == Material.BEE_NEST || b.getType() == Material.BEEHIVE)
            return harvestNest(b, tool, player);
        if (b.getType() == Material.CREAKING_HEART) return harvestHeart(b, tool, player);
        return false;
    }

    private static boolean harvestHeart(Block b, ItemStack tool, Player player) {
        if (!(b.getBlockData() instanceof CreakingHeart state) || !state.isNatural()) return false;
        // Vanilla loot tables perform the 100%-guaranteed resin-clump roll,
        // Fortune scaling and Silk Touch item selection. Use a real block break
        // to trigger the Heart's Creaking entity/link teardown.
        boolean broken = b.breakNaturally(tool, true, false);
        if (!broken || b.getType() != Material.AIR) return false;
        // Experience is explicitly managed to avoid double XP. This harvest path
        // is restricted to Survival and naturally-generated Heart blocks.
        int xp = ThreadLocalRandom.current().nextInt(20, 25);
        player.getWorld().spawn(b.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class,
                orb -> orb.setExperience(xp));
        return true;
    }

    private static boolean harvestNest(Block b, ItemStack tool, Player player) {
        if (!(b.getState() instanceof Beehive hive)) return false;
        Material originalType = b.getType();
        if (originalType != Material.BEE_NEST && originalType != Material.BEEHIVE) return false;
        boolean silkTouch = tool != null && tool.containsEnchantment(Enchantment.SILK_TOUCH);

        // Only bees linked to this exact hive count. Nearby unrelated bees are not targeted.
        // Keep the original hive coordinates because destroying the block can clear their link.
        List<Bee> outside = new ArrayList<>();
        for (Bee bee : b.getWorld().getNearbyEntitiesByType(Bee.class,
                b.getLocation().add(0.5, 0.5, 0.5), 12)) {
            if (bee.getHive() != null && bee.getHive().getBlock().equals(b)) outside.add(bee);
        }

        // Vanilla's Silk Touch loot table serializes both the BEES component
        // and BLOCK_DATA (including honey_level). BlockStateMeta alone only
        // copies the block entity data; it does not preserve honey_level.
        // Calculate loot *before* the world block is changed or bees released.
        ItemStack hiveItem = null;
        if (silkTouch) {
            int originalHoney = -1;
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Beehive beeData)
                originalHoney = beeData.getHoneyLevel();
            if (originalHoney < 0) return false;
            for (ItemStack candidate : b.getDrops(tool, player)) {
                if (candidate.getType() != originalType || candidate.getAmount() != 1) continue;
                BlockItemDataProperties properties = candidate.getData(DataComponentTypes.BLOCK_DATA);
                if (properties == null) continue;
                org.bukkit.block.data.BlockData placedData = properties.applyTo(b.getBlockData());
                if (!(placedData instanceof org.bukkit.block.data.type.Beehive serialized)
                        || serialized.getHoneyLevel() != originalHoney) continue;
                hiveItem = candidate.clone();
                break;
            }
            // Fail safely instead of removing a nest and silently losing honey.
            if (hiveItem == null) return false;
        }

        List<Bee> released = new ArrayList<>();
        if (!silkTouch && hive.getEntityCount() > 0) {
            // Releasing bees while the hive still exists is necessary to avoid
            // silently losing resident entities when the block is removed.
            released.addAll(hive.releaseEntities());
            if (!hive.update(true, false)) return false;
        }

        // Do not rely on the normal block loot path: doing so could duplicate the
        // Silk Touch hive item or add unwanted drops. The two branches are exclusive.
        b.setType(Material.AIR, false);
        if (b.getType() != Material.AIR) return false;
        if (silkTouch) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), hiveItem);
        } else {
            // Configured behaviour: a small honeycomb reward with or without honey.
            int count = ThreadLocalRandom.current().nextInt(1, 4);
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5),
                    new ItemStack(Material.HONEYCOMB, count));
        }

        // Outdoors bees always retaliate when their linked hive is harvested.
        // Residents retaliate only when released by a non-Silk Touch harvest.
        Set<Bee> attackers = new LinkedHashSet<>(outside);
        attackers.addAll(released);
        for (Bee bee : attackers) {
            if (!bee.isValid() || bee.hasStung()) continue;
            bee.setAnger(600);
            bee.setTarget(player);
        }
        return true;
    }
}
