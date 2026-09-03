package coop.util;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

public final class CoopLog {
    private CoopLog() {
    }

    public static Logger getLogger(Class<?> source) {
        try {
            return Global.getLogger(source);
        } catch (RuntimeException | LinkageError ex) {
            return Logger.getLogger(source);
        }
    }

    public static void debug(Class<?> source, String message) {
        getLogger(source).debug(message);
    }

    public static void info(Class<?> source, String message) {
        getLogger(source).info(message);
    }

    public static void warn(Class<?> source, String message) {
        getLogger(source).warn(message);
    }

    public static void warn(Class<?> source, String message, Throwable throwable) {
        getLogger(source).warn(message, throwable);
    }

    public static void error(Class<?> source, String message) {
        getLogger(source).error(message);
    }

    public static void error(Class<?> source, String message, Throwable throwable) {
        getLogger(source).error(message, throwable);
    }
}
