package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Anchor-entity aware selection of {@link DefinitionSelector} plus the
 * {@link ResetDefinition#respawnBossEntity()} accessor it relies on.
 *
 * <p>The scenario under test is the Cataclysm {@code cataclysm:boss_respawner}
 * anchor block: the block stores the entity type it belongs to, and a definition
 * that respawns another boss may only be picked when it lists the entered
 * structure id exactly; a definition that merely matched the anchor block must
 * respawn exactly that entity type. Otherwise, when the structure id does not
 * resolve, the Netherite Monstrosity definition could be used inside the Soul
 * Black Smith and the wrong boss would appear.</p>
 */
class AnchorEntitySelectionTest {

    /** Cataclysm's Soul Black Smith boss. */
    private static final String THE_HARBINGER = "cataclysm:the_harbinger";
    /** Cataclysm's Ancient Factory boss. */
    private static final String NETHERITE_MONSTROSITY = "cataclysm:netherite_monstrosity";

    private static ResetDefinition bossDefinition(String id, String structureRef,
                                                  int priority, String respawnEntity) {
        ResetDefinition.Builder builder = ResetDefinition.builder(id)
                .addStructure(structureRef)
                .trigger(ResetTrigger.EITHER)
                .priority(priority);
        if (respawnEntity == null) {
            // a respawn_boss action whose params carry no entity at all
            builder.addAction(ResetDefinition.RESPAWN_BOSS_ACTION);
        } else {
            JsonObject params = new JsonObject();
            params.addProperty("entity", respawnEntity);
            builder.addAction(new ResetActionSpec(ResetDefinition.RESPAWN_BOSS_ACTION, params));
        }
        return builder.build();
    }

    private static ResetDefinition definitionWithAction(ResetActionSpec action) {
        return ResetDefinition.builder("structurereset:with_action")
                .addStructure("minecraft:village")
                .addAction(action)
                .build();
    }

    private static ResetDefinition soulBlackSmith() {
        return bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 0, THE_HARBINGER);
    }

    private static ResetDefinition ancientFactory() {
        return bossDefinition("structurereset:ancient_factory",
                "cataclysm:ancient_factory", 0, NETHERITE_MONSTROSITY);
    }

    // ------------------------------------------------- anchor entity matching

    @Test
    void theAnchorEntityTypeSelectsTheBossItActuallyStores() {
        ResetDefinition harbinger = soulBlackSmith();
        ResetDefinition monstrosity = ancientFactory();
        // The boss_respawner of the Soul Black Smith stores cataclysm:the_harbinger,
        // so only structurereset:soul_black_smith may be selected.
        assertSame(harbinger, DefinitionSelector.select(List.of(monstrosity, harbinger),
                null, List.of(), true, THE_HARBINGER).orElseThrow());
        assertSame(harbinger, DefinitionSelector.select(List.of(harbinger, monstrosity),
                null, List.of(), true, THE_HARBINGER).orElseThrow());
    }

    @Test
    void theMirroredAnchorEntityTypeSelectsTheMirroredDefinition() {
        ResetDefinition harbinger = soulBlackSmith();
        ResetDefinition monstrosity = ancientFactory();
        assertSame(monstrosity, DefinitionSelector.select(List.of(harbinger, monstrosity),
                null, List.of(), true, NETHERITE_MONSTROSITY).orElseThrow());
        assertSame(monstrosity, DefinitionSelector.select(List.of(monstrosity, harbinger),
                null, List.of(), true, NETHERITE_MONSTROSITY).orElseThrow());
    }

    @Test
    void anAnchorEntityThatNoDefinitionRespawnsIsRefused() {
        List<ResetDefinition> definitions = List.of(soulBlackSmith(), ancientFactory());
        assertTrue(DefinitionSelector.select(definitions, null, List.of(), true,
                "cataclysm:ignis").isEmpty());
        assertTrue(DefinitionSelector.select(definitions, null, List.of(), true,
                "minecraft:zombie").isEmpty());
    }

    @Test
    void anExactStructureIdOutranksTheAnchorEntityMatch() {
        // the entered structure id is claimed exactly by one definition, so that
        // definition wins even though the anchor block stores a different boss
        ResetDefinition exact = bossDefinition("structurereset:ancient_factory",
                "cataclysm:ancient_factory", 0, NETHERITE_MONSTROSITY);
        ResetDefinition entityMatch = bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 100, THE_HARBINGER);
        assertSame(exact, DefinitionSelector.select(List.of(entityMatch, exact),
                "cataclysm:ancient_factory", List.of(), true, THE_HARBINGER).orElseThrow());
        assertSame(exact, DefinitionSelector.select(List.of(exact, entityMatch),
                "cataclysm:ancient_factory", List.of(), true, THE_HARBINGER).orElseThrow());
        assertEquals("structurereset:ancient_factory", exact.id());
    }

    @Test
    void theStoredAnchorEntityPicksSoulBlackSmithWhenTheStructureIdDoesNotResolve() {
        // the real bug: the structure id does NOT resolve (null), the anchor
        // block belongs to the Soul Black Smith and stores cataclysm:the_harbinger,
        // but the Netherite Monstrosity definition is listed first, so without
        // the anchor entity check the wrong boss would be summoned
        ResetDefinition soulBlackSmith = soulBlackSmith();
        ResetDefinition ancientFactory = ancientFactory();
        ResetDefinition winner = DefinitionSelector.select(
                List.of(ancientFactory, soulBlackSmith),
                null, List.of(), true, THE_HARBINGER).orElseThrow();
        assertSame(soulBlackSmith, winner);
        assertEquals("structurereset:soul_black_smith", winner.id());
        // the result may not depend on the iteration order of the definitions
        assertSame(soulBlackSmith, DefinitionSelector.select(
                List.of(soulBlackSmith, ancientFactory),
                null, List.of(), true, THE_HARBINGER).orElseThrow());
    }

    @Test
    void anAnchorEntityMismatchOnlyVetoesCandidatesWithoutAnExactStructureId() {
        // no exact structure id match and a different boss: refused
        ResetDefinition mismatchingOnly = bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 0, NETHERITE_MONSTROSITY);
        assertTrue(DefinitionSelector.select(List.of(mismatchingOnly),
                null, List.of(), true, THE_HARBINGER).isEmpty());
        // the same mismatching definition WITH the exact structure id stays
        // selectable and wins over a pure anchor entity match
        ResetDefinition mismatchingExact = bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 0, NETHERITE_MONSTROSITY);
        ResetDefinition anchorOwner = bossDefinition("structurereset:soul_black_smith_alt",
                "cataclysm:soul_black_smith_alt", 100, THE_HARBINGER);
        assertSame(mismatchingExact, DefinitionSelector.select(List.of(anchorOwner, mismatchingExact),
                "cataclysm:soul_black_smith", List.of(), true, THE_HARBINGER).orElseThrow());
        assertSame(mismatchingExact, DefinitionSelector.select(List.of(mismatchingExact),
                "cataclysm:soul_black_smith", List.of(), true, THE_HARBINGER).orElseThrow());
        // a null respawn_boss entity is treated exactly like a different entity
        ResetDefinition noBossAtAll = bossDefinition("structurereset:ancient_factory_loot",
                "cataclysm:ancient_factory", 100, null);
        assertTrue(DefinitionSelector.select(List.of(noBossAtAll),
                null, List.of(), true, THE_HARBINGER).isEmpty());
        assertSame(noBossAtAll, DefinitionSelector.select(List.of(noBossAtAll),
                "cataclysm:ancient_factory", List.of(), true, THE_HARBINGER).orElseThrow());
    }

    @Test
    void theAnchorEntityTierOutranksATagMatchAndAPureAnchorMatch() {
        ResetDefinition entityMatch = bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 0, THE_HARBINGER);
        ResetDefinition tagMatch = bossDefinition("structurereset:tagged",
                "#cataclysm:soul_black_smith", 100, null);
        ResetDefinition anchorOnly = bossDefinition("structurereset:anchor_only",
                "cataclysm:elsewhere", 100, null);
        List<ResetDefinition> definitions = List.of(tagMatch, anchorOnly, entityMatch);
        // without a stored anchor entity the tag match beats the pure anchor match
        assertSame(tagMatch, DefinitionSelector.select(definitions, null,
                List.of("cataclysm:soul_black_smith"), true, "").orElseThrow());
        // the stored anchor entity promotes its own definition above both of them
        assertSame(entityMatch, DefinitionSelector.select(definitions, null,
                List.of("cataclysm:soul_black_smith"), true, THE_HARBINGER).orElseThrow());
    }

    @Test
    void aBlankAnchorEntityTypeKeepsTheExactTagAndAnchorTiersOrdered() {
        ResetDefinition exact = bossDefinition("structurereset:exact",
                "minecraft:village", 0, null);
        ResetDefinition tagged = bossDefinition("structurereset:tagged",
                "#minecraft:village", 100, null);
        ResetDefinition anchorOnly = bossDefinition("structurereset:anchor_only",
                "minecraft:other", 100, null);
        assertSame(exact, DefinitionSelector.select(List.of(tagged, anchorOnly, exact),
                "minecraft:village", List.of("minecraft:village"), true, "").orElseThrow());
        assertSame(tagged, DefinitionSelector.select(List.of(anchorOnly, tagged),
                "minecraft:village", List.of("minecraft:village"), true, "").orElseThrow());
        assertSame(anchorOnly, DefinitionSelector.select(List.of(anchorOnly),
                "minecraft:village", List.of(), true, "").orElseThrow());
    }

    @Test
    void theAnchorEntityComparisonIsCaseSensitive() {
        ResetDefinition definition = soulBlackSmith();
        assertTrue(DefinitionSelector.select(List.of(definition), null, List.of(), true,
                "cataclysm:The_Harbinger").isEmpty());
        assertTrue(DefinitionSelector.select(List.of(definition), null, List.of(), true,
                "CATACLYSM:THE_HARBINGER").isEmpty());
        assertSame(definition, DefinitionSelector.select(List.of(definition), null, List.of(), true,
                THE_HARBINGER).orElseThrow());
    }

    @Test
    void aNullAnchorEntityTypeBehavesExactlyLikeTheLegacyOverload() {
        ResetDefinition exact = bossDefinition("structurereset:exact",
                "minecraft:village", 0, THE_HARBINGER);
        ResetDefinition tagged = bossDefinition("structurereset:tagged",
                "#minecraft:village", 0, null);
        ResetDefinition anchorOnly = bossDefinition("structurereset:anchor_only",
                "minecraft:other", 7, null);
        List<ResetDefinition> definitions = List.of(tagged, anchorOnly, exact);

        Optional<ResetDefinition> legacy = DefinitionSelector.select(definitions,
                "minecraft:village", List.of("minecraft:village"), true);
        Optional<ResetDefinition> withNull = DefinitionSelector.select(definitions,
                "minecraft:village", List.of("minecraft:village"), true, null);
        assertSame(legacy.orElseThrow(), withNull.orElseThrow());
        assertSame(exact, withNull.orElseThrow());

        // the pure anchor tier (rank 3) stays reachable on both paths
        Optional<ResetDefinition> legacyAnchor = DefinitionSelector.select(List.of(anchorOnly),
                null, List.of(), true);
        Optional<ResetDefinition> nullAnchor = DefinitionSelector.select(List.of(anchorOnly),
                null, List.of(), true, null);
        assertSame(legacyAnchor.orElseThrow(), nullAnchor.orElseThrow());
        assertSame(anchorOnly, nullAnchor.orElseThrow());
    }

    @Test
    void aBlankAnchorEntityTypeDisablesTheEntityConstraint() {
        ResetDefinition anchorOnly = bossDefinition("structurereset:anchor_only",
                "minecraft:other", 0, null);
        assertSame(anchorOnly, DefinitionSelector.select(List.of(anchorOnly), null, List.of(),
                true, "   ").orElseThrow());
        assertSame(anchorOnly, DefinitionSelector.select(List.of(anchorOnly), null, List.of(),
                true, "").orElseThrow());
        // but a non-blank entity the definition does not respawn filters it out again
        assertTrue(DefinitionSelector.select(List.of(anchorOnly), null, List.of(), true,
                THE_HARBINGER).isEmpty());
    }

    // --------------------------------------------------------- ambiguity

    @Test
    void fourteenEqualPureAnchorCandidatesAreRefused() {
        List<ResetDefinition> candidates = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            candidates.add(bossDefinition("structurereset:anchor_" + index,
                    "minecraft:other_" + index, 0, null));
        }
        assertTrue(DefinitionSelector.select(candidates, null, List.of(), true, null).isEmpty());
        assertTrue(DefinitionSelector.select(candidates, null, List.of(), true, "").isEmpty());

        // the refusal is about ambiguity, not about the size of the candidate set
        assertSame(candidates.get(0), DefinitionSelector.select(candidates,
                "minecraft:other_0", List.of(), true, null).orElseThrow());
    }

    @Test
    void twoCandidatesWithTheSameAnchorEntityRankAreRefusedEvenWithDifferentPriorities() {
        ResetDefinition low = bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 0, THE_HARBINGER);
        ResetDefinition high = bossDefinition("structurereset:soul_black_smith_clone",
                "cataclysm:soul_black_smith_alt", 50, THE_HARBINGER);
        // both rank 1: priority may not be used to guess between them
        assertTrue(DefinitionSelector.select(List.of(low, high), null, List.of(), true,
                THE_HARBINGER).isEmpty());
    }

    @Test
    void twoCandidatesWithTheSameExactStructureIdAreStillBrokenByPriority() {
        ResetDefinition low = bossDefinition("structurereset:soul_black_smith",
                "cataclysm:soul_black_smith", 0, THE_HARBINGER);
        ResetDefinition high = bossDefinition("structurereset:soul_black_smith_clone",
                "cataclysm:soul_black_smith", 50, THE_HARBINGER);
        assertSame(high, DefinitionSelector.select(List.of(low, high),
                "cataclysm:soul_black_smith", List.of(), true, THE_HARBINGER).orElseThrow());
    }

    // ------------------------------------------------- respawnBossEntity()

    @Test
    void respawnBossEntityIsNullWithoutTheRespawnBossAction() {
        ResetDefinition lootOnly = ResetDefinition.builder("structurereset:loot_only")
                .addStructure("cataclysm:soul_black_smith")
                .addAction("structurereset:refill_loot")
                .addAction("structurereset:reset_blocks")
                .build();
        assertNull(lootOnly.respawnBossEntity());
        assertNull(ResetDefinition.builder("structurereset:no_actions_at_all")
                .addStructure("minecraft:village")
                .addAction("structurereset:message")
                .build()
                .respawnBossEntity());
    }

    @Test
    void respawnBossEntityIsNullWithoutTheEntityParameter() {
        JsonObject onlyCount = new JsonObject();
        onlyCount.addProperty("count", 1);
        assertNull(definitionWithAction(new ResetActionSpec(
                ResetDefinition.RESPAWN_BOSS_ACTION, onlyCount)).respawnBossEntity());

        // a respawn_boss action without any params at all
        ResetDefinition bare = ResetDefinition.builder("structurereset:bare_respawn")
                .addStructure("minecraft:village")
                .addAction(ResetDefinition.RESPAWN_BOSS_ACTION)
                .build();
        assertNull(bare.respawnBossEntity());

        // an explicitly null entity value inside params
        JsonObject explicitNull = new JsonObject();
        explicitNull.add("entity", com.google.gson.JsonNull.INSTANCE);
        assertNull(definitionWithAction(new ResetActionSpec(
                ResetDefinition.RESPAWN_BOSS_ACTION, explicitNull)).respawnBossEntity());
    }

    @Test
    void respawnBossEntityIsNullForAbsentParamsAndReturnsTheExactNamespacedStringOtherwise() {
        // the record normalises a null params object to an empty one, which carries no entity
        ResetActionSpec nullParams = new ResetActionSpec(ResetDefinition.RESPAWN_BOSS_ACTION, null);
        assertNull(definitionWithAction(nullParams).respawnBossEntity());

        // a non-string primitive must not be coerced into an entity id
        JsonObject numeric = new JsonObject();
        numeric.addProperty("entity", 7);
        assertNull(definitionWithAction(new ResetActionSpec(
                ResetDefinition.RESPAWN_BOSS_ACTION, numeric)).respawnBossEntity());

        // a structured value must not be coerced either
        JsonObject structured = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("id", THE_HARBINGER);
        structured.add("entity", inner);
        assertNull(definitionWithAction(new ResetActionSpec(
                ResetDefinition.RESPAWN_BOSS_ACTION, structured)).respawnBossEntity());

        // the happy path returns the exact namespaced string, untouched
        assertEquals(THE_HARBINGER, soulBlackSmith().respawnBossEntity());
        assertEquals(NETHERITE_MONSTROSITY, ancientFactory().respawnBossEntity());
        assertEquals(THE_HARBINGER, soulBlackSmith().getRespawnBossEntity());
        assertEquals(ResetDefinition.RESPAWN_BOSS_ACTION,
                soulBlackSmith().actions().get(0).type());
    }
}
