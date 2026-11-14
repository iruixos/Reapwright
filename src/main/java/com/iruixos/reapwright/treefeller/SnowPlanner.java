package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Snow layers directly supported by tree blocks in an already validated harvest plan.
 * Does not claim full snow blocks, snow on terrain, or snow supported by other trees.
 */
final class SnowPlanner {
    record Selection(Set<Block> layers, String rejection) {
        Selection { layers = Collections.unmodifiableSet(new LinkedHashSet<>(layers)); }
        boolean valid() { return rejection == null; }
    }

    Selection select(Set<Block> logs, Set<Block> leaves) {
        Set<Block> layers = new LinkedHashSet<>();
        Set<Block> tree = new LinkedHashSet<>(logs);
        tree.addAll(leaves);
        for (Block support : tree) {
            Block above = support.getRelative(BlockFace.UP);
            if (!above.getWorld().isChunkLoaded(above.getX() >> 4, above.getZ() >> 4)) {
                return new Selection(Set.of(), "UNLOADED_SNOW_CHUNK");
            }
            // SNOW is the thin, layered snow block. SNOW_BLOCK is terrain and is excluded.
            if (above.getType() == Material.SNOW) layers.add(above);
        }
        return new Selection(layers, null);
    }
}
