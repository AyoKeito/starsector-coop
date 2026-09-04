package coop.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoopTextTest {

    @Test
    void nullValueThrowsNpeNamingTheField() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> CoopText.requireText(null, "fieldName"));
        assertEquals("fieldName", ex.getMessage());
    }

    @Test
    void blankValueThrowsIllegalArgumentNamingTheField() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CoopText.requireText("   ", "fieldName"));
        assertEquals("fieldName is blank", ex.getMessage());
    }

    @Test
    void paddedValueIsTrimmed() {
        assertEquals("value", CoopText.requireText("  value  ", "fieldName"));
    }
}
