package coop.util;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

public final class CoopLog {
    private CoopLog() {
    }

    public static Logger getLogger(Class<?> source) {
        return Global.getLogger(source);
    }

    public static void info(Class<?> source, String message) {
        getLogger(source).info(message);
    }
}

