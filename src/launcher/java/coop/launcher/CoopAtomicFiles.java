package coop.launcher;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file writes: write the new content to a sibling temp file, then rename it onto the
 * target. A write interrupted by a crash or power loss leaves either the old file or the fully
 * written new one, never a truncated one.
 *
 * <p>That matters most for {@code coop_options.json.data}: a truncated file sets
 * {@link CoopLauncherConfig#readError()} on the next read, and {@link CoopLauncherConfig#write} then
 * refuses to touch it - leaving the player stuck until they delete the file by hand. The other three
 * write sites in the launcher ({@code vmparams}, {@code enabled_mods.json}) get the same protection
 * for the same reason.
 */
final class CoopAtomicFiles {

    private CoopAtomicFiles() {
    }

    /**
     * Writes {@code bytes} to {@code target} by way of a temp file in the same directory, so the
     * final rename is same-volume and (on the usual filesystems) atomic.
     *
     * @throws IOException when the temp file could not be written or the rename failed; the temp
     *         file is removed (best effort) before this is thrown, and the original exception is
     *         rethrown unchanged so callers that catch a specific subtype (for example
     *         {@link java.nio.file.AccessDeniedException}) keep seeing it.
     */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(temp);
            throw ex;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort - the temp file has a random name and is never read by anything.
        }
    }
}
