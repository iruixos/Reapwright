package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

/** Conservative, bounded removal plan for vegetation directly supported by a selected tree. */
final class AttachmentPlanner {
    record Selection(Set<Block> blocks, Map<Block, Material> types, String rejection) {
        Selection { blocks = Set.copyOf(blocks); types = Map.copyOf(types); }
        boolean valid() { return rejection == null; }
    }
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static boolean loaded(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4);
    }
    Selection select(String species, boolean twoByTwo, Set<Block> logs, Set<Block> leaves, FileConfiguration config) {
        if (!config.getBoolean("tree-feller.experimental-harvesting.attachments.enabled", true))
            return new Selection(Set.of(), Map.of(), null);
        Set<String> accepted = switch (species) {
            case "JUNGLE" -> Set.of("VINE", "COCOA");
            case "OAK" -> Set.of("VINE");
            case "PALE_OAK" -> Set.of("PALE_HANGING_MOSS", "RESIN_CLUMP");
            case "POPLAR" -> Set.of("SHELF_MUSHROOM");
            default -> Set.of();
        };
        if (accepted.isEmpty()) return new Selection(Set.of(), Map.of(), null);
        // Jungle attachment budgets must not depend on the 2x2 trunk classifier.
        // An unusually shaped giant Jungle can be temporarily classified as 1x1.
        boolean jungleAttachments = species.equals("JUNGLE");
        int max = jungleAttachments
                ? Math.max(1, Math.min(2048, config.getInt(
                    "tree-feller.experimental-harvesting.attachments.giant-jungle.max-vines", 512)
                    + config.getInt("tree-feller.experimental-harvesting.attachments.giant-jungle.max-cocoa-pods", 128)))
                : Math.max(1, Math.min(256, config.getInt(
                    "tree-feller.experimental-harvesting.attachments.max-blocks", 128)));
        int chainLength = jungleAttachments
                ? Math.max(0, Math.min(96, config.getInt(
                    "tree-feller.experimental-harvesting.attachments.giant-jungle.max-hanging-length", 64)))
                : Math.max(0, Math.min(32, config.getInt(
                    "tree-feller.experimental-harvesting.attachments.max-hanging-length", 20)));
        int vineLimit = jungleAttachments ? Math.max(0, Math.min(1024, config.getInt(
                "tree-feller.experimental-harvesting.attachments.giant-jungle.max-vines",512))) : max;
        int cocoaLimit = jungleAttachments ? Math.max(0, Math.min(256, config.getInt(
                "tree-feller.experimental-harvesting.attachments.giant-jungle.max-cocoa-pods",128))) : max;
        int vines = 0, cocoa = 0;
        Set<Block> planned = new LinkedHashSet<>();
        Map<Block, Material> materials = new LinkedHashMap<>();
        ArrayDeque<Block> hanging = new ArrayDeque<>();
        Set<Block> supports = new HashSet<>(logs);
        supports.addAll(leaves);
        for (Block support : supports) for (BlockFace face : FACES) {
            Block candidate = support.getRelative(face);
            if (!loaded(candidate)) return new Selection(Set.of(), Map.of(), "UNLOADED_ATTACHMENT_CHUNK");
            if (!accepted.contains(candidate.getType().name())) continue;
            if (planned.add(candidate)) {
                materials.put(candidate, candidate.getType());
                if (candidate.getType() == Material.VINE) vines++;
                if (candidate.getType() == Material.COCOA) cocoa++;
                if (planned.size() > max || vines > vineLimit || cocoa > cocoaLimit)
                    return new Selection(Set.of(), Map.of(), "ATTACHMENT_LIMIT_EXCEEDED total="
                            + planned.size() + " vines=" + vines + " cocoa=" + cocoa
                            + " maxVines=" + vineLimit + " maxCocoa=" + cocoaLimit);
                // Side-attached trunk vines are also roots of long hanging chains.
                // Their origin face must not determine whether we traverse downward.
                if (candidate.getType() == Material.VINE
                        || candidate.getType().name().equals("PALE_HANGING_MOSS")) hanging.add(candidate);
            }
        }
        // Follow vertical vine chains from every tree-owned attachment, including
        // vines on the sides of trunks. Traversal never jumps sideways between trees.
        // A second bounded seed pass picks up vine columns beside a discovered
        // attachment (e.g. curtain-like hanging vines near a canopy edge), but
        // only when the candidate remains close to selected logs or leaves.
        if (jungleAttachments) {
            Set<Block> extraSeeds = new LinkedHashSet<>();
            for (Block seed : new ArrayList<>(hanging)) {
                for (BlockFace side : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH,
                        BlockFace.EAST, BlockFace.WEST}) {
                    Block near = seed.getRelative(side);
                    if (!loaded(near)) return new Selection(Set.of(), Map.of(), "UNLOADED_ATTACHMENT_CHUNK");
                    if (near.getType() != Material.VINE || planned.contains(near)) continue;
                    // Only a one-block sideways extension from a selected tree attachment,
                    // not an unrestricted horizontal flood-fill into nearby foliage.
                    boolean nearSelectedTree = false;
                    // Directly adjacent vines were already seeded in the main pass.
                    // Permit a one-vine gap from those direct seeds by requiring
                    // selected tree support no more than two blocks away.
                    for (BlockFace nearbyFace : FACES) {
                        Block adjacent = near.getRelative(nearbyFace);
                        if (!loaded(adjacent)) return new Selection(Set.of(), Map.of(), "UNLOADED_ATTACHMENT_CHUNK");
                        for (BlockFace nextFace : FACES) {
                            Block twoAway = adjacent.getRelative(nextFace);
                            if (!loaded(twoAway)) return new Selection(Set.of(), Map.of(), "UNLOADED_ATTACHMENT_CHUNK");
                            if (supports.contains(twoAway)) { nearSelectedTree = true; break; }
                        }
                        if (nearSelectedTree) break;
                    }
                    if (nearSelectedTree) extraSeeds.add(near);
                }
            }
            for (Block seed : extraSeeds) {
                if (!planned.add(seed)) continue;
                materials.put(seed, Material.VINE);
                vines++;
                if (planned.size() > max || vines > vineLimit)
                    return new Selection(Set.of(), Map.of(), "ATTACHMENT_LIMIT_EXCEEDED total="
                            + planned.size() + " vines=" + vines + " cocoa=" + cocoa
                            + " maxVines=" + vineLimit + " maxCocoa=" + cocoaLimit);
                hanging.add(seed);
            }
        }
        // Never walk sideways along a complete vine curtain or follow chains upward
        // into a neighbouring canopy. The scan remains bounded per vertical chain.
        for (int depth = 0; depth < chainLength && !hanging.isEmpty(); depth++) {
            int count = hanging.size();
            for (int n = 0; n < count; n++) {
                Block above = hanging.removeFirst();
                Block beneath = above.getRelative(BlockFace.DOWN);
                if (!loaded(beneath)) return new Selection(Set.of(), Map.of(), "UNLOADED_ATTACHMENT_CHUNK");
                if (beneath.getType() != above.getType() || !planned.add(beneath)) continue;
                materials.put(beneath, beneath.getType());
                if (beneath.getType() == Material.VINE) vines++;
                if (planned.size() > max || vines > vineLimit)
                    return new Selection(Set.of(), Map.of(), "ATTACHMENT_LIMIT_EXCEEDED total="
                            + planned.size() + " vines=" + vines + " maxVines=" + vineLimit);
                hanging.add(beneath);
            }
        }
        // A chain that still continues beyond the configured bound is ambiguous.
        if (!hanging.isEmpty()) for (Block last : hanging) {
            Block below = last.getRelative(BlockFace.DOWN);
            if (!loaded(below) || below.getType() == last.getType())
                return new Selection(Set.of(), Map.of(), "ATTACHMENT_CHAIN_TOO_LONG");
        }
        return new Selection(planned, materials, null);
    }
}
