package com.iruixos.reapwright;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Records plugin writes without changing existing YAML values or manual edits. */
public final class ConfigVersionComments {
    private static final String CREATED = "# Created by Reapwright: ";
    private static final String MODIFIED = "# Last modified by Reapwright: ";
    private static final Pattern CREATION_LINE = Pattern.compile("(?m)^" + CREATED + "([^\\r\\n]+)");
    private static final Pattern VERSION_LINES = Pattern.compile(
            "(?m)^(?:" + CREATED + "|" + MODIFIED + ")[^\\r\\n]*(?:\\r?\\n|$)");

    private ConfigVersionComments() {}

    public static void ensure(Path path, String version, Logger logger) {
        try {
            String contents = Files.readString(path);
            int firstLineEnd = contents.indexOf('\n');
            if (firstLineEnd >= 0 && contents.startsWith(CREATED) && contents.startsWith(MODIFIED, firstLineEnd + 1)) {
                return;
            }
            write(path, withVersions(contents, creationVersion(contents), version));
        } catch (IOException error) {
            logger.warning("Could not write version comments to " + path.getFileName() + ": " + error.getMessage());
        }
    }

    static void copyAsNew(Path source, Path destination, String version) throws IOException {
        write(destination, withVersions(Files.readString(source), version, version));
    }

    static void save(YamlConfiguration config, Path path, String version, boolean created) throws IOException {
        String originalVersion = created ? version : creationVersion(Files.readString(path));
        write(path, withVersions(config.saveToString(), originalVersion, version));
    }

    private static String creationVersion(String contents) {
        var line = CREATION_LINE.matcher(contents);
        return line.find() ? line.group(1).strip() : "unknown (legacy file)";
    }

    private static String withVersions(String contents, String created, String modified) {
        String newline = contents.contains("\r\n") ? "\r\n" : "\n";
        String yaml = contents.startsWith("\uFEFF") ? contents.substring(1) : contents;
        return CREATED + created + newline + MODIFIED + modified + newline
                + VERSION_LINES.matcher(yaml).replaceAll("");
    }

    private static void write(Path path, String contents) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
