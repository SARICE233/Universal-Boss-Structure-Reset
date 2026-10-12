package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour of {@link DefinitionParser} fed with real gson JsonObjects. */
class DefinitionParserTest {

    private static final String MINIMAL = "{"
            + "\"structure\": \"minecraft:village_plains\","
            + "\"actions\": [{\"type\": \"structurereset:refill_loot\"}]"
            + "}";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** The minimal valid body with one extra JSON member appended. */
    private static ResetDefinition withFields(String extraFields) {
        return DefinitionParser.parse("structurereset:test",
                json("{\"structure\": \"minecraft:village\","
                        + " \"actions\": [{\"type\": \"structurereset:noop\"}]"
                        + (extraFields.isEmpty() ? "" : ", " + extraFields) + "}"));
    }

    /** Parses the minimal body plus one field and expects a DefinitionException. */
    private static DefinitionException expectFailure(String extraFields) {
        return assertThrows(DefinitionException.class, () -> withFields(extraFields));
    }

    @Test
    void parsesAMinimalDefinitionWithEveryDefault() {
        ResetDefinition definition = DefinitionParser.parse("structurereset:test", json(MINIMAL));
        assertEquals("structurereset:test", definition.id());
        assertEquals("structurereset:test", definition.getId());
        assertEquals(1, definition.structures().size());
        StructureRef reference = definition.structures().get(0);
        assertEquals("minecraft:village_plains", reference.id());
        assertFalse(reference.tag());
        assertEquals(ResetDefinition.DEFAULT_RADIUS, definition.radius());
        assertEquals(64.0D, definition.radius());
        assertEquals(ResetTrigger.EITHER, definition.trigger());
        assertEquals(ResetMode.GLOBAL, definition.mode());
        assertEquals(0, definition.cooldownSeconds());
        assertTrue(definition.consumeItem());
        assertEquals(0, definition.priority());
        assertNull(definition.requiredItem());
        assertNull(definition.extra());
        assertTrue(definition.anchorBlocks().isEmpty());
        assertEquals(1, definition.actions().size());
        assertEquals("structurereset:refill_loot", definition.actions().get(0).type());
        assertFalse(definition.actions().get(0).hasParams());
    }

    @Test
    void parsesEveryKnownField() {
        String text = "{"
                + "\"structure\": [\"cataclysm:ruined_citadel\", \"#minecraft:village\"],"
                + "\"radius\": 12.5,"
                + "\"trigger\": \"anchor_block\","
                + "\"anchor_blocks\": [\"minecraft:lodestone\", \"minecraft:beacon\"],"
                + "\"mode\": \"per_player\","
                + "\"cooldown_seconds\": 30,"
                + "\"consume_item\": false,"
                + "\"required_item\": \"minecraft:nether_star\","
                + "\"priority\": 7,"
                + "\"actions\": ["
                + "{\"type\": \"structurereset:respawn_boss\","
                + " \"params\": {\"entity\": \"cataclysm:ender_guardian\"}},"
                + "{\"type\": \"structurereset:refill_loot\"}],"
                + "\"extra\": {\"debug\": true}"
                + "}";
        ResetDefinition definition = DefinitionParser.parse("structurereset:full", json(text));
        assertEquals(2, definition.structures().size());
        assertEquals("cataclysm:ruined_citadel", definition.structures().get(0).id());
        assertFalse(definition.structures().get(0).tag());
        assertEquals("minecraft:village", definition.structures().get(1).id());
        assertTrue(definition.structures().get(1).tag());
        assertEquals(12.5D, definition.radius());
        assertEquals(ResetTrigger.ANCHOR_BLOCK, definition.trigger());
        assertEquals(List.of("minecraft:lodestone", "minecraft:beacon"), definition.anchorBlocks());
        assertEquals(ResetMode.PER_PLAYER, definition.mode());
        assertEquals(30, definition.cooldownSeconds());
        assertFalse(definition.consumeItem());
        assertEquals("minecraft:nether_star", definition.requiredItem());
        assertEquals(7, definition.priority());
        assertEquals(2, definition.actions().size());
        assertTrue(definition.actions().get(0).hasParams());
        assertEquals("cataclysm:ender_guardian",
                definition.actions().get(0).params().get("entity").getAsString());
        assertFalse(definition.actions().get(1).hasParams());
        assertNotNull(definition.extra());
        assertTrue(definition.extra().get("debug").getAsBoolean());
    }

    @Test
    void acceptsAllThreeTriggerSpellings() {
        assertEquals(ResetTrigger.IN_STRUCTURE,
                withFields("\"trigger\": \"in_structure\"").trigger());
        assertEquals(ResetTrigger.ANCHOR_BLOCK,
                withFields("\"trigger\": \"anchor_block\"").trigger());
        assertEquals(ResetTrigger.EITHER,
                withFields("\"trigger\": \"either\"").trigger());
    }

    @Test
    void acceptsBothModeSpellings() {
        assertEquals(ResetMode.GLOBAL, withFields("\"mode\": \"global\"").mode());
        assertEquals(ResetMode.PER_PLAYER, withFields("\"mode\": \"per_player\"").mode());
    }

    @Test
    void treatsAnExplicitNullRequiredItemAsAbsent() {
        assertNull(withFields("\"required_item\": null").requiredItem());
    }

    @Test
    void treatsAnExplicitNullExtraAsAbsent() {
        assertNull(withFields("\"extra\": null").extra());
    }

    @Test
    void anEmptyDeclaredExtraObjectIsStillPresent() {
        ResetDefinition definition = withFields("\"extra\": {}");
        assertNotNull(definition.extra());
        assertEquals(0, definition.extra().size());
    }

    @Test
    void aNullJsonBodyIsAnEmptyObjectAndMissesTheStructure() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test", null));
        assertEquals("structure", failure.fieldPath());
        assertEquals("structure: is required and must be a string or an array of strings",
                failure.getMessage());
    }

    @Test
    void rejectsAMissingStructureField() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"actions\": [{\"type\": \"x\"}]}")));
        assertEquals("structure", failure.fieldPath());
        assertEquals("structure: is required and must be a string or an array of strings",
                failure.getMessage());
    }

    @Test
    void rejectsAnEmptyStructureArray() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": [], \"actions\": [{\"type\": \"x\"}]}")));
        assertEquals("structure", failure.fieldPath());
        assertEquals("structure: must contain at least one structure reference",
                failure.getMessage());
    }

    @Test
    void rejectsAStructureThatIsNeitherStringNorArray() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": 42, \"actions\": [{\"type\": \"x\"}]}")));
        assertEquals("structure", failure.fieldPath());
        assertEquals("structure: must be a string or an array of strings", failure.getMessage());
    }

    @Test
    void rejectsAStructureEntryThatIsNotAString() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": [42], \"actions\": [{\"type\": \"x\"}]}")));
        assertEquals("structure[0]", failure.fieldPath());
        assertEquals("structure[0]: must be a string structure reference", failure.getMessage());
    }

    @Test
    void rejectsAnInvalidStructureReferenceWithItsFieldPath() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft\", \"actions\": [{\"type\": \"x\"}]}")));
        assertEquals("structure", failure.fieldPath());
        assertEquals("structure: structure id must be exactly 'namespace:path': minecraft",
                failure.getMessage());
    }

    @Test
    void rejectsANonPositiveRadius() {
        DefinitionException failure = expectFailure("\"radius\": 0");
        assertEquals("radius", failure.fieldPath());
        assertEquals("radius: must be a positive number but was 0.0", failure.getMessage());
    }

    @Test
    void rejectsANegativeRadius() {
        DefinitionException failure = expectFailure("\"radius\": -5");
        assertEquals("radius", failure.fieldPath());
        assertEquals("radius: must be a positive number but was -5.0", failure.getMessage());
    }

    @Test
    void rejectsANonNumericRadius() {
        DefinitionException failure = expectFailure("\"radius\": \"big\"");
        assertEquals("radius", failure.fieldPath());
        assertEquals("radius: must be a number", failure.getMessage());
    }

    @Test
    void rejectsAnArrayRadiusAsANonPrimitive() {
        DefinitionException failure = expectFailure("\"radius\": [1]");
        assertEquals("radius", failure.fieldPath());
        assertTrue(failure.getMessage().startsWith("radius: must be a primitive value"));
    }

    @Test
    void rejectsANullRadiusAsANonPrimitive() {
        DefinitionException failure = expectFailure("\"radius\": null");
        assertEquals("radius", failure.fieldPath());
        assertEquals("radius: must be a primitive value but was a null", failure.getMessage());
    }

    @Test
    void rejectsMissingActions() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\"}")));
        assertEquals("actions", failure.fieldPath());
        assertEquals("actions: is required and must be a non-empty array of action objects",
                failure.getMessage());
    }

    @Test
    void rejectsAnEmptyActionsArray() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\", \"actions\": []}")));
        assertEquals("actions", failure.fieldPath());
        assertEquals("actions: must contain at least one action", failure.getMessage());
    }

    @Test
    void rejectsANonArrayActionsField() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\", \"actions\": \"x\"}")));
        assertEquals("actions", failure.fieldPath());
        assertEquals("actions: must be an array of action objects", failure.getMessage());
    }

    @Test
    void rejectsAnActionThatIsNotAnObject() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\", \"actions\": [42]}")));
        assertEquals("actions[0]", failure.fieldPath());
        assertEquals("actions[0]: must be an object", failure.getMessage());
    }

    @Test
    void rejectsAnActionWithoutAType() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\", \"actions\": [{}]}")));
        assertEquals("actions[0].type", failure.fieldPath());
        assertEquals("actions[0].type: must be a non-blank string", failure.getMessage());
    }

    @Test
    void rejectsABlankActionType() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\","
                                + " \"actions\": [{\"type\": \"   \"}]}")));
        assertEquals("actions[0].type", failure.fieldPath());
        assertEquals("actions[0].type: must be a non-blank string", failure.getMessage());
    }

    @Test
    void rejectsActionParamsThatAreNotAnObject() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:test",
                        json("{\"structure\": \"minecraft:village\","
                                + " \"actions\": [{\"type\": \"x\", \"params\": []}]}")));
        assertEquals("actions[0].params", failure.fieldPath());
        assertEquals("actions[0].params: must be an object", failure.getMessage());
    }

    @Test
    void rejectsAnUnknownTriggerValue() {
        DefinitionException failure = expectFailure("\"trigger\": \"sometimes\"");
        assertEquals("trigger", failure.fieldPath());
        assertEquals("trigger: must be one of in_structure, anchor_block, either but was 'sometimes'",
                failure.getMessage());
    }

    @Test
    void rejectsAnUnknownModeValue() {
        DefinitionException failure = expectFailure("\"mode\": \"forever\"");
        assertEquals("mode", failure.fieldPath());
        assertEquals("mode: must be one of global, per_player but was 'forever'",
                failure.getMessage());
    }

    @Test
    void rejectsANonBooleanConsumeItem() {
        DefinitionException failure = expectFailure("\"consume_item\": \"yes\"");
        assertEquals("consume_item", failure.fieldPath());
        assertEquals("consume_item: must be a boolean", failure.getMessage());
    }

    @Test
    void rejectsANonIntegerCooldown() {
        DefinitionException failure = expectFailure("\"cooldown_seconds\": 1.5");
        assertEquals("cooldown_seconds", failure.fieldPath());
        assertEquals("cooldown_seconds: must be a whole number but was 1.5", failure.getMessage());
    }

    @Test
    void rejectsANegativeCooldown() {
        DefinitionException failure = expectFailure("\"cooldown_seconds\": -1");
        assertTrue(failure.fieldPath().startsWith("cooldown_seconds"));
        assertEquals("cooldown_seconds (cooldownSeconds): must be >= 0 but was -1",
                failure.getMessage());
    }

    @Test
    void rejectsANonStringAnchorBlockEntry() {
        DefinitionException failure = expectFailure("\"anchor_blocks\": [\"minecraft:stone\", 4]");
        assertEquals("anchor_blocks[1]", failure.fieldPath());
        assertEquals("anchor_blocks[1]: must be a string", failure.getMessage());
    }

    @Test
    void rejectsAnchorBlocksThatAreNotAnArray() {
        DefinitionException failure = expectFailure("\"anchor_blocks\": \"minecraft:stone\"");
        assertEquals("anchor_blocks", failure.fieldPath());
        assertEquals("anchor_blocks: must be an array of strings", failure.getMessage());
    }

    @Test
    void rejectsANonObjectExtraField() {
        DefinitionException failure = expectFailure("\"extra\": []");
        assertEquals("extra", failure.fieldPath());
        assertEquals("extra: must be an object", failure.getMessage());
    }

    @Test
    void keepsUnknownTopLevelFieldsInExtra() {
        ResetDefinition definition = withFields("\"my_extension\": 7, \"nested\": {\"a\": true}");
        assertNotNull(definition.extra());
        assertEquals(2, definition.extra().size());
        assertEquals(7, definition.extra().get("my_extension").getAsInt());
        assertTrue(definition.extra().get("nested").getAsJsonObject().get("a").getAsBoolean());
    }

    @Test
    void mergesTheDeclaredExtraObjectWithUnknownFields() {
        ResetDefinition definition = withFields("\"extra\": {\"declared\": 1}, \"unknown_field\": \"kept\"");
        assertNotNull(definition.extra());
        assertEquals(2, definition.extra().size());
        assertEquals(1, definition.extra().get("declared").getAsInt());
        assertEquals("kept", definition.extra().get("unknown_field").getAsString());
    }

    @Test
    void rejectsAMalformedDefinitionId() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset", json(MINIMAL)));
        assertEquals("id", failure.fieldPath());
        assertEquals("id: must be 'namespace:path' but was 'structurereset'", failure.getMessage());
    }

    @Test
    void rejectsADefinitionIdWithAnEmptyPath() {
        DefinitionException failure = assertThrows(DefinitionException.class,
                () -> DefinitionParser.parse("structurereset:", json(MINIMAL)));
        assertEquals("id", failure.fieldPath());
        assertEquals("id: must be 'namespace:path' but was 'structurereset:'", failure.getMessage());
    }

    @Test
    void collectionAccessorsHandOutUnmodifiableCopies() {
        ResetDefinition definition = withFields("");
        assertThrows(UnsupportedOperationException.class,
                () -> definition.structures().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.anchorBlocks().add("minecraft:stone"));
        assertThrows(UnsupportedOperationException.class,
                () -> definition.actions().clear());
    }
}
