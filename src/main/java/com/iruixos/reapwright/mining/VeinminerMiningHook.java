package com.iruixos.reapwright.mining;

import com.iruixos.reapwright.ReapwrightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VeinminerMiningHook implements Listener {
    private static final String EVENT_OWNER = "de.miraculixx.veinminer.VeinMinerEvent";
    private static final int ORIGIN_LIFETIME_TICKS = 5 * 60 * 20;

    private final MiningBonusLoot mining;
    private final Class<? extends Event> veinBreak;
    private final Method sourceLocation;
    private final Method dropPlayer;
    private final Method dropBlockState;
    private final Method dropItems;
    private final Map<BlockKey, BreakOrigin> origins = new HashMap<>();
    private final Map<BlockKey, PendingDrop> pending = new HashMap<>();

    private record BlockKey(UUID player, UUID world, int x, int y, int z) {
        static BlockKey at(Player player, Location location) {
            return new BlockKey(player.getUniqueId(), location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private record BreakOrigin(MiningBonusLoot.MiningTool tool, int tick) {
        boolean expired(int currentTick) {
            return currentTick - tick >= ORIGIN_LIFETIME_TICKS;
        }
    }

    private record PendingDrop(MiningBonusLoot.MiningTool tool, int tick) {}

    private VeinminerMiningHook(MiningBonusLoot mining, Class<? extends Event> veinBreak, Method sourceLocation,
                                Method dropPlayer, Method dropBlockState, Method dropItems) {
        this.mining = mining;
        this.veinBreak = veinBreak;
        this.sourceLocation = sourceLocation;
        this.dropPlayer = dropPlayer;
        this.dropBlockState = dropBlockState;
        this.dropItems = dropItems;
    }

    public static void register(ReapwrightPlugin plugin, MiningBonusLoot mining) {
        PluginManager manager = plugin.getServer().getPluginManager();
        Plugin veinminer = manager.getPlugin("Veinminer");
        if (veinminer == null || !veinminer.isEnabled()) {
            plugin.getLogger().info("Mining: Veinminer not hooked (plugin unavailable).");
            return;
        }

        try {
            ClassLoader loader = veinminer.getClass().getClassLoader();
            Class<? extends Event> breakEvent = Class.forName(EVENT_OWNER + "$VeinminerEvent", false, loader)
                    .asSubclass(BlockBreakEvent.class);
            Class<? extends Event> dropEvent = Class.forName(EVENT_OWNER + "$VeinminerDropEvent", false, loader)
                    .asSubclass(Event.class);
            VeinminerMiningHook hook = new VeinminerMiningHook(mining, breakEvent,
                    breakEvent.getMethod("getSourceLocation"), dropEvent.getMethod("getPlayer"),
                    dropEvent.getMethod("getBlockState"), dropEvent.getMethod("getItems"));
            manager.registerEvent(dropEvent, hook, EventPriority.HIGHEST, (listener, event) -> {
                if (dropEvent.isInstance(event)) hook.addVeinDrops(event);
            }, plugin, true);
            manager.registerEvents(hook, plugin);
            plugin.getServer().getScheduler().runTaskTimer(plugin, hook::prune, 200L, 200L);
            plugin.getLogger().info("Mining: hooked Veinminer " + veinminer.getPluginMeta().getVersion() + ".");
        } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException | LinkageError error) {
            plugin.getLogger().warning("Mining: Veinminer not hooked (compatible drop events unavailable). "
                    + "Manual mining bonuses are still active when enabled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStartingBreak(BlockBreakEvent event) {
        if (!mining.isBonusLootActive() || veinBreak.isInstance(event)
                || event.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        Player player = event.getPlayer();
        BlockKey key = BlockKey.at(player, event.getBlock().getLocation());
        MiningBonusLoot.MiningTool tool = MiningBonusLoot.MiningTool.capture(player.getInventory().getItemInMainHand());
        int tick = Bukkit.getCurrentTick();
        BreakOrigin previous = origins.get(key);
        // Delayed breaks from two runs can share an origin. Only use it when both runs agree on the tool.
        if (previous != null && !previous.expired(tick) && !tool.equals(previous.tool())) tool = null;
        origins.put(key, new BreakOrigin(tool, tick));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVeinBreak(BlockBreakEvent event) throws ReflectiveOperationException {
        if (!veinBreak.isInstance(event)) return;
        Player player = event.getPlayer();
        BlockKey target = BlockKey.at(player, event.getBlock().getLocation());
        pending.remove(target);
        if (event.isCancelled() || !event.isDropItems()) return;

        Location source = (Location) sourceLocation.invoke(event);
        if (source.getWorld() != event.getBlock().getWorld()) return;
        BlockKey originKey = BlockKey.at(player, source);
        BreakOrigin origin = origins.get(originKey);
        int tick = Bukkit.getCurrentTick();
        if (origin == null || origin.tool() == null || origin.expired(tick)) return;
        pending.put(target, new PendingDrop(origin.tool(), tick));
    }

    @SuppressWarnings("unchecked")
    private void addVeinDrops(Event event) throws EventException {
        try {
            Player player = (Player) dropPlayer.invoke(event);
            BlockState block = (BlockState) dropBlockState.invoke(event);
            PendingDrop match = pending.remove(BlockKey.at(player, block.getLocation()));
            if (match == null || match.tick() != Bukkit.getCurrentTick()) return;

            List<ItemStack> items = (List<ItemStack>) dropItems.invoke(event);
            if (!items.isEmpty()) mining.addRewards(player, block.getWorld(), block.getType(), match.tool(), items::add);
        } catch (ReflectiveOperationException error) {
            throw new EventException(error);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        origins.keySet().removeIf(key -> key.player().equals(player));
        pending.keySet().removeIf(key -> key.player().equals(player));
    }

    private void prune() {
        int tick = Bukkit.getCurrentTick();
        origins.values().removeIf(origin -> origin.expired(tick));
        pending.values().removeIf(drop -> drop.tick() != tick);
    }
}
