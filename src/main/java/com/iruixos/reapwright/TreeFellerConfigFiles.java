package com.iruixos.reapwright;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/** Moves the legacy Tree Feller section and fills missing settings in the module file. */
final class TreeFellerConfigFiles {
    static final String FILE_NAME = "config-treefeller.yml";
    private static final String SECTION = "tree-feller";
    private static final String PREVIOUS_FILE = "reapwright-module-treefeller.yml";
    private static final String BACKUP_FILE = "config.yml.before-treefeller-migration.bak";
    private static final String LEGACY_DIAMOND = "tree-feller.bonus-loot.eligible-tools.diamond-fortune-3";
    private static final String DIAMOND = "tree-feller.bonus-loot.eligible-tools.diamond";

    private TreeFellerConfigFiles() {}

    static YamlConfiguration load(Path directory, YamlConfiguration defaults, String version, Logger logger)
            throws IOException, InvalidConfigurationException {
        Path mainFile = directory.resolve("config.yml");
        Path moduleFile = directory.resolve(FILE_NAME);
        Path previousFile = directory.resolve(PREVIOUS_FILE);
        YamlConfiguration main = read(mainFile);
        boolean migrate = main.isSet(SECTION);
        if (migrate) {
            checkTreeFellerSection(main, mainFile);
            migrateDiamondOption(main);
        }

        boolean createModule = Files.notExists(moduleFile);
        boolean copyPrevious = createModule && !Files.notExists(previousFile);
        Path source = copyPrevious ? previousFile : moduleFile;
        YamlConfiguration module = read(source);
        checkTreeFellerSection(module, source);

        boolean changed = migrateDiamondOption(module);
        if (migrate) changed |= mergeMissing(module, main, SECTION);
        changed |= mergeMissing(module, defaults, SECTION);

        Path backup = migrate ? backUpMainConfig(mainFile) : null;
        if (createModule || changed) {
            ConfigVersionComments.save(module, moduleFile, version, createModule);
            if (copyPrevious) logger.info("Upgraded " + PREVIOUS_FILE + " to " + FILE_NAME + ".");
            else if (!migrate && !createModule) logger.info("Added missing Tree Feller settings to " + FILE_NAME + ".");
        } else {
            ConfigVersionComments.ensure(moduleFile, version, logger);
        }

        if (migrate) {
            main.set(SECTION, null);
            // The destination is already on disk. A failed cleanup can safely be retried.
            ConfigVersionComments.save(main, mainFile, version, false);
            logger.info("Moved Tree Feller settings from config.yml to " + FILE_NAME
                    + ". Original config backed up as " + backup.getFileName() + ".");
        } else if (Files.isRegularFile(mainFile)) {
            ConfigVersionComments.ensure(mainFile, version, logger);
        }
        return module;
    }

    private static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        if (!Files.notExists(path)) config.load(path.toFile());
        return config;
    }

    private static void checkTreeFellerSection(YamlConfiguration config, Path file) throws IOException {
        if (config.isSet(SECTION) && !config.isConfigurationSection(SECTION)) {
            throw new IOException(file.getFileName() + " must contain a YAML mapping at " + SECTION);
        }
    }

    private static boolean migrateDiamondOption(YamlConfiguration config) {
        if (config.isSet(DIAMOND) || !config.isBoolean(LEGACY_DIAMOND)) return false;
        config.set(DIAMOND, config.getBoolean(LEGACY_DIAMOND));
        return true;
    }

    private static boolean mergeMissing(YamlConfiguration target, YamlConfiguration source, String path) {
        if (!source.isSet(path)) return false;
        ConfigurationSection from = source.getConfigurationSection(path);
        if (!target.isSet(path)) {
            if (from == null) {
                target.set(path, source.get(path));
            } else {
                target.createSection(path);
                for (String key : from.getKeys(false)) mergeMissing(target, source, path + "." + key);
            }
            if (!source.getComments(path).isEmpty()) target.setComments(path, source.getComments(path));
            if (!source.getInlineComments(path).isEmpty()) target.setInlineComments(path, source.getInlineComments(path));
            return true;
        }
        // These maps are complete user choices. Adding omitted entries would change loot.
        if (from == null || !target.isConfigurationSection(path)
                || path.equals("tree-feller.bonus-loot.trees") || path.equals("tree-feller.sapling-drops.rates")) {
            return false;
        }

        boolean changed = false;
        for (String key : from.getKeys(false)) {
            changed |= mergeMissing(target, source, path + "." + key);
        }
        return changed;
    }

    private static Path backUpMainConfig(Path main) throws IOException {
        Path backup = main.resolveSibling(BACKUP_FILE);
        if (!Files.notExists(backup)) {
            if (Files.isRegularFile(backup) && Files.mismatch(main, backup) == -1) return backup;
            backup = Files.createTempFile(main.getParent(), "config.yml.before-treefeller-migration-", ".bak");
        }
        Files.copy(main, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }
}
