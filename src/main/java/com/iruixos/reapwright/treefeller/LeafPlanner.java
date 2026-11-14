package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only, bounded foliage ownership estimate. Never follows player-placed
 * persistent leaves; leaves with competing log support remain untouched.
 * This is intentionally conservative around overlapping canopies.
 */
public final class LeafPlanner {
    public record Selection(Set<Block> leaves, String rejection) {
        public Selection { leaves = Collections.unmodifiableSet(new LinkedHashSet<>(leaves)); }
        public boolean valid() { return rejection == null; }
    }

    // Resolve optional materials at runtime: some species names in newer Minecraft
    // versions are not available in every Paper API build. Never call valueOf()
    // from a static initializer, since that can disable the entire plugin.
    private static final Map<String, String> SPECIES_LEAVES = Map.ofEntries(
        Map.entry("OAK", "OAK_LEAVES"),
        Map.entry("AZALEA", "AZALEA_LEAVES"),
        Map.entry("BIRCH", "BIRCH_LEAVES"),
        Map.entry("SPRUCE", "SPRUCE_LEAVES"),
        Map.entry("JUNGLE", "JUNGLE_LEAVES"),
        Map.entry("ACACIA", "ACACIA_LEAVES"),
        Map.entry("CHERRY", "CHERRY_LEAVES"),
        Map.entry("POPLAR", "RED_POPLAR_LEAVES"),
        Map.entry("DARK_OAK", "DARK_OAK_LEAVES"),
        Map.entry("PALE_OAK", "PALE_OAK_LEAVES"),
        Map.entry("RED_MUSHROOM", "RED_MUSHROOM_BLOCK"),
        Map.entry("BROWN_MUSHROOM", "BROWN_MUSHROOM_BLOCK")
    );
    private static final BlockFace[] SIDES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
        BlockFace.EAST, BlockFace.WEST
    };

    private static boolean loaded(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }

    private static int distance(Block a, Block b) {
        return Math.max(Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())),
            Math.abs(a.getZ() - b.getZ()));
    }

    public static boolean isNaturalLeaf(Block b, Material expected) {
        // Azalea trees naturally contain both plain and flowering leaf blocks.
        // Treat the pair as one canopy, not two incompatible material plans.
        if (expected == Material.AZALEA_LEAVES || expected == Material.FLOWERING_AZALEA_LEAVES) {
            if (b.getType() != Material.AZALEA_LEAVES
                    && b.getType() != Material.FLOWERING_AZALEA_LEAVES) return false;
        } else if (b.getType() != expected) return false;
        if (expected == Material.RED_MUSHROOM_BLOCK || expected == Material.BROWN_MUSHROOM_BLOCK)
            return true;
        return b.getBlockData() instanceof Leaves leaves && !leaves.isPersistent();
    }

    /**
     * Estimate ownership using the nearest surviving log rather than treating
     * every foreign log within range as an automatic veto. The comparison is
     * intentionally local: two touching canopies must not merge merely because
     * the leaf blocks connect.
     */
    private static boolean ownedByTree(Block leaf, Set<Block> logs, int reach) {
        int ours = Integer.MAX_VALUE;
        int oursSquared = Integer.MAX_VALUE;
        for (Block log : logs) {
            int d = distance(leaf, log);
            if (d < ours) { ours = d; oursSquared = squaredDistance(leaf, log); }
            else if (d == ours) oursSquared = Math.min(oursSquared, squaredDistance(leaf, log));
        }
        if (ours > reach) return false;
        int foreign = Integer.MAX_VALUE;
        int foreignSquared = Integer.MAX_VALUE;
        // Only candidates as near as our own nearest log can defeat ownership.
        for (int dx = -ours; dx <= ours; dx++) {
            for (int dy = -ours; dy <= ours; dy++) {
                for (int dz = -ours; dz <= ours; dz++) {
                    Block neighbour = leaf.getRelative(dx, dy, dz);
                    if (!loaded(neighbour)) return false;
                    if (logs.contains(neighbour)) continue;
                    String name = neighbour.getType().name();
                    if (!(name.endsWith("_LOG") || name.endsWith("_WOOD")
                            || name.endsWith("_STEM") || name.endsWith("_HYPHAE"))) continue;
                    int d = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
                    int square = dx * dx + dy * dy + dz * dz;
                    if (d < foreign || (d == foreign && square < foreignSquared)) {
                        foreign = d;
                        foreignSquared = square;
                    }
                }
            }
        }
        if (foreign > ours) return true;
        if (foreign < ours) return false;
        // When Chebyshev distance ties, prefer the physically closer log for
        // leaves near the original tree. Preserve more distant ambiguous ties.
        return ours <= 3 && oursSquared < foreignSquared;
    }

    private static int squaredDistance(Block a, Block b) {
        int dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Full three-dimensional mushroom cap, including lower perimeter walls.
     *  A cap must remain within a bounded box around its stem; touching a
     *  second independent stem rejects harvesting rather than merging fungi.
     */
    private Selection mushroomCap(Set<Block> stems, Material capType, FileConfiguration config) {
        int limit = Math.max(32, Math.min(2048,
                config.getInt("tree-feller.detection.max-foliage", 768)));
        int top = stems.stream().mapToInt(Block::getY).max().orElse(0);
        int minX = stems.stream().mapToInt(Block::getX).min().orElse(0);
        int maxX = stems.stream().mapToInt(Block::getX).max().orElse(0);
        int minZ = stems.stream().mapToInt(Block::getZ).min().orElse(0);
        int maxZ = stems.stream().mapToInt(Block::getZ).max().orElse(0);
        Set<Block> visited = new HashSet<>();
        Set<Block> cap = new LinkedHashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        for (Block stem : stems) for (int dx=-1;dx<=1;dx++)
            for (int dy=-1;dy<=2;dy++) for (int dz=-1;dz<=1;dz++) {
                Block b=stem.getRelative(dx,dy,dz);
                if (!loaded(b)) return new Selection(Set.of(), "UNLOADED_MUSHROOM_CHUNK");
                if (b.getType()==capType) queue.add(b);
            }
        while (!queue.isEmpty()) {
            Block b=queue.removeFirst();
            if (!visited.add(b)) continue;
            if (!loaded(b)) return new Selection(Set.of(), "UNLOADED_MUSHROOM_CHUNK");
            if (b.getType()!=capType) continue;
            if (b.getX()<minX-5 || b.getX()>maxX+5 || b.getZ()<minZ-5 || b.getZ()>maxZ+5
                    || b.getY()<top-5 || b.getY()>top+4)
                return new Selection(Set.of(), "MUSHROOM_CAP_OUTSIDE_BOUNDS");
            if (cap.size()>=limit) return new Selection(Set.of(), "MUSHROOM_CAP_LIMIT");
            cap.add(b);
            // Mushroom cap corners can touch diagonally, unlike ordinary leaves.
            // The bounded cap box and independent-stem check prevent unbounded merging.
            for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++)
                for (int dz=-1;dz<=1;dz++) {
                    if (dx==0 && dy==0 && dz==0) continue;
                    Block next=b.getRelative(dx,dy,dz);
                    if (!loaded(next)) return new Selection(Set.of(), "UNLOADED_MUSHROOM_CHUNK");
                    if (next.getType()==capType && !visited.contains(next)) queue.add(next);
                }
        }
        if (cap.isEmpty()) return new Selection(Set.of(), "MUSHROOM_CAP_NOT_FOUND");
        for (Block b:cap) for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++)
            for (int dy=-2;dy<=1;dy++) {
                Block near=b.getRelative(dx,dy,dz);
                if (!loaded(near)) return new Selection(Set.of(), "UNLOADED_MUSHROOM_CHUNK");
                if (near.getType()==Material.MUSHROOM_STEM && !stems.contains(near))
                    return new Selection(Set.of(), "AMBIGUOUS_MUSHROOM_CAP");
            }
        return new Selection(cap, null);
    }

    public Selection select(TreeInspector.Plan tree, FileConfiguration config) {
        if (tree.result().species().endsWith("MUSHROOM") && !tree.mushroomCaps().isEmpty())
            return new Selection(tree.mushroomCaps(), null);
        String materialName = SPECIES_LEAVES.get(tree.result().species());
        if (materialName == null) return new Selection(Set.of(), "UNSUPPORTED_FOLIAGE");
        Material material = Material.matchMaterial(materialName);
        if (tree.result().species().equals("POPLAR")) {
            // Poplar has three different leaf materials. Select the actual colour
            // represented around this validated trunk instead of POPLAR_LEAVES.
            Map<String, Integer> counts = new java.util.HashMap<>();
            for (Block log : tree.logs()) for (int dx=-2; dx<=2; dx++)
                for (int dy=-2; dy<=2; dy++) for (int dz=-2; dz<=2; dz++) {
                    Block neighbor = log.getRelative(dx,dy,dz);
                    if (!loaded(neighbor)) return new Selection(Set.of(), "UNLOADED_FOLIAGE_CHUNK");
                    String name = neighbor.getType().name();
                    if (Set.of("RED_POPLAR_LEAVES", "ORANGE_POPLAR_LEAVES",
                            "YELLOW_POPLAR_LEAVES").contains(name))
                        counts.merge(name, 1, Integer::sum);
                }
            if (!counts.isEmpty()) {
                materialName = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                        .orElseThrow().getKey();
                material = Material.matchMaterial(materialName);
            }
        }
        if (material == null || !material.isBlock()) {
            return new Selection(Set.of(), "UNAVAILABLE_FOLIAGE_MATERIAL_" + materialName);
        }
        Set<Block> logs = tree.logs();
        if (material == Material.RED_MUSHROOM_BLOCK || material == Material.BROWN_MUSHROOM_BLOCK) {
            return mushroomCap(logs, material, config);
        }
        int limit = Math.max(32, Math.min(2048, config.getInt("tree-feller.detection.max-foliage", 768)));
        // Detection examines a larger canopy than the break ownership radius.
        // This distinction follows a documented behavioural pattern, without
        // reusing any external implementation. A longer scan never authorizes
        // harvesting foliage with competing log support.
        int breakReach = Math.max(2, Math.min(6, config.getInt(
                "tree-feller.experimental-harvesting.leaves.max-log-distance", 5)));
        int detectReach = Math.max(breakReach, Math.min(7, config.getInt(
                "tree-feller.experimental-harvesting.leaves.detect-log-distance", 6)));
        int reach = tree.result().species().equals("AZALEA") ? detectReach : breakReach;
        Set<Block> selected = new LinkedHashSet<>();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        // Seeds include foliage around offset branches, but never arbitrary distant foliage.
        for (Block log : logs) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block candidate = log.getRelative(dx, dy, dz);
                if (!loaded(candidate)) return new Selection(Set.of(), "UNLOADED_FOLIAGE_CHUNK");
                if (isNaturalLeaf(candidate, material)) queue.add(candidate);
            }
        }
        while (!queue.isEmpty()) {
            Block leaf = queue.removeFirst();
            if (!visited.add(leaf)) continue;
            if (!loaded(leaf)) return new Selection(Set.of(), "UNLOADED_FOLIAGE_CHUNK");
            if (!isNaturalLeaf(leaf, material) || !ownedByTree(leaf, logs, reach)) continue;
            if (selected.size() >= limit) return new Selection(Set.of(), "FOLIAGE_LIMIT_EXCEEDED");
            selected.add(leaf);
            // Large vanilla Oak and Azalea crowns can contain leaf clusters that
            // touch only diagonally. Discover those leaves without extending the
            // harvest range: ownedByTree() still requires nearby *selected* logs
            // and rejects competing log support. Other species retain face-only
            // foliage traversal until their generation profiles are validated.
            boolean diagonalCanopy = config.getBoolean(
                    "tree-feller.experimental-harvesting.leaves.diagonal-canopy-connections", true)
                    && Set.of("OAK", "AZALEA", "CHERRY").contains(tree.result().species());
            if (diagonalCanopy) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            Block next = leaf.getRelative(dx, dy, dz);
                            if (!loaded(next)) return new Selection(Set.of(), "UNLOADED_FOLIAGE_CHUNK");
                            if (!visited.contains(next) && isNaturalLeaf(next, material)) queue.add(next);
                        }
            } else {
                for (BlockFace face : SIDES) {
                    Block next = leaf.getRelative(face);
                    if (!loaded(next)) return new Selection(Set.of(), "UNLOADED_FOLIAGE_CHUNK");
                    if (!visited.contains(next) && isNaturalLeaf(next, material)) queue.add(next);
                }
            }
        }
        // Huge mushroom caps are not leaf blocks: reject cap selection that is
        // adjacent to a second independent mushroom stem.
        if (material == Material.RED_MUSHROOM_BLOCK || material == Material.BROWN_MUSHROOM_BLOCK) {
            for (Block cap : selected) for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                Block nearby = cap.getRelative(dx, -1, dz);
                if (!loaded(nearby)) return new Selection(Set.of(), "UNLOADED_MUSHROOM_CHUNK");
                if (nearby.getType() == Material.MUSHROOM_STEM && !logs.contains(nearby))
                    return new Selection(Set.of(), "AMBIGUOUS_MUSHROOM_CAP");
            }
        }
        return new Selection(selected, null);
    }
}
