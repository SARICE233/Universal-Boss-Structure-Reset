package com.structurereset.api;

import com.google.gson.JsonObject;

/**
 * One action of a reset definition: an action {@code type} plus its raw
 * parameters.
 *
 * @param type action type, must not be null or blank
 * @param params action parameters, never null (an empty object when absent)
 */
public record ResetActionSpec(String type, JsonObject params) {

    public ResetActionSpec {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("reset action type must not be null or blank");
        }
        if (params == null) {
            params = new JsonObject();
        }
    }

    /** An action of {@code type} without parameters. */
    public static ResetActionSpec of(String type) {
        return new ResetActionSpec(type, new JsonObject());
    }

    /** True when this action carries no parameter at all. */
    public boolean hasParams() {
        return params.size() > 0;
    }
}
