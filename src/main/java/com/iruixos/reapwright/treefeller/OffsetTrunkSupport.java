package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;

/** Verifies rising offsets in Acacia and Azalea trunks before log discovery crosses them. */
final class OffsetTrunkSupport {
    static final int MAX_ACACIA_RISE = 8;

    private OffsetTrunkSupport() { }

    static boolean eligible(Block block) {
        return block.getType() == Material.ACACIA_LOG || block.getType() == Material.OAK_LOG;
    }

    private static boolean loaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static boolean hasAzaleaCanopy(Block top) {
        // OAK_LOG must have Azalea foliage nearby to distinguish this exceptional
        // low-bend rule from ordinary Oak branches and neighbouring Oak trees.
        for (int dx = -4; dx <= 4; dx++) for (int dy = -2; dy <= 7; dy++)
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 20) continue;
                Block b = top.getRelative(dx, dy, dz);
                if (!loaded(b)) continue;
                if (b.getType() != Material.AZALEA_LEAVES
                        && b.getType() != Material.FLOWERING_AZALEA_LEAVES) continue;
                if (b.getBlockData() instanceof Leaves leaves && leaves.isPersistent()) continue;
                return true;
            }
        return false;
    }

    static boolean isBend(Block lower, Block upper) {
        if (!loaded(lower) || !loaded(upper) || !eligible(lower)
                || lower.getType() != upper.getType() || upper.getY() != lower.getY() + 1) return false;
        int dx = upper.getX() - lower.getX();
        int dz = upper.getZ() - lower.getZ();
        if (Math.abs(dx) + Math.abs(dz) != 1) return false;

        if (lower.getType() == Material.ACACIA_LOG) {
            return isAcaciaBend(lower, upper, dx, dz);
        }

        Block next = upper.getRelative(0, 1, 0);
        if (!loaded(next) || next.getType() != Material.OAK_LOG) return false;
        Block base = lower;
        for (int descent = 0; descent < 4; descent++) {
            Block below = base.getRelative(0, -1, 0);
            if (!loaded(below)) return false;
            if (below.getType() != Material.OAK_LOG) break;
            base = below;
        }
        return grounded(base) && !hasRootedColumnBelow(upper) && hasAzaleaCanopy(upper);
    }

    private static boolean isAcaciaBend(Block lower, Block upper, int dx, int dz) {
        // A second grounded column cannot serve as the upper half of a bend.
        if (grounded(upper) || hasRootedColumnBelow(upper)) return false;

        Block base = lower;
        int descent = 0;
        while (!grounded(base)) {
            if (descent == MAX_ACACIA_RISE) return false;
            base = acaciaStep(base, -dx, -1, -dz);
            if (base == null) return false;
            descent++;
        }

        Block tip = upper;
        for (int rise = 0; rise <= MAX_ACACIA_RISE; rise++) {
            // The path itself must reach matching natural foliage. A canopy
            // merely near the lower trunk could belong to a different tree.
            if (descent + rise >= 2 && hasAcaciaCanopy(tip)) return true;
            tip = acaciaStep(tip, dx, 1, dz);
            if (tip == null || grounded(tip)) return false;
        }
        return false;
    }

    private static Block acaciaStep(Block from, int dx, int dy, int dz) {
        Block vertical = from.getRelative(0, dy, 0);
        if (!loaded(vertical)) return null;
        if (vertical.getType() == Material.ACACIA_LOG) return vertical;

        // Vanilla Acacia's staircase advances in one cardinal direction.
        // Following that direction avoids traversing arbitrary diagonal logs.
        Block offset = from.getRelative(dx, dy, dz);
        return loaded(offset) && offset.getType() == Material.ACACIA_LOG ? offset : null;
    }

    private static boolean hasAcaciaCanopy(Block log) {
        int foliage = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++) {
                Block block = log.getRelative(dx, dy, dz);
                if (loaded(block) && block.getType() == Material.ACACIA_LEAVES
                        && block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent()
                        && ++foliage >= 3) return true;
            }
        return false;
    }

    private static boolean grounded(Block log) {
        Block below = log.getRelative(0, -1, 0);
        if (!loaded(below)) return false;
        Material type = below.getType();
        String name = type.name();
        return type.isSolid() && !name.endsWith("_LOG") && !name.endsWith("_WOOD") && !name.endsWith("_LEAVES");
    }

    private static boolean hasRootedColumnBelow(Block upper) {
        for (int depth = 1; depth <= 4; depth++) {
            Block below = upper.getRelative(0, -depth, 0);
            if (!loaded(below) || (below.getType() == upper.getType() && grounded(below))) return true;
        }
        return false;
    }

    static boolean isOffsetRoot(Block base) {
        if (!eligible(base) || !loaded(base)) return false;
        if (!grounded(base)) return false;
        Block first = base.getRelative(0, 1, 0);
        if (loaded(first) && first.getType() == base.getType()) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) != 1) continue;
                Block upper = first.getRelative(dx, 1, dz);
                if (isBend(first, upper)) return true;
            }
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dz) != 1) continue;
            Block upper = base.getRelative(dx, 1, dz);
            if (isBend(base, upper)) return true;
        }
        return false;
    }
}
