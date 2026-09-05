package coop.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CoopAtomicFiles} is the crash-safety net under the four launcher write sites - a truncated
 * write must never be what a crash or power loss leaves behind. These tests stay at the level of
 * "the bytes land, and nothing but the target file is left in the directory afterwards"; the actual
 * interrupted-write case is not reproducible on Windows without a fault-injecting filesystem.
 */
class CoopAtomicFilesTest {

    @Test
    void writesTheExactBytesAndLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("coop_options.json.data");
        byte[] bytes = "{\n\t\"coop.hostPort\": \"7777\"\n}\n".getBytes(StandardCharsets.UTF_8);

        CoopAtomicFiles.writeAtomically(target, bytes);

        assertArrayEquals(bytes, Files.readAllBytes(target));
        try (var listing = Files.list(dir)) {
            assertEquals(List.of(target), listing.toList());
        }
    }

    @Test
    void replacesAnExistingFilesContent(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("coop_options.json.data");
        Files.writeString(target, "{ \"old\": true }\n", StandardCharsets.UTF_8);

        byte[] bytes = "{\n\t\"coop.hostPort\": \"7777\"\n}\n".getBytes(StandardCharsets.UTF_8);
        CoopAtomicFiles.writeAtomically(target, bytes);

        assertArrayEquals(bytes, Files.readAllBytes(target));
        try (var listing = Files.list(dir)) {
            assertEquals(List.of(target), listing.toList());
        }
    }

    /**
     * Windows will not let a real filesystem fault be injected mid-write in a unit test, so this
     * exercises the failure path a different way: the rename step fails because the target is an
     * existing, non-empty directory rather than a file. Either way the contract under test is the
     * same - a failed write throws {@link IOException} and does not leave a stray temp file lying
     * around next to the target.
     */
    @Test
    void aFailedWriteLeavesNoStrayTempFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("coop_options.json.data");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep-directory-non-empty.txt"), "x");

        assertThrows(IOException.class,
                () -> CoopAtomicFiles.writeAtomically(target, "irrelevant".getBytes(StandardCharsets.UTF_8)));

        try (var listing = Files.list(dir)) {
            assertEquals(List.of(target), listing.toList());
        }
    }
}
