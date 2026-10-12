package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Behaviour of {@link StructureRef} parsing and its record contract. */
class StructureRefTest {

    @Test
    void parsesAPlainNamespacePathReference() {
        StructureRef reference = StructureRef.parse("minecraft:village_plains");
        assertEquals("minecraft:village_plains", reference.id());
        assertFalse(reference.tag());
        assertEquals("minecraft:village_plains", reference.raw());
    }

    @Test
    void parsesATagReferenceAndStripsTheLeadingHashFromTheId() {
        StructureRef reference = StructureRef.parse("#minecraft:village");
        assertEquals("minecraft:village", reference.id());
        assertTrue(reference.tag());
        assertEquals("#minecraft:village", reference.raw());
    }

    @Test
    void trimsSurroundingWhitespaceBeforeParsing() {
        StructureRef reference = StructureRef.parse("   #minecraft:village  ");
        assertEquals("minecraft:village", reference.id());
        assertTrue(reference.tag());
        assertEquals("#minecraft:village", reference.raw());
    }

    @Test
    void rejectsNullWithItsOwnMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse(null));
        assertEquals("structure reference must not be null", failure.getMessage());
    }

    @Test
    void rejectsABlankReference() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse("   "));
        assertEquals("structure reference must not be blank", failure.getMessage());
    }

    @Test
    void rejectsAReferenceWithoutAColon() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse("minecraft"));
        assertEquals("structure id must be exactly 'namespace:path': minecraft",
                failure.getMessage());
    }

    @Test
    void rejectsAReferenceWithMoreThanOneColon() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse("minecraft:village:extra"));
        assertEquals("structure id must be exactly 'namespace:path': minecraft:village:extra",
                failure.getMessage());
    }

    @Test
    void rejectsAnEmptyNamespace() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse(":village"));
        assertEquals("structure id must have a non-empty namespace and path: :village",
                failure.getMessage());
    }

    @Test
    void rejectsAnEmptyPath() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse("minecraft:"));
        assertEquals("structure id must have a non-empty namespace and path: minecraft:",
                failure.getMessage());
    }

    @Test
    void rejectsWhitespaceInsideTheId() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StructureRef.parse("mine craft:village"));
        assertEquals("structure id must not contain whitespace: mine craft:village",
                failure.getMessage());
    }

    @Test
    void rejectsATagMarkerInsideADirectlyBuiltId() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new StructureRef("#minecraft:village", false));
        assertEquals("structure id must not contain '#': #minecraft:village",
                failure.getMessage());
    }

    @Test
    void matchesIdOnlyForNonTagReferences() {
        StructureRef plain = StructureRef.parse("minecraft:village");
        StructureRef tag = StructureRef.parse("#minecraft:village");
        assertTrue(plain.matchesId("minecraft:village"));
        assertFalse(plain.matchesId("minecraft:other"));
        assertFalse(tag.matchesId("minecraft:village"));
    }

    @Test
    void equalsAndHashCodeFollowTheRecordContract() {
        StructureRef first = StructureRef.parse("minecraft:village");
        StructureRef second = new StructureRef("minecraft:village", false);
        StructureRef other = StructureRef.parse("#minecraft:village");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
    }
}
