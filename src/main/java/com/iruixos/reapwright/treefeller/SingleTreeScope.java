package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent clean-room scope selection for a single vanilla tree.
 *
 * The goal is deliberately narrower than a log flood-fill: trees that happen
 * to touch through branches do not become one permitted harvesting operation.
 * No world data is changed while establishing this scope.
 */
final class SingleTreeScope {
    /** North-west corner and ground level of an independently rooted 2x2 tree. */
    private record Root(int x, int z, int y) { }
    private final Root selected;
    private final List<Root> neighbours;
    private final int radius;
    private final int logBudget;
    private final boolean doubleTrunk;

    private SingleTreeScope(Root selected, List<Root> neighbours, int radius,
                            int logBudget, boolean doubleTrunk) {
        this.selected = selected;
        this.neighbours = List.copyOf(neighbours);
        this.radius = radius;
        this.logBudget = logBudget;
        this.doubleTrunk = doubleTrunk;
    }

    int maxRadius() { return radius; }
    int maxLogs() { return logBudget; }

    boolean owns(Block block) {
        int x = block.getX(), z = block.getZ();
        if (Math.abs(x - selected.x) > radius || Math.abs(z - selected.z) > radius) return false;
        int home = distance4(x, z, selected, doubleTrunk);
        for (Root other : neighbours) {
            // A tie is not proof that the log belongs to the selected tree.
            // Keep the neighbour's side of a shared junction untouched.
            if (distance4(x, z, other, doubleTrunk) <= home) return false;
        }
        return true;
    }

    /** Squared distance to the centre of a 2x2 base, multiplied by four. */
    private static int distance4(int x, int z, Root root, boolean large) {
        int dx = 2 * (x - root.x) - (large ? 1 : 0);
        int dz = 2 * (z - root.z) - (large ? 1 : 0);
        return dx * dx + dz * dz;
    }

    /** True when two candidate 2x2 bases share a column. */
    private static boolean overlaps(Root a, Root b) {
        return Math.abs(a.x - b.x) < 2 && Math.abs(a.z - b.z) < 2;
    }

    static SingleTreeScope discover(Block clicked, FileConfiguration config) {
        String type = clicked.getType().name();
        boolean canHaveGiantTrunk = Set.of("DARK_OAK_LOG", "PALE_OAK_LOG",
                "JUNGLE_LOG", "SPRUCE_LOG").contains(type);
        boolean requiresGiantTrunk = type.equals("DARK_OAK_LOG") || type.equals("PALE_OAK_LOG");
        // These internal budgets are deliberately species-aware: a giant jungle
        // must not inherit the old generic 8-block / 160-log scope cap.
        int defaultRadius = type.equals("JUNGLE_LOG") ? 20 : type.equals("SPRUCE_LOG") ? 12 : 8;
        int radiusCeiling = type.equals("JUNGLE_LOG") ? 24 : 16;
        int defaultLogs = type.equals("JUNGLE_LOG") ? 768 : type.equals("SPRUCE_LOG") ? 384 : 160;
        int logCeiling = type.equals("JUNGLE_LOG") ? 1024 : 512;
        int radius = Math.max(4, Math.min(radiusCeiling, config.getInt(
                "tree-feller.detection.single-tree.max-horizontal-radius", defaultRadius)));
        int maxLogs = Math.max(24, Math.min(logCeiling, config.getInt(
                "tree-feller.detection.single-tree.max-logs", defaultLogs)));
        if (!canHaveGiantTrunk) return discoverLegacy(clicked, radius, maxLogs, false);

        int top = Math.min(clicked.getWorld().getMaxHeight() - 4, clicked.getY() + 12);
        int bottom = Math.max(clicked.getWorld().getMinHeight() + 1, clicked.getY() - 96);
        // Find genuine vertical columns separately. Do not require all four
        // bases to be level: vanilla trees can be generated on sloped terrain.
        java.util.Map<Long, Integer> columns = new java.util.HashMap<>();
        for (int x = clicked.getX() - radius; x <= clicked.getX() + radius; x++)
            for (int z = clicked.getZ() - radius; z <= clicked.getZ() + radius; z++) {
                if (!clicked.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = bottom; y <= top; y++) {
                    Block b = clicked.getWorld().getBlockAt(x, y, z);
                    if (b.getType() != clicked.getType() || !grounded(b)) continue;
                    // Two blocks above the base must be logs. This excludes
                    // most downward side branches and stranded single logs.
                    if (b.getRelative(0, 1, 0).getType() != clicked.getType()
                            || b.getRelative(0, 2, 0).getType() != clicked.getType()) continue;
                    columns.put(pack(x, z), y);
                    break;
                }
            }

        List<Root> candidates = new ArrayList<>();
        for (var entry : columns.entrySet()) {
            int x = (int)(entry.getKey() >> 32), z = (int)(long)entry.getKey();
            Integer a = columns.get(pack(x, z));
            Integer b = columns.get(pack(x + 1, z));
            Integer c = columns.get(pack(x, z + 1));
            Integer d = columns.get(pack(x + 1, z + 1));
            if (a == null || b == null || c == null || d == null) continue;
            int lo = Math.min(Math.min(a, b), Math.min(c, d));
            int hi = Math.max(Math.max(a, b), Math.max(c, d));
            if (hi - lo <= 3) candidates.add(new Root(x, z, lo));
        }
        candidates.sort(Comparator.comparingInt((Root r) -> distance4(
                    clicked.getX(), clicked.getZ(), r, true))
                .thenComparingInt(r -> Math.abs(clicked.getY() - r.y)));
        if (candidates.isEmpty()) {
            // Retain the existing fallback for irregular or damaged trees.
            return discoverLegacy(clicked, radius, maxLogs, requiresGiantTrunk);
        }
        Root selected = candidates.getFirst();
        if (!requiresGiantTrunk) {
            // Jungle and Spruce also have genuine 1x1 forms. When the clicked
            // column is well away from every 2x2 root, find an independent 1x1
            // root instead of adopting a nearby giant tree merely by species.
            int dx = Math.max(0, Math.max(selected.x - clicked.getX(), clicked.getX() - (selected.x + 1)));
            int dz = Math.max(0, Math.max(selected.z - clicked.getZ(), clicked.getZ() - (selected.z + 1)));
            if (dx > 2 || dz > 2) {
                return discoverLegacy(clicked, radius, maxLogs, false);
            }
        }
        List<Root> others = new ArrayList<>();
        for (Root root : candidates) {
            if (overlaps(root, selected)) continue;
            if (others.stream().noneMatch(existing -> overlaps(existing, root))) others.add(root);
        }
        // Additional grounded vertical columns outside the chosen 2x2 and
        // known 2x2 neighbours are treated as potential *other* tree roots.
        // This is a boundary only, never permission for extra harvesting.
        for (var entry : columns.entrySet()) {
            int x = (int)(entry.getKey() >> 32), z = (int)(long)entry.getKey();
            if (insideRoot(x, z, selected)) continue;
            boolean insideNeighbour = false;
            for (Root r : others) if (insideRoot(x, z, r)) { insideNeighbour = true; break; }
            if (insideNeighbour) continue;
            if (distance4(x, z, selected, true) < 36) continue; // likely lower branches
            boolean nearNeighbour = false;
            for (Root r : others) if (distance4(x, z, r, true) < 16) { nearNeighbour = true; break; }
            if (!nearNeighbour) others.add(new Root(x, z, entry.getValue()));
        }
        return new SingleTreeScope(selected, others, radius, maxLogs, true);
    }

    private static boolean insideRoot(int x, int z, Root root) {
        return x >= root.x && x <= root.x + 1 && z >= root.z && z <= root.z + 1;
    }

    private static long pack(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }

    /** Legacy rooted-column detection for ordinary 1x1 trees and irregular fallbacks. */
    private static SingleTreeScope discoverLegacy(Block clicked, int radius, int maxLogs,
                                                    boolean twoByTwoSpecies) {
        List<Root> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int yTop = Math.min(clicked.getWorld().getMaxHeight() - 3, clicked.getY() + 3);
        int yBottom = Math.max(clicked.getWorld().getMinHeight() + 1, clicked.getY() - 80);
        for (int x = clicked.getX() - radius; x <= clicked.getX() + radius; x++)
            for (int z = clicked.getZ() - radius; z <= clicked.getZ() + radius; z++) {
                if (!clicked.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = yTop; y >= yBottom; y--) {
                    Block b = clicked.getWorld().getBlockAt(x, y, z);
                    if (b.getType() != clicked.getType()) continue;
                    if (!twoByTwoSpecies) {
                        if (!grounded(b)) continue;
                        if (b.getRelative(BlockFace.UP).getType() != b.getType()
                                || b.getRelative(0, 2, 0).getType() != b.getType()) continue;
                        if (seen.add(x + ":" + z)) roots.add(new Root(x, z, y));
                        break;
                    }
                    if (!clicked.getWorld().isChunkLoaded((x + 1) >> 4, z >> 4)
                            || !clicked.getWorld().isChunkLoaded(x >> 4, (z + 1) >> 4)
                            || !clicked.getWorld().isChunkLoaded((x + 1) >> 4, (z + 1) >> 4)) continue;
                    Block east = b.getRelative(BlockFace.EAST), south = b.getRelative(BlockFace.SOUTH);
                    Block diagonal = b.getRelative(1, 0, 1);
                    if (east.getType() != b.getType() || south.getType() != b.getType()
                            || diagonal.getType() != b.getType()) continue;
                    if (!grounded(b) || !grounded(east) || !grounded(south) || !grounded(diagonal)) continue;
                    if (b.getRelative(BlockFace.UP).getType() != b.getType()
                            || east.getRelative(BlockFace.UP).getType() != b.getType()
                            || south.getRelative(BlockFace.UP).getType() != b.getType()
                            || diagonal.getRelative(BlockFace.UP).getType() != b.getType()) continue;
                    if (seen.add(x + ":" + z)) roots.add(new Root(x, z, y));
                    break;
                }
            }
        roots.sort(Comparator.comparingInt((Root r) -> distance4(clicked.getX(), clicked.getZ(), r, false))
                .thenComparingInt(r -> Math.abs(clicked.getY() - r.y)));
        if (roots.isEmpty()) return new SingleTreeScope(
                new Root(clicked.getX(), clicked.getZ(), clicked.getY()), List.of(), radius, maxLogs, false);
        Root selected = roots.getFirst();
        List<Root> others = new ArrayList<>();
        for (Root root : roots) {
            if (root == selected) continue;
            if (twoByTwoSpecies && overlaps(root, selected)) continue;
            others.add(root);
        }
        return new SingleTreeScope(selected, others, radius, maxLogs, false);
    }

    private static boolean grounded(Block log) {
        Material below = log.getRelative(BlockFace.DOWN).getType();
        return below.isSolid() && !below.name().endsWith("_LOG") && !below.name().endsWith("_WOOD");
    }
}
