package com.structurereset.api;

/**
 * Raised when a reset definition is malformed.
 *
 * <p>The message always names the offending field path, for example
 * {@code actions[0].type: must be a string}.</p>
 */
public class DefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String fieldPath;

    /** A failure that is not tied to a single field. */
    public DefinitionException(String message) {
        super(message);
        this.fieldPath = "";
    }

    /**
     * A failure at {@code fieldPath}; the resulting message is
     * {@code fieldPath + ": " + message}.
     */
    public DefinitionException(String fieldPath, String message) {
        super(render(fieldPath, message));
        this.fieldPath = fieldPath == null ? "" : fieldPath;
    }

    /** As {@link #DefinitionException(String, String)} but with a cause. */
    public DefinitionException(String fieldPath, String message, Throwable cause) {
        super(render(fieldPath, message), cause);
        this.fieldPath = fieldPath == null ? "" : fieldPath;
    }

    /** Static factory for {@link #DefinitionException(String, String)}. */
    public static DefinitionException at(String fieldPath, String message) {
        return new DefinitionException(fieldPath, message);
    }

    /** Static factory for {@link #DefinitionException(String, String, Throwable)}. */
    public static DefinitionException at(String fieldPath, String message, Throwable cause) {
        return new DefinitionException(fieldPath, message, cause);
    }

    /** Alias of {@link #at(String, String)}. */
    public static DefinitionException of(String fieldPath, String message) {
        return new DefinitionException(fieldPath, message);
    }

    /** Alias of {@link #at(String, String, Throwable)}. */
    public static DefinitionException of(String fieldPath, String message, Throwable cause) {
        return new DefinitionException(fieldPath, message, cause);
    }

    /** The field path this failure belongs to, or {@code ""} when unknown. */
    public String fieldPath() {
        return fieldPath;
    }

    /** JavaBean alias of {@link #fieldPath()}. */
    public String getFieldPath() {
        return fieldPath;
    }

    private static String render(String fieldPath, String message) {
        String detail = (message == null || message.isBlank()) ? "invalid value" : message;
        return (fieldPath == null || fieldPath.isBlank()) ? detail : fieldPath + ": " + detail;
    }
}
