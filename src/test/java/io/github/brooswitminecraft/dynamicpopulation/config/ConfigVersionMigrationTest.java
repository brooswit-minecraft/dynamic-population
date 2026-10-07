package io.github.brooswitminecraft.dynamicpopulation.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigVersionMigrationTest {
    @Test
    void mismatchRenamesFileAsideWithByteIdenticalBackup(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("dynamicpopulation-server.toml");
        byte[] original = "cell_size = 64\nconfigVersion = 1\n".getBytes();
        Files.write(config, original);

        var result = ConfigVersionMigration.renameAsideIfMismatched(config, 1, 2);

        assertTrue(result.renamed());
        assertEquals(dir.resolve("dynamicpopulation-server.toml.bak-1"), result.backupPath());
        assertFalse(Files.exists(config), "original path must no longer hold the stale file");
        assertTrue(Files.exists(result.backupPath()), "a backup nobody can restore is theatre");
        assertArrayEquals(original, Files.readAllBytes(result.backupPath()));
    }

    @Test
    void unchangedConfigVersionDoesNotRename(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("dynamicpopulation-server.toml");
        byte[] original = "cell_size = 64\nconfigVersion = 1\n".getBytes();
        Files.write(config, original);

        var result = ConfigVersionMigration.renameAsideIfMismatched(config, 1, 1);

        assertFalse(result.renamed());
        assertTrue(Files.exists(config), "unchanged configVersion must never touch the existing file");
        assertArrayEquals(original, Files.readAllBytes(config));
        assertFalse(Files.exists(dir.resolve("dynamicpopulation-server.toml.bak-1")));
    }

    @Test
    void missingFileWithMismatchedVersionsIsANoOp(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("does-not-exist-yet.toml");

        var result = ConfigVersionMigration.renameAsideIfMismatched(config, 1, 2);

        assertFalse(result.renamed());
        assertFalse(Files.exists(config));
    }
}
