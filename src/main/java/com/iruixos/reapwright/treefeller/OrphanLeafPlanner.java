package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

/** Collects only provably unsupported nonpersistent leaves near a validated
 * harvest; never follows a neighbouring canopy into a supported tree.
 * Scans before removing blocks and models the planned logs as already absent.
 */
final class OrphanLeafPlanner {
    record Selection(Set<Block> leaves, String rejection) {
        boolean valid() { return rejection == null; }
    }
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN,
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static boolean loaded(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }
    private static boolean leaf(Block b, Material type) {
        return LeafPlanner.isNaturalLeaf(b, type) && b.getBlockData() instanceof Leaves;
    }
    private static int distance(Block a, Block b) {
        return Math.max(Math.max(Math.abs(a.getX()-b.getX()), Math.abs(a.getY()-b.getY())),
                Math.abs(a.getZ()-b.getZ()));
    }
    private static boolean closeToTree(Block b, Set<Block> logs, int max) {
        for (Block log : logs) if (distance(b, log)<=max) return true;
        return false;
    }
    /** Check vanilla-compatible orthogonal leaf support within six leaf steps.
     *  Any surviving log is accepted, including logs from a neighbouring tree.
     */
    private static boolean supported(Block start, Set<Block> removedLogs, int budget) {
        Map<Block, Integer> seen = new HashMap<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        seen.put(start,0); queue.add(start);
        while (!queue.isEmpty()) {
            Block b=queue.removeFirst(); int depth=seen.get(b);
            if (depth>=6) continue;
            for (BlockFace face:FACES) {
                Block next=b.getRelative(face);
                if (!loaded(next)) return true; // fail closed
                if ((next.getType().name().endsWith("_LOG") || next.getType().name().endsWith("_WOOD"))
                        && !removedLogs.contains(next)) return true;
                if (next.getBlockData() instanceof Leaves && !seen.containsKey(next)) {
                    if (seen.size()>=budget) return true; // ambiguous, preserve
                    seen.put(next,depth+1); queue.add(next);
                }
            }
        }
        return false;
    }
    Selection select(Set<Block> logs, Set<Block> plannedLeaves, FileConfiguration config) {
        if (plannedLeaves.isEmpty() || logs.isEmpty()) return new Selection(Set.of(), null);
        if (!config.getBoolean("tree-feller.experimental-harvesting.leaves.orphan-cleanup.enabled", true))
            return new Selection(Set.of(), null);
        int max = Math.max(16,Math.min(512,config.getInt(
                "tree-feller.experimental-harvesting.leaves.orphan-cleanup.max-blocks",256)));
        int radius = Math.max(4,Math.min(9,config.getInt(
                "tree-feller.experimental-harvesting.leaves.orphan-cleanup.max-log-distance",7)));
        Material type = plannedLeaves.iterator().next().getType();
        Set<Block> selected = new LinkedHashSet<>();
        Set<Block> examined = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        // Seeds are the foliage that the primary ownership planner has confirmed.
        for (Block base : plannedLeaves) for (BlockFace face : FACES) queue.add(base.getRelative(face));
        while (!queue.isEmpty()) {
            Block b=queue.removeFirst();
            if (!examined.add(b)) continue;
            if (!loaded(b)) return new Selection(Set.of(), "UNLOADED_ORPHAN_CHUNK");
            if (!leaf(b,type) || plannedLeaves.contains(b) || !closeToTree(b,logs,radius)) continue;
            if (examined.size()>max*16) return new Selection(Set.of(), "ORPHAN_SCAN_LIMIT");
            if (!supported(b,logs,2048)) {
                if (selected.size()>=max) return new Selection(Set.of(), "ORPHAN_LEAF_LIMIT");
                selected.add(b);
                for (BlockFace face:FACES) queue.add(b.getRelative(face));
            }
        }
        return new Selection(Collections.unmodifiableSet(selected),null);
    }
}
