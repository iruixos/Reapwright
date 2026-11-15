package com.iruixos.reapwright;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates an existing module file or copies the newest legacy file into place. */
public final class ModuleConfigFiles {
    private ModuleConfigFiles() {}

    public static boolean useExisting(JavaPlugin plugin, File file, String... legacyNames) throws IOException {
        Path target = file.toPath();
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target)) throw new IOException("Expected a file at " + target);
            return true;
        }
        for (String name : legacyNames) {
            Path previous = target.resolveSibling(name);
            if (Files.notExists(previous)) continue;
            if (!Files.isRegularFile(previous)) throw new IOException("Expected a file at " + previous);
            ConfigVersionComments.copyAsNew(previous, target, plugin.getPluginMeta().getVersion());
            plugin.getLogger().info("Copied " + name + " to " + file.getName() + ". The original was kept as a backup.");
            return true;
        }
        return false;
    }
}
