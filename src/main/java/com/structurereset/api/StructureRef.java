package com.structurereset.api;

/**
 * An immutable reference to a structure, either by id ({@code namespace:path})
 * or by tag ({@code #namespace:path}).
 *
 * <p>The {@link #id()} of a tag reference never carries the leading {@code '#'};
 * the {@link #tag()} flag carries that information instead.</p>
 */
public record StructureRef(String id, boolean tag) {

    /** Rejects ids that are not exactly {@code namespace:path}. */
    public StructureRef {
        id = requireValidId(id);
    }

    /**
     * Parses a raw reference such as {@code minecraft:village_plains} or
     * {@code #minecraft:village}.
     *
     * @throws IllegalArgumentException when {@code raw} is null, blank or not a
     *         {@code namespace:path} reference
     */
    public static StructureRef parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("structure reference must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("structure reference must not be blank");
        }
        boolean tag = trimmed.startsWith("#");
        String id = tag ? trimmed.substring(1) : trimmed;
        return new StructureRef(id, tag);
    }

    /** True when this non-tag reference points at exactly {@code structureId}. */
    public boolean matchesId(String structureId) {
        return !tag && id.equals(structureId);
    }

    /** The reference exactly as it is written in a datapack JSON file. */
    public String raw() {
        return tag ? "#" + id : id;
    }

    private static String requireValidId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("structure id must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("structure id must not be blank");
        }
        if (value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("structure id must not contain '#': " + value);
        }
        int colon = value.indexOf(':');
        if (colon < 0 || colon != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("structure id must be exactly 'namespace:path': " + value);
        }
        String namespace = value.substring(0, colon);
        String path = value.substring(colon + 1);
        if (namespace.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("structure id must have a non-empty namespace and path: " + value);
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                throw new IllegalArgumentException("structure id must not contain whitespace: " + value);
            }
        }
        return value;
    }
}
