package coop.launcher;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Starts {@code starsector.exe} the way a double-click would: the install root as working directory,
 * no inherited streams, output discarded.
 *
 * <p>The launcher deliberately does not own the game's lifetime. Closing the launcher window never
 * kills the game, and the window stays open after Launch only to keep tailing the log.
 */
public final class CoopGameProcess {

    private CoopGameProcess() {
    }

    /**
     * @param layout the install to start
     * @return the started process, whose only use is its pid and exit code
     * @throws IOException when the executable is missing or the OS refuses to start it
     */
    public static Process launch(CoopInstallLayout layout) throws IOException {
        Objects.requireNonNull(layout, "layout");
        File exe = layout.starsectorExe();
        if (!exe.isFile()) {
            throw new IOException("starsector.exe not found at " + exe);
        }
        ProcessBuilder builder = new ProcessBuilder(exe.getAbsolutePath());
        builder.directory(layout.installRoot());
        // The game writes its own log; anything it puts on stdout would otherwise fill a pipe nobody
        // reads and eventually block it.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
