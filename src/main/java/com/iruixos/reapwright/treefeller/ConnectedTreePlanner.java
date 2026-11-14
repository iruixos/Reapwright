package com.iruixos.reapwright.treefeller;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

/**
 * Original, clean-room grounded-trunk selection. One ownership cluster, multiple
 * independently planted anchors. Natural growth can share logs, but the plan
 * holds them only once. Any ambiguous base layout is rejected before removal.
 */
public final class ConnectedTreePlanner {
    public record Selection(List<List<Block>> anchors, String rejection) {
        public Selection { anchors = anchors.stream().map(List::copyOf).toList(); }
        public boolean valid() { return rejection == null; }
        public int trees() { return anchors.size(); }
    }
    private static Selection fail(String reason) { return new Selection(List.of(), reason); }
    private static long columnKey(int x,int z) { return ((long)x<<32) ^ (z & 0xffffffffL); }
    private static boolean loaded(Block b) { return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4); }

    public Selection select(TreeInspector.Plan inspected, FileConfiguration config) {
        String species=inspected.result().species();
        Set<Block> logs=inspected.logs();
        if (logs.isEmpty()) return fail("EMPTY_LOG_CLUSTER");
        int maxTrees=Math.max(1,Math.min(16,config.getInt(
                "tree-feller.detection.connected-structures.max-connected-trees",8)));
        boolean needsFour=Set.of("DARK_OAK", "PALE_OAK").contains(species);
        boolean acceptsFour=needsFour || Set.of("SPRUCE", "JUNGLE").contains(species);
        // Only bottommost grounded logs of real vertical columns are candidates.
        Map<Long, Block> grounded=new LinkedHashMap<>();
        int floor=logs.stream().mapToInt(Block::getY).min().orElse(0);
        for (Block log:logs) {
            Block below=log.getRelative(BlockFace.DOWN);
            if (!loaded(below)) return fail("UNLOADED_ANCHOR_CHUNK");
            if (logs.contains(below) || !below.getType().isSolid()) continue;
            // Avoid declaring a high canopy branch a separate tree.
            if (log.getY()>floor+4) continue;
            int vertical=1;
            for (Block next=log.getRelative(BlockFace.UP); logs.contains(next) && vertical<5;
                 next=next.getRelative(BlockFace.UP)) vertical++;
            if (vertical<3) continue;
            long key=columnKey(log.getX(),log.getZ());
            Block prior=grounded.get(key);
            if (prior==null || log.getY()<prior.getY()) grounded.put(key,log);
        }
        if (grounded.isEmpty()) return new Selection(List.of(), null);
        List<List<Block>> anchors=new ArrayList<>();
        // Deterministic, nonoverlapping partition of anchored trunk columns.
        // For example, two touching 2x2 trunks form a 4x2 rectangle: although
        // a sliding search finds three overlapping 2x2 squares, the grounded
        // columns have one complete tiling using the left and right squares.
        // Picking every sliding square would incorrectly reject this normal case.
        Set<Block> unclaimed=new LinkedHashSet<>(grounded.values());
        while (!unclaimed.isEmpty()) {
            Block corner=unclaimed.stream()
                    .min(Comparator.comparingInt(Block::getX)
                            .thenComparingInt(Block::getZ).thenComparingInt(Block::getY))
                    .orElseThrow();
            List<Block> four=new ArrayList<>();
            if (acceptsFour) {
                for (int x=0;x<2;x++) for(int z=0;z<2;z++) {
                    Block b=grounded.get(columnKey(corner.getX()+x,corner.getZ()+z));
                    if (b!=null && unclaimed.contains(b)
                            && Math.abs(b.getY()-corner.getY())<=1) four.add(b);
                }
            }
            if (four.size()==4) {
                anchors.add(List.copyOf(four));
                unclaimed.removeAll(four);
            } else if (!needsFour) {
                anchors.add(List.of(corner));
                unclaimed.remove(corner);
            } else {
                // A grounded offshoot does not need to be another 2x2 trunk.
                // Ignore it for planting; it is still harvested if it was in
                // the validated log plan. Never invent a sapling footprint.
                unclaimed.remove(corner);
            }
            if (anchors.size()>maxTrees)
                return fail("CONNECTED_TREE_LIMIT_EXCEEDED count="+anchors.size());
        }
        // Missing planting anchors are not a reason to refuse harvesting.
        if (anchors.size()>maxTrees) return fail("CONNECTED_TREE_LIMIT_EXCEEDED count="+anchors.size());
        // All bottom anchors in this cluster must have been accounted for.
        // This is not a license to harvest any nearby player-placed wood.
        return new Selection(anchors,null);
    }
}
