package com.structurereset.config;

import com.structurereset.api.ResetMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

/**
 * The two {@code ModConfigSpec}s of the addon.
 *
 * <p>COMMON carries the values a modpack author tunes once (the defaults used
 * when a datapack definition leaves a field out, the structure search radius
 * and the debug switch). SERVER carries the values that belong to one world
 * (item consumption and player feedback).</p>
 *
 * <p>Every accessor is null safe and falls back to its documented default, so
 * the engine stays usable before the config files are loaded.</p>
 */
public final class StructureResetConfig {

    /** The mod id, matching {@code mods.toml} and {@code StructureResetMod} . */
    public static final String MOD_ID = "structurereset";

    /** The COMMON spec, registered by {@link #register()}. */
    public static final ForgeConfigSpec COMMON_SPEC;
    /** The COMMON values. */
    public static final Common COMMON;
    /** The SERVER spec, registered by {@link #register()}. */
    public static final ForgeConfigSpec SERVER_SPEC;
    /** The SERVER values. */
    public static final Server SERVER;

    static {
        Pair<Common, ForgeConfigSpec> common = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = common.getLeft();
        COMMON_SPEC = common.getRight();
        Pair<Server, ForgeConfigSpec> server = new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();
    }

    private StructureResetConfig() {
    }

    /** Registers both specs on the mod loading context. */
    public static void register() {
        ModLoadingContext context = ModLoadingContext.get();
        context.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
        context.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    // ------------------------------------------------------------ accessors

    /** Master switch; a false value blocks every non forced reset. */
    public static boolean enabled() {
        return bool(COMMON == null ? null : COMMON.enabled, true);
    }

    /** Radius a definition gets when its JSON leaves {@code radius} out. */
    public static double defaultRadius() {
        return number(COMMON == null ? null : COMMON.defaultRadius, 64.0D);
    }

    /** Cooldown a definition gets when its JSON leaves {@code cooldown_seconds} out. */
    public static int defaultCooldownSeconds() {
        return integer(COMMON == null ? null : COMMON.defaultCooldownSeconds, 60);
    }

    /** The raw {@code global} / {@code per_player} default mode. */
    public static String defaultModeName() {
        return text(COMMON == null ? null : COMMON.defaultMode, "global");
    }

    /** The default mode as an enum. */
    public static ResetMode defaultMode() {
        return "per_player".equalsIgnoreCase(defaultModeName()) ? ResetMode.PER_PLAYER : ResetMode.GLOBAL;
    }

    /** How far around the player a structure is searched for, in blocks. */
    public static int structureSearchRadius() {
        return integer(COMMON == null ? null : COMMON.structureSearchRadius, 32);
    }

    /** When true the engine logs every reset and every failing action. */
    public static boolean debugLogging() {
        return bool(COMMON == null ? null : COMMON.debugLogging, false);
    }

    /** When true the teaching feature (sneak and use the core on a mob) is enabled. */
    public static boolean learningEnabled() {
        return bool(COMMON == null ? null : COMMON.learningEnabled, true);
    }

    /** Radius, in blocks, a learning record is matched against when it is replayed. */
    public static int learningRadius() {
        return integer(COMMON == null ? null : COMMON.learningRadius, 64);
    }

    /**
     * Lowest maximum health a mob needs before it may be taught.
     *
     * <p>{@code 0} or a negative value disables the health criterion, which
     * then accepts every mob that reached the other classification rules; the
     * shipped default of 300 only matters for a mob that has neither a boss tag
     * nor a place on the allow list.</p>
     */
    public static int learningMinMaxHealth() {
        return integer(COMMON == null ? null : COMMON.learningMinMaxHealth, 300);
    }

    /**
     * When true the teaching player must stand inside a recognised structure.
     *
     * <p>The shipped default keeps the strict behaviour: a boss can only be
     * taught where the structure manager really sees a structure. Turning it off
     * allows teaching anywhere, which is what a hand built arena needs - the
     * operator then carries the risk of teaching the wrong place, and
     * {@code /structurereset learned teach-here} remains the targeted way to do
     * it.</p>
     */
    public static boolean learningRequireStructure() {
        return bool(COMMON == null ? null : COMMON.learningRequireStructure, true);
    }

    /**
     * When true a structure may only be reset once its boss has actually been
     * beaten and has not been brought back yet.
     *
     * <p>The gate only refuses when the registry <em>knows</em> the boss: a
     * structure nobody has observed yet stays resettable, so the setting can
     * never lock a player out of a structure the mod has not seen.</p>
     */
    public static boolean resetRequiresDefeated() {
        return bool(COMMON == null ? null : COMMON.resetRequiresDefeated, true);
    }

    /** When true the aimed respawn position must be inside the structure. */
    public static boolean aimRequireInsideStructure() {
        return bool(COMMON == null ? null : COMMON.aimRequireInsideStructure, true);
    }

    /** How long the aiming phase lasts, in ticks. */
    public static int aimWindowTicks() {
        return integer(COMMON == null ? null : COMMON.aimWindowTicks, 600);
    }

    /** How long the cast lasts once the position is locked, in ticks. */
    public static int aimCastTicks() {
        return integer(COMMON == null ? null : COMMON.aimCastTicks, 100);
    }

    /** How far the crosshair reaches when a respawn position is chosen. */
    public static int aimReach() {
        return integer(COMMON == null ? null : COMMON.aimReach, 20);
    }

    /** Entity namespaces that may never be taught; {@code minecraft} by default. */
    public static List<String> learningExcludedNamespaces() {
        return stringList(COMMON == null ? null : COMMON.learningExcludedNamespaces,
                List.of("minecraft"));
    }

    /** Entity ids that are accepted as a boss without any further test. */
    public static List<String> learningEntityAllowlist() {
        return stringList(COMMON == null ? null : COMMON.learningEntityAllowlist, List.of());
    }

    /** Entity ids that are never accepted as a boss. */
    public static List<String> learningEntityDenylist() {
        return stringList(COMMON == null ? null : COMMON.learningEntityDenylist, List.of());
    }

    /**
     * Entity tags that mark a boss, checked before the health threshold.
     *
     * <p>The default follows both ecosystem conventions - the Forge one
     * ({@code forge:bosses}) and the common one ({@code c:bosses}) - and adds the
     * addon's own {@code structurereset:known_bosses} fallback tag. A mod that
     * tags its bosses therefore needs no configuration at all. In the authors'
     * test pack those tags alone cover 29 bosses across 11 mods.</p>
     */
    public static List<String> learningBossTags() {
        return stringList(COMMON == null ? null : COMMON.learningBossTags,
                List.of("forge:bosses", "c:bosses", "structurereset:known_bosses"));
    }

    /** When true the crafting recipe is built from the recipe.* settings below. */
    public static boolean recipeEnabled() {
        return bool(COMMON == null ? null : COMMON.recipeEnabled, false);
    }

    /** How many cores one crafted result yields when the config recipe is on. */
    public static int recipeResultCount() {
        return integer(COMMON == null ? null : COMMON.recipeResultCount, 1);
    }

    /**
     * The nine recipe slots in row-major order; an empty entry means an empty
     * slot and every other entry is an item id (no per-slot count syntax).
     */
    public static List<String> recipeSlots() {
        return stringList(COMMON == null ? null : COMMON.recipeSlots, List.of());
    }

    /** The null-safe list reader shared by every list valued accessor. */
    private static List<String> stringList(ForgeConfigSpec.ConfigValue<List<? extends String>> value,
                                           List<String> fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            List<? extends String> raw = value.get();
            if (raw == null) {
                return fallback;
            }
            List<String> values = new ArrayList<>();
            for (String entry : raw) {
                if (entry != null && !entry.isBlank()) {
                    values.add(entry);
                }
            }
            return List.copyOf(values);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /**
     * The largest angle, in degrees, between the player's look direction and the
     * direction to the target at which teaching by item is still accepted.
     *
     * <p>Only the item path is gated on this; the {@code teach-here} command
     * skips the check.</p>
     */
    public static int learningMaxAngleDegrees() {
        return integer(COMMON == null ? null : COMMON.learningMaxAngleDegrees, 45);
    }

    /**
     * Top level NBT keys that additionally mark a target as a boss.
     *
     * <p>The list is empty by default, which disables the NBT criterion
     * completely: nothing is then gated on NBT. A non-empty list acts as a
     * bonus criterion, never as the only admission path, and the entry values
     * are ordinary key names such as {@code Health} or {@code CustomName}.</p>
     *
     * @return the configured keys, never null and never containing a null or
     *         blank entry
     */
    public static List<String> learningBossNbtKeys() {
        ForgeConfigSpec.ConfigValue<List<? extends String>> value =
                COMMON == null ? null : COMMON.learningBossNbtKeys;
        if (value == null) {
            return List.of();
        }
        try {
            List<? extends String> raw = value.get();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (String key : raw) {
                if (key != null && !key.isBlank()) {
                    keys.add(key);
                }
            }
            return List.copyOf(keys);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** When true only operators may teach an anchor. */
    public static boolean learningRequireOp() {
        return bool(COMMON == null ? null : COMMON.learningRequireOp, false);
    }

    /**
     * When true a right click on a mob only teaches while the player sneaks.
     *
     * <p>A false value lets a plain (non sneaking) right click teach as well,
     * so the core item no longer needs the shift key to record a boss.</p>
     */
    public static boolean learningRequiresSneak() {
        return bool(COMMON == null ? null : COMMON.learningRequiresSneak, true);
    }

    /** When true a successful reset uses up one required item. */
    public static boolean consumeItem() {
        return bool(SERVER == null ? null : SERVER.consumeItem, true);
    }

    /** When true the acting player is told about the outcome. */
    public static boolean notifyPlayer() {
        return bool(SERVER == null ? null : SERVER.notifyPlayer, true);
    }

    /** How long, in seconds, a first right click may be confirmed by a second one. */
    public static int confirmWindowSeconds() {
        return integer(SERVER == null ? null : SERVER.confirmWindowSeconds, 3);
    }

    // -------------------------------------------------------------- values

    private static boolean bool(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value.get();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(ForgeConfigSpec.IntValue value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value.get();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double number(ForgeConfigSpec.DoubleValue value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value.get();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String text(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            String raw = value.get();
            return raw == null || raw.isBlank() ? fallback : raw;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ sections

    /** The COMMON section. */
    public static final class Common {

        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.DoubleValue defaultRadius;
        public final ForgeConfigSpec.IntValue defaultCooldownSeconds;
        public final ForgeConfigSpec.ConfigValue<String> defaultMode;
        public final ForgeConfigSpec.IntValue structureSearchRadius;
        public final ForgeConfigSpec.BooleanValue debugLogging;
        public final ForgeConfigSpec.BooleanValue learningEnabled;
        public final ForgeConfigSpec.IntValue learningRadius;
        public final ForgeConfigSpec.IntValue learningMinMaxHealth;
        public final ForgeConfigSpec.IntValue learningMaxAngleDegrees;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> learningBossNbtKeys;
        public final ForgeConfigSpec.BooleanValue learningRequireOp;
        public final ForgeConfigSpec.BooleanValue learningRequiresSneak;
        public final ForgeConfigSpec.BooleanValue learningRequireStructure;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> learningExcludedNamespaces;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> learningEntityAllowlist;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> learningEntityDenylist;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> learningBossTags;
            public final ForgeConfigSpec.BooleanValue resetRequiresDefeated;
        public final ForgeConfigSpec.BooleanValue aimRequireInsideStructure;
        public final ForgeConfigSpec.IntValue aimWindowTicks;
        public final ForgeConfigSpec.IntValue aimCastTicks;
        public final ForgeConfigSpec.IntValue aimReach;
        public final ForgeConfigSpec.BooleanValue recipeEnabled;
        public final ForgeConfigSpec.IntValue recipeResultCount;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> recipeSlots;

        Common(ForgeConfigSpec.Builder builder) {
            builder.comment("Structure Reset - common settings").push("general");
            this.enabled = builder
                    .comment("Master switch for structure resets.",
                            "false blocks every reset that is not requested with force=true.")
                    .define("enabled", true);
            this.defaultRadius = builder
                    .comment("Radius, in blocks, given to a definition whose JSON has no 'radius'.")
                    .defineInRange("defaultRadius", 64.0D, 1.0D, 4096.0D);
            this.defaultCooldownSeconds = builder
                    .comment("Cooldown, in seconds, given to a definition whose JSON has no",
                            "'cooldown_seconds'.")
                    .defineInRange("defaultCooldownSeconds", 60, 0, 31_536_000);
            this.defaultMode = builder
                    .comment("Mode given to a definition whose JSON has no 'mode':",
                            "'global' or 'per_player'.")
                    .define("defaultMode", "global");
            this.structureSearchRadius = builder
                    .comment("How far, in blocks, around the trigger position a structure is",
                            "searched for when the exact block is not inside one yet.")
                    .defineInRange("structureSearchRadius", 32, 0, 512);
            this.debugLogging = builder
                    .comment("Log every executed reset and every failing action.")
                    .define("debugLogging", false);
            builder.pop();
            builder.comment("Structure Reset - learning/teaching settings").push("learning");
            this.learningEnabled = builder
                    .comment("Master switch for the learning feature: sneak and use the core",
                            "item on a mob to record the surrounding structure, its boss and",
                            "its loot containers.")
                    .define("learningEnabled", true);
            this.learningRadius = builder
                    .comment("How far, in blocks, around a learned anchor a structure is",
                            "searched for when the anchor is taught or replayed.")
                    .defineInRange("learningRadius", 64, 1, 512);
            this.learningMinMaxHealth = builder
                    .comment("Last resort of the boss classification: a mob may only be taught",
                            "when its maximum health is at least this value. It is only",
                            "consulted when no script filter, allow list entry, boss entity",
                            "tag or boss NBT key decided the target first; 0 disables it.",
                            "Prefer tagging your bosses with forge:bosses over tuning this.")
                    .defineInRange("learningMinMaxHealth", 300, 0, 100000);
            this.learningMaxAngleDegrees = builder
                    .comment("Largest angle, in degrees, between the player's look direction",
                            "and the direction to the target at which teaching the target by",
                            "right clicking the core on it is accepted. Only the item path is",
                            "gated on this; /structurereset learned teach-here skips it.")
                    .defineInRange("learningMaxAngleDegrees", 45, 1, 180);
            this.learningBossNbtKeys = builder
                    .comment("Top level NBT keys that additionally mark a target as a boss.",
                            "Empty by default, which disables the NBT criterion completely; a",
                            "non-empty list is only ever a bonus criterion, never the sole",
                            "admission path. Example entry: 'CustomName'.")
                    .defineList("learningBossNbtKeys", java.util.List.of(),
                            element -> element instanceof String);
            this.learningRequireOp = builder
                    .comment("When true only players with permission level 2 or higher may",
                            "teach an anchor.")
                    .define("learningRequireOp", false);
            this.learningRequiresSneak = builder
                    .comment("When true a right click on a mob only teaches while the player",
                            "sneaks. Set it to false to let a plain (non sneaking) right",
                            "click teach too.")
                    .define("learningRequiresSneak", true);
            this.learningRequireStructure = builder
                    .comment("When true the teaching player must stand inside a recognised",
                            "structure. Set it to false to allow teaching anywhere - useful",
                            "for hand built arenas, at the price of a bigger mis-teaching",
                            "risk. /structurereset learned teach-here stays the targeted",
                            "way to register a boss outside any structure.")
                    .define("learningRequireStructure", true);
            this.resetRequiresDefeated = builder
                    .comment("When true a structure may only be reset after its boss has been",
                            "beaten and has not been brought back yet. The gate refuses only",
                            "when the boss registry knows the boss, so a structure the mod",
                            "has never observed stays resettable.")
                    .define("resetRequiresDefeated", true);
            this.aimRequireInsideStructure = builder
                    .comment("When true the position chosen with the crosshair must be inside",
                            "the structure the player stands in.")
                    .define("aimRequireInsideStructure", true);
            this.aimWindowTicks = builder
                    .comment("How long the aiming phase lasts, in ticks (600 = 30 seconds).",
                            "Aiming never spawns or consumes anything.")
                    .defineInRange("aimWindowTicks", 600, 40, 7200);
            this.aimCastTicks = builder
                    .comment("How long the cast lasts once the position is locked, in ticks",
                            "(100 = 5 seconds). The summoning effect and the retreating bar",
                            "are played for exactly this long, and the boss appears when the",
                            "cast ends.")
                    .defineInRange("aimCastTicks", 100, 20, 400);
            this.aimReach = builder
                    .comment("How far the crosshair reaches when a respawn position is chosen,",
                            "in blocks.")
                    .defineInRange("aimReach", 20, 4, 64);
            this.learningExcludedNamespaces = builder
                    .comment("Entity namespaces that may never be taught, checked before the",
                            "deny list and before the boss tags. The default excludes every",
                            "vanilla mob, including the vanilla bosses.")
                    .defineList("learningExcludedNamespaces", java.util.List.of("minecraft"),
                            element -> element instanceof String);
            this.learningEntityAllowlist = builder
                    .comment("Entity ids that are accepted as a boss immediately, before the",
                            "excluded namespaces, the deny list, the boss tags and the health",
                            "threshold. Example entry: 'cataclysm:the_harbinger'.")
                    .defineList("learningEntityAllowlist", java.util.List.of(),
                            element -> element instanceof String);
            this.learningEntityDenylist = builder
                    .comment("Entity ids that are never accepted as a boss.")
                    .defineList("learningEntityDenylist", java.util.List.of(),
                            element -> element instanceof String);
            this.learningBossTags = builder
                    .comment("Entity tags that mark a boss, checked before the health",
                            "threshold; entries may be written with or without a leading",
                            "'#'. The default follows both conventions (forge:bosses and",
                            "c:bosses) plus the addon's own fallback tag, so a mod that tags",
                            "its bosses needs no configuration at all.")
                    .defineList("learningBossTags",
                            java.util.List.of("forge:bosses", "c:bosses", "structurereset:known_bosses"),
                            element -> element instanceof String);
            builder.pop();
            builder.comment("Structure Reset - optional config driven crafting recipe",
                    "Disabled by default: the bundled data pack recipe in",
                    "data/structurereset/recipes/structure_reset_core.json is used instead.").push("recipe");
            this.recipeEnabled = builder
                    .comment("When true the core recipe is built from the recipe.* settings",
                            "below instead of relying on the bundled data pack recipe; the",
                            "bundled recipe is then replaced, never duplicated.")
                    .define("enabled", false);
            this.recipeResultCount = builder
                    .comment("How many structure reset cores one craft yields.")
                    .defineInRange("result_count", 1, 1, 64);
            this.recipeSlots = builder
                    .comment("The nine recipe slots in row-major order (top left to bottom",
                            "right). An empty string is an empty slot; every other entry is",
                            "an item id such as 'minecraft:diamond' and contributes exactly",
                            "one item - there is no per-slot count notation.")
                    .defineList("slots", java.util.List.of(
                                    "minecraft:diamond", "minecraft:ancient_debris", "minecraft:diamond",
                                    "minecraft:ancient_debris", "minecraft:nether_star",
                                    "minecraft:ancient_debris",
                                    "minecraft:diamond", "minecraft:ancient_debris", "minecraft:diamond"),
                            element -> element instanceof String);
            builder.pop();
        }
    }

    /** The SERVER section. */
    public static final class Server {

        public final ForgeConfigSpec.BooleanValue consumeItem;
        public final ForgeConfigSpec.BooleanValue notifyPlayer;
        public final ForgeConfigSpec.IntValue confirmWindowSeconds;

        Server(ForgeConfigSpec.Builder builder) {
            builder.comment("Structure Reset - per world settings").push("gameplay");
            this.consumeItem = builder
                    .comment("Use up one 'required_item' when a definition that asks for it resets.")
                    .define("consumeItem", true);
            this.notifyPlayer = builder
                    .comment("Tell the acting player whether the reset succeeded.")
                    .define("notifyPlayer", true);
            this.confirmWindowSeconds = builder
                    .comment("Window, in seconds, in which a second right click confirms the",
                            "reset announced by the first one. The first click never resets",
                            "anything by itself.")
                    .defineInRange("confirmWindowSeconds", 3, 1, 600);
            builder.pop();
        }
    }
}
