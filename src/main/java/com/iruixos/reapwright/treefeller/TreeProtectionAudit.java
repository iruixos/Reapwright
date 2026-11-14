package com.iruixos.reapwright.treefeller;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.protection.flags.Flags;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Collection;

/** Optional WorldGuard 7 / CoreProtect API v10+ integration. Fail closed if either API cannot be queried. */
final class TreeProtectionAudit {
    private final JavaPlugin plugin;
    private boolean installed(String pluginName) {
        return plugin.getServer().getPluginManager().isPluginEnabled(pluginName);
    }
    private boolean optedIn(String key) {
        return plugin.getConfig().getBoolean("tree-feller.integrations." + key, false);
    }
    String unreadyReason() {
        if (installed("WorldGuard") && !optedIn("worldguard"))
            return "WorldGuard integration is disabled in config.yml; automatic felling is blocked.";
        if (installed("CoreProtect") && !optedIn("coreprotect"))
            return "CoreProtect integration is disabled in config.yml; automatic felling is blocked.";
        if (installed("CoreProtect") && !readyCoreProtect())
            return "CoreProtect audit API unavailable; automatic felling is blocked.";
        return null;
    }
    private boolean readyCoreProtect() {
        try { return api() != null; }
        catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("CoreProtect API unavailable: " + failure);
            return false;
        }
    }
    TreeProtectionAudit(JavaPlugin plugin) { this.plugin = plugin; }

    boolean canModify(Player player, Block block) {
        return regionAllowed(player, block, false);
    }
    boolean canPlace(Player player, Block block) {
        return regionAllowed(player, block, true);
    }
    private boolean regionAllowed(Player player, Block block, boolean placing) {
        if (!installed("WorldGuard")) return true;
        if (!optedIn("worldguard")) return false;
        try {
            LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
            if (WorldGuard.getInstance().getPlatform().getSessionManager()
                    .hasBypass(local, BukkitAdapter.adapt(block.getWorld()))) return true;
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            return placing ? query.testBuild(BukkitAdapter.adapt(block.getLocation()), local, Flags.BLOCK_PLACE)
                    : query.testBuild(BukkitAdapter.adapt(block.getLocation()), local, Flags.BLOCK_BREAK);
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("WorldGuard permission query failed at " + block.getLocation() + ": " + failure);
            return false;
        }
    }

    boolean canModifyAll(Player player, Collection<Block> blocks) {
        for (Block block : blocks) if (!canModify(player, block)) return false;
        return true;
    }

    private CoreProtectAPI api() {
        Plugin installed = plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if (installed == null || !installed.isEnabled()) return null;
        if (!(installed instanceof CoreProtect cp)) return null;
        CoreProtectAPI api = cp.getAPI();
        return api != null && api.isEnabled() && api.APIVersion() >= 10 ? api : null;
    }

    boolean ready() { return unreadyReason() == null; }

    /** Must be called after a verified successful removal, with the original snapshot. */
    void removed(Player player, BlockState original) {
        if (!installed("CoreProtect") || !optedIn("coreprotect")) return;
        CoreProtectAPI api = api();
        if (api == null || !api.logRemoval(player.getName(), original))
            plugin.getLogger().severe("CoreProtect could not record removal by " + player.getName()
                    + " at " + original.getLocation());
    }

    /** Must be called after a verified successful placement, with a new block-state snapshot. */
    void placed(Player player, Block block) {
        if (!installed("CoreProtect") || !optedIn("coreprotect")) return;
        CoreProtectAPI api = api();
        if (api == null || !api.logPlacement(player.getName(), block.getState()))
            plugin.getLogger().severe("CoreProtect could not record placement by " + player.getName()
                    + " at " + block.getLocation());
    }

    /** Record a block-data mutation as removal of old state followed by placement of new state. */
    void changed(Player player, BlockState original, Block block) {
        removed(player, original);
        placed(player, block);
    }
}
