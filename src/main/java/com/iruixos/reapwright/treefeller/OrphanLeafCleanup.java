package com.iruixos.reapwright.treefeller;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockState;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Delayed, bounded vanilla leaf-distance repair and orphaned vine cleanup after felling.
 * Never loads chunks or destroys persistent leaves. Only demonstrably unsupported vines are removed.
 * All world access runs on the Paper main server thread (not Folia).
 */
final class OrphanLeafCleanup {
    private static final int FIRST_DELAY_TICKS = 60; // 3 seconds at 20 TPS
    private static final int SECOND_DELAY_TICKS = 100; // another 5 seconds
    private static final int COOLDOWN_TICKS = 200; // 10 seconds after second pass
    private static final int MARGIN = 4;
    private static final int BLOCKS_PER_TICK = 1000;
    private static final int MAX_VOLUME = 160_000;
    private static final int MAX_ACTIVE_REGIONS = 12;
    private static final int MAX_VINE_CHAIN = 128; // Fail closed on very long chains.
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final JavaPlugin plugin;
    private final TreeProtectionAudit audit;
    private final List<Job> jobs = new ArrayList<>();
    private BukkitTask ticker;
    private long tick;

    OrphanLeafCleanup(JavaPlugin plugin, TreeProtectionAudit audit) {
        this.plugin = plugin;
        this.audit = audit;
    }

    void schedule(Player player, List<Block> logs, List<Block> foliage) {
        if (!plugin.getConfig().getBoolean("tree-feller.orphan-leaf-cleanup", true)) return;
        if (logs.isEmpty() || logs.get(0).getWorld() == null) return;
        World world = logs.get(0).getWorld();
        Box box = Box.around(world, logs, foliage);
        if (box == null || box.volume() > MAX_VOLUME) {
            plugin.getLogger().fine("Skipping orphan leaf cleanup: region too large");
            return;
        }
        for (Job job : jobs) {
            if (job.owner.equals(player.getUniqueId()) && job.world.getUID().equals(world.getUID()) && job.box.touches(box)) {
                Box combined = job.box.union(box);
                if (combined.volume() <= MAX_VOLUME) {
                    job.box = combined;
                    // If a scan is underway or already completed, queue another pair
                    // after this region's cooldown rather than dropping this harvest.
                    if (job.phase != 0 || job.scanning) job.rescanRequested = true;
                    ensureTicker();
                    return;
                }
            }
        }
        if (jobs.size() >= MAX_ACTIVE_REGIONS) {
            plugin.getLogger().fine("Skipping orphan leaf cleanup: maximum regions queued");
            return;
        }
        jobs.add(new Job(world, box, tick + FIRST_DELAY_TICKS, player.getUniqueId()));
        ensureTicker();
    }

    private void ensureTicker() {
        if (ticker == null) ticker = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::step, 1L, 1L);
    }

    private void step() {
        tick++;
        Iterator<Job> it = jobs.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            if (job.scanning) {
                scanBatch(job);
                continue;
            }
            if (tick < job.nextTick) continue;
            if (job.phase == 2) {
                if (job.rescanRequested) {
                    job.rescanRequested = false;
                    job.phase = 0;
                    job.nextTick = tick + FIRST_DELAY_TICKS;
                } else {
                    it.remove();
                }
                continue;
            }
            job.scanning = true;
            job.cursor = 0;
            job.activeBox = job.box;
            scanBatch(job);
        }
        if (jobs.isEmpty() && ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void scanBatch(Job job) {
        Box area = job.activeBox;
        int width = area.maxX - area.minX + 1;
        int depth = area.maxZ - area.minZ + 1;
        int layerSize = width * depth;
        long volume = area.volume();
        int inspected = 0;
        while (job.cursor < volume && inspected++ < BLOCKS_PER_TICK) {
            long index = job.cursor++;
            int y = area.minY + (int)(index / layerSize);
            int remainder = (int)(index % layerSize);
            int x = area.minX + remainder % width;
            int z = area.minZ + remainder / width;
            if (!job.world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block block = job.world.getBlockAt(x, y, z);
            Player owner = plugin.getServer().getPlayer(job.owner);
            if (owner == null || !owner.isOnline() || !audit.ready() || !audit.canModify(owner, block)) continue;
            if (block.getType() == Material.VINE) {
                // Vines have no leaf-like decay flag. Only break truly unattached
                // hanging vines; allow vanilla physics to update neighbours.
                if (vineSupport(block) == Support.UNSUPPORTED) {
                    BlockState original = block.getState();
                    block.setType(Material.AIR, true);
                    if (block.getType() == Material.AIR) {
                        audit.removed(owner, original);
                        job.vinesRemoved++;
                    }
                }
                continue;
            }
            if (!(block.getBlockData() instanceof Leaves data) || data.isPersistent()) continue;
            Support support = supportedBySurvivingLog(block);
            if (support != Support.UNSUPPORTED) continue;
            if (data.getDistance() != 7 && audit.canPlace(owner, block)) {
                data.setDistance(7);
                // Let vanilla physics and random ticks handle natural decay.
                // Never overwrite player-placed leaves or force-delete blocks.
                BlockState original = block.getState();
                block.setBlockData(data, true);
                audit.changed(owner, original, block);
                job.changed++;
            }
        }
        if (job.cursor >= volume) {
            job.scanning = false;
            if (job.phase == 0) {
                job.phase = 1;
                job.nextTick = tick + SECOND_DELAY_TICKS;
            } else {
                job.phase = 2;
                job.nextTick = tick + COOLDOWN_TICKS;
                plugin.getLogger().fine("Orphan leaf cleanup completed: updated "
                        + job.changed + " leaf distances, removed " + job.vinesRemoved
                        + " unsupported vines (two passes)");
            }
        }
    }

    /**
     * Ordinary vines may be attached horizontally to a solid block, to the
     * underside of a block, or hang below an anchored vine. Follow only the
     * upward chain; never flood-fill sideways into a neighbouring canopy.
     * Unknown or very long chains are deliberately preserved.
     */
    private Support vineSupport(Block origin) {
        Block current = origin;
        Set<Block> visited = new HashSet<>();
        for (int length = 0; length < MAX_VINE_CHAIN; length++) {
            if (!loaded(current)) return Support.UNKNOWN;
            if (current.getType() != Material.VINE) return Support.UNKNOWN;
            if (!visited.add(current)) return Support.UNKNOWN;
            if (!(current.getBlockData() instanceof MultipleFacing faces)) return Support.UNKNOWN;
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH,
                    BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
                Block beside = current.getRelative(face);
                if (beside.getY() >= current.getWorld().getMaxHeight()) continue;
                if (!loaded(beside)) return Support.UNKNOWN;
                // Only a face that is actually present may count as an anchor.
                // Keep vines attached to existing natural foliage or structures.
                if (faces.getAllowedFaces().contains(face) && faces.hasFace(face)
                        && beside.getType() != Material.VINE && (beside.getType().isSolid()
                        || beside.getBlockData() instanceof Leaves))
                    return Support.SUPPORTED;
                // A vine can hang from a block above even without an UP state.
                if (face == BlockFace.UP && beside.getType() != Material.VINE
                        && (beside.getType().isSolid()
                        || beside.getBlockData() instanceof Leaves)) return Support.SUPPORTED;
            }
            Block above = current.getRelative(BlockFace.UP);
            if (above.getY() >= current.getWorld().getMaxHeight()) return Support.UNSUPPORTED;
            if (!loaded(above)) return Support.UNKNOWN;
            if (above.getType() != Material.VINE) return Support.UNSUPPORTED;
            current = above;
        }
        return Support.UNKNOWN;
    }

    private static boolean loaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    /** Unknown (unloaded-chunk) paths are preserved, never misclassified as orphaned. */
    private Support supportedBySurvivingLog(Block first) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();
        queue.add(new Node(first, 0));
        visited.add(first);
        boolean unknown = false;
        while (!queue.isEmpty()) {
            Node next = queue.removeFirst();
            if (next.depth == 6) continue;
            for (BlockFace face : FACES) {
                int x = next.block.getX() + face.getModX();
                int y = next.block.getY() + face.getModY();
                int z = next.block.getZ() + face.getModZ();
                World world = next.block.getWorld();
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    unknown = true;
                    continue;
                }
                Block adjacent = world.getBlockAt(x, y, z);
                Material type = adjacent.getType();
                if (isLog(type)) return Support.SUPPORTED;
                if (next.depth < 5 && adjacent.getBlockData() instanceof Leaves
                        && visited.add(adjacent)) {
                    queue.addLast(new Node(adjacent, next.depth + 1));
                }
            }
        }
        return unknown ? Support.UNKNOWN : Support.UNSUPPORTED;
    }

    private static boolean isLog(Material type) {
        String name = type.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    private enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
    private record Node(Block block, int depth) { }

    private static final class Job {
        final World world;
        Box box;
        Box activeBox;
        int phase = 0;
        long nextTick;
        long cursor;
        boolean scanning;
        boolean rescanRequested;
        int changed;
        int vinesRemoved;
        final UUID owner;
        Job(World world, Box box, long first, UUID owner) {
            this.owner = owner;
            this.world = world;
            this.box = box;
            this.nextTick = first;
        }
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Box around(World world, List<Block> logs, List<Block> foliage) {
            if (logs.isEmpty()) return null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (Block block : logs) {
                minX = Math.min(minX, block.getX()); maxX = Math.max(maxX, block.getX());
                minY = Math.min(minY, block.getY()); maxY = Math.max(maxY, block.getY());
                minZ = Math.min(minZ, block.getZ()); maxZ = Math.max(maxZ, block.getZ());
            }
            for (Block block : foliage) {
                minX = Math.min(minX, block.getX()); maxX = Math.max(maxX, block.getX());
                minY = Math.min(minY, block.getY()); maxY = Math.max(maxY, block.getY());
                minZ = Math.min(minZ, block.getZ()); maxZ = Math.max(maxZ, block.getZ());
            }
            return new Box(minX - MARGIN, Math.max(world.getMinHeight(), minY - MARGIN), minZ - MARGIN,
                    maxX + MARGIN, Math.min(world.getMaxHeight() - 1, maxY + MARGIN), maxZ + MARGIN);
        }
        long volume() { return (long)(maxX-minX+1) * (maxY-minY+1) * (maxZ-minZ+1); }
        boolean touches(Box other) {
            return minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }
        Box union(Box other) {
            return new Box(Math.min(minX,other.minX),Math.min(minY,other.minY),Math.min(minZ,other.minZ),
                    Math.max(maxX,other.maxX),Math.max(maxY,other.maxY),Math.max(maxZ,other.maxZ));
        }
    }
}
