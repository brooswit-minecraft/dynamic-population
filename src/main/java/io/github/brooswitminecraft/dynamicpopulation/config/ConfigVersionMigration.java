package io.github.brooswitminecraft.dynamicpopulation.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Pure filesystem rename-aside for a {@code configVersion} mismatch. Operates
 * on plain {@link Path} only -- no FML/nightconfig/Minecraft classes -- so
 * it is directly testable with a JUnit {@code @TempDir}. Never deletes: the
 * previous file is always kept, renamed, never overwritten in place.
 */
public final class ConfigVersionMigration {
    private ConfigVersionMigration() { }

    public record Result(boolean renamed, Path backupPath) {
        static Result notRenamed() {
            return new Result(false, null);
        }
    }

    /**
     * If {@code storedVersion != currentVersion} and {@code configFile}
     * exists, renames it aside to {@code "<name>.toml.bak-<storedVersion>"}
     * (a filesystem move, so the backup is byte-identical to whatever
     * existed at {@code configFile} before this call) and returns the
     * backup path. Does nothing -- and touches nothing on disk -- when the
     * versions already match, or when the file does not exist yet (a first
     * run has nothing to preserve).
     */
    public static Result renameAsideIfMismatched(Path configFile, int storedVersion, int currentVersion) throws IOException {
        if (storedVersion == currentVersion || Files.notExists(configFile)) {
            return Result.notRenamed();
        }
        Path backup = configFile.resolveSibling(configFile.getFileName().toString() + ".bak-" + storedVersion);
        Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
        return new Result(true, backup);
    }
}
