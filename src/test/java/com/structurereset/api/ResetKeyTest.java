package com.structurereset.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behaviour of {@link ResetKey}, its scope factory and its storage format. */
class ResetKeyTest {

    private static final UUID PLAYER = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final String DIMENSION = "minecraft:overworld";
    private static final String DEFINITION = "structurereset:test";

    @Test
    void valueEqualityAndHashCodeFollowTheRecordContract() {
        ResetKey first = new ResetKey(DIMENSION, ResetKey.GLOBAL_SCOPE, DEFINITION);
        ResetKey second = new ResetKey(DIMENSION, ResetKey.GLOBAL_SCOPE, DEFINITION);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new ResetKey(DIMENSION, ResetKey.GLOBAL_SCOPE, "structurereset:other"));
        assertNotEquals(first, new ResetKey("minecraft:the_nether", ResetKey.GLOBAL_SCOPE, DEFINITION));
        assertNotEquals(first, new ResetKey(DIMENSION, PLAYER.toString(), DEFINITION));
    }

    @Test
    void globalModeAlwaysUsesTheSharedScopeId() {
        ResetKey key = ResetKey.of(DIMENSION, ResetMode.GLOBAL, PLAYER, DEFINITION);
        assertEquals(ResetKey.GLOBAL_SCOPE, key.scopeId());
        assertEquals("global", key.scopeId());
        assertEquals(new ResetKey(DIMENSION, "global", DEFINITION), key);
        ResetKey withoutPlayer = ResetKey.of(DIMENSION, ResetMode.GLOBAL, null, DEFINITION);
        assertEquals(ResetKey.GLOBAL_SCOPE, withoutPlayer.scopeId());
    }

    @Test
    void perPlayerModeUsesThePlayerUuidAsTheScopeId() {
        ResetKey key = ResetKey.of(DIMENSION, ResetMode.PER_PLAYER, PLAYER, DEFINITION);
        assertEquals(PLAYER.toString(), key.scopeId());
        assertNotEquals(ResetKey.GLOBAL_SCOPE, key.scopeId());
        assertEquals(new ResetKey(DIMENSION, PLAYER.toString(), DEFINITION), key);
    }

    @Test
    void perPlayerModeWithoutAPlayerIdIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ResetKey.of(DIMENSION, ResetMode.PER_PLAYER, null, DEFINITION));
        assertEquals("a per player reset key requires a player id", failure.getMessage());
    }

    @Test
    void aNullModeIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ResetKey.of(DIMENSION, null, PLAYER, DEFINITION));
        assertEquals("reset mode must not be null", failure.getMessage());
    }

    @Test
    void storageStringUsesOnePipeAsTheSeparator() {
        ResetKey key = new ResetKey(DIMENSION, ResetKey.GLOBAL_SCOPE, DEFINITION);
        assertEquals('|', ResetKey.SEPARATOR);
        assertEquals("minecraft:overworld|global|structurereset:test", key.toStorageString());
    }

    @Test
    void storageStringRoundTripsForEveryMode() {
        for (ResetMode mode : ResetMode.values()) {
            ResetKey key = ResetKey.of(DIMENSION, mode, PLAYER, DEFINITION);
            assertEquals(key, ResetKey.fromStorageString(key.toStorageString()));
        }
        ResetKey perPlayer = ResetKey.of(DIMENSION, ResetMode.PER_PLAYER, PLAYER, DEFINITION);
        assertEquals("minecraft:overworld|" + PLAYER + "|structurereset:test",
                perPlayer.toStorageString());
    }

    @Test
    void fromStorageStringRejectsMalformedValues() {
        IllegalArgumentException nullFailure = assertThrows(IllegalArgumentException.class,
                () -> ResetKey.fromStorageString(null));
        assertEquals("storage string must not be null", nullFailure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ResetKey.fromStorageString("only|two"));
        assertThrows(IllegalArgumentException.class, () -> ResetKey.fromStorageString("a|b|c|d"));
        assertThrows(IllegalArgumentException.class, () -> ResetKey.fromStorageString("|b|c"));
        assertThrows(IllegalArgumentException.class, () -> ResetKey.fromStorageString("a||c"));
        assertThrows(IllegalArgumentException.class, () -> ResetKey.fromStorageString("a|b|"));
        assertThrows(IllegalArgumentException.class, () -> ResetKey.fromStorageString(""));
    }

    @Test
    void theCanonicalConstructorRejectsBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResetKey(null, ResetKey.GLOBAL_SCOPE, DEFINITION));
        assertThrows(IllegalArgumentException.class,
                () -> new ResetKey("  ", ResetKey.GLOBAL_SCOPE, DEFINITION));
        assertThrows(IllegalArgumentException.class,
                () -> new ResetKey(DIMENSION, null, DEFINITION));
        assertThrows(IllegalArgumentException.class,
                () -> new ResetKey(DIMENSION, ResetKey.GLOBAL_SCOPE, " "));
    }
}
