package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour of {@link DefinitionSelector}. */
class DefinitionSelectorTest {

    private static ResetDefinition definition(String id, String structure,
                                              ResetTrigger trigger, int priority) {
        return ResetDefinition.builder(id)
                .addStructure(structure)
                .trigger(trigger)
                .priority(priority)
                .addAction("structurereset:refill_loot")
                .build();
    }

    @Test
    void returnsEmptyForNoDefinitionsAtAll() {
        assertTrue(DefinitionSelector.select(List.of(), "minecraft:village", List.of(), false).isEmpty());
        assertTrue(DefinitionSelector.select(null, "minecraft:village", List.of(), false).isEmpty());
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        ResetDefinition only = definition("structurereset:only",
                "minecraft:desert_pyramid", ResetTrigger.EITHER, 0);
        assertTrue(DefinitionSelector.select(List.of(only), "minecraft:village",
                List.of("minecraft:village"), false).isEmpty());
        assertTrue(DefinitionSelector.select(List.of(only), null, null, false).isEmpty());
    }

    @Test
    void anExactStructureIdBeatsATagMatch() {
        ResetDefinition tagged = definition("structurereset:tagged",
                "#minecraft:village", ResetTrigger.IN_STRUCTURE, 0);
        ResetDefinition exact = definition("structurereset:exact",
                "minecraft:village", ResetTrigger.IN_STRUCTURE, 0);
        assertSame(exact, DefinitionSelector.select(List.of(tagged, exact),
                "minecraft:village", List.of("minecraft:village"), false).orElseThrow());
    }

    @Test
    void aHigherPriorityWinsBetweenTwoExactMatches() {
        ResetDefinition low = definition("structurereset:low",
                "minecraft:village", ResetTrigger.EITHER, 3);
        ResetDefinition high = definition("structurereset:high",
                "minecraft:village", ResetTrigger.EITHER, 9);
        assertSame(high, DefinitionSelector.select(List.of(low, high),
                "minecraft:village", List.of(), false).orElseThrow());
    }

    @Test
    void theAlphabeticallyFirstIdBreaksARankAndPriorityTie() {
        ResetDefinition zzz = definition("structurereset:zzz",
                "minecraft:village", ResetTrigger.EITHER, 1);
        ResetDefinition aaa = definition("structurereset:aaa",
                "minecraft:village", ResetTrigger.EITHER, 1);
        assertSame(aaa, DefinitionSelector.select(List.of(zzz, aaa),
                "minecraft:village", List.of(), false).orElseThrow());
    }

    @Test
    void aStructureMatchOutranksAPureAnchorMatchEvenWithLowerPriority() {
        ResetDefinition structureMatch = definition("structurereset:structure",
                "minecraft:village", ResetTrigger.EITHER, 0);
        ResetDefinition anchorOnly = definition("structurereset:anchor",
                "minecraft:other", ResetTrigger.EITHER, 100);
        assertSame(structureMatch, DefinitionSelector.select(List.of(anchorOnly, structureMatch),
                "minecraft:village", List.of(), true).orElseThrow());
    }

    @Test
    void inStructureTriggerIsNotEligibleWithoutAStructureMatch() {
        ResetDefinition inStructure = definition("structurereset:in_structure",
                "minecraft:village", ResetTrigger.IN_STRUCTURE, 0);
        assertTrue(DefinitionSelector.select(List.of(inStructure), null, List.of(), true).isEmpty());
        assertTrue(DefinitionSelector.select(List.of(inStructure), "minecraft:other",
                List.of(), true).isEmpty());
        assertSame(inStructure, DefinitionSelector.select(List.of(inStructure),
                "minecraft:village", List.of(), false).orElseThrow());
    }

    @Test
    void anchorBlockTriggerIsNotEligibleWithoutTheAnchorBlock() {
        ResetDefinition anchor = definition("structurereset:anchor",
                "minecraft:village", ResetTrigger.ANCHOR_BLOCK, 0);
        assertTrue(DefinitionSelector.select(List.of(anchor), "minecraft:village",
                List.of(), false).isEmpty());
        assertSame(anchor, DefinitionSelector.select(List.of(anchor), "minecraft:village",
                List.of(), true).orElseThrow());
    }

    @Test
    void eitherTriggerFiresOnAStructureOrOnTheAnchor() {
        ResetDefinition either = definition("structurereset:either",
                "minecraft:village", ResetTrigger.EITHER, 0);
        assertSame(either, DefinitionSelector.select(List.of(either), "minecraft:village",
                List.of(), false).orElseThrow());
        assertSame(either, DefinitionSelector.select(List.of(either), null,
                List.of(), true).orElseThrow());
        assertTrue(DefinitionSelector.select(List.of(either), "minecraft:other",
                List.of("minecraft:other"), false).isEmpty());
    }

    @Test
    void aTagMatchWorksWithAndWithoutTheHashPrefix() {
        ResetDefinition tagged = definition("structurereset:tagged",
                "#minecraft:village", ResetTrigger.IN_STRUCTURE, 0);
        assertSame(tagged, DefinitionSelector.select(List.of(tagged), "minecraft:other",
                List.of("#minecraft:village"), false).orElseThrow());
        assertSame(tagged, DefinitionSelector.select(List.of(tagged), "minecraft:other",
                List.of("minecraft:village"), false).orElseThrow());
        assertTrue(DefinitionSelector.select(List.of(tagged), "minecraft:other",
                List.of("#minecraft:other"), false).isEmpty());
    }

    @Test
    void aTagReferenceNeverMatchesAnExactStructureId() {
        ResetDefinition tagged = definition("structurereset:tagged",
                "#minecraft:village", ResetTrigger.IN_STRUCTURE, 0);
        assertTrue(DefinitionSelector.select(List.of(tagged), "minecraft:village",
                List.of(), false).isEmpty());
    }

    @Test
    void nullEntriesInTheDefinitionCollectionAreIgnored() {
        ResetDefinition only = definition("structurereset:only",
                "minecraft:village", ResetTrigger.EITHER, 0);
        List<ResetDefinition> withNull = Arrays.asList(null, only);
        assertSame(only, DefinitionSelector.select(withNull, "minecraft:village",
                List.of(), false).orElseThrow());
    }
}
