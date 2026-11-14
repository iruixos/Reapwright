package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Single-trunk, resource-backed replanting for experimental Tree Feller. */
final class TreeReplanter {
    private static final Set<Material> SOILS = Set.of(
            Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.PODZOL, Material.ROOTED_DIRT, Material.MOSS_BLOCK,
            Material.MYCELIUM, Material.PALE_MOSS_BLOCK);

    record Site(List<Block> positions, Map<Block, Material> soils, Material sapling) { }
    record Location(Site site, String reason) { }

    /**
     * Find the footprint from the actual lowest end of every continuous trunk
     * column. Sapling suitability is evaluated separately from tree identity.
     * Never invent/flatten a hillside planting position.
     */
    Location locateDetailed(Set<Block> logs, String species) {
        Material sapling = species.equals("AZALEA") ? Material.AZALEA
                : Material.matchMaterial(species + "_SAPLING");
        if (sapling == null || !sapling.isBlock() || logs.isEmpty())
            return new Location(null, "NO_SUPPORTED_PLANTING_MATERIAL species=" + species);
        Map<Long, List<Block>> columns = new java.util.LinkedHashMap<>();
        for (Block log : logs) {
            long key = ((long) log.getX() << 32) ^ (log.getZ() & 0xffffffffL);
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(log);
        }
        List<Block> grounded = new ArrayList<>();
        for (List<Block> column : columns.values()) {
            column.sort(java.util.Comparator.comparingInt(Block::getY));
            // Branch blocks at lower elevations must not become trunk anchors.
            // Require a contiguous vertical column of at least two logs.
            for (Block base : column) {
                if (base.getRelative(BlockFace.DOWN).getType() == base.getType()) continue;
                int run = 1;
                Block next = base.getRelative(BlockFace.UP);
                while (logs.contains(next) && run < 32) {
                    run++;
                    next = next.getRelative(BlockFace.UP);
                }
                if (run >= 2) grounded.add(base);
                break;
            }
        }
        boolean large = Set.of("DARK_OAK", "PALE_OAK", "SPRUCE", "JUNGLE").contains(species);
        // An ordinary Spruce or Jungle tree can have a single trunk too.
        List<List<Block>> candidates = new ArrayList<>();
        if (large) {
            for (Block candidate : grounded) {
                List<Block> group = new ArrayList<>();
                for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                    int x = candidate.getX() + dx, z = candidate.getZ() + dz;
                    grounded.stream().filter(b -> b.getX() == x && b.getZ() == z)
                            .findFirst().ifPresent(group::add);
                }
                if (group.size() == 4) candidates.add(group);
            }
        }
        List<Block> bases;
        if (!candidates.isEmpty()) {
            if (candidates.size() != 1)
                return new Location(null, "MULTIPLE_POSSIBLE_2X2_BASES count=" + candidates.size());
            bases = candidates.getFirst();
            int minY = bases.stream().mapToInt(Block::getY).min().orElse(0);
            int maxY = bases.stream().mapToInt(Block::getY).max().orElse(0);
            if (maxY != minY)
                return new Location(null, "UNEVEN_2X2_BASE minY=" + minY + " maxY=" + maxY);
        } else if (Set.of("DARK_OAK", "PALE_OAK").contains(species)) {
            return new Location(null, "NO_UNAMBIGUOUS_2X2_BASE groundedColumns=" + grounded.size());
        } else if (grounded.size() == 1) {
            bases = List.of(grounded.getFirst());
        } else {
            return new Location(null, "AMBIGUOUS_SINGLE_TRUNK groundedColumns=" + grounded.size());
        }
        Map<Block, Material> soils = new java.util.LinkedHashMap<>();
        for (Block base : bases) {
            Block below = base.getRelative(BlockFace.DOWN);
            Material substrate = below.getType();
            if (!SOILS.contains(substrate))
                return new Location(null, "UNSUPPORTED_SOIL at " + below.getX() + "," + below.getY()
                        + "," + below.getZ() + " type=" + substrate);
            soils.put(base, substrate);
        }
        return new Location(new Site(List.copyOf(bases), Map.copyOf(soils), sapling), "READY");
    }

    /** Consumes the original, pre-harvest inspector footprint. Never rediscover
     * a 2x2 square in the remaining logs or harvested world. */
    Location locateFromInspection(TreeInspector.Plan inspection) {
        String species = inspection.result().species();
        Material sapling = species.equals("AZALEA") ? Material.AZALEA
                : Material.matchMaterial(species + "_SAPLING");
        if (sapling == null || !sapling.isBlock())
            return new Location(null, "UNSUPPORTED_PLANT_ITEM " + species);
        List<Block> bases = inspection.trunkFootprint();
        int expected = inspection.result().twoByTwo() ? 4 : 1;
        if (bases.size()!=expected)
            return new Location(null, "NO_UNIQUE_GROUNDED_FOOTPRINT found=" + bases.size()
                    + " expected=" + expected);
        Map<Block, Material> soils = new java.util.LinkedHashMap<>();
        for (Block base : bases) {
            Block below=base.getRelative(BlockFace.DOWN);
            if (!SOILS.contains(below.getType()))
                return new Location(null, "UNSUPPORTED_SOIL at " + below.getX() + "," + below.getY()
                        + "," + below.getZ() + " type=" + below.getType());
            soils.put(base,below.getType());
        }
        return new Location(new Site(List.copyOf(bases),Map.copyOf(soils),sapling),"READY");
    }

    /** Preserve a separately verified anchor for every connected trunk before removal. */
    Location locateFromAnchor(String species, List<Block> bases) {
        Material sapling = species.equals("AZALEA") ? Material.AZALEA
                : Material.matchMaterial(species + "_SAPLING");
        if (sapling == null || !sapling.isBlock())
            return new Location(null, "UNSUPPORTED_PLANT_ITEM " + species);
        if (bases.size() != 1 && bases.size() != 4)
            return new Location(null, "INVALID_ANCHOR_COUNT " + bases.size());
        Map<Block, Material> soils = new java.util.LinkedHashMap<>();
        int y = bases.getFirst().getY();
        for (Block base : bases) {
            if (base.getY() != y) return new Location(null, "UNEVEN_2X2_BASE");
            Block soil = base.getRelative(BlockFace.DOWN);
            if (!SOILS.contains(soil.getType()))
                return new Location(null, "UNSUPPORTED_SOIL at " + soil.getLocation() + " type=" + soil.getType());
            soils.put(base, soil.getType());
        }
        return new Location(new Site(List.copyOf(bases), Map.copyOf(soils), sapling), "READY");
    }

    Site locate(Set<Block> logs, String species) { return locateDetailed(logs, species).site(); }

    /** Check only; no saplings or world blocks are modified. */
    boolean unchanged(Site site) {
        if (site == null) return false;
        for (Block position : site.positions())
            if (position.getRelative(BlockFace.DOWN).getType() != site.soils().get(position)) return false;
        return true;
    }

    private int countDrops(Map<Block, Collection<ItemStack>> drops, Material item) {
        int amount = 0;
        for (Collection<ItemStack> entries : drops.values())
            for (ItemStack stack : entries) if (stack.getType() == item) amount += stack.getAmount();
        return amount;
    }

    /** Consume one resource only AFTER a sapling has been placed and verified. */
    String plant(Site site, Map<Block, Collection<ItemStack>> drops, Player player,
                 boolean inventoryFallback, boolean freeSilkTouchReplant,
                 TreeProtectionAudit audit) {
        if (site == null) return "unsupported or ambiguous planting position";
        if (!unchanged(site)) return "soil changed";
        for (Block pos : site.positions()) if (pos.getType() != Material.AIR)
            return "planting position occupied at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " type=" + pos.getType();
        int required = site.positions().size();
        // Both azalea bush items can be replanted after an azalea tree harvest.
        // Prefer plain azalea when available, otherwise flowering azalea.
        Material selected = site.sapling();
        int inDrops = countDrops(drops, selected);
        PlayerInventory inventory = player.getInventory();
        if (selected == Material.AZALEA && inDrops < required
                && (!inventoryFallback || !inventory.containsAtLeast(new ItemStack(selected), required - inDrops))) {
            Material flowering = Material.FLOWERING_AZALEA;
            int floweringDrops = countDrops(drops, flowering);
            if (floweringDrops > 0 || (inventoryFallback
                    && inventory.containsAtLeast(new ItemStack(flowering), required - Math.min(required, floweringDrops)))) {
                selected = flowering;
                inDrops = floweringDrops;
            }
        }
        // Repair the original huge-fungus base independently of whether a
        // planting item was recovered. A missing fungus must not leave the
        // previously harvested base as Netherrack. This is a single-block,
        // recorded-site operation, not an area-wide terrain conversion.
        Material requiredNylium = selected == Material.CRIMSON_FUNGUS
                ? Material.CRIMSON_NYLIUM : selected == Material.WARPED_FUNGUS
                ? Material.WARPED_NYLIUM : null;
        boolean restoredNylium = false;
        if (requiredNylium != null) {
            if (site.positions().size() != 1) return "invalid Nether fungus planting footprint";
            Block plantingPos = site.positions().getFirst();
            Block soil = plantingPos.getRelative(BlockFace.DOWN);
            Material original = site.soils().get(plantingPos);
            if (original != requiredNylium && original != Material.NETHERRACK)
                return "invalid Nether fungus substrate " + original;
            if (soil.getType() == Material.NETHERRACK) {
                org.bukkit.block.BlockState beforeSoil = soil.getState();
                try {
                    soil.setType(requiredNylium, false);
                    if (soil.getType() != requiredNylium)
                        return "Nylium restoration failed";
                    restoredNylium = true;
                    audit.changed(player, beforeSoil, soil);
                } catch (RuntimeException ex) {
                    return "Nylium restoration failed: " + ex.getMessage();
                }
            }
        }
        String restorationNote = restoredNylium ? "; restored " + requiredNylium + " beneath fungus" : "";
        // Unlike Overworld saplings, huge Nether fungi do not normally yield a
        // matching small fungus when their stems and wart blocks are harvested.
        // Grant one *placement only*, never an item stack, after a complete
        // verified harvest; do not debit harvested drops or player inventory.
        boolean freeNetherReplant = requiredNylium != null;
        // Silk Touch removes ordinary leaf sapling drops. With the opt-in
        // configuration enabled, supply the *placement*, not a drop or item,
        // for the original, validated tree footprint. Large trees receive one
        // sapling per footprint position (normally four).
        boolean freeSilkReplant = !freeNetherReplant && freeSilkTouchReplant;
        boolean freePlacement = freeNetherReplant || freeSilkReplant;
        int fromDrops = freePlacement ? 0 : Math.min(required, inDrops);
        int fromInventory = freePlacement ? 0 : required - fromDrops;
        if (!freePlacement && fromInventory > 0 && (!inventoryFallback ||
                !inventory.containsAtLeast(new ItemStack(selected), fromInventory)))
            return "needs " + required + " " + selected + " (drops=" + inDrops + ")" + restorationNote;
        // Restore was completed independently. Attempt planting only if we
        // have the item; on placement failure retain the repaired substrate.
        List<Block> placed = new ArrayList<>();
        try {
            for (Block position : site.positions()) {
                position.setType(selected, false);
                if (position.getType() != selected)
                    throw new IllegalStateException("placement not retained");
                placed.add(position);
            }
        } catch (RuntimeException ex) {
            for (Block pos : placed) if (pos.getType() == selected)
                pos.setType(Material.AIR, false);
            return "placement failed: " + ex.getMessage() + restorationNote;
        }
        // Log placements only once the complete verified footprint has been planted.
        for (Block pos : placed) audit.placed(player, pos);
        int take = fromDrops;
        for (Collection<ItemStack> items : drops.values()) {
            java.util.Iterator<ItemStack> iter = items.iterator();
            while (iter.hasNext() && take > 0) {
                ItemStack stack = iter.next();
                if (stack.getType() != selected) continue;
                int n = Math.min(take, stack.getAmount());
                if (stack.getAmount() == n) iter.remove();
                else stack.setAmount(stack.getAmount() - n);
                take -= n;
            }
        }
        int debit = fromInventory;
        for (int slot = 0; slot < inventory.getSize() && debit > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != selected) continue;
            int n = Math.min(stack.getAmount(), debit);
            if (stack.getAmount() == n) inventory.setItem(slot, null);
            else stack.setAmount(stack.getAmount() - n);
            debit -= n;
        }
        if (freeNetherReplant)
            return "replanted 1 " + selected + " (free Nether fungus replant)" + restorationNote;
        if (freeSilkReplant)
            return "replanted " + required + " " + selected + " (free Silk Touch replant)";
        return "replanted " + required + " " + selected + " (" + fromDrops
                + " harvested, " + fromInventory + " inventory)"
                + restorationNote;
    }
}
