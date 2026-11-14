package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A narrow, vanilla-equivalent audit for three state-sensitive tree attachments. */
final class TreeAttachmentStates {
    private TreeAttachmentStates() { }

    /** Save only the relevant attachments, before the felling event is cancelled. */
    static Map<Block, String> capture(Collection<Block> attachments) {
        Map<Block, String> states = new LinkedHashMap<>();
        for (Block block : attachments) {
            if (audited(block.getType())) states.put(block, block.getBlockData().getAsString());
        }
        return Map.copyOf(states);
    }

    static boolean unchanged(Map<Block, String> states) {
        for (var entry : states.entrySet()) {
            Block block = entry.getKey();
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    || !entry.getValue().equals(block.getBlockData().getAsString())) return false;
        }
        return true;
    }

    private static boolean audited(Material material) {
        return material == Material.COCOA || material == Material.SHELF_MUSHROOM
                || material == Material.MANGROVE_PROPAGULE;
    }

    /**
     * For these three specific blocks the vanilla result is deterministic and independent
     * of Fortune and Silk Touch. Calculating from the original state avoids relying on
     * neighboring supports that have already been removed without physics.
     * Other attachments continue using Paper's native loot calculation.
     */
    static Collection<ItemStack> drops(Block block, Collection<ItemStack> nativeDrops) {
        BlockData data = block.getBlockData();
        if (block.getType() == Material.COCOA) {
            if (!(data instanceof Ageable cocoa)) throw new IllegalStateException("Missing Cocoa age");
            return List.of(new ItemStack(Material.COCOA_BEANS, cocoa.getAge() >= cocoa.getMaximumAge() ? 3 : 1));
        }
        if (block.getType() == Material.SHELF_MUSHROOM) {
            if (!(data instanceof Ageable shelf)) throw new IllegalStateException("Missing Shelf Mushroom age");
            return List.of(new ItemStack(Material.SHELF_MUSHROOM,
                    shelf.getAge() >= shelf.getMaximumAge() ? 2 : 1));
        }
        if (block.getType() == Material.MANGROVE_PROPAGULE) {
            if (!(data instanceof Ageable propagule)) throw new IllegalStateException("Missing Propagule age");
            boolean hanging = hasBoolean(data, "hanging", true);
            return !hanging || propagule.getAge() >= propagule.getMaximumAge()
                    ? List.of(new ItemStack(Material.MANGROVE_PROPAGULE)) : List.of();
        }
        return nativeDrops;
    }

    /** Read the vanilla namespaced state property without assuming a Paper-specific subtype. */
    static boolean isWaterloggedPropagule(Block block) {
        return block.getType() == Material.MANGROVE_PROPAGULE
                && hasBoolean(block.getBlockData(), "waterlogged", true);
    }

    private static boolean hasBoolean(BlockData data, String name, boolean expected) {
        String text = data.getAsString();
        String token = name + "=" + expected;
        int start = text.indexOf('[');
        if (start < 0) return false;
        String[] properties = text.substring(start + 1, text.length() - 1).split(",");
        for (String property : properties) if (property.trim().equals(token)) return true;
        return false;
    }
}
