package com.structurereset.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.structurereset.api.DefinitionParser;
import com.structurereset.api.ResetDefinition;
import com.structurereset.config.StructureResetConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Loads {@code data/<namespace>/structure_resets/**&#47;*.json} into
 * {@link StructureResetRegistry}.
 *
 * <p>Each file is parsed on its own: a malformed definition is logged and
 * skipped, it never aborts the reload and never takes the server down. Fields a
 * file does not declare fall back to the values of
 * {@link StructureResetConfig}, which is what makes those config options
 * meaningful for datapack authors.</p>
 */
public class DefinitionReloadListener extends SimpleJsonResourceReloadListener {

    /** The data pack directory this listener reads. */
    public static final String DIRECTORY = "structure_resets";

    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final Logger LOGGER = LogUtils.getLogger();

    public DefinitionReloadListener() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        List<ResetDefinition> parsed = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            String id = entry.getKey().toString();
            JsonElement element = entry.getValue();
            if (element == null || !element.isJsonObject()) {
                skipped++;
                LOGGER.error("[{}] skipping structure reset definition {}: the body must be a JSON object",
                        StructureResetConfig.MOD_ID, id);
                continue;
            }
            try {
                JsonObject body = withConfiguredDefaults(element.getAsJsonObject());
                parsed.add(DefinitionParser.parse(id, body));
            } catch (RuntimeException failure) {
                skipped++;
                LOGGER.error("[{}] skipping structure reset definition {}: {}",
                        StructureResetConfig.MOD_ID, id, failure.getMessage());
            }
        }
        StructureResetRegistry.replaceDatapackDefinitions(parsed);
        LOGGER.info("[{}] loaded {} structure reset definition(s) from data packs ({} skipped)",
                StructureResetConfig.MOD_ID, parsed.size(), skipped);
    }

    /**
     * Fills radius, cooldown and mode in when a file leaves them out, so the
     * three "default*" config entries actually drive the data pack.
     */
    private static JsonObject withConfiguredDefaults(JsonObject body) {
        boolean needsRadius = !body.has(DefinitionParser.FIELD_RADIUS);
        boolean needsCooldown = !body.has(DefinitionParser.FIELD_COOLDOWN_SECONDS);
        boolean needsMode = !body.has(DefinitionParser.FIELD_MODE);
        if (!needsRadius && !needsCooldown && !needsMode) {
            return body;
        }
        JsonObject filled = body.deepCopy();
        if (needsRadius) {
            filled.addProperty(DefinitionParser.FIELD_RADIUS, StructureResetConfig.defaultRadius());
        }
        if (needsCooldown) {
            filled.addProperty(DefinitionParser.FIELD_COOLDOWN_SECONDS,
                    StructureResetConfig.defaultCooldownSeconds());
        }
        if (needsMode) {
            filled.addProperty(DefinitionParser.FIELD_MODE, StructureResetConfig.defaultModeName());
        }
        return filled;
    }
}
