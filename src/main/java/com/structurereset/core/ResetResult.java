package com.structurereset.core;

import java.util.List;

/**
 * The outcome of one reset (or of one action of a reset).
 *
 * @param success true when every action ran
 * @param messageKey translation key handed to the player, never null
 * @param actionsRun the action type ids that were actually executed, never null
 * @param error the first error that occurred, or an empty string, never null
 */
public record ResetResult(boolean success, String messageKey, List<String> actionsRun, String error) {

    /** Translation key of a successful reset. */
    public static final String MESSAGE_OK = "structurereset.message.reset.success";
    /** Translation key of a reset that failed. */
    public static final String MESSAGE_FAILED = "structurereset.message.reset.failed";

    public ResetResult {
        messageKey = messageKey == null ? "" : messageKey;
        actionsRun = actionsRun == null ? List.of() : List.copyOf(actionsRun);
        error = error == null ? "" : error;
    }

    /** A successful result. */
    public static ResetResult ok(String messageKey, List<String> actionsRun) {
        return new ResetResult(true, messageKey == null ? MESSAGE_OK : messageKey, actionsRun, "");
    }

    /** A successful result without a custom message key. */
    public static ResetResult ok(String messageKey) {
        return ok(messageKey, List.of());
    }

    /** A successful result carrying only the executed action ids. */
    public static ResetResult ok(List<String> actionsRun) {
        return ok(MESSAGE_OK, actionsRun);
    }

    /** A failed result without a custom message key. */
    public static ResetResult fail(String error) {
        return new ResetResult(false, MESSAGE_FAILED, List.of(), error);
    }

    /** A failed result with an explicit message key and error text. */
    public static ResetResult fail(String messageKey, String error) {
        return new ResetResult(false, messageKey, List.of(), error);
    }

    /** How many actions were executed. */
    public int actionCount() {
        return actionsRun.size();
    }

    /** True when at least one action failed. */
    public boolean failed() {
        return !success;
    }

    /** True when an error text was recorded. */
    public boolean hasError() {
        return !error.isEmpty();
    }
}
