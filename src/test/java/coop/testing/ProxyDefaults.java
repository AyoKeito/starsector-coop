package coop.testing;

/**
 * The zero-value a JDK dynamic proxy has to return for a method the test does not stub.
 *
 * <p>The game API interfaces are wide, so the tests proxy them and answer a handful of methods by
 * name; everything else falls through to here. Returning {@code null} for a primitive return type
 * throws {@code NullPointerException} out of the proxy, which reads as a bug in the code under test
 * rather than a gap in the stub, so this exists in every such test - eight of them character for
 * character, the rest as narrower versions of the same switch.
 *
 * <p>The narrower copies were not equivalent: two of them ended in a bare {@code return 0}, which
 * hands an {@code Integer} to a method declared {@code byte}, {@code short}, {@code char} or
 * {@code double} and blows up with a {@code ClassCastException} inside the proxy. They passed only
 * because no such method was ever called on them. This version covers every primitive with its own
 * type, which is a superset of what each copy did on the paths it actually took.
 */
public final class ProxyDefaults {

    private ProxyDefaults() {
    }

    /** The default value for {@code type}: its zero if primitive, {@code null} otherwise. */
    public static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
