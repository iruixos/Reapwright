package com.iruixos.reapwright.treefeller;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Optional, bounded domino traversal. Does not merge tree plans or bypass the
 * existing single-tree safety checks. Entirely independent implementation.
 * All calls execute on the primary server thread.
 */
public final class DominoManager {
    private static final BlockFace[] DIRECTIONS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };
    private final JavaPlugin plugin;
    private final TreeFeller harvester;
    private final java.util.Map<UUID, Session> sessions = new java.util.HashMap<>();

    public static final class Session {
        final UUID playerId;
        final UUID worldId;
        final Material originalLog;
        final int maximumAdditional;
        final int checksPerTick;
        final int searchDistance;
        final boolean allowMixed;
        final ArrayDeque<Block> queue = new ArrayDeque<>();
        final Set<String> queued = new HashSet<>();
        final Set<String> visitedTrunks = new HashSet<>();
        int additionalHarvests;
        int successfulHarvests;
        boolean waitingForHarvest;
        boolean scheduled;
        boolean closed;

        Session(Player player, Material originalLog, int maximumAdditional, int checksPerTick,
                int searchDistance, boolean allowMixed) {
            this.playerId = player.getUniqueId();
            this.worldId = player.getWorld().getUID();
            this.originalLog = originalLog;
            this.maximumAdditional = maximumAdditional;
            this.checksPerTick = checksPerTick;
            this.searchDistance = searchDistance;
            this.allowMixed = allowMixed;
        }
    }

    DominoManager(JavaPlugin plugin, TreeFeller harvester) {
        this.plugin = plugin;
        this.harvester = harvester;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("tree-feller.domino.enabled", false);
    }

    Session begin(Player player, TreeInspector.Plan plan) {
        Session session = new Session(player, plan.logs().iterator().next().getType(),
                Math.max(0, Math.min(16, plugin.getConfig().getInt("tree-feller.domino.max-additional-trees", 2))),
                Math.max(1, Math.min(256, plugin.getConfig().getInt("tree-feller.domino.max-checks-per-tick", 64))),
                Math.max(1, Math.min(16, plugin.getConfig().getInt("tree-feller.domino.max-search-distance", 8))),
                plugin.getConfig().getBoolean("tree-feller.domino.allow-mixed-species", false));
        if (session.maximumAdditional == 0) return null;
        cancel(player.getUniqueId());
        markPlan(session, plan);
        session.waitingForHarvest = true;
        sessions.put(player.getUniqueId(), session);
        return session;
    }

    void cancel(UUID playerId) {
        Session previous = sessions.remove(playerId);
        if (previous != null) previous.closed = true;
    }

    private static String id(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static String treeKey(TreeInspector.Plan plan) {
        // Trunk anchors survive branch selection and distinguish trees even when
        // several candidate logs point into the same single-tree plan.
        List<Block> anchors = plan.trunkFootprint();
        if (anchors.isEmpty()) {
            int lowest = plan.logs().stream().mapToInt(Block::getY).min().orElse(0);
            anchors = plan.logs().stream().filter(b -> b.getY() == lowest).toList();
        }
        return anchors.stream().map(DominoManager::id).sorted().findFirst().orElse("empty");
    }

    private static void markPlan(Session session, TreeInspector.Plan plan) {
        session.visitedTrunks.add(treeKey(plan));
    }

    boolean reserveTree(Session session, TreeInspector.Plan plan) {
        if (session.closed || sessions.get(session.playerId) != session) return false;
        return session.visitedTrunks.add(treeKey(plan));
    }

    /**
     * Discover possible neighbours while the original foliage still exists.
     * Candidate discovery never grants permission to break: every candidate
     * must pass TreeFeller's complete single-tree inspection independently.
     */
    List<Block> discover(Block origin, Set<Block> logs, Set<Block> leaves, Session session) {
        if (session == null || session.closed || !enabled()) return List.of();
        Set<Block> observed = new LinkedHashSet<>();
        Set<Block> owned = new HashSet<>(logs);
        owned.addAll(leaves);
        // Sample the owned canopy perimeter and log perimeter, not the whole
        // forest. A nearest block is not necessarily part of the same tree.
        Set<Block> frontier = new LinkedHashSet<>(leaves);
        frontier.addAll(logs);
        int checked = 0;
        int budget = Math.max(64, session.checksPerTick * 8);
        for (Block member : frontier) {
            if (checked >= budget || observed.size() >= 128) break;
            for (BlockFace dir : DIRECTIONS) {
                if (++checked > budget) break;
                Block adjacent = member.getRelative(dir);
                if (!adjacent.getWorld().isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)) continue;
                if (owned.contains(adjacent)) continue;
                // Require a log immediately across the selected tree boundary,
                // or across one leaf block. This is discovery, not ownership.
                candidate(adjacent, origin, session, owned, observed);
                if (adjacent.getType().name().endsWith("_LEAVES")) {
                    for (BlockFace next : DIRECTIONS) {
                        if (++checked > budget) break;
                        candidate(adjacent.getRelative(next), origin, session, owned, observed);
                    }
                }
            }
        }
        List<Block> ranked = new ArrayList<>(observed);
        ranked.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(origin.getLocation())));
        return ranked;
    }

    private void candidate(Block b, Block origin, Session session, Set<Block> owned, Set<Block> found) {
        if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)
                || owned.contains(b) || !b.getType().name().endsWith("_LOG")) return;
        if (!session.allowMixed && b.getType() != session.originalLog) return;
        if (Math.abs(b.getX() - origin.getX()) > session.searchDistance + 16
                || Math.abs(b.getZ() - origin.getZ()) > session.searchDistance + 16) return;
        found.add(b);
    }

    void completed(Session session, List<Block> candidates) {
        if (session.closed) return;
        if (!session.waitingForHarvest) return;
        if (session.additionalHarvests >= session.maximumAdditional) {
            finish(session);
            return;
        }
        session.successfulHarvests++;
        session.additionalHarvests = Math.max(0, session.successfulHarvests - 1);
        for (Block b : candidates) {
            if (session.queue.size() >= 128) break;
            if (session.queued.add(id(b))) session.queue.addLast(b);
        }
        session.waitingForHarvest = false;
        if (session.additionalHarvests >= session.maximumAdditional) finish(session);
        else schedule(session);
    }

    void failed(Session session) {
        if (session.closed) return;
        session.waitingForHarvest = false;
        // A failed attempted tree never expands domino. Continue with other
        // candidates only while the original player/permissions remain valid.
        schedule(session);
    }

    void releaseIfPending(Session session) {
        if (session != null && session.waitingForHarvest && !session.closed) failed(session);
    }

    private void schedule(Session session) {
        if (session.closed || session.scheduled || session.waitingForHarvest) return;
        session.scheduled = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> process(session), 1L);
    }

    private void process(Session session) {
        session.scheduled = false;
        if (session.closed || sessions.get(session.playerId) != session || !enabled()
                || session.additionalHarvests >= session.maximumAdditional) {
            finish(session);
            return;
        }
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                || !player.getWorld().getUID().equals(session.worldId)) {
            finish(session);
            return;
        }
        int inspected = 0;
        // One accepted harvest per tick, regardless of the configured concurrency
        // ceiling. This avoids scheduling overlapping plans against changing blocks.
        while (!session.queue.isEmpty() && inspected++ < session.checksPerTick) {
            Block seed = session.queue.removeFirst();
            if (!seed.getWorld().isChunkLoaded(seed.getX() >> 4, seed.getZ() >> 4)) continue;
            if (!seed.getType().name().endsWith("_LOG")) continue;
            if (harvester.attemptDominoHarvest(player, seed, session)) {
                session.waitingForHarvest = true;
                return;
            }
        }
        if (session.queue.isEmpty()) finish(session);
        else schedule(session);
    }

    private void finish(Session session) {
        session.closed = true;
        sessions.remove(session.playerId, session);
    }
}
