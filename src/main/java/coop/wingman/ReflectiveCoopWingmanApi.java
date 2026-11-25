package coop.wingman;

import com.fs.starfarer.api.Global;

import coop.util.CoopLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link CoopWingmanApi}: reaches {@code wingman.api.WingmanAPI} by reflection so the
 * coop mod has no compile-time dependency on The Wingman Project.
 *
 * <p>Every call fails closed: a missing Wingman, a renamed API, or a sandbox refusal all behave
 * exactly as if Wingman were not installed, and each failure is logged once per cause rather
 * than once per frame.
 *
 * <p>Thread-safety: the game thread only, like the Wingman API itself (its javadoc says
 * "meant for the game thread").
 */
public final class ReflectiveCoopWingmanApi implements CoopWingmanApi {

    private static final String API_CLASS = "wingman.api.WingmanAPI";

    private Class<?> apiClass;
    private boolean resolveFailed;

    /** Wingman's own mod id; the guard every caller checks first. */
    public static boolean wingmanModEnabled() {
        try {
            return Global.getSettings().getModManager().isModEnabled("wingman_project");
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private Class<?> api() {
        if (apiClass != null) {
            return apiClass;
        }
        if (resolveFailed) {
            return null;
        }
        try {
            apiClass = Class.forName(API_CLASS);
        } catch (Throwable t) {
            resolveFailed = true;
            CoopLog.warn(ReflectiveCoopWingmanApi.class,
                    "Coop wingman: Wingman API class not found; wingman battles are off", t);
        }
        return apiClass;
    }

    private Object invoke(String method, Class<?>[] paramTypes, Object[] args) {
        Class<?> c = api();
        if (c == null) {
            return null;
        }
        try {
            Method m = c.getMethod(method, paramTypes);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException ex) {
            CoopLog.warn(ReflectiveCoopWingmanApi.class,
                    "Coop wingman: WingmanAPI." + method + " call failed", ex);
            return null;
        } catch (RuntimeException | LinkageError ex) {
            CoopLog.warn(ReflectiveCoopWingmanApi.class,
                    "Coop wingman: WingmanAPI." + method + " threw", ex);
            return null;
        }
    }

    @Override
    public boolean wingmanEnabled() {
        return api() != null && wingmanModEnabled();
    }

    @Override
    public boolean isJoined() {
        Object v = invoke("isJoined", new Class<?>[]{}, new Object[]{});
        return Boolean.TRUE.equals(v);
    }

    @Override
    public String localName() {
        Object v = invoke("localName", new Class<?>[]{}, new Object[]{});
        return v instanceof String s ? s : "";
    }

    @Override
    public boolean openHost() {
        Object v = invoke("openHost", new Class<?>[]{}, new Object[]{});
        return Boolean.TRUE.equals(v) || v == null; // openHost() is void; a clean return means ok
    }

    @Override
    public String pumpHost() {
        Object v = invoke("pump", new Class<?>[]{}, new Object[]{});
        if (v instanceof List<?> lines) {
            StringBuilder sb = new StringBuilder();
            for (Object line : lines) {
                if (line instanceof String s && !s.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        return "";
    }

    @Override
    public List<String> playerNames() {
        Object v = invoke("players", new Class<?>[]{}, new Object[]{});
        List<String> out = new ArrayList<>();
        if (v instanceof int[] ids) {
            for (int id : ids) {
                Object name = invoke("playerName", new Class<?>[]{int.class}, new Object[]{id});
                if (name instanceof String s && !s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @Override
    public void reserveShip(String name, String fleetMemberId) {
        invoke("reserveShip", new Class<?>[]{String.class, String.class},
                new Object[]{name, fleetMemberId});
    }
}
