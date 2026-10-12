package com.structurereset.api;

import java.util.UUID;

/**
 * Storage key of one independent reset state.
 *
 * @param dimensionId the dimension the state lives in
 * @param scopeId {@link #GLOBAL_SCOPE} or the player UUID
 * @param definitionId the definition id that owns the state
 */
public record ResetKey(String dimensionId, String scopeId, String definitionId) {

    /** Scope id shared by every player of a global reset. */
    public static final String GLOBAL_SCOPE = "global";

    /** Field separator of {@link #toStorageString()}. */
    public static final char SEPARATOR = '|';

    public ResetKey {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimension id must not be null or blank");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scope id must not be null or blank");
        }
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definition id must not be null or blank");
        }
    }

    /**
     * Builds the key for a reset scope.
     *
     * @throws IllegalArgumentException when a per player key is requested
     *         without a player UUID
     */
    public static ResetKey of(String dimensionId, ResetMode mode, UUID playerId,
                              String definitionId) {
        if (mode == null) {
            throw new IllegalArgumentException("reset mode must not be null");
        }
        switch (mode) {
            case GLOBAL:
                return new ResetKey(dimensionId, GLOBAL_SCOPE, definitionId);
            case PER_PLAYER:
                if (playerId == null) {
                    throw new IllegalArgumentException(
                            "a per player reset key requires a player id");
                }
                return new ResetKey(dimensionId, playerId.toString(), definitionId);
            default:
                throw new IllegalArgumentException("unsupported reset mode: " + mode);
        }
    }

    /** The key as {@code dimensionId|scopeId|definitionId}. */
    public String toStorageString() {
        return dimensionId + SEPARATOR + scopeId + SEPARATOR + definitionId;
    }

    /**
     * Parses {@link #toStorageString()}.
     *
     * @throws IllegalArgumentException when the value is null, does not contain
     *         exactly three fields or has an empty field
     */
    public static ResetKey fromStorageString(String stored) {
        if (stored == null) {
            throw new IllegalArgumentException("storage string must not be null");
        }
        int first = stored.indexOf(SEPARATOR);
        int second = first < 0 ? -1 : stored.indexOf(SEPARATOR, first + 1);
        if (first < 0 || second < 0 || stored.indexOf(SEPARATOR, second + 1) >= 0) {
            throw new IllegalArgumentException(
                    "storage string must have exactly three '|' separated fields: '" + stored + "'");
        }
        String dimensionId = stored.substring(0, first);
        String scopeId = stored.substring(first + 1, second);
        String definitionId = stored.substring(second + 1);
        if (dimensionId.isEmpty() || scopeId.isEmpty() || definitionId.isEmpty()) {
            throw new IllegalArgumentException(
                    "storage string must not contain an empty field: '" + stored + "'");
        }
        return new ResetKey(dimensionId, scopeId, definitionId);
    }
}
