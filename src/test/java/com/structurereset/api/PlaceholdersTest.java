package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Behaviour of the {@code ${key}} substitution in {@link Placeholders}. */
class PlaceholdersTest {

    @Test
    void replacesSeveralPlaceholdersInOneTemplate() {
        Map<String, String> vars = Map.of("player", "Steve", "structure", "minecraft:village");
        assertEquals("Steve entered minecraft:village",
                Placeholders.resolve("${player} entered ${structure}", vars));
    }

    @Test
    void adjacentPlaceholdersAreAllReplaced() {
        Map<String, String> vars = Map.of("a", "1", "b", "2");
        assertEquals("12", Placeholders.resolve("${a}${b}", vars));
        assertEquals("1-2", Placeholders.resolve("${a}-${b}", vars));
    }

    @Test
    void keepsUnknownPlaceholdersVerbatim() {
        Map<String, String> vars = Map.of("player", "Steve");
        assertEquals("${nope}", Placeholders.resolve("${nope}", vars));
        assertEquals("Steve-${nope}", Placeholders.resolve("${player}-${nope}", vars));
    }

    @Test
    void aNullTemplateResolvesToAnEmptyString() {
        assertEquals("", Placeholders.resolve(null, Map.of("player", "Steve")));
        assertEquals("", Placeholders.resolve(null, null));
        assertEquals("", Placeholders.resolve("", Map.of("player", "Steve")));
        assertEquals("", Placeholders.resolve("", null));
    }

    @Test
    void aNullOrEmptyVariableMapReturnsTheTemplateUnchanged() {
        assertEquals("${player}", Placeholders.resolve("${player}", null));
        assertEquals("${player}", Placeholders.resolve("${player}", Map.of()));
    }

    @Test
    void textWithoutAnyPlaceholderIsReturnedUnchanged() {
        assertEquals("plain text", Placeholders.resolve("plain text", Map.of("player", "Steve")));
    }

    @Test
    void aPlaceholderWithoutAClosingBraceIsPreserved() {
        assertEquals("before ${player",
                Placeholders.resolve("before ${player", Map.of("player", "Steve")));
    }

    @Test
    void theSamePlaceholderCanAppearSeveralTimes() {
        assertEquals("Steve/Steve",
                Placeholders.resolve("${player}/${player}", Map.of("player", "Steve")));
    }

    @Test
    void anEmptyVariableValueReplacesThePlaceholderWithNothing() {
        assertEquals("[]", Placeholders.resolve("[${player}]", Map.of("player", "")));
    }

    @Test
    void aPlaceholderWithAnEmptyKeyIsLeftAloneUnlessTheMapDefinesIt() {
        assertEquals("${}", Placeholders.resolve("${}", Map.of("player", "Steve")));
    }
}
