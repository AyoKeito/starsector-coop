package coop.launcher;

import java.io.File;
import java.io.IOException;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

/**
 * The launcher's own log4j setup, wired programmatically because the launcher runs in its own JVM
 * with no Starsector configuration anywhere near it.
 *
 * <p>One file, {@code <mod>/coop-launcher.log}, overwritten every run: a bug report wants the run
 * that just went wrong, not a year of them. Every button press, every check verdict and every
 * exception goes in.
 */
public final class CoopLauncherLogging {

    /** Layout: {@code 12:04:31.220 INFO  coop.launcher.X - message}. */
    private static final String PATTERN = "%d{HH:mm:ss.SSS} %-5p %c{1} - %m%n";

    private static volatile File logFile;

    private CoopLauncherLogging() {
    }

    /**
     * Points log4j at {@code file}. Falls back to the console when the file cannot be opened - a
     * read-only mod folder must not stop the launcher from starting.
     *
     * @return the file actually being written, or {@code null} when only the console is in use
     */
    public static File configure(File file) {
        File current = logFile;
        if (current != null && file != null && current.equals(file)) {
            // Already writing this file. Reconfiguring would reopen it in overwrite mode and throw
            // away the lines written before the caller worked out which install it was looking at.
            return current;
        }
        Logger root = Logger.getRootLogger();
        root.removeAllAppenders();
        root.setLevel(Level.INFO);
        root.addAppender(new ConsoleAppender(new PatternLayout(PATTERN)));
        if (file == null) {
            logFile = null;
            return null;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            root.addAppender(new FileAppender(new PatternLayout(PATTERN), file.getAbsolutePath(), false));
            logFile = file;
            return file;
        } catch (IOException ex) {
            root.warn("Could not open the launcher log at " + file + "; logging to the console only",
                    ex);
            logFile = null;
            return null;
        }
    }

    /** The file being written, or {@code null}. */
    public static File logFile() {
        return logFile;
    }

    public static Logger logger(Class<?> source) {
        return Logger.getLogger(source);
    }
}
