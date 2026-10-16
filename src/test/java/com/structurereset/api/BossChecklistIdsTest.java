package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour of the pure Boss Checklist id list parser in {@link BossChecklistIds}. */
class BossChecklistIdsTest {

    /**
     * The head of the real server data pack file of Boss Checklist 5.2.0,
     * verbatim, tabs and newlines included.
     */
    private static final String REAL_HEAD = "[\n"
            + "\t{\n\t\t\"id\": \"minecraft:wither\"\n\t},\n"
            + "\t{\n\t\t\"id\": \"minecraft:ender_dragon\"\n\t},\n"
            + "\t{\n\t\t\"id\": \"minecraft:elder_guardian\"\n\t}\n"
            + "]";

    @Test
    void theResourceIdIsTheOneTheBridgeReads() {
        assertEquals("boss_checklist", BossChecklistIds.NAMESPACE);
        assertEquals("server_bosses_ids.json", BossChecklistIds.RESOURCE_PATH);
        assertEquals("id", BossChecklistIds.FIELD_ID);
    }

    @Test
    void theRealDataPackFileIsRead() {
        BossChecklistIds parsed = BossChecklistIds.parse(REAL_HEAD);
        assertTrue(parsed.usable());
        assertEquals(0, parsed.skipped());
        assertEquals(3, parsed.size());
        assertEquals("minecraft:wither", parsed.ids().iterator().next());
        assertTrue(parsed.contains("minecraft:wither"));
        assertTrue(parsed.contains("minecraft:ender_dragon"));
        assertTrue(parsed.contains("minecraft:elder_guardian"));
        assertFalse(parsed.contains("minecraft:cow"));
    }

    @Test
    void theCompactFormOfTheBriefIsReadAsWell() {
        BossChecklistIds parsed = BossChecklistIds.parse(
                "[{\"id\": \"minecraft:wither\"}, {\"id\": \"minecraft:ender_dragon\"}]");
        assertTrue(parsed.usable());
        assertEquals(2, parsed.size());
        assertTrue(parsed.contains("minecraft:wither"));
        assertTrue(parsed.contains("minecraft:ender_dragon"));
    }

    @Test
    void aMissingFileIsUnusableAndEmpty() {
        for (String text : new String[] {null, "", "   ", "\n\t"}) {
            BossChecklistIds parsed = BossChecklistIds.parse(text);
            assertFalse(parsed.usable(), "text=" + text);
            assertTrue(parsed.isEmpty());
            assertEquals(0, parsed.size());
            assertEquals(0, parsed.skipped());
            assertFalse(parsed.contains("minecraft:wither"));
        }
    }

    @Test
    void aMalformedFileIsUnusableAndEmpty() {
        // gson is lenient about a trailing comma, so only text it cannot parse
        // into an array at all lands here.
        for (String text : new String[] {"{not json", "[[[", "[},", "null", "[1,2", "true"}) {
            BossChecklistIds parsed = BossChecklistIds.parse(text);
            assertFalse(parsed.usable(), "text=" + text);
            assertTrue(parsed.isEmpty());
        }
    }

    @Test
    void aTopLevelObjectIsNotTheList() {
        BossChecklistIds parsed = BossChecklistIds.parse("{\"id\": \"minecraft:wither\"}");
        assertFalse(parsed.usable());
        assertTrue(parsed.isEmpty());
    }

    @Test
    void entriesWithoutAUsableIdAreCountedInsteadOfFatal() {
        BossChecklistIds parsed = BossChecklistIds.parse("["
                + "{\"id\": \"minecraft:wither\"},"
                + "{},"                                            // no id at all
                + "{\"id\": 7},"                                   // not a string
                + "{\"id\": \"\"},"                              // blank
                + "{\"id\": \"   \"},"                           // whitespace only
                + "42,"                                              // not an object
                + "null,"                                            // JSON null element
                + "{\"id\": \" mod:two \"}"                     // trimmed, kept
                + "]");
        assertTrue(parsed.usable());
        assertEquals(2, parsed.size());
        assertEquals(6, parsed.skipped());
        assertTrue(parsed.contains("minecraft:wither"));
        assertTrue(parsed.contains("mod:two"));
    }

    @Test
    void anEmptyArrayIsUsableButNamesNothing() {
        BossChecklistIds parsed = BossChecklistIds.parse("[]");
        assertTrue(parsed.usable());
        assertTrue(parsed.isEmpty());
        assertEquals(0, parsed.size());
    }

    @Test
    void duplicatedIdsCollapse() {
        BossChecklistIds parsed = BossChecklistIds.parse(
                "[{\"id\": \"minecraft:wither\"}, {\"id\": \"minecraft:wither\"}]");
        assertTrue(parsed.usable());
        assertEquals(1, parsed.size());
    }

    @Test
    void containsNeverMatchesNullOrBlank() {
        BossChecklistIds parsed = BossChecklistIds.parse(REAL_HEAD);
        assertFalse(parsed.contains(null));
        assertFalse(parsed.contains(""));
        assertFalse(parsed.contains("   "));
        assertFalse(parsed.contains("minecraft:Wither"));
    }

    @Test
    void ofBuildsAUsableListAndDropsBlanks() {
        BossChecklistIds parsed = BossChecklistIds.of(List.of("a:b", " c:d ", ""));
        assertTrue(parsed.usable());
        assertEquals(2, parsed.size());
        assertEquals(1, parsed.skipped());
        assertTrue(parsed.contains("a:b"));
        assertTrue(parsed.contains("c:d"));

        assertTrue(BossChecklistIds.of(null).usable());
        assertTrue(BossChecklistIds.of(List.of()).isEmpty());
        assertTrue(BossChecklistIds.of(List.of()).usable());
    }

    @Test
    void emptyIsTheUnusableEmptyList() {
        assertFalse(BossChecklistIds.empty().usable());
        assertTrue(BossChecklistIds.empty().isEmpty());
        assertEquals(0, BossChecklistIds.empty().size());
    }
}
