package com.iruixos.reapwright.treefeller;

import com.iruixos.reapwright.ReapwrightChat;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Experimental, guarded tree-felling transaction; executes after the originating break event. */
public final class TreeFeller implements Listener {
    private static final Set<Material> AXES = EnumSet.of(
            Material.WOODEN_AXE, Material.STONE_AXE, Material.COPPER_AXE,
            Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE,
            Material.NETHERITE_AXE);
    private static final Set<String> SUPPORTED = Set.of("OAK", "AZALEA", "BIRCH", "SPRUCE", "JUNGLE", "ACACIA", "CHERRY", "POPLAR", "DARK_OAK", "PALE_OAK", "RED_MUSHROOM", "BROWN_MUSHROOM", "MANGROVE", "CRIMSON", "WARPED");
    private final JavaPlugin plugin;
    private final TreeInspector inspector = new TreeInspector();
    private final ExoticTreePlanner exoticTreePlanner = new ExoticTreePlanner();
    private final LeafPlanner leafPlanner = new LeafPlanner();
    private final OrphanLeafPlanner orphanLeafPlanner = new OrphanLeafPlanner();
    private final TreeReplanter replanter = new TreeReplanter();
    private final ConnectedTreePlanner connectedTreePlanner = new ConnectedTreePlanner();
    private final SnowPlanner snowPlanner = new SnowPlanner();
    private final AttachmentPlanner attachmentPlanner = new AttachmentPlanner();
    private final OrphanLeafCleanup orphanLeafCleanup;
    private final TreeBonusLoot bonusLoot;
    private final TreeSaplingDrops saplingDrops;
    private final TreeProtectionAudit protectionAudit;
    private final DominoManager dominoManager;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastNotice = new HashMap<>();
    private final Set<Block> reservedBlocks = new HashSet<>();
    private record Pending(Block block, long expiresAt) { }
    private record BreakAxeOffer(Block origin, ItemStack axe, String token, long expiresAt) { }
    private final Map<UUID, BreakAxeOffer> breakAxeOffers = new HashMap<>();

    /** One-use acknowledgement. Never grants a protection bypass or affects another tree. */
    public void acknowledgeAxeBreak(Player player, String token) {
        BreakAxeOffer offer = breakAxeOffers.remove(player.getUniqueId());
        if (offer == null || !offer.token().equals(token) || System.currentTimeMillis() > offer.expiresAt()
                || !player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                || !player.hasPermission("reapwright.treefeller")
                || !plugin.getConfig().getBoolean("tree-feller.enabled", false)
                || !player.getInventory().getItemInMainHand().isSimilar(offer.axe())
                || player.getInventory().getItemInMainHand().getAmount() != offer.axe().getAmount()
                || !player.getWorld().equals(offer.origin().getWorld())
                || player.getLocation().distanceSquared(offer.origin().getLocation()) > 64
                || offer.origin().getType().isAir()) {
            player.sendMessage(ReapwrightChat.PREFIX + "§e Axe-break acknowledgement expired or conditions changed. Try again.");
            return;
        }
        if (!attemptHarvest(player, offer.origin(), null, null, true)) {
            player.sendMessage(ReapwrightChat.PREFIX + "§e Tree is no longer eligible for harvesting.");
        }
    }

    private void offerAxeBreak(Player player, Block origin, ItemStack axe, int wear, int remaining) {
        String token = UUID.randomUUID().toString();
        breakAxeOffers.put(player.getUniqueId(), new BreakAxeOffer(origin, axe.clone(), token,
                System.currentTimeMillis() + 15000L));
        player.sendMessage(ReapwrightChat.prefix().append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text("Axe may break (wear " + wear + ", durability " + remaining + "). ", NamedTextColor.YELLOW))
                .append(Component.text("[Break Axe Anyway]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/reapwright tree bypass " + token))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Accept partial harvesting if the axe breaks. No full-tree bonus loot or replanting.")))));
    }

    public TreeFeller(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protectionAudit = new TreeProtectionAudit(plugin);
        this.orphanLeafCleanup = new OrphanLeafCleanup(plugin, protectionAudit);
        this.bonusLoot = new TreeBonusLoot(plugin);
        this.saplingDrops = new TreeSaplingDrops(plugin);
        this.dominoManager = new DominoManager(plugin, this);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        lastNotice.remove(event.getPlayer().getUniqueId());
        breakAxeOffers.remove(event.getPlayer().getUniqueId());
        dominoManager.cancel(event.getPlayer().getUniqueId());
    }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        breakAxeOffers.remove(event.getPlayer().getUniqueId());
        dominoManager.cancel(event.getPlayer().getUniqueId());
    }

    private static boolean matchingHarvestFoliage(Block b, Material expected, boolean mushroom) {
        if (expected==Material.MANGROVE_LEAVES) return LeafPlanner.isNaturalLeaf(b,expected);
        if (expected==Material.NETHER_WART_BLOCK || expected==Material.WARPED_WART_BLOCK)
            return b.getType()==expected || b.getType()==Material.SHROOMLIGHT;
        if (mushroom) return b.getType()==Material.RED_MUSHROOM_BLOCK
                || b.getType()==Material.BROWN_MUSHROOM_BLOCK;
        return LeafPlanner.isNaturalLeaf(b,expected);
    }

    /** Deduplicate repeated informational warnings per player AND message category.
     *  Distinct warnings are never blocked by a previous warning's cooldown.
     *  Clickable axe-break offers and critical confirmations intentionally bypass this method.
     */
    private void notice(Player player, String message) {
        notice(player, message, message);
    }

    private void notice(Player player, String key, String message) {
        int seconds = Math.max(0, Math.min(60,
                plugin.getConfig().getInt("tree-feller.message-repeat-cooldown-seconds", 10)));
        if (seconds == 0) {
            player.sendMessage(message);
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> sent = lastNotice.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Long previous = sent.get(key);
        if (previous != null && now - previous < seconds * 1000L) return;
        // Keep this bounded even when a player encounters many different rejection reasons.
        if (sent.size() >= 64) sent.entrySet().removeIf(entry -> now - entry.getValue() >= seconds * 1000L);
        if (sent.size() >= 64) sent.clear();
        sent.put(key, now);
        player.sendMessage(message);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("tree-feller.enabled", false)) return;
        attemptHarvest(event.getPlayer(), event.getBlock(), event, null, false);
    }

    /** Uses the same validation and execution pipeline for a domino candidate; never fires a fake break event. */
    boolean attemptDominoHarvest(Player player, Block origin, DominoManager.Session session) {
        return attemptHarvest(player, origin, null, session, false);
    }

    private boolean attemptHarvest(Player player, Block origin, BlockBreakEvent event, DominoManager.Session dominoSession, boolean acknowledgedAxeBreak) {
        if (!plugin.getConfig().getBoolean("tree-feller.enabled", false)) return false;
        boolean domino = dominoSession != null;
        if (!(origin.getType().name().endsWith("_LOG") || origin.getType() == Material.MUSHROOM_STEM
                || origin.getType()==Material.CRIMSON_STEM || origin.getType()==Material.WARPED_STEM
                || origin.getType()==Material.MANGROVE_ROOTS
                || origin.getType()==Material.MUDDY_MANGROVE_ROOTS)
                || reservedBlocks.contains(origin)) return false;
        if (!player.hasPermission("reapwright.treefeller") || player.getGameMode() != GameMode.SURVIVAL) return false;
        ItemStack axe = player.getInventory().getItemInMainHand();
        if (!AXES.contains(axe.getType())) return false;

        // One axe action selects one natural tree, regardless of the legacy
        // connected-structures option. Prevent chain-felling entire dark forests.
        boolean rootInitiated = origin.getType()==Material.MANGROVE_ROOTS
                || origin.getType()==Material.MUDDY_MANGROVE_ROOTS;
        boolean exotic = rootInitiated || origin.getType()==Material.MANGROVE_LOG
                || origin.getType()==Material.CRIMSON_STEM || origin.getType()==Material.WARPED_STEM;
        boolean harvestLeaves = plugin.getConfig().getBoolean("tree-feller.break-leaves", true);
        ExoticTreePlanner.Selection special = !exotic ? null : rootInitiated
                ? exoticTreePlanner.scanFromMangroveRoot(origin,harvestLeaves)
                : exoticTreePlanner.scan(origin,harvestLeaves);
        if(exotic && special.valid() && special.diagnostics()!=null
                && plugin.getConfig().getBoolean("tree-feller.mangrove-debug",false)){
            plugin.getLogger().info(special.diagnostics());
        }
        if(exotic && !special.valid()){
            if(!domino)notice(player,ReapwrightChat.PREFIX + "§e Tree Feller skipped: "+special.error()+". This block breaks normally.");
            return false;
        }
        TreeInspector.Plan inspected = exotic ? special.inspection()
                : inspector.plan(origin, plugin.getConfig(), false);
        TreeInspector.Result result = inspected.result();
        if (!result.valid()) {
            if ("MULTIPLE_CONNECTED_TRUNKS".equals(result.reason())) {
                if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Multiple connected tree trunks detected. "
                        + "Their logs cannot be safely separated; this log breaks normally.");
            } else {
                if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + result.reason()
                        + ". This log breaks normally.");
            }
            return false;
        }
        boolean mushroom = result.species().endsWith("MUSHROOM");
        if (!SUPPORTED.contains(result.species()) ||
                (result.twoByTwo() && !Set.of("DARK_OAK", "PALE_OAK", "SPRUCE", "JUNGLE").contains(result.species()))) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e This tree form is not yet supported for experimental harvesting.");
            return false;
        }
        // Grounded-column proximity/ownership heuristics no longer veto a valid
        // natural tree. The inspector has already bounded the log scan; hard
        // hazards and chunk/plan revalidation below remain mandatory.
        if (result.suspiciousBlocks() > 0) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Sensitive blocks detected; tree felling refused.");
            return false;
        }
        if (result.attachments() > 0) {
            if (domino) return false; // No implicit acknowledgement of player-made attachments.
            // A second break is a warning acknowledgement, never permission to bypass
            // unsupported attachment drops or the tree safety validator.
            if (event != null) event.setCancelled(true);
            Pending old = pending.get(player.getUniqueId());
            long now = System.currentTimeMillis();
            long period = 1000L * Math.max(1, plugin.getConfig().getInt(
                    "tree-feller.confirmation.attachment-second-break-window-seconds", 15));
            if (old == null || !old.block().equals(origin) || old.expiresAt() < now) {
                pending.put(player.getUniqueId(), new Pending(origin, now + period));
                player.sendMessage(ReapwrightChat.PREFIX + "§e Potential player-made attachments detected. Break the SAME log again within "
                        + (period / 1000) + " seconds to acknowledge the warning.");
            } else {
                pending.remove(player.getUniqueId());
                player.sendMessage(ReapwrightChat.PREFIX + "§c Attachment drop preservation is not implemented; no tree blocks were removed.");
            }
            return false;
        }
        pending.remove(player.getUniqueId());
        if (!protectionAudit.ready()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§c " + protectionAudit.unreadyReason());
            return false;
        }
        if (!(axe.getItemMeta() instanceof Damageable damageable)) return false;
        Set<Block> logs = inspected.logs();
        // Root activation is safe only when the broken root is an actual planned
        // attachment of the uniquely identified Mangrove. This also guarantees
        // that the cancelled vanilla root break is completed by our transaction.
        if (rootInitiated && !special.attachments().containsKey(origin)) return false;
        Material expectedLogType = rootInitiated ? Material.MANGROVE_LOG : origin.getType();
        LeafPlanner.Selection selectedLeaves = exotic
                ? new LeafPlanner.Selection(special.foliage(),null)
                : harvestLeaves ? leafPlanner.select(inspected, plugin.getConfig())
                : new LeafPlanner.Selection(Set.of(), null);
        if (!selectedLeaves.valid()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + selectedLeaves.rejection()
                    + ". No foliage or logs were felled automatically.");
            return false;
        }
        Set<Block> foliage = new java.util.LinkedHashSet<>(selectedLeaves.leaves());
        int orphanPlanned = 0;
        if (!mushroom && !exotic && !foliage.isEmpty()) {
            OrphanLeafPlanner.Selection orphans = orphanLeafPlanner.select(logs, foliage, plugin.getConfig());
            if (!orphans.valid()) {
                if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + orphans.rejection());
                return false;
            }
            orphanPlanned = orphans.leaves().size();
            foliage.addAll(orphans.leaves());
        }
        final int orphanCount = orphanPlanned;
        boolean harvestSnow = harvestLeaves && plugin.getConfig().getBoolean(
                "tree-feller.experimental-harvesting.leaves.clear-snow-layers", true);
        SnowPlanner.Selection selectedSnow = harvestSnow && !exotic
                ? snowPlanner.select(inspected.logs(), foliage)
                : new SnowPlanner.Selection(Set.of(), null);
        if (!selectedSnow.valid()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + selectedSnow.rejection() + ".");
            return false;
        }
        AttachmentPlanner.Selection selectedAttachments = exotic
                ? new AttachmentPlanner.Selection(special.attachments().keySet(),special.attachments(),null)
                : attachmentPlanner.select(result.species(), result.twoByTwo(), logs, foliage, plugin.getConfig());
        if (!selectedAttachments.valid()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + selectedAttachments.rejection());
            return false;
        }
        // Special blocks are distinct from optional vegetation attachments.
        // Never harvest a hive or heart without belonging to this selected tree.
        TreeSpecialBlocks.Selection specialBlocks = TreeSpecialBlocks.select(
                result.species(), logs, player.getWorld());
        if (!specialBlocks.valid()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + specialBlocks.reason());
            return false;
        }
        if (result.beeNests() > specialBlocks.nests().size()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e A bee nest could not be safely assigned to this tree.");
            return false;
        }
        boolean replantEnabled = plugin.getConfig().getBoolean(
                "tree-feller.auto-replant", true);
        // Separate the shared log/leaf harvest from each grounded tree's
        // original pre-harvest planting footprint. No post-harvest re-scans.
        ConnectedTreePlanner.Selection cluster = mushroom || exotic
                ? new ConnectedTreePlanner.Selection(List.of(), null)
                : connectedTreePlanner.select(inspected, plugin.getConfig());
        if (!cluster.valid()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: " + cluster.rejection());
            return false;
        }
        if (!mushroom && cluster.anchors().size() > 1) {
            List<Block> nearest = cluster.anchors().stream()
                    .min(java.util.Comparator.comparingDouble(anchor -> anchor.stream()
                            .mapToDouble(b -> b.getLocation().distanceSquared(origin.getLocation()))
                            .min().orElse(Double.POSITIVE_INFINITY)))
                    .orElse(List.of());
            cluster = new ConnectedTreePlanner.Selection(nearest.isEmpty() ? List.of() : List.of(nearest), null);
        }
        final int treeCount = (mushroom || exotic) ? 1 : cluster.trees();
        List<TreeReplanter.Location> plantingLocations = new ArrayList<>();
        if (replantEnabled && exotic) plantingLocations.add(special.planting());
        else if (replantEnabled && !mushroom) {
            for (List<Block> anchor : cluster.anchors())
                plantingLocations.add(replanter.locateFromAnchor(result.species(), anchor));
        }
        // An unplantable footprint is not an unsafe tree. Preserve the terrain and
        // allow felling; the replant result will explain why planting was skipped.
        if (harvestLeaves && foliage.isEmpty()) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree Feller skipped: no safely attributable leaves found.");
            return false;
        }
        Material foliageType = exotic ? (result.species().equals("MANGROVE")
                ? Material.MANGROVE_LEAVES : result.species().equals("CRIMSON")
                ? Material.NETHER_WART_BLOCK : Material.WARPED_WART_BLOCK)
                : foliage.isEmpty() ? null : foliage.iterator().next().getType();
        if (logs.isEmpty() || logs.size() != result.logs()) return false;
        // The scheduled transaction rolls Unbreaking wear once, immediately before
        // harvest. A non-broken axe is sufficient at planning time; no optimistic
        // durability estimate is used to reject enchanted tools here.
        int remaining = axe.getType().getMaxDurability() - damageable.getDamage();
        if (remaining < 1) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§e This axe has no durability remaining.");
            return false;
        }
        for (Block log : logs) {
            if (reservedBlocks.contains(log) || !log.getWorld().isChunkLoaded(log.getX() >> 4, log.getZ() >> 4)
                    || log.getType() != expectedLogType) {
                if (!domino) notice(player, ReapwrightChat.PREFIX + "§e Tree changed during validation; using normal mining.");
                return false;
            }
        }

        // Validate every planned mutation, including affected planting positions and
        // potential Nylium substrate restoration, before cancelling the vanilla break.
        Set<Block> protectedPlan = new java.util.LinkedHashSet<>();
        protectedPlan.addAll(logs);
        protectedPlan.addAll(foliage);
        protectedPlan.addAll(selectedSnow.layers());
        protectedPlan.addAll(selectedAttachments.blocks());
        protectedPlan.addAll(specialBlocks.blocks());
        if (replantEnabled) for (TreeReplanter.Location planting : plantingLocations) {
            if (planting.site() != null) for (Block position : planting.site().positions()) {
                protectedPlan.add(position);
                // Keep the substrate in the preflight because Nether replanting may
                // convert Netherrack into matching Nylium.
                protectedPlan.add(position.getRelative(BlockFace.DOWN));
            }
        }
        boolean protectedDenied = !protectionAudit.canModifyAll(player, protectedPlan);
        if (replantEnabled) for (TreeReplanter.Location planting : plantingLocations) {
            if (planting.site() != null) for (Block position : planting.site().positions()) {
                if (!protectionAudit.canPlace(player, position)) protectedDenied = true;
                if ((planting.site().sapling() == Material.CRIMSON_FUNGUS
                        || planting.site().sapling() == Material.WARPED_FUNGUS)
                        && !protectionAudit.canPlace(player, position.getRelative(BlockFace.DOWN)))
                    protectedDenied = true;
            }
        }
        if (protectedDenied) {
            if (!domino) notice(player, ReapwrightChat.PREFIX + "§c Tree crosses protected terrain. Automatic harvesting denied.");
            return false;
        }

        // Domino uses distinct, independently validated trees. A candidate that resolves
        // to the same original trunk is skipped, even when a different branch was queued.
        DominoManager.Session nextDominoSession = dominoSession;
        if (domino) {
            if (exotic || !dominoManager.reserveTree(dominoSession, inspected)) return false;
        } else if (!exotic && dominoManager.enabled()) {
            nextDominoSession = dominoManager.begin(player, inspected);
        }
        final DominoManager.Session activeDominoSession = nextDominoSession;
        final List<Block> neighbouringTreeCandidates = activeDominoSession == null
                ? List.of() : dominoManager.discover(origin, logs, foliage, activeDominoSession);

        // Critical fix: do NOT set blocks to AIR while handling a cancellable break event.
        // Paper/other listeners can apply the original break state after our handler returns.
        // Cancel vanilla handling now, and execute on the next main-server tick instead.
        if (event != null) {
            event.setCancelled(true);
            event.setDropItems(false);
        }
        List<Block> plan = new ArrayList<>(logs);
        List<Block> leafPlan = new ArrayList<>(foliage);
        List<Block> snowPlan = new ArrayList<>(selectedSnow.layers());
        List<Block> attachmentPlan = new ArrayList<>(selectedAttachments.blocks());
        Map<Block, Material> attachmentTypes = selectedAttachments.types();
        Map<Block, String> auditedAttachmentStates = TreeAttachmentStates.capture(attachmentPlan);
        reservedBlocks.addAll(plan);
        reservedBlocks.addAll(leafPlan);
        reservedBlocks.addAll(snowPlan);
        reservedBlocks.addAll(attachmentPlan);
        reservedBlocks.addAll(specialBlocks.blocks());
        UUID owner = player.getUniqueId();
        UUID world = origin.getWorld().getUID();
        Material logType = expectedLogType;
        ItemStack toolAtBreak = axe.clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                Player current = plugin.getServer().getPlayer(owner);
                if (current == null || !current.isOnline() || !current.getWorld().getUID().equals(world)
                        || !plugin.getConfig().getBoolean("tree-feller.enabled", false)) return;
                ItemStack held = current.getInventory().getItemInMainHand();
                if (!held.isSimilar(toolAtBreak) || held.getAmount() != toolAtBreak.getAmount()
                        || !(held.getItemMeta() instanceof Damageable currentDamage)) {
                    current.sendMessage(ReapwrightChat.PREFIX + "§e Tree felling cancelled: axe changed before harvesting.");
                    return;
                }
                // Unbreaking on tools prevents each individual durability point
                // with probability level/(level+1). Roll the entire wear budget
                // *before* touching the world, then debit only removed logs.
                // This module intentionally never consumes the last point of an axe.
                final int unbreakingLevel = held.getEnchantmentLevel(Enchantment.UNBREAKING);
                final boolean[] wearsOnLog = AxeWear.roll(plan.size(), unbreakingLevel,
                        ThreadLocalRandom.current());
                final int totalWear = AxeWear.count(wearsOnLog, plan.size());
                final int durabilityLeft = held.getType().getMaxDurability() - currentDamage.getDamage();
                if (durabilityLeft <= 0) {
                    current.sendMessage(ReapwrightChat.PREFIX + "§e Axe has no durability remaining.");
                    return;
                }
                if (totalWear >= durabilityLeft && !acknowledgedAxeBreak) {
                    offerAxeBreak(current, origin, held, totalWear, durabilityLeft);
                    return;
                }
                // With acknowledgement, perform only the log prefix ending at the
                // durability-consuming hit that breaks the axe. The remaining logs,
                // foliage, attachments, and special blocks are left for later.
                int logLimit = plan.size();
                if (acknowledgedAxeBreak && totalWear >= durabilityLeft) {
                    int wearSoFar = 0;
                    for (int i = 0; i < wearsOnLog.length; i++) {
                        if (wearsOnLog[i]) wearSoFar++;
                        if (wearSoFar == durabilityLeft) {
                            logLimit = i + 1;
                            break;
                        }
                    }
                }
                final boolean partialForAxe = logLimit < plan.size();
                // No partial harvest if a block, chunk, or tree status changed while queued.
                boolean deniedNow = !protectionAudit.ready() || !protectionAudit.canModifyAll(current, protectedPlan);
                if (replantEnabled) for (TreeReplanter.Location planting : plantingLocations) {
                    if (planting.site() != null) for (Block position : planting.site().positions()) {
                        if (!protectionAudit.canPlace(current, position)) deniedNow = true;
                        if ((planting.site().sapling() == Material.CRIMSON_FUNGUS
                                || planting.site().sapling() == Material.WARPED_FUNGUS)
                                && !protectionAudit.canPlace(current, position.getRelative(BlockFace.DOWN)))
                            deniedNow = true;
                    }
                }
                if (deniedNow) {
                    current.sendMessage(ReapwrightChat.PREFIX + "§c Tree felling cancelled: protection/audit preflight denied.");
                    return;
                }
                for (Block log : plan) {
                    if (!log.getWorld().isChunkLoaded(log.getX() >> 4, log.getZ() >> 4)
                            || log.getType() != logType) {
                        current.sendMessage(ReapwrightChat.PREFIX + "§e Tree changed before harvesting. No blocks removed.");
                        return;
                    }
                }
                // Revalidate the actual planned blocks, not a separate inferred
                // set of ground columns. The latter produced false positives on
                // normal Dark Oak trunks in dense natural forests.
                for (Block leaf : leafPlan) {
                    if (!leaf.getWorld().isChunkLoaded(leaf.getX() >> 4, leaf.getZ() >> 4)
                            || !matchingHarvestFoliage(leaf, foliageType, mushroom)) {
                        current.sendMessage(ReapwrightChat.PREFIX + "§e Foliage changed before harvesting. No blocks removed.");
                        return;
                    }
                }
                for (Block snow : snowPlan) {
                    if (!snow.getWorld().isChunkLoaded(snow.getX() >> 4, snow.getZ() >> 4)
                            || snow.getType() != Material.SNOW
                            || (!plan.contains(snow.getRelative(BlockFace.DOWN))
                                && !leafPlan.contains(snow.getRelative(BlockFace.DOWN)))) {
                        current.sendMessage(ReapwrightChat.PREFIX + "§e Snow cover changed before harvesting. No blocks removed.");
                        return;
                    }
                }
                for (Block attachment : attachmentPlan) {
                    if (!attachment.getWorld().isChunkLoaded(attachment.getX() >> 4, attachment.getZ() >> 4)
                            || attachment.getType() != attachmentTypes.get(attachment)) {
                        current.sendMessage(ReapwrightChat.PREFIX + "§e Attached vegetation changed. No blocks removed.");
                        return;
                    }
                }
                if (!TreeAttachmentStates.unchanged(auditedAttachmentStates)) {
                    current.sendMessage(ReapwrightChat.PREFIX + "§e Cocoa, Shelf Mushroom or Propagule state changed; harvest cancelled.");
                    return;
                }
                // Validate special-block state before removing any logs.
                if (!TreeSpecialBlocks.unchanged(specialBlocks)) {
                    current.sendMessage(ReapwrightChat.PREFIX + "§e Bee nest or Creaking Heart changed before harvesting; cancelled.");
                    return;
                }
                int specialRemoved = 0;
                // Harvest special blocks before their support logs. Natural Creaking
                // effects are triggered by the actual block break instead of AIR.
                // This is within the same validated, reserved tree transaction.
                for (Block specialBlock : (partialForAxe ? List.<Block>of() : specialBlocks.blocks())) {
                    BlockState beforeSpecial = specialBlock.getState();
                    if (TreeSpecialBlocks.harvest(specialBlock, toolAtBreak, current)) {
                        specialRemoved++;
                        protectionAudit.removed(current, beforeSpecial);
                    }
                    else {
                        current.sendMessage(ReapwrightChat.PREFIX + "§c Special block failed to harvest; remaining tree left intact.");
                        return;
                    }
                }
                int attempts = 0, removed = 0, failures = 0;
                int appliedWear = 0;
                Map<Block, Collection<ItemStack>> pendingDrops = new LinkedHashMap<>();
                for (Block log : plan.subList(0, logLimit)) {
                    attempts++;
                    Collection<ItemStack> calculated = log.getDrops(toolAtBreak, current);
                    // Preserve each block's location for natural item spawning after removal.
                    BlockState original = log.getState();
                    try {
                        log.setType(Material.AIR, false);
                        if (log.getType() == Material.AIR) {
                            removed++;
                            if (wearsOnLog[attempts - 1]) appliedWear++;
                            protectionAudit.removed(current, original);
                            pendingDrops.put(log, calculated);
                        } else {
                            failures++;
                            plugin.getLogger().warning("Tree removal failed at " + original.getLocation());
                        }
                    } catch (RuntimeException exception) {
                        failures++;
                        plugin.getLogger().warning("Tree removal exception at " + original.getLocation()
                                + ": " + exception.getMessage());
                    }
                }
                // Harvest only foliage in the already validated ownership plan.
                // Logs are removed first; leaves are removed in the same server tick.
                int leafAttempts = 0, leafRemoved = 0, leafFailures = 0;
                for (Block leaf : (removed == plan.size() ? leafPlan : List.<Block>of())) {
                    if (!matchingHarvestFoliage(leaf, foliageType, mushroom)) {
                        leafFailures++;
                        continue;
                    }
                    leafAttempts++;
                    Material foliageTypeForDrop = leaf.getType();
                    BlockState originalLeaf = leaf.getState();
                    Collection<ItemStack> calculated = leaf.getDrops(toolAtBreak, current);
                    try {
                        leaf.setType(Material.AIR, false);
                        if (leaf.getType() == Material.AIR) {
                            leafRemoved++;
                            protectionAudit.removed(current, originalLeaf);
                            pendingDrops.put(leaf, saplingDrops.adjust(plugin.getConfig(), result.species(),
                                    foliageTypeForDrop, toolAtBreak, calculated));
                        } else {
                            leafFailures++;
                            plugin.getLogger().warning("Leaf removal failed at " + leaf.getLocation());
                        }
                    } catch (RuntimeException exception) {
                        leafFailures++;
                        plugin.getLogger().warning("Leaf removal exception at " + leaf.getLocation()
                                + ": " + exception.getMessage());
                    }
                }
                // Removing tree blocks with physics disabled intentionally preserves
                // attached snow layers. Explicitly remove only layers supported by
                // our validated logs/leaves, never ground snow or full snow blocks.
                int snowRemoved = 0, snowFailed = 0;
                if (removed == plan.size() && leafRemoved == leafPlan.size()) {
                    for (Block snow : snowPlan) {
                        if (snow.getType() != Material.SNOW) {
                            snowFailed++;
                            continue;
                        }
                        Collection<ItemStack> calculated = snow.getDrops(toolAtBreak, current);
                        BlockState originalSnow = snow.getState();
                        try {
                            snow.setType(Material.AIR, false);
                            if (snow.getType() == Material.AIR) {
                                snowRemoved++;
                                protectionAudit.removed(current, originalSnow);
                                pendingDrops.put(snow, calculated);
                            } else snowFailed++;
                        } catch (RuntimeException exception) {
                            snowFailed++;
                            plugin.getLogger().warning("Snow layer removal exception at "
                                    + snow.getLocation() + ": " + exception.getMessage());
                        }
                    }
                }
                int attachmentRemoved = 0, attachmentFailed = 0;
                if (removed == plan.size() && leafRemoved == leafPlan.size()) {
                    for (Block attachment : attachmentPlan) {
                        if (attachment.getType() != attachmentTypes.get(attachment)) {
                            attachmentFailed++;
                            continue;
                        }
                        BlockState originalAttachment = attachment.getState();
                        try {
                            Collection<ItemStack> calculated = TreeAttachmentStates.drops(
                                    attachment, attachment.getDrops(toolAtBreak, current));
                            Material replacement = exotic && attachmentTypes.get(attachment)
                                    == Material.MUDDY_MANGROVE_ROOTS ? Material.MUD : Material.AIR;
                            if (exotic && attachmentTypes.get(attachment)==Material.MANGROVE_ROOTS
                                    && attachment.getBlockData() instanceof Waterlogged wet && wet.isWaterlogged())
                                replacement=Material.WATER;
                            // Keep the water source when a hanging propagule was waterlogged.
                            if (TreeAttachmentStates.isWaterloggedPropagule(attachment))
                                replacement=Material.WATER;
                            attachment.setType(replacement,false);
                            if (attachment.getType() == replacement) {
                                attachmentRemoved++;
                                protectionAudit.removed(current, originalAttachment);
                                if (replacement != Material.AIR) protectionAudit.placed(current, attachment);
                                pendingDrops.put(attachment, calculated);
                            } else attachmentFailed++;
                        } catch (RuntimeException ex) {
                            attachmentFailed++;
                            plugin.getLogger().warning("Attachment removal failed at " + attachment.getLocation()
                                    + ": " + ex.getMessage());
                        }
                    }
                }
                String replantResult = "disabled";
                if (replantEnabled && !mushroom && removed == plan.size() && leafRemoved == leafPlan.size()) {
                    List<String> outcomes=new ArrayList<>();
                    int index=0;
                    for (TreeReplanter.Location planting : plantingLocations) {
                        index++;
                        String outcome=planting.site()==null ? planting.reason()
                            : replanter.plant(planting.site(), pendingDrops, current,
                                plugin.getConfig().getBoolean(
                                    "tree-feller.experimental-harvesting.replanting.use-inventory-fallback", true),
                                toolAtBreak.containsEnchantment(Enchantment.SILK_TOUCH)
                                        && plugin.getConfig().getBoolean("tree-feller.free-silk-touch-replant", true),
                                protectionAudit);
                        if (planting.site() == null && planting.reason().startsWith("UNEVEN_2X2_BASE")) {
                            // Player-only explanation; the terrain is never flattened automatically.
                            notice(current, "UNEVEN_2X2_BASE", ReapwrightChat.PREFIX + "§e Could not replant this 2×2 tree: "
                                    + "Minecraft requires all four saplings to be planted at the same Y level. "
                                    + "The original trunk bases are at different heights.");
                        }
                        outcomes.add("tree " + index + ": " + outcome);
                    }
                    replantResult = outcomes.isEmpty() ? "skipped (no unambiguous planting anchor)" : String.join("; ", outcomes);
                } else if (replantEnabled) {
                    replantResult = mushroom ? "mushroom replanting not yet supported" : "skipped (incomplete harvest)";
                }
                for (var entry : pendingDrops.entrySet()) {
                    for (ItemStack item : entry.getValue()) {
                        if (!item.getType().isAir() && item.getAmount() > 0) {
                            entry.getKey().getWorld().dropItemNaturally(entry.getKey().getLocation(), item);
                        }
                    }
                }
                // Bonus loot is independent of vanilla block drops and rolls once
                // per fully successful tree, never once per log or leaf.
                if (removed == plan.size() && leafRemoved == leafPlan.size()
                        && attachmentRemoved == attachmentPlan.size()
                        && snowRemoved == snowPlan.size()
                        && specialRemoved == specialBlocks.blocks().size()) {
                    for (ItemStack bonus : bonusLoot.roll(plugin.getConfig(), result.species(), toolAtBreak)) {
                        origin.getWorld().dropItemNaturally(origin.getLocation(), bonus);
                    }
                }
                // Mangrove removal uses the same orphan leaf/vine repair as
                // ordinary trees. This was previously skipped for all exotic
                // trees, leaving unsupported Mangrove vine curtains behind.
                if (!mushroom && (!exotic || result.species().equals("MANGROVE"))
                        && removed == plan.size() && leafRemoved == leafPlan.size()) {
                    orphanLeafCleanup.schedule(current, plan, leafPlan);
                }
                if (removed > 0) {
                    // Debited only for logs actually removed. Wear for a failed
                    // log is not charged, even if its precomputed roll was true.
                    if (appliedWear > 0) {
                        int newDamage = currentDamage.getDamage() + appliedWear;
                        if (newDamage >= held.getType().getMaxDurability()) {
                            current.getInventory().setItemInMainHand(null);
                            current.getWorld().playSound(current.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                        } else {
                            currentDamage.setDamage(newDamage);
                            held.setItemMeta(currentDamage);
                            current.getInventory().setItemInMainHand(held);
                        }
                    }
                }
                // Keep player-facing safety notices even when diagnostics are disabled.
                // The axe-break warning above already explains an intentional partial harvest.
                if (partialForAxe) {
                    boolean axeBroke = current.getInventory().getItemInMainHand().getType().isAir();
                    current.sendMessage(ReapwrightChat.PREFIX + "§e " + (axeBroke ? "Axe broke" : "Partial harvest")
                            + ": " + removed + "/" + plan.size()
                            + " logs harvested. No bonus loot or replanting.");
                }
                boolean debugMessages = plugin.getConfig().getBoolean("tree-feller.debug-messages", false);
                if (debugMessages) {
                    if (replantEnabled) current.sendMessage(ReapwrightChat.PREFIX + "§b Replanting: " + replantResult + ".");
                    if (!specialBlocks.blocks().isEmpty()) current.sendMessage(ReapwrightChat.PREFIX + "§b Special blocks harvested: " + specialRemoved + ".");
                    current.sendMessage(ReapwrightChat.PREFIX + "§b Tree Feller: connected trees=" + treeCount + "; planned=" + plan.size()
                            + ", attempted=" + attempts + ", removed=" + removed
                            + ", failed=" + failures + "; leaves planned=" + leafPlan.size()
                            + ", attempted=" + leafAttempts + ", removed=" + leafRemoved
                            + ", failed=" + leafFailures + "; snow layers planned="
                            + snowPlan.size() + ", removed=" + snowRemoved + ", failed=" + snowFailed
                            + "; orphan leaves added=" + orphanCount + "; attachments planned=" + attachmentPlan.size() + ", removed="
                            + attachmentRemoved + ", failed=" + attachmentFailed + ".");
                }
                if (removed == plan.size() && leafRemoved == leafPlan.size()
                        && activeDominoSession != null) {
                    dominoManager.completed(activeDominoSession, neighbouringTreeCandidates);
                } else if (activeDominoSession != null) {
                    dominoManager.failed(activeDominoSession);
                }
                if (debugMessages) {
                    if (removed == plan.size()) {
                        if (leafRemoved == leafPlan.size()) {
                            current.sendMessage(ReapwrightChat.PREFIX + "§a Removed all planned logs ("
                                    + result.diagonalBranches() + " diagonal branches) and "
                                    + leafRemoved + " owned leaves instantly.");
                        } else {
                            current.sendMessage(ReapwrightChat.PREFIX + "§c INCOMPLETE_FOLIAGE_HARVEST: "
                                    + (leafPlan.size() - leafRemoved) + " leaves remain or failed.");
                        }
                    } else {
                        current.sendMessage(ReapwrightChat.PREFIX + "§c INCOMPLETE_HARVEST: "
                                + (plan.size() - removed) + " planned logs remain or failed.");
                    }
                } else if (!partialForAxe && removed != plan.size()) {
                    // Unexpected incomplete harvest is operationally important, not debug noise.
                    current.sendMessage(ReapwrightChat.PREFIX + "§c Tree harvesting was incomplete; some logs remain.");
                } else if (!partialForAxe && leafRemoved != leafPlan.size()) {
                    current.sendMessage(ReapwrightChat.PREFIX + "§e Tree felled, but some foliage could not be removed.");
                }
            } finally {
                if (activeDominoSession != null) dominoManager.releaseIfPending(activeDominoSession);
                reservedBlocks.removeAll(plan);
                reservedBlocks.removeAll(leafPlan);
                reservedBlocks.removeAll(snowPlan);
                reservedBlocks.removeAll(attachmentPlan);
                reservedBlocks.removeAll(specialBlocks.blocks());
            }
        });
        return true;
    }
}
