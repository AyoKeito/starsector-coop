package coop.net;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escaper and the parser on their own, away from any particular message. {@link
 * CoopMessagesGoldenTest} locks what the wire looks like; this locks what the codec does.
 */
class CoopJsonTest {

    // ---- escaper ----------------------------------------------------------------------------------

    @Test
    void escapesEveryNamedBranch() {
        assertEquals("\\\"", CoopJson.escape("\""));
        assertEquals("\\\\", CoopJson.escape("\\"));
        assertEquals("\\b", CoopJson.escape("\b"));
        assertEquals("\\f", CoopJson.escape("\f"));
        assertEquals("\\n", CoopJson.escape("\n"));
        assertEquals("\\r", CoopJson.escape("\r"));
        assertEquals("\\t", CoopJson.escape("\t"));
    }

    @Test
    void escapesOtherControlCharactersAsLowercaseFourDigitHex() {
        assertEquals("\\u0000", CoopJson.escape("\u0000"));
        assertEquals("\\u0001", CoopJson.escape("\u0001"));
        assertEquals("\\u001f", CoopJson.escape("\u001f"));
        // The record separator the datagram format uses, which is why this branch is load-bearing.
        assertEquals("a\\u001fb", CoopJson.escape("a\u001fb"));
    }

    @Test
    void leavesEverythingFromSpaceUpwardAlone() {
        assertEquals(" ", CoopJson.escape(" "));
        assertEquals("~", CoopJson.escape("~"));
        assertEquals("/", CoopJson.escape("/"), "solidus is legal unescaped and stays that way");
        assertEquals("\u007f", CoopJson.escape("\u007f"), "DEL is not a C0 control");
        assertEquals("\u00e9\u661f", CoopJson.escape("\u00e9\u661f"), "non-ASCII rides as UTF-8");
    }

    @Test
    void escapesNothingInAPlainStringAndHandlesEmpty() {
        assertEquals("", CoopJson.escape(""));
        assertEquals("jangala_market", CoopJson.escape("jangala_market"));
    }

    // ---- parser: accepted -------------------------------------------------------------------------

    @Test
    void parseObjectReadsAFlatObjectInWireOrder() {
        Map<String, Object> fields =
                CoopJson.parseObject("{\"b\":1,\"a\":\"x\",\"c\":null,\"d\":-9223372036854775808}");

        assertEquals(List.of("b", "a", "c", "d"), List.copyOf(fields.keySet()));
        assertEquals(1L, fields.get("b"));
        assertEquals("x", fields.get("a"));
        assertNull(fields.get("c"));
        assertEquals(Long.MIN_VALUE, fields.get("d"));
    }

    @Test
    void numbersDecodeAsLongNotInteger() {
        assertInstanceOf(Long.class, CoopJson.parseObject("{\"a\":1}").get("a"));
        assertEquals(Long.MAX_VALUE, CoopJson.parseObject("{\"a\":9223372036854775807}").get("a"));
        assertEquals(0L, CoopJson.parseObject("{\"a\":-0}").get("a"));
    }

    @Test
    void everyStringEscapeIsUnescaped() {
        Map<String, Object> fields = CoopJson.parseObject(
                "{\"a\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\\u00e9\"}");
        assertEquals("\"\\/\b\f\n\r\tA\u00e9", fields.get("a"));
    }

    @Test
    void parseAcceptsAnyTopLevelValue() {
        assertEquals("x", CoopJson.parse("\"x\""));
        assertEquals(7L, CoopJson.parse("7"));
        assertNull(CoopJson.parse("null"));
        assertEquals(List.of(), CoopJson.parse("[]"));
        assertEquals(Map.of(), CoopJson.parse("{}"));
    }

    @Test
    void parseHandlesNestedObjectsAndArrays() {
        Object parsed = CoopJson.parse(
                "{\"mods\":[{\"id\":\"coop\",\"jars\":[\"a.jar\",\"b.jar\"]}],\"n\":3}");

        Map<?, ?> root = assertInstanceOf(Map.class, parsed);
        List<?> mods = assertInstanceOf(List.class, root.get("mods"));
        Map<?, ?> entry = assertInstanceOf(Map.class, mods.get(0));
        assertEquals("coop", entry.get("id"));
        assertEquals(List.of("a.jar", "b.jar"), entry.get("jars"));
        assertEquals(3L, root.get("n"));
    }

    @Test
    void whitespaceIsSkippedAroundEveryToken() {
        Map<String, Object> fields =
                CoopJson.parseObject("\n\t {\n \"a\" \t: \n [ 1 , 2 ] \n, \"b\" : { } \n} \n");
        assertEquals(List.of(1L, 2L), fields.get("a"));
        assertEquals(Map.of(), fields.get("b"));
    }

    @Test
    void duplicateKeysKeepTheLastValue() {
        Map<String, Object> fields = CoopJson.parseObject("{\"a\":1,\"b\":2,\"a\":3}");
        assertEquals(2, fields.size());
        assertEquals(3L, fields.get("a"));
    }

    // ---- parser: rejected -------------------------------------------------------------------------

    private static IllegalArgumentException rejectsObject(String json) {
        return assertThrows(IllegalArgumentException.class, () -> CoopJson.parseObject(json),
                "should reject: " + json);
    }

    @Test
    void parseObjectRefusesANonObjectTopLevel() {
        rejectsObject("[1]");
        rejectsObject("\"x\"");
        rejectsObject("1");
        rejectsObject("null");
        rejectsObject("");
    }

    @Test
    void trailingContentIsRejectedEvenAfterAnEmptyObject() {
        // The flat wire parser used to let "{}}" through: it returned early on the empty object and
        // never reached its own trailing check. The manifest parser rejected it. Reconciled strict.
        assertEquals("Trailing content at index 2", rejectsObject("{}}").getMessage());
        assertEquals("Trailing content at index 8", rejectsObject("{\"a\":1} x").getMessage());
        assertThrows(IllegalArgumentException.class, () -> CoopJson.parse("[] []"));
        assertThrows(IllegalArgumentException.class, () -> CoopJson.parse("null null"));
    }

    @Test
    void truncationIsRejectedAtEveryPoint() {
        rejectsObject("{");
        rejectsObject("{\"a");
        rejectsObject("{\"a\"");
        rejectsObject("{\"a\":");
        rejectsObject("{\"a\":1");
        rejectsObject("{\"a\":1,");
        rejectsObject("{\"a\":[1");
        rejectsObject("{\"a\":{");
    }

    @Test
    void badEscapesAreRejectedRatherThanPassedThrough() {
        assertEquals("Unsupported escape sequence: \\q at index 8",
                rejectsObject("{\"a\":\"\\q\"}").getMessage());
        assertEquals("Incomplete unicode escape at index 8",
                rejectsObject("{\"a\":\"\\u12").getMessage());
        assertEquals("Invalid unicode escape: 12\"} at index 12",
                rejectsObject("{\"a\":\"\\u12\"}").getMessage(),
                "four characters are available, so it is a hex failure and not a truncation");
        assertEquals("Invalid unicode escape: zzzz at index 12",
                rejectsObject("{\"a\":\"\\uzzzz\"}").getMessage());
        assertEquals("Unterminated escape sequence at index 8",
                rejectsObject("{\"a\":\"x\\").getMessage());
    }

    @Test
    void unterminatedStringIsRejected() {
        assertEquals("Unterminated string at index 8", rejectsObject("{\"a\":\"x}").getMessage());
    }

    @Test
    void booleansAreNotPartOfTheGrammar() {
        // Neither shipped parser accepted them; booleans travel as the quoted strings "true"/"false"
        // (linkStatus's udpInboundOk, timeSnapshot's paused), and widening this would let a peer
        // send a shape the accessors would then silently treat as a missing field.
        rejectsObject("{\"a\":true}");
        rejectsObject("{\"a\":false}");
        assertThrows(IllegalArgumentException.class, () -> CoopJson.parse("true"));
    }

    @Test
    void fractionalAndExponentNumbersAreNotPartOfTheGrammar() {
        // Floats travel as quoted strings so that no formatting difference can reach the wire.
        rejectsObject("{\"a\":1.5}");
        rejectsObject("{\"a\":1e3}");
        rejectsObject("{\"a\":+1}");
        assertEquals("Expected number at index 6", rejectsObject("{\"a\":-}").getMessage());
        assertEquals("Expected number at index 5", rejectsObject("{\"a\":x}").getMessage());
    }

    @Test
    void anOutOfRangeIntegerIsRejectedRatherThanSilentlyWrapping() {
        assertEquals("Invalid long value at index 25",
                rejectsObject("{\"a\":99999999999999999999}").getMessage());
    }

    @Test
    void unquotedKeysAreRejected() {
        assertEquals("Expected '\"' at index 1", rejectsObject("{a:1}").getMessage());
        assertEquals("Expected ':' at index 5", rejectsObject("{\"a\" 1}").getMessage());
    }

    @Test
    void nestingDeeperThanTheCapIsRejectedRatherThanOverflowingTheStack() {
        assertTrue(CoopJson.parse("[".repeat(60) + "]".repeat(60)) instanceof List);
        assertThrows(IllegalArgumentException.class,
                () -> CoopJson.parse("[".repeat(500) + "]".repeat(500)));
        assertThrows(IllegalArgumentException.class,
                () -> CoopJson.parseObject("{\"a\":".repeat(500) + "1" + "}".repeat(500)));
    }
}
