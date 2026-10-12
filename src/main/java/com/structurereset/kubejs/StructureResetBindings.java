package com.structurereset.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.structurereset.api.ItemSettings;
import com.structurereset.api.ResetDefinition;
import com.structurereset.api.DefinitionParser;
import com.structurereset.api.DefinitionException;
import com.structurereset.api.LearnedAnchor;
import com.structurereset.core.BossFilterApi;
import com.structurereset.core.ItemAppearanceApi;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import com.structurereset.core.StructureResetApi;
import com.structurereset.impl.LearnedAnchorData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * The {@code StructureReset} global that KubeJS scripts see.
 *
 * <p>Every method here is {@code public static} on purpose: KubeJS exposes a
 * {@link Class} binding as "a java class whose static methods are callable from
 * script".</p>
 */
public final class StructureResetBindings {

    private StructureResetBindings() {
    }

    // ------------------------------------------------------------- definitions

    /**
     * Registers (or replaces) a reset definition.
     *
     * <p>{@code definition} is parsed with {@link DefinitionParser}, so script
     * authors get exactly the same validation and the same field names as a
     * datapack JSON file.</p>
     *
     * @param definition the definition object, including its {@code id}
     * @return the id of the registered definition
     */
    public static String register(JsonObject definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (!definition.has("id")) {
            throw new DefinitionException("id", "a script definition must declare 'id'");
        }
        String id = definition.get("id").getAsString();
        ResetDefinition parsed = DefinitionParser.parse(id, definition);
        StructureResetApi.registerDefinition(parsed);
        return id;
    }

    /**
     * Removes a definition by id.
     *
     * @return true when a definition was actually removed
     */
    public static boolean remove(String id) {
        return StructureResetApi.removeDefinition(id);
    }

    /** @return every loaded definition id, sorted for stable output. */
    public static List<String> list() {
        List<String> ids = new ArrayList<>();
        for (ResetDefinition definition : StructureResetApi.definitions()) {
            ids.add(definition.id());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /** @return the definition with that id, or {@code null}. */
    public static ResetDefinition get(String id) {
        return StructureResetApi.definition(id);
    }

    /**
     * Re-runs the vanilla {@code /reload} pipeline so datapack changes to
     * {@code data/<ns>/structure_resets/} are picked up.
     *
     * @return true when a server was available and the reload was dispatched
     */
    public static boolean reload() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        return true;
    }

    // -------------------------------------------------------- learned anchors

    /**
     * Every learned anchor of the running server, as JSON.
     *
     * <p>Teaching itself is not exposed to scripts: a record can only be
     * created in game, by sneaking and using the reset core on a mob (or with
     * {@code /structurereset learned teach-here}).  What a script gets here is
     * the read side of the learning feature.</p>
     *
     * <p>The returned list is a fresh {@link ArrayList} of freshly built
     * {@link JsonObject}s - each element is a copy produced by
     * {@link LearnedAnchor#toJson()}, so a script may reorder or modify what it
     * receives without ever writing back to the stored records.</p>
     *
     * @return one JSON object per learned anchor, sorted by id; empty when no
     *         server is running or nothing was taught yet
     */
    public static List<JsonObject> learned() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        List<JsonObject> anchors = new ArrayList<>();
        for (LearnedAnchor anchor : LearnedAnchorData.get(server).all()) {
            anchors.add(anchor.toJson());
        }
        return anchors;
    }

    /**
     * Forgets one learned anchor by id.
     *
     * <p>Like {@link #learned()} this is the script side of the learning
     * feature: nothing is ever taught from a script, records can only be
     * removed.</p>
     *
     * @param id the learning id, e.g. {@code learned:1}
     * @return true when a record was actually removed; false when no server is
     *         running, the id is null or blank, or nothing carries that id
     */
    public static boolean forget(String id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || id == null || id.isBlank()) {
            return false;
        }
        return LearnedAnchorData.get(server).remove(id);
    }

    // ----------------------------------------------------------------- actions

    /**
     * Registers a custom action type backed by a script function.
     *
     * <p>{@link ResetActionType} has two methods and therefore cannot be a
     * lambda, so {@link ScriptActionType} adapts the script callback to it.</p>
     *
     * @param type    the action id, e.g. {@code mypack:announce}
     * @param handler called with a {@link ResetContextJS} for every execution
     */
    public static void registerAction(String type, Consumer<ResetContextJS> handler) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("action type must not be blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("action handler must not be null");
        }
        ResourceLocation id = new ResourceLocation(type);
        StructureResetApi.registerActionType(id, new ScriptActionType(id.toString(), handler));
    }

    /** @return the ids of every registered action type. */
    public static List<String> actionTypes() {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : StructureResetApi.registeredActionTypeIds()) {
            ids.add(id.toString());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /**
     * Adapter that turns a script function into a {@link ResetActionType}.
     *
     * <p>Not a lambda on purpose: {@code ResetActionType} declares both
     * {@code id()} and {@code run(...)}.</p>
     */
    public static final class ScriptActionType implements ResetActionType {

        private final String id;
        private final Consumer<ResetContextJS> handler;

        public ScriptActionType(String id, Consumer<ResetContextJS> handler) {
            this.id = id;
            this.handler = handler;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ResetResult run(ResetContext context) {
            ResetContextJS scriptContext = new ResetContextJS(context, null);
            try {
                handler.accept(scriptContext);
            } catch (RuntimeException error) {
                return ResetResult.fail(String.valueOf(error));
            }
            if (scriptContext.isCancelled()) {
                return ResetResult.fail(scriptContext.getMessage(), "cancelled by script");
            }
            return ResetResult.ok(scriptContext.getMessage(), List.of(id));
        }

        @Override
        public String toString() {
            return "ScriptActionType[" + id + "]";
        }
    }

    // ----------------------------------------------------------- item appearance

    /**
     * Overrides the model of an item for the client.
     *
     * @param itemId  e.g. {@code structurereset:structure_reset_core}
     * @param modelId e.g. {@code mypack:item/reset_core_gold}
     */
    public static boolean setItemModel(String itemId, String modelId) {
        Item item = item(itemId);
        if (item == null) {
            return false;
        }
        ItemAppearanceApi.setModelOverride(item, new ResourceLocation(modelId));
        return true;
    }

    /** Clears a model override previously set by {@link #setItemModel}. */
    public static boolean clearItemModel(String itemId) {
        Item item = item(itemId);
        if (item == null) {
            return false;
        }
        ItemAppearanceApi.clearModelOverride(item);
        return true;
    }

    /** @return the current model override of an item, or {@code null}. */
    public static String itemModel(String itemId) {
        Item item = item(itemId);
        if (item == null) {
            return null;
        }
        return ItemAppearanceApi.modelOverride(item).map(ResourceLocation::toString).orElse(null);
    }

    private static Item item(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
    }

    // ----------------------------------------------------------- item settings

    /**
     * Overrides the behaviour settings of an item.
     *
     * <p>Every field is optional: a field that is missing from {@code settings}
     * keeps the value currently in force, so a script can change one knob
     * without having to restate the others. An unknown item id (or a
     * {@code null} one) returns {@code false} and changes nothing; a value the
     * record rejects - {@code maxUses} below 1 or a negative
     * {@code cooldownSeconds} - throws an {@link IllegalArgumentException},
     * exactly like an invalid definition does.</p>
     *
     * @param itemId the item id, e.g. {@code structurereset:structure_reset_core}
     * @param settings the fields to change:
     *        {@code maxUses}, {@code unbreakable}, {@code cooldownSeconds},
     *        {@code teachConsumes}; may be {@code null} for "no change"
     * @return true when the settings were applied, false when the item is unknown
     */
    public static boolean setItemSettings(String itemId, JsonObject settings) {
        Item item = item(itemId);
        if (item == null) {
            return false;
        }
        ItemSettings current = StructureResetApi.itemSettings(item);
        int maxUses = current.maxUses();
        boolean unbreakable = current.unbreakable();
        int cooldownSeconds = current.cooldownSeconds();
        boolean teachConsumes = current.teachConsumes();
        if (settings != null) {
            maxUses = intField(settings, "maxUses", maxUses);
            unbreakable = booleanField(settings, "unbreakable", unbreakable);
            cooldownSeconds = intField(settings, "cooldownSeconds", cooldownSeconds);
            teachConsumes = booleanField(settings, "teachConsumes", teachConsumes);
        }
        StructureResetApi.setItemSettings(item,
                new ItemSettings(maxUses, unbreakable, cooldownSeconds, teachConsumes));
        return true;
    }

    /**
     * The behaviour settings currently in force for an item.
     *
     * @param itemId the item id, e.g. {@code structurereset:structure_reset_core}
     * @return an object with {@code maxUses}, {@code unbreakable},
     *         {@code cooldownSeconds} and {@code teachConsumes}, or {@code null}
     *         when the item id is unknown
     */
    public static JsonObject getItemSettings(String itemId) {
        Item item = item(itemId);
        if (item == null) {
            return null;
        }
        ItemSettings settings = StructureResetApi.itemSettings(item);
        JsonObject json = new JsonObject();
        json.addProperty("maxUses", settings.maxUses());
        json.addProperty("unbreakable", settings.unbreakable());
        json.addProperty("cooldownSeconds", settings.cooldownSeconds());
        json.addProperty("teachConsumes", settings.teachConsumes());
        return json;
    }

    /** One integer field of a settings object, or the current value when it is absent. */
    private static int intField(JsonObject settings, String key, int current) {
        JsonElement element = settings == null ? null : settings.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return current;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException failure) {
            return current;
        }
    }

    /** One boolean field of a settings object, or the current value when it is absent. */
    private static boolean booleanField(JsonObject settings, String key, boolean current) {
        JsonElement element = settings == null ? null : settings.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            return current;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException failure) {
            return current;
        }
    }

    // ---------------------------------------------------------- boss filter

    /**
     * Registers the script boss filter of the learning feature.
     *
     * <p>While a filter is registered it is the only boss criterion: it gets a
     * JSON description of the candidate ({@code id}, {@code max_health},
     * {@code health}, {@code nbt_keys}, {@code nbt}) and returns whether that
     * creature may be taught. A filter that throws counts as "not a boss" and
     * never breaks teaching.</p>
     *
     * @param filter the filter, or {@code null} to clear it
     */
    public static void setBossFilter(Function<JsonObject, Boolean> filter) {
        BossFilterApi.setBossFilter(filter);
    }

    /** Removes the script boss filter, restoring the built in boss criterion. */
    public static void clearBossFilter() {
        BossFilterApi.clearBossFilter();
    }

    /** @return the placeholder variables of a context, for debugging scripts. */
    public static Map<String, String> vars(ResetContextJS context) {
        return context == null ? Map.of() : context.getVars();
    }
}
