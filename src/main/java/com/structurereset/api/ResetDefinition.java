package com.structurereset.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of one structure reset, completely decoupled from
 * Minecraft.
 *
 * <p>Instances are created through {@link Builder}. Every collection accessor
 * hands out an unmodifiable copy, so a definition can be shared freely.</p>
 */
public final class ResetDefinition {

    /** Radius used when a definition does not declare one. */
    public static final double DEFAULT_RADIUS = 64.0D;
    /** Trigger used when a definition does not declare one. */
    public static final ResetTrigger DEFAULT_TRIGGER = ResetTrigger.EITHER;
    /** Mode used when a definition does not declare one. */
    public static final ResetMode DEFAULT_MODE = ResetMode.GLOBAL;
    /** Cooldown (seconds) used when a definition does not declare one. */
    public static final int DEFAULT_COOLDOWN_SECONDS = 0;
    /** Item consumption used when a definition does not declare one. */
    public static final boolean DEFAULT_CONSUME_ITEM = true;
    /** Priority used when a definition does not declare one. */
    public static final int DEFAULT_PRIORITY = 0;
    /** Action type that re-spawns the boss stored on the anchor block. */
    public static final String RESPAWN_BOSS_ACTION = "structurereset:respawn_boss";

    private final String id;
    private final List<StructureRef> structures;
    private final double radius;
    private final ResetTrigger trigger;
    private final List<String> anchorBlocks;
    private final ResetMode mode;
    private final int cooldownSeconds;
    private final boolean consumeItem;
    private final String requiredItem;
    private final int priority;
    private final List<ResetActionSpec> actions;
    private final JsonObject extra;

    private ResetDefinition(Builder builder) {
        this.id = builder.id;
        this.structures = List.copyOf(builder.structures);
        this.radius = builder.radius;
        this.trigger = builder.trigger;
        this.anchorBlocks = List.copyOf(builder.anchorBlocks);
        this.mode = builder.mode;
        this.cooldownSeconds = builder.cooldownSeconds;
        this.consumeItem = builder.consumeItem;
        this.requiredItem = builder.requiredItem;
        this.priority = builder.priority;
        this.actions = List.copyOf(builder.actions);
        this.extra = builder.extra;
    }

    /** A builder with every documented default already applied. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder that already knows its definition id. */
    public static Builder builder(String id) {
        return new Builder().id(id);
    }

    // ------------------------------------------------------------ accessors

    /** The definition id, always {@code namespace:path}. */
    public String id() {
        return id;
    }

    /** JavaBean alias of {@link #id()}. */
    public String getId() {
        return id;
    }

    /** The structure references this definition applies to. */
    public List<StructureRef> structures() {
        return structures;
    }

    /** JavaBean alias of {@link #structures()}. */
    public List<StructureRef> getStructures() {
        return structures;
    }

    /** The trigger radius in blocks. */
    public double radius() {
        return radius;
    }

    /** JavaBean alias of {@link #radius()}. */
    public double getRadius() {
        return radius;
    }

    /** What makes this definition fire. */
    public ResetTrigger trigger() {
        return trigger;
    }

    /** JavaBean alias of {@link #trigger()}. */
    public ResetTrigger getTrigger() {
        return trigger;
    }

    /** Block ids that count as the anchor block. */
    public List<String> anchorBlocks() {
        return anchorBlocks;
    }

    /** JavaBean alias of {@link #anchorBlocks()}. */
    public List<String> getAnchorBlocks() {
        return anchorBlocks;
    }

    /** How widely the reset state is shared. */
    public ResetMode mode() {
        return mode;
    }

    /** JavaBean alias of {@link #mode()}. */
    public ResetMode getMode() {
        return mode;
    }

    /** Cooldown in seconds between two resets. */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** JavaBean alias of {@link #cooldownSeconds()}. */
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /** True when the reset consumes the held item. */
    public boolean consumeItem() {
        return consumeItem;
    }

    /** JavaBean alias of {@link #consumeItem()}. */
    public boolean getConsumeItem() {
        return consumeItem;
    }

    /** The item required to trigger a reset, or null when none is required. */
    public String requiredItem() {
        return requiredItem;
    }

    /** JavaBean alias of {@link #requiredItem()}. */
    public String getRequiredItem() {
        return requiredItem;
    }

    /** Selection priority; higher wins. */
    public int priority() {
        return priority;
    }

    /** JavaBean alias of {@link #priority()}. */
    public int getPriority() {
        return priority;
    }

    /** The ordered actions of this definition, at least one. */
    public List<ResetActionSpec> actions() {
        return actions;
    }

    /** JavaBean alias of {@link #actions()}. */
    public List<ResetActionSpec> getActions() {
        return actions;
    }

    /**
     * The entity id carried by this definition's {@code structurereset:respawn_boss}
     * action, or {@code null} when this definition has no such action, when that
     * action has no {@code entity} parameter, or when that value is not a JSON
     * string. Never throws.
     */
    public String respawnBossEntity() {
        for (ResetActionSpec action : actions) {
            if (action == null || !RESPAWN_BOSS_ACTION.equals(action.type())) {
                continue;
            }
            JsonObject params = action.params();
            if (params == null) {
                return null;
            }
            JsonElement entity = params.get("entity");
            if (entity == null || !entity.isJsonPrimitive()
                    || !entity.getAsJsonPrimitive().isString()) {
                return null;
            }
            return entity.getAsString();
        }
        return null;
    }

    /** JavaBean alias of {@link #respawnBossEntity()}. */
    public String getRespawnBossEntity() {
        return respawnBossEntity();
    }

    /** The raw "extra" JSON object, or null when there is none. */
    public JsonObject extra() {
        return extra;
    }

    /** JavaBean alias of {@link #extra()}. */
    public JsonObject getExtra() {
        return extra;
    }

    // -------------------------------------------------------- value object

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResetDefinition)) {
            return false;
        }
        ResetDefinition that = (ResetDefinition) other;
        return Double.compare(radius, that.radius) == 0
                && cooldownSeconds == that.cooldownSeconds
                && consumeItem == that.consumeItem
                && priority == that.priority
                && Objects.equals(id, that.id)
                && Objects.equals(structures, that.structures)
                && trigger == that.trigger
                && Objects.equals(anchorBlocks, that.anchorBlocks)
                && mode == that.mode
                && Objects.equals(requiredItem, that.requiredItem)
                && Objects.equals(actions, that.actions)
                && Objects.equals(extra, that.extra);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, structures, radius, trigger, anchorBlocks, mode,
                cooldownSeconds, consumeItem, requiredItem, priority, actions, extra);
    }

    @Override
    public String toString() {
        return "ResetDefinition[id=" + id
                + ", structures=" + structures
                + ", radius=" + radius
                + ", trigger=" + trigger
                + ", anchorBlocks=" + anchorBlocks
                + ", mode=" + mode
                + ", cooldownSeconds=" + cooldownSeconds
                + ", consumeItem=" + consumeItem
                + ", requiredItem=" + requiredItem
                + ", priority=" + priority
                + ", actions=" + actions
                + ", extra=" + extra + "]";
    }

    // ------------------------------------------------------------- builder

    /** Mutable builder producing an immutable {@link ResetDefinition}. */
    public static final class Builder {

        private String id;
        private final List<StructureRef> structures = new ArrayList<>();
        private double radius = DEFAULT_RADIUS;
        private ResetTrigger trigger = DEFAULT_TRIGGER;
        private final List<String> anchorBlocks = new ArrayList<>();
        private ResetMode mode = DEFAULT_MODE;
        private int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
        private boolean consumeItem = DEFAULT_CONSUME_ITEM;
        private String requiredItem;
        private int priority = DEFAULT_PRIORITY;
        private final List<ResetActionSpec> actions = new ArrayList<>();
        private JsonObject extra;

        /** An empty builder; every value starts at its documented default. */
        public Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /** Replaces the structure references (null clears them). */
        public Builder structures(Collection<StructureRef> values) {
            this.structures.clear();
            if (values != null) {
                for (StructureRef value : values) {
                    addStructure(value);
                }
            }
            return this;
        }

        public Builder addStructure(StructureRef value) {
            if (value != null) {
                this.structures.add(value);
            }
            return this;
        }

        /** Parses and appends a raw reference such as {@code #minecraft:village}. */
        public Builder addStructure(String raw) {
            return addStructure(StructureRef.parse(raw));
        }

        public Builder radius(double radius) {
            this.radius = radius;
            return this;
        }

        public Builder trigger(ResetTrigger trigger) {
            if (trigger != null) {
                this.trigger = trigger;
            }
            return this;
        }

        /** Replaces the anchor block ids (null clears them). */
        public Builder anchorBlocks(Collection<String> values) {
            this.anchorBlocks.clear();
            if (values != null) {
                for (String value : values) {
                    addAnchorBlock(value);
                }
            }
            return this;
        }

        public Builder anchorBlock(String value) {
            if (value != null) {
                this.anchorBlocks.add(value);
            }
            return this;
        }

        /** Alias of {@link #anchorBlock(String)}. */
        public Builder addAnchorBlock(String value) {
            return anchorBlock(value);
        }

        public Builder mode(ResetMode mode) {
            if (mode != null) {
                this.mode = mode;
            }
            return this;
        }

        public Builder cooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
            return this;
        }

        public Builder consumeItem(boolean consumeItem) {
            this.consumeItem = consumeItem;
            return this;
        }

        public Builder requiredItem(String requiredItem) {
            this.requiredItem = requiredItem;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Replaces the actions (null clears them). */
        public Builder actions(Collection<ResetActionSpec> values) {
            this.actions.clear();
            if (values != null) {
                for (ResetActionSpec value : values) {
                    addAction(value);
                }
            }
            return this;
        }

        public Builder addAction(ResetActionSpec value) {
            if (value != null) {
                this.actions.add(value);
            }
            return this;
        }

        /** Appends a parameterless action of {@code type}. */
        public Builder addAction(String type) {
            return addAction(ResetActionSpec.of(type));
        }

        public Builder extra(JsonObject extra) {
            this.extra = extra;
            return this;
        }

        /**
         * Validates and freezes the definition.
         *
         * @throws DefinitionException when the id is malformed, when no
         *         structure reference is present, when the radius is not
         *         positive, when the cooldown is negative or when no action is
         *         present
         */
        public ResetDefinition build() {
            String checkedId = requireId(this.id);
            if (structures.isEmpty()) {
                throw DefinitionException.at("structures",
                        "at least one structure reference is required");
            }
            if (!(radius > 0.0D) || Double.isInfinite(radius)) {
                throw DefinitionException.at("radius",
                        "must be a positive number but was " + radius);
            }
            if (cooldownSeconds < 0) {
                throw DefinitionException.at("cooldown_seconds (cooldownSeconds)",
                        "must be >= 0 but was " + cooldownSeconds);
            }
            if (actions.isEmpty()) {
                throw DefinitionException.at("actions", "at least one action is required");
            }
            return new ResetDefinition(this);
        }

        private static String requireId(String value) {
            if (value == null || value.isBlank()) {
                throw DefinitionException.at("id", "must not be null or blank");
            }
            int colon = value.indexOf(':');
            if (colon <= 0 || colon != value.lastIndexOf(':') || colon == value.length() - 1) {
                throw DefinitionException.at("id",
                        "must be 'namespace:path' but was '" + value + "'");
            }
            for (int index = 0; index < value.length(); index++) {
                if (Character.isWhitespace(value.charAt(index))) {
                    throw DefinitionException.at("id",
                            "must not contain whitespace but was '" + value + "'");
                }
            }
            return value;
        }
    }
}
