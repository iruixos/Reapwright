package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Beehive;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

/** Strictly read-only, bounded inspection. An accepted scan is NOT authorization to fell. */
public final class TreeInspector {
    public record Result(String species, int logs, int foliage, boolean twoByTwo,
                         boolean valid, String reason, int diagonalBranches,
                         int beeNests, int storedBees, int attachments,
                         int nearbyChunksMissing, int suspiciousBlocks) { }
    /** The exact same log set is used by inspection and destructive harvesting. */
    public record Plan(Result result, Set<Block> logs, List<Block> trunkFootprint, Set<Block> mushroomCaps) {
        public Plan { 
            logs = Collections.unmodifiableSet(new LinkedHashSet<>(logs));
            trunkFootprint = List.copyOf(trunkFootprint);
            mushroomCaps = Collections.unmodifiableSet(new LinkedHashSet<>(mushroomCaps));
        }
        public Plan(Result result, Set<Block> logs) { this(result, logs, List.of(), Set.of()); }
        public Plan(Result result, Set<Block> logs, List<Block> trunkFootprint) { this(result, logs, trunkFootprint, Set.of()); }
    }
    private record Profile(String species, String log, String leaves, boolean requiresTwoByTwo,
                           int minimumFoliage) { }
    private static final List<Profile> PROFILES = List.of(
        new Profile("OAK", "OAK_LOG", "OAK_LEAVES", false, 12),
        new Profile("AZALEA", "OAK_LOG", "AZALEA_LEAVES", false, 10),
        new Profile("BIRCH", "BIRCH_LOG", "BIRCH_LEAVES", false, 12),
        new Profile("SPRUCE", "SPRUCE_LOG", "SPRUCE_LEAVES", false, 10),
        new Profile("JUNGLE", "JUNGLE_LOG", "JUNGLE_LEAVES", false, 10),
        new Profile("DARK_OAK", "DARK_OAK_LOG", "DARK_OAK_LEAVES", true, 16),
        new Profile("ACACIA", "ACACIA_LOG", "ACACIA_LEAVES", false, 6),
        new Profile("CHERRY", "CHERRY_LOG", "CHERRY_LEAVES", false, 16),
        new Profile("PALE_OAK", "PALE_OAK_LOG", "PALE_OAK_LEAVES", true, 16),
        new Profile("POPLAR", "POPLAR_LOG", "RED_POPLAR_LEAVES", false, 10),
        new Profile("POPLAR", "POPLAR_LOG", "ORANGE_POPLAR_LEAVES", false, 10),
        new Profile("POPLAR", "POPLAR_LOG", "YELLOW_POPLAR_LEAVES", false, 10),
        new Profile("MANGROVE", "MANGROVE_LOG", "MANGROVE_LEAVES", false, 12),
        new Profile("RED_MUSHROOM", "MUSHROOM_STEM", "RED_MUSHROOM_BLOCK", false, 6),
        new Profile("BROWN_MUSHROOM", "MUSHROOM_STEM", "BROWN_MUSHROOM_BLOCK", false, 6),
        new Profile("CRIMSON", "CRIMSON_STEM", "NETHER_WART_BLOCK", false, 6),
        new Profile("WARPED", "WARPED_STEM", "WARPED_WART_BLOCK", false, 6)
    );
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
        BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final Set<String> NATURAL_ATTACHMENTS = Set.of("BEE_NEST", "VINE", "COCOA", "HANGING_ROOTS",
        "MANGROVE_PROPAGULE", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "WEEPING_VINES", "TWISTING_VINES",
        "SHROOMLIGHT", "GLOW_LICHEN", "MOSS_CARPET");

    /** A grounded 2x2 stem is a four-column trunk, not an arbitrary group of touching branches.
     * Soil is intentionally NOT validated here: structure identity and replanting are distinct.
     * The shape is required to have four columns reaching the common lower trunk region.
     */
    private record TrunkBase(int x, int z, int y) { }
    private List<TrunkBase> trunkBases(Set<Block> logs, Material type) {
        if (logs.isEmpty()) return List.of();
        Map<Long, List<Block>> columns = new HashMap<>();
        for (Block b : logs) {
            long key = ((long)b.getX() << 32) ^ (b.getZ() & 0xffffffffL);
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        Map<Long, Integer> bottoms = new HashMap<>();
        for (var entry : columns.entrySet()) {
            List<Block> column = entry.getValue();
            column.sort(Comparator.comparingInt(Block::getY));
            // Reject single stray branch blocks as trunk candidates.
            int best = 0, streak = 0, prev = Integer.MIN_VALUE;
            for (Block b : column) {
                streak = b.getY() == prev + 1 ? streak + 1 : 1;
                best = Math.max(best, streak);
                prev = b.getY();
            }
            if (best >= 3) bottoms.put(entry.getKey(), column.getFirst().getY());
        }
        List<TrunkBase> bases = new ArrayList<>();
        for (Block b : logs) {
            long nw = ((long)b.getX() << 32) ^ (b.getZ() & 0xffffffffL);
            long ne = ((long)(b.getX()+1) << 32) ^ (b.getZ() & 0xffffffffL);
            long sw = ((long)b.getX() << 32) ^ ((b.getZ()+1) & 0xffffffffL);
            long se = ((long)(b.getX()+1) << 32) ^ ((b.getZ()+1) & 0xffffffffL);
            if (!bottoms.containsKey(nw) || !bottoms.containsKey(ne)
                    || !bottoms.containsKey(sw) || !bottoms.containsKey(se)) continue;
            int lo = Math.min(Math.min(bottoms.get(nw), bottoms.get(ne)),
                    Math.min(bottoms.get(sw), bottoms.get(se)));
            int hi = Math.max(Math.max(bottoms.get(nw), bottoms.get(ne)),
                    Math.max(bottoms.get(sw), bottoms.get(se)));
            if (hi - lo > 3) continue;
            TrunkBase base = new TrunkBase(b.getX(), b.getZ(), lo);
            if (bases.stream().noneMatch(existing -> existing.x() == base.x() && existing.z() == base.z())) bases.add(base);
        }
        return bases;
    }

    // Scoped ownership is chosen once, before traversing logs. A connected branch
    // is evidence of adjacency, NOT automatic permission to fell its owner.
    private SingleTreeScope activeScope;
    private boolean outsideOtherTree(Block b) {
        return activeScope != null && !activeScope.owns(b);
    }

    private boolean inBounds(Block origin, Block b, int radius) {
        // Natural trunks can extend far below a clicked upper branch. Keep a
        // separate finite vertical envelope instead of tying height to canopy width.
        int verticalLimit = origin.getType() == Material.JUNGLE_LOG ? 128 : 96;
        return Math.abs(origin.getX()-b.getX()) <= radius && Math.abs(origin.getY()-b.getY()) <= verticalLimit
            && Math.abs(origin.getZ()-b.getZ()) <= radius && !outsideOtherTree(b);
    }
    private boolean loaded(Block b) { return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4); }
    private boolean construction(Material material) {
        String n = material.name();
        if (NATURAL_ATTACHMENTS.contains(n) || n.endsWith("_LEAVES") || n.endsWith("_LOG")
            || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")
            || n.endsWith("_ROOTS") || n.endsWith("_VINES") || n.endsWith("_WART_BLOCK")) return false;
        return n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN") || n.endsWith("_BUTTON")
            || n.endsWith("_TORCH") || n.endsWith("_LADDER") || n.endsWith("_FENCE")
            || n.endsWith("_GATE") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR")
            || n.endsWith("_PLANKS") || n.endsWith("_SLAB") || n.endsWith("_STAIRS")
            || n.endsWith("_PRESSURE_PLATE") || n.endsWith("_WALL") || n.endsWith("_BANNER")
            || Set.of("CHEST", "TRAPPED_CHEST", "BARREL", "HOPPER", "FURNACE", "BLAST_FURNACE",
                "SMOKER", "LECTERN", "CRAFTING_TABLE", "LANTERN", "SOUL_LANTERN", "ITEM_FRAME",
                "GLOW_ITEM_FRAME", "DECORATED_POT", "DISPENSER", "DROPPER", "BREWING_STAND").contains(n);
    }
    private boolean hardHazard(Material material) {
        String n = material.name();
        return n.equals("CHEST")
            || n.equals("TRAPPED_CHEST") || n.equals("BARREL") || n.equals("HOPPER")
            || n.equals("DISPENSER") || n.equals("DROPPER") || n.equals("FURNACE")
            || n.equals("BLAST_FURNACE") || n.equals("SMOKER") || n.equals("BREWING_STAND");
    }
    private Result fail(String species, int logs, int leaves, boolean large, String reason,
                        int diagonal, int nests, int bees, int attachments, int unloaded, int hazards) {
        return new Result(species,logs,leaves,large,false,reason,diagonal,nests,bees,attachments,unloaded,hazards);
    }
    private Plan rejected(String species, int logs, int leaves, boolean large, String reason,
                          int diagonal, int nests, int bees, int attachments, int unloaded, int hazards) {
        return new Plan(fail(species,logs,leaves,large,reason,diagonal,nests,bees,attachments,unloaded,hazards), Set.of());
    }
    /**
     * Huge mushrooms are not foliage-bearing trees. Detect the whole connected cap
     * component (both vanilla colours), then discover every genuine stem descending
     * from that cap. This allows overlapping natural mushrooms without repeatedly
     * counting shared cap blocks. It never traverses adjacent wooden structures.
     */
    private Plan mushroomCluster(Block start, FileConfiguration config) {
        final int radius = Math.max(6, Math.min(16,
                config.getInt("tree-feller.detection.mushrooms.max-cap-radius", 12)));
        final int capLimit = Math.max(32, Math.min(2048,
                config.getInt("tree-feller.detection.mushrooms.max-cap-blocks", 768)));
        final int stemLimit = Math.max(16, Math.min(256,
                config.getInt("tree-feller.detection.mushrooms.max-stem-blocks", 128)));
        final int maxStems = Math.max(1, Math.min(8,
                config.getInt("tree-feller.detection.mushrooms.max-stems", 4)));
        Set<Block> stems = new LinkedHashSet<>();
        Set<Block> caps = new LinkedHashSet<>();
        Set<Block> visited = new HashSet<>();
        ArrayDeque<Block> pendingCaps = new ArrayDeque<>();
        // Follow the initial stem in both directions, not merely from the
        // player-selected block (which may be halfway up a tall mushroom).
        ArrayDeque<Block> pendingStems = new ArrayDeque<>();
        pendingStems.add(start);
        int columns = 0;
        Set<Long> stemColumns = new HashSet<>();
        boolean expanded = true;
        while (expanded) {
            expanded = false;
            while (!pendingStems.isEmpty()) {
                Block stem = pendingStems.removeFirst();
                if (!loaded(stem)) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                        "UNLOADED_MUSHROOM_CHUNK",0,0,0,0,1,0);
                if (stem.getType()!=Material.MUSHROOM_STEM || stems.contains(stem)) continue;
                if (Math.abs(stem.getX()-start.getX())>radius || Math.abs(stem.getZ()-start.getZ())>radius
                        || Math.abs(stem.getY()-start.getY())>48)
                    return rejected("MUSHROOM",stems.size(),caps.size(),false,
                            "MUSHROOM_STEM_OUTSIDE_BOUNDS",0,0,0,0,0,0);
                if (stems.size()>=stemLimit) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                        "MUSHROOM_STEM_LIMIT",0,0,0,0,0,0);
                stems.add(stem);
                long key = ((long)stem.getX()<<32) ^ (stem.getZ() & 0xffffffffL);
                if (stemColumns.add(key) && ++columns>maxStems)
                    return rejected("MUSHROOM",stems.size(),caps.size(),false,
                            "MUSHROOM_CLUSTER_LIMIT",0,0,0,0,0,0);
                for (BlockFace face:FACES) {
                    Block b=stem.getRelative(face);
                    if (!loaded(b)) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                            "UNLOADED_MUSHROOM_CHUNK",0,0,0,0,1,0);
                    if (b.getType()==Material.MUSHROOM_STEM && !stems.contains(b)) pendingStems.add(b);
                }
                // Cap blocks can be one block away from the stem or form lower
                // walls. Seed locally; a bounded cap flood handles the remainder.
                for (int dx=-2;dx<=2;dx++) for (int dy=-1;dy<=2;dy++) for(int dz=-2;dz<=2;dz++) {
                    Block b=stem.getRelative(dx,dy,dz);
                    if (!loaded(b)) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                            "UNLOADED_MUSHROOM_CHUNK",0,0,0,0,1,0);
                    if (isMushroomCap(b.getType()) && !visited.contains(b)) pendingCaps.add(b);
                }
                expanded=true;
            }
            while (!pendingCaps.isEmpty()) {
                Block cap = pendingCaps.removeFirst();
                if (!loaded(cap)) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                        "UNLOADED_MUSHROOM_CHUNK",0,0,0,0,1,0);
                if (!visited.add(cap) || !isMushroomCap(cap.getType())) continue;
                // Hard bounds are intentionally independent of the narrow cap
                // assumptions previously causing MUSHROOM_CAP_OUTSIDE_BOUNDS.
                if (Math.abs(cap.getX()-start.getX())>radius || Math.abs(cap.getZ()-start.getZ())>radius
                        || Math.abs(cap.getY()-start.getY())>48)
                    return rejected("MUSHROOM",stems.size(),caps.size(),false,
                            "MUSHROOM_CLUSTER_OUTSIDE_BOUNDS",0,0,0,0,0,0);
                if (caps.size()>=capLimit) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                        "MUSHROOM_CAP_LIMIT",0,0,0,0,0,0);
                caps.add(cap);
                for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
                    if(dx==0&&dy==0&&dz==0) continue;
                    Block b=cap.getRelative(dx,dy,dz);
                    if (!loaded(b)) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                            "UNLOADED_MUSHROOM_CHUNK",0,0,0,0,1,0);
                    if (isMushroomCap(b.getType()) && !visited.contains(b)) pendingCaps.add(b);
                    // A cap meeting another mushroom stem is evidence for an
                    // additional natural mushroom, not grounds to reject the cap.
                    if (b.getType()==Material.MUSHROOM_STEM && !stems.contains(b)) pendingStems.add(b);
                }
            }
            if (!pendingStems.isEmpty()) expanded=true;
        }
        if (stems.size()<2 || caps.size()<6) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                "INCOMPLETE_MUSHROOM",0,0,0,0,0,0);
        // Sensitive blocks near stems still require a hard stop; never consume
        // a Creaking Heart, container or player beehive as mushroom vegetation.
        int hazards=0, nearbyStructures=0;
        for (Block natural : stems) for (BlockFace face : FACES) {
            Material neighbor=natural.getRelative(face).getType();
            if (hardHazard(neighbor)) hazards++;
            else if (construction(neighbor)) nearbyStructures++;
        }
        // Mushroom caps often grow close to dirt and stone; those are safe,
        // but attached man-made blocks are not covered by the current
        // confirmation/rollback system and must stop the entire cluster.
        for (Block natural : caps) for (BlockFace face : FACES) {
            Material neighbor=natural.getRelative(face).getType();
            if (hardHazard(neighbor)) hazards++;
            else if (construction(neighbor)) nearbyStructures++;
        }
        if(hazards>0) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                "SENSITIVE_BLOCK_ADJACENT",0,0,0,0,0,hazards);
        if(nearbyStructures>0) return rejected("MUSHROOM",stems.size(),caps.size(),false,
                "MUSHROOM_NEAR_PLAYER_STRUCTURE",0,0,0,nearbyStructures,0,0);
        String species = caps.stream().anyMatch(b->b.getType()==Material.RED_MUSHROOM_BLOCK)
                ? "RED_MUSHROOM" : "BROWN_MUSHROOM";
        Result result=new Result(species,stems.size(),caps.size(),false,true,
                "VALID_MUSHROOM_CLUSTER",0,0,0,0,0,0);
        return new Plan(result,stems,List.of(),caps);
    }
    private static boolean isMushroomCap(Material m) {
        return m==Material.RED_MUSHROOM_BLOCK || m==Material.BROWN_MUSHROOM_BLOCK;
    }

    public Result inspect(Block start, FileConfiguration config) { return plan(start, config).result(); }

    public Plan plan(Block start, FileConfiguration config) { return plan(start, config, false); }

    /**
     * Finish face-connected log discovery after optional branch/canopy recovery.
     *
     * Earlier scans seeded diagonal branches and canopy logs *after* the first
     * connected-component flood fill. These new seeds could touch omitted lower
     * trunks, but their complete face-connected components were never revisited.
     * The final grounded completeness guard would then reject a legitimate tree.
     *
     * This method only adds exact same-material face-connected logs, respecting
     * existing hard scan bounds, unloaded chunks, and the total log budget.
     * No world changes occur during this pass.
     */
    private record Completion(boolean overflow, int unloaded, int added) { }

    private Completion completeFaceComponent(Block start, Set<Block> logs, int radius, int maxLogs) {
        ArrayDeque<Block> frontier = new ArrayDeque<>(logs);
        int added = 0, unloaded = 0;
        while (!frontier.isEmpty()) {
            Block current = frontier.removeFirst();
            for (BlockFace face : FACES) {
                Block next = current.getRelative(face);
                if (!inBounds(start, next, radius)) {
                    if (!outsideOtherTree(next) && loaded(next) && next.getType() == start.getType())
                        return new Completion(true, unloaded, added);
                    continue;
                }
                if (!loaded(next)) { unloaded++; continue; }
                if (next.getType() != start.getType() || logs.contains(next)) continue;
                if (logs.size() >= maxLogs) return new Completion(true, unloaded, added);
                logs.add(next);
                frontier.add(next);
                added++;
            }
        }
        return new Completion(false, unloaded, added);
    }

    /** Allow multiple grounded trunks of the same vanilla log material to share one bounded plan. */
    public Plan plan(Block start, FileConfiguration config, boolean connectedTrees) {
        if (start.getType()==Material.MUSHROOM_STEM) return mushroomCluster(start,config);
        // Each axe action selects ONE rooted tree. This intentionally supersedes
        // the former connected-forest option for vanilla log trees. Mushroom
        // clusters have a separate bounded detector.
        connectedTrees = false;
        activeScope = SingleTreeScope.discover(start, config);
        List<Profile> candidates = PROFILES.stream().filter(p -> p.log.equals(start.getType().name())).toList();
        if (candidates.isEmpty()) return rejected("UNKNOWN",0,0,false,"UNSUPPORTED_TRUNK",0,0,0,0,0,0);
        int maxLogs = start.getType() == Material.JUNGLE_LOG
                ? Math.max(16, Math.min(1024, config.getInt("tree-feller.detection.giant-jungle.max-logs", 768)))
                : Math.max(16,Math.min(512,config.getInt("tree-feller.detection.max-logs",256)));
        int maxFoliage = start.getType() == Material.JUNGLE_LOG
                ? Math.max(32, Math.min(3072, config.getInt("tree-feller.detection.giant-jungle.max-foliage", 2048)))
                : Math.max(32,Math.min(2048,config.getInt("tree-feller.detection.max-foliage",768)));
        int radius = start.getType() == Material.JUNGLE_LOG
                ? Math.max(4, Math.min(24, config.getInt("tree-feller.detection.giant-jungle.max-horizontal-radius", 20)))
                : Math.max(4,Math.min(20,config.getInt("tree-feller.detection.max-radius",12)));
        if (connectedTrees) {
            maxLogs = Math.max(maxLogs, Math.max(32, Math.min(1024, config.getInt(
                    "tree-feller.detection.connected-structures.max-logs", 768))));
            radius = Math.max(radius, Math.max(6, Math.min(32, config.getInt(
                    "tree-feller.detection.connected-structures.max-horizontal-radius", 24))));
            maxFoliage = Math.max(maxFoliage, Math.max(32, Math.min(3072, config.getInt(
                    "tree-feller.detection.connected-structures.max-foliage", 2048))));
        }
        if (activeScope != null) {
            radius = Math.min(radius, activeScope.maxRadius());
            maxLogs = Math.min(maxLogs, activeScope.maxLogs());
        }
        Set<Block> logs = new LinkedHashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        logs.add(start); queue.add(start);
        boolean overflow=false; int unloaded=0; int diagonal=0;
        // Face connectivity establishes the initial trunk and branch backbone.
        while (!queue.isEmpty()) {
            Block b=queue.removeFirst();
            for (BlockFace face:FACES) {
                Block next=b.getRelative(face);
                if (!inBounds(start,next,radius)) {
                    if (!outsideOtherTree(next) && loaded(next) && next.getType()==start.getType()) overflow=true;
                    continue;
                }
                if (!loaded(next)) { unloaded++; continue; }
                if (next.getType()!=start.getType() || logs.contains(next)) continue;
                if (logs.size()>=maxLogs) { overflow=true; continue; }
                logs.add(next); queue.add(next);
            }
        }
        // Grounded-column completion happens BEFORE species classification,
        // leaf planning, anchor selection, and destructive work. This fixes the
        // earlier pipeline that discovered missing bases only after planning.
        // A candidate must be supported by solid ground, contain a real vertical
        // log run, and physically touch this log component. Proximity alone is
        // insufficient: neighbouring trees are not adopted simply for being close.
        if (!overflow && unloaded == 0 && Set.of(Material.DARK_OAK_LOG,
                Material.PALE_OAK_LOG, Material.JUNGLE_LOG, Material.SPRUCE_LOG).contains(start.getType())) {
            int floor = logs.stream().mapToInt(Block::getY).min().orElse(start.getY());
            Set<Long> nearby = new LinkedHashSet<>();
            for (Block log : logs) {
                if (log.getY() > floor + 5) continue;
                for (int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                    int x=log.getX()+dx, z=log.getZ()+dz;
                    nearby.add(((long)x << 32) ^ (z & 0xffffffffL));
                }
            }
            // Two bounded passes handle a second trunk found alongside the first.
            for (int pass=0;pass<2 && !overflow;pass++) {
                boolean changed=false;
                for (long packed:nearby) {
                    int x=(int)(packed>>32), z=(int)packed;
                    Block base=null;
                    for (int y=Math.min(start.getWorld().getMaxHeight()-4, floor+4);
                            y>=Math.max(start.getWorld().getMinHeight()+1, floor-80);y--) {
                        Block b=start.getWorld().getBlockAt(x,y,z);
                        if (!inBounds(start,b,radius)) continue;
                        if (!loaded(b)) { unloaded++; break; }
                        Block below = b.getRelative(BlockFace.DOWN);
                        Block above = b.getRelative(BlockFace.UP);
                        Block above2 = b.getRelative(0, 2, 0);
                        if (!loaded(below) || !loaded(above) || !loaded(above2)) { unloaded++; continue; }
                        if (b.getType()!=start.getType() || !below.getType().isSolid()
                                || below.getType()==start.getType()) continue;
                        if (above.getType()!=start.getType() || above2.getType()!=start.getType()) continue;
                        base=b; break;
                    }
                    if (base==null || logs.contains(base)) continue;
                    // A vertical stem is included only when it ACTUALLY connects to
                    // the currently planned cluster; do not claim nearby trunks.
                    boolean touches=false;
                    for (int dy=0;dy<96 && inBounds(start,base.getRelative(0,dy,0),radius);dy++) {
                        Block b=base.getRelative(0,dy,0);
                        if (!loaded(b)) { unloaded++; break; }
                        if (b.getType()!=start.getType()) break;
                        for (BlockFace face:FACES) if (logs.contains(b.getRelative(face))) { touches=true; break; }
                        if (touches) break;
                    }
                    if (!touches) continue;
                    ArrayDeque<Block> recovery=new ArrayDeque<>();
                    recovery.add(base);
                    while (!recovery.isEmpty() && !overflow) {
                        Block b=recovery.removeFirst();
                        if (!inBounds(start,b,radius)) {
                            if (!outsideOtherTree(b) && loaded(b) && b.getType()==start.getType()) overflow=true;
                            continue;
                        }
                        if (!loaded(b)) { unloaded++; continue; }
                        if (b.getType()!=start.getType() || logs.contains(b)) continue;
                        if (logs.size()>=maxLogs) { overflow=true; break; }
                        logs.add(b); changed=true;
                        for (BlockFace face:FACES) {
                            Block next=b.getRelative(face);
                            if (!inBounds(start,next,radius)) {
                                if (!outsideOtherTree(next) && loaded(next) && next.getType()==start.getType()) overflow=true;
                                continue;
                            }
                            if (!loaded(next)) { unloaded++; continue; }
                            if (next.getType()==start.getType() && !logs.contains(next)) recovery.add(next);
                        }
                    }
                }
                if (!changed) break;
            }
        }
        // Cherry, oak, acacia, dark oak and pale oak can generate crown branches connected by corners rather
        // than faces. Discover those branches *before* calculating canopy and validity.
        // A diagonal jump is allowed only above the lower trunk, with matching natural
        // foliage immediately around the target. Each accepted seed brings in its
        // face-connected branch. This is deliberately NOT an unlimited 26-way flood fill.
        // Reject scans with missing chunks or capacity/boundary violations.
        if (Set.of("OAK_LOG","ACACIA_LOG","CHERRY_LOG","DARK_OAK_LOG","PALE_OAK_LOG","JUNGLE_LOG").contains(start.getType().name()) && !overflow) {
            String leafName = start.getType().name().replace("_LOG", "_LEAVES");
            int lowest = logs.stream().mapToInt(Block::getY).min().orElse(start.getY());
            // Multiple passes allow offset branches that start beyond the first branch.
            for (int pass=0; pass<5 && !overflow; pass++) {
                List<Block> seeds=new ArrayList<>();
                for (Block b:new ArrayList<>(logs)) {
                    for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++) for (int dz=-1;dz<=1;dz++) {
                        if (Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<2) continue;
                        Block n=b.getRelative(dx,dy,dz);
                        if (!inBounds(start,n,radius)) continue;
                        if (!loaded(n)) { unloaded++; continue; }
                        if (logs.contains(n) || seeds.contains(n) || n.getType()!=start.getType()) continue;
                        if (n.getY()<lowest+2) continue; // never jump between separate trunk bases
                        int leafSupport=0;
                        for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) {
                            Block around=n.getRelative(x,y,z);
                            if (!inBounds(start,around,radius)) continue;
                            if (!loaded(around)) { unloaded++; continue; }
                            if (around.getType().name().equals(leafName)
                                && (!(around.getBlockData() instanceof Leaves leaf) || !leaf.isPersistent())) leafSupport++;
                        }
                        if (leafSupport>=3) seeds.add(n);
                    }
                }
                int added=0;
                for (Block seed:seeds) {
                    if (logs.contains(seed)) continue;
                    diagonal++;
                    ArrayDeque<Block> branch=new ArrayDeque<>(); branch.add(seed);
                    while(!branch.isEmpty()) {
                        Block b=branch.removeFirst();
                        if (!inBounds(start,b,radius)) { if (!outsideOtherTree(b)) overflow=true; continue; }
                        if (!loaded(b)) { unloaded++; continue; }
                        if (b.getType()!=start.getType() || logs.contains(b)) continue;
                        if (b.getY()<lowest+2) { overflow=true; continue; }
                        if (logs.size()>=maxLogs) { overflow=true; break; }
                        logs.add(b); added++;
                        for (BlockFace face:FACES) {
                            Block next=b.getRelative(face);
                            if (!inBounds(start,next,radius)) {
                                if (!outsideOtherTree(next) && loaded(next) && next.getType()==start.getType()) overflow=true;
                                continue;
                            }
                            if (!loaded(next)) { unloaded++; continue; }
                            if (next.getType()==start.getType() && !logs.contains(next)) branch.add(next);
                        }
                    }
                }
                if (added==0) break;
            }
        }
        // Diagonal seeds may connect into entire lower columns. Close their
        // face-connected components before assigning a species or canopy.
        if (!overflow && unloaded == 0) {
            Completion completion = completeFaceComponent(start, logs, radius, maxLogs);
            overflow = completion.overflow();
            unloaded += completion.unloaded();
        }
        Profile best=candidates.getFirst(); int count=-1;
        for (Profile profile:candidates) {
            Set<Block> foliage=new HashSet<>();
            for (Block log:logs) for (int x=-2;x<=2;x++) for (int y=-2;y<=2;y++) for (int z=-2;z<=2;z++) {
                if (Math.abs(x)+Math.abs(y)+Math.abs(z)>3) continue;
                Block b=log.getRelative(x,y,z);
                if (!inBounds(start,b,radius) || !loaded(b)) { if (!loaded(b)) unloaded++; continue; }
                boolean match = b.getType().name().equals(profile.leaves)
                        || (profile.species.equals("AZALEA")
                            && b.getType() == Material.FLOWERING_AZALEA_LEAVES);
                if (!match) continue;
                // Player-placed persistent leaves are not reliable evidence of a naturally grown canopy.
                if (b.getBlockData() instanceof Leaves leaf && leaf.isPersistent()) continue;
                foliage.add(b);
            }
            // Both azalea foliage types belong to the same species profile.
            // Prefer Azalea when its mixed canopy outnumbers ordinary oak leaves.
            // Never classify an oak log as Azalea from a single nearby flowering leaf.
            if (foliage.size()>count) { count=foliage.size(); best=profile; }
        }
        // Targeted canopy recovery: some generated crowns contain isolated logs
        // with no face-connected path to their trunk. Require abundant matching,
        // nonpersistent foliage and reject any candidate close to another trunk.
        // This is not custom-tree support and never scans arbitrary block types.
        int recoveredCanopyLogs = 0;
        if (config.getBoolean("tree-feller.experimental-harvesting.canopy-log-recovery.enabled", true)
                && Set.of("AZALEA", "OAK", "CHERRY", "DARK_OAK", "PALE_OAK", "ACACIA", "JUNGLE").contains(best.species)
                && !overflow && unloaded == 0) {
            int extraLimit = best.species.equals("JUNGLE")
                    ? Math.max(0, Math.min(64, config.getInt(
                        "tree-feller.experimental-harvesting.canopy-log-recovery.giant-jungle-max-extra-logs", 32)))
                    : Math.max(0, Math.min(32, config.getInt(
                        "tree-feller.experimental-harvesting.canopy-log-recovery.max-extra-logs", 16)));
            int search = Math.max(2, Math.min(8, config.getInt(
                    "tree-feller.experimental-harvesting.canopy-log-recovery.max-distance", 5)));
            String selectedSpecies = best.species;
            int canopyFloor = logs.stream().mapToInt(Block::getY).min().orElse(start.getY()) + 2;
            Set<Block> candidatesToCheck = new LinkedHashSet<>();
            for (Block trunkLog : new ArrayList<>(logs)) {
                if (trunkLog.getY() < canopyFloor) continue;
                for (int dx=-search; dx<=search; dx++) for (int dy=-3; dy<=3; dy++)
                    for (int dz=-search; dz<=search; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) > search) continue;
                        Block b=trunkLog.getRelative(dx,dy,dz);
                        if (!inBounds(start,b,radius) || !loaded(b) || logs.contains(b)
                                || b.getType()!=start.getType() || b.getY()<canopyFloor) continue;
                        candidatesToCheck.add(b);
                    }
            }
            for (Block candidate : candidatesToCheck) {
                if (logs.contains(candidate)) continue;
                int sameFoliage = 0;
                boolean foreignLog = false;
                for (int dx=-2;dx<=2;dx++) for (int dy=-2;dy<=2;dy++) for (int dz=-2;dz<=2;dz++) {
                    Block around=candidate.getRelative(dx,dy,dz);
                    if (!loaded(around)) { foreignLog=true; continue; }
                    if (around!=candidate && !logs.contains(around) && around.getType().name().endsWith("_LOG")
                            && around.getType()!=candidate.getType()) foreignLog=true;
                    boolean ownLeaf = selectedSpecies.equals("AZALEA")
                            ? around.getType()==Material.AZALEA_LEAVES || around.getType()==Material.FLOWERING_AZALEA_LEAVES
                            : around.getType().name().equals(selectedSpecies+"_LEAVES");
                    if (ownLeaf && around.getBlockData() instanceof Leaves leaf && !leaf.isPersistent()) sameFoliage++;
                }
                // Never claim logs at/near ground, regardless of foliage density.
                Block below = candidate.getRelative(BlockFace.DOWN);
                if (below.getType().isSolid() && below.getType()!=candidate.getType()) continue;
                // Big Oak generation places some branches on rising/diagonal
                // trajectories ending inside separate foliage clusters. Recognize
                // one such canopy log only when a previously selected log is
                // nearby and the candidate remains inside this single-tree scope.
                // This is *not* permission to traverse further trunk networks.
                boolean plausibleBigOakBranch = false;
                if (selectedSpecies.equals("OAK")) {
                    for (Block existing : logs) {
                        int dx = Math.abs(candidate.getX() - existing.getX());
                        int dz = Math.abs(candidate.getZ() - existing.getZ());
                        int dy = candidate.getY() - existing.getY();
                        if (dx + dz >= 1 && dx + dz <= 4 && dy >= 0 && dy <= 3) {
                            plausibleBigOakBranch = true;
                            break;
                        }
                    }
                }
                int foliageThreshold = plausibleBigOakBranch ? 5 : 7;
                if (foreignLog || sameFoliage < foliageThreshold) continue;
                // The optional canopy recovery budget is NOT a hard tree-size failure.
                // Previous versions incorrectly marked a valid tree as overflowing as soon
                // as more than max-extra-logs viable candidates were found. The safety
                // limit applies to the full log component, but reaching this *optional*
                // recovery budget must not prevent the original validated trunk/branches
                // from being felled.
                if (recoveredCanopyLogs >= extraLimit) break;
                if (logs.size() >= maxLogs) {
                    overflow = true; break;
                }
                logs.add(candidate);
                recoveredCanopyLogs++;
            }
        }
        // Canopy recovery may create another bridge to a lower grounded column.
        // Finish its face-connected logs too, before anchor selection and the
        // downstream completeness guard. Otherwise the guard sees connected
        // logs which the harvest plan has never been given a chance to include.
        if (!overflow && unloaded == 0) {
            Completion completion = completeFaceComponent(start, logs, radius, maxLogs);
            overflow = completion.overflow();
            unloaded += completion.unloaded();
            if (completion.added() > 0) {
                Set<Block> refreshedFoliage = new HashSet<>();
                for (Block log : logs) for (int x = -2; x <= 2; x++)
                    for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
                        if (Math.abs(x) + Math.abs(y) + Math.abs(z) > 3) continue;
                        Block b = log.getRelative(x, y, z);
                        if (!inBounds(start, b, radius) || !loaded(b)) continue;
                        boolean match = b.getType().name().equals(best.leaves)
                                || (best.species.equals("AZALEA")
                                && b.getType() == Material.FLOWERING_AZALEA_LEAVES);
                        if (match && (!(b.getBlockData() instanceof Leaves leaf)
                                || !leaf.isPersistent())) refreshedFoliage.add(b);
                    }
                count = Math.max(count, refreshedFoliage.size());
            }
        }
        int bottomY = logs.stream().mapToInt(Block::getY).min().orElse(start.getY());
        boolean large=false;
        for (Block b:logs) if (b.getY() == bottomY
            && logs.contains(b.getRelative(BlockFace.EAST))
            && logs.contains(b.getRelative(BlockFace.SOUTH)) && logs.contains(b.getRelative(1,0,1))) {
            large=true; break;
        }
        // Separate distinct 2x2 trunk anchors even when their branches share log faces.
        // A connected component containing two disjoint trunk anchors is not safe to fell.
        List<TrunkBase> anchors = trunkBases(logs, start.getType());
        boolean multipleBases = false;
        for (int i=0; i<anchors.size(); i++) for (int j=i+1; j<anchors.size(); j++) {
            TrunkBase a = anchors.get(i), b = anchors.get(j);
            if (Math.abs(a.x()-b.x()) > 1 || Math.abs(a.z()-b.z()) > 1) multipleBases = true;
        }
        // For normal trees, multiple widely separated ground-contact clusters also
        // indicate a second independent tree. Side-by-side 2x2 stems are treated above.
        Set<Block> groundedBases = new HashSet<>();
        int lowY = logs.stream().mapToInt(Block::getY).min().orElse(start.getY());
        for (Block log:logs) {
            if (log.getY()>lowY+3) continue;
            Block below=log.getRelative(BlockFace.DOWN);
            if (loaded(below) && below.getType()!=start.getType() && below.getType().isSolid()) groundedBases.add(log);
        }
        for (Block a:groundedBases) for (Block b:groundedBases) {
            if (Math.abs(a.getX()-b.getX())>2 || Math.abs(a.getZ()-b.getZ())>2) multipleBases=true;
        }
        // Trust the trunk-anchor test rather than an arbitrary lowest-Y 2x2 branch.
        if (best.requiresTwoByTwo) large = !anchors.isEmpty();
        Set<Block> attachments=new HashSet<>(), hazards=new HashSet<>(), nests=new HashSet<>();
        int bees=0;
        for (Block log:logs) for (BlockFace face:FACES) {
            Block adjacent=log.getRelative(face);
            if (!loaded(adjacent)) { unloaded++; continue; }
            if ((adjacent.getType() == Material.BEE_NEST || adjacent.getType() == Material.BEEHIVE) && nests.add(adjacent)
                && adjacent.getState() instanceof Beehive nest) bees+=nest.getEntityCount();
            if (construction(adjacent.getType())) {
                attachments.add(adjacent);
                if (hardHazard(adjacent.getType())) hazards.add(adjacent);
            }
        }
        // Multiple grounded columns can be branches on naturally widened trees.
        // Their existence alone is not grounds for rejecting the selected tree.

        if (unloaded>0) return rejected(best.species,logs.size(),count,large,"UNLOADED_CHUNK",diagonal,nests.size(),bees,attachments.size(),unloaded,hazards.size());
        if (overflow) return rejected(best.species,logs.size(),count,large,"LOG_LIMIT_OR_RADIUS_EXCEEDED",diagonal,nests.size(),bees,attachments.size(),0,hazards.size());
        if (!hazards.isEmpty()) return rejected(best.species,logs.size(),count,large,"SENSITIVE_BLOCK_ADJACENT",diagonal,nests.size(),bees,attachments.size(),0,hazards.size());
        if (logs.size()<(best.species.endsWith("MUSHROOM") ? 2 : config.getInt("tree-feller.detection.min-logs",4))) return rejected(best.species,logs.size(),count,large,"INSUFFICIENT_TRUNK",diagonal,nests.size(),bees,attachments.size(),0,0);
        if (count<best.minimumFoliage) return rejected(best.species,logs.size(),count,large,"INSUFFICIENT_CANOPY_OR_PREVIOUSLY_HARVESTED",diagonal,nests.size(),bees,attachments.size(),0,0);
        if (count>maxFoliage) return rejected(best.species,logs.size(),count,large,"FOLIAGE_LIMIT_EXCEEDED",diagonal,nests.size(),bees,attachments.size(),0,0);
        if (best.requiresTwoByTwo && !large) return rejected(best.species,logs.size(),count,large,"MISSING_2X2_TRUNK",diagonal,nests.size(),bees,attachments.size(),0,0);
        List<Block> footprint = List.of();
        if (large && Set.of("DARK_OAK", "PALE_OAK", "SPRUCE", "JUNGLE").contains(best.species)) {
            // Only grounded 2x2 squares with four equal-height bases are
            // usable as planting footprints. An elevated widening is not a base.
            List<List<Block>> supported = new ArrayList<>();
            for (Block a : logs) {
                if (a.getY() > bottomY + 3) continue;
                List<Block> four = List.of(a, a.getRelative(1,0,0),
                        a.getRelative(0,0,1), a.getRelative(1,0,1));
                if (!logs.containsAll(four)) continue;
                boolean grounded = true;
                for (Block b : four) {
                    Block below = b.getRelative(BlockFace.DOWN);
                    if (!below.getType().isSolid() || logs.contains(below)) { grounded=false; break; }
                }
                if (grounded) supported.add(four);
            }
            if (supported.size()==1) footprint = supported.getFirst();
            // Multiple truly grounded 2x2 anchors are not guessed; the
            // harvesting safety validator already rejects separate trees.
        } else if (!large) {
            List<Block> grounded = new ArrayList<>();
            for (Block b : logs) {
                if (b.getY()>bottomY+3) continue;
                Block below=b.getRelative(BlockFace.DOWN);
                if (below.getType().isSolid() && !logs.contains(below)) grounded.add(b);
            }
            if (grounded.size()==1) footprint=List.of(grounded.getFirst());
        }
        return new Plan(new Result(best.species,logs.size(),count,large,true,
            attachments.isEmpty()?"VALID_PRELIMINARY_SCAN":"ATTACHMENTS_REQUIRE_CONFIRMATION",
            diagonal,nests.size(),bees,attachments.size(),0,0), logs, footprint);
    }
}
