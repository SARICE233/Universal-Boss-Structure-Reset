package com.structurereset.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.structurereset.api.LearnedAnchor;
import com.structurereset.api.ResetActionSpec;
import com.structurereset.api.ResetDefinition;
import com.structurereset.api.StructureRef;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.core.StructureResetApi;
import com.structurereset.impl.action.RefillLootAction;
import com.structurereset.impl.action.RestoreLearnedAnchorAction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * The read only announcement of the two click confirmation.
 *
 * <p>The first right click on the structure reset core executes nothing at all:
 * no reset, no durability damage, no cooldown and no item consumption. It only
 * calls {@link #send(ServerPlayer, ServerLevel, BlockPos, ResetEngine.DefinitionLookup)},
 * which describes what a confirming click would do and arms the player for the
 * configured window. Nothing in this class ever runs the reset flow.</p>
 *
 * <p>Every line is a translation key so the same prompt works in every
 * language; the lines are joined with {@code "\n"} into a single component and
 * sent as one ordinary chat message ({@code displayClientMessage(..., false)}).</p>
 */
public final class ResetPrompt {

    /** Detected structure ids. */
    public static final String KEY_STRUCTURE =
            "item.structurereset.structure_reset_core.confirm.structure";
    /** No owning structure was detected. */
    public static final String KEY_STRUCTURE_NONE =
            "item.structurereset.structure_reset_core.confirm.structure.none";
    /** The structure is covered by the bundled mapping table. */
    public static final String KEY_LISTED =
            "item.structurereset.structure_reset_core.confirm.listed";
    /** The structure is not covered by the bundled mapping table. */
    public static final String KEY_UNLISTED =
            "item.structurereset.structure_reset_core.confirm.unlisted";
    /** The boss a confirming click would summon. */
    public static final String KEY_BOSS =
            "item.structurereset.structure_reset_core.confirm.boss";
    /** No boss would be summoned. */
    public static final String KEY_BOSS_NONE =
            "item.structurereset.structure_reset_core.confirm.boss.none";
    /** The boss comes from a data pack definition. */
    public static final String KEY_SOURCE_DATAPACK =
            "item.structurereset.structure_reset_core.confirm.source.datapack";
    /** The boss comes from a learning record. */
    public static final String KEY_SOURCE_LEARNED =
            "item.structurereset.structure_reset_core.confirm.source.learned";
    /** How many loot containers a confirming click would refill. */
    /** The loot line: how many containers the reset will refill. */
    public static final String KEY_CHESTS_NONE =
            "item.structurereset.structure_reset_core.confirm.chests.none";
    public static final String KEY_CHESTS =
            "item.structurereset.structure_reset_core.confirm.chests";
    /** The footer asking for the confirming click. */
    public static final String KEY_PROMPT =
            "item.structurereset.structure_reset_core.confirm.prompt";
    /** The existing "not right now" key, appended when the reset is not allowed. */
    public static final String KEY_NOT_READY =
            "item.structurereset.structure_reset_core.not_ready";

    private ResetPrompt() {
    }

    /**
     * Announces what a confirming click would do and arms the player when the
     * reset is allowed right now.
     *
     * <p>The player is only armed when
     * {@link StructureResetApi#canReset(ServerLevel, BlockPos, ServerPlayer, String, boolean)}
     * says the reset would run; otherwise the existing "not ready" line is
     * appended instead. A refusal reason reported by the lookup (the ambiguous
     * anchor case) is appended as one more line, and it never arms either
     * because there is no definition to arm for.</p>
     */
    public static void send(ServerPlayer player, ServerLevel level, BlockPos pos,
                            ResetEngine.DefinitionLookup lookup) {
        if (player == null || level == null || pos == null) {
            return;
        }
        ResetDefinition definition = lookup == null ? null : lookup.definition();
        Set<String> detected = ResetEngine.structureIdsAt(level, pos);
        List<Component> lines = new ArrayList<>();
        lines.add(structureLine(detected));
        lines.add(Component.translatable(listed(definition, detected) ? KEY_LISTED : KEY_UNLISTED)
                .withStyle(ChatFormatting.GRAY));
        lines.add(bossLine(definition));
        int chests = chestCount(level, pos, definition);
        lines.add(chests > 0
                ? Component.translatable(KEY_CHESTS, chests).withStyle(ChatFormatting.GRAY)
                : Component.translatable(KEY_CHESTS_NONE).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(KEY_PROMPT, StructureResetConfig.confirmWindowSeconds())
                .withStyle(ChatFormatting.GOLD));
        if (definition != null
                && StructureResetApi.canReset(level, pos, player, definition.id(), false)) {
            ResetConfirmation.arm(player, level, definition.id());
        } else if (definition != null) {
            lines.add(Component.translatable(KEY_NOT_READY).withStyle(ChatFormatting.RED));
        }
        if (lookup != null && lookup.messageKey() != null && !lookup.messageKey().isBlank()) {
            lines.add(Component.translatable(lookup.messageKey()).withStyle(ChatFormatting.RED));
        }
        player.displayClientMessage(join(lines), false);
    }

    /** The detected structure ids, or the "none detected" line. */
    private static Component structureLine(Set<String> detected) {
        if (detected.isEmpty()) {
            return Component.translatable(KEY_STRUCTURE_NONE).withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(KEY_STRUCTURE, String.join(", ", detected))
                .withStyle(ChatFormatting.GRAY);
    }

    /** The boss a confirming click would summon, with where that boss comes from. */
    private static Component bossLine(ResetDefinition definition) {
        String boss = definition == null ? null : definition.respawnBossEntity();
        if (boss == null || boss.isBlank()) {
            return Component.translatable(KEY_BOSS_NONE).withStyle(ChatFormatting.GRAY);
        }
        Component source = Component.translatable(
                StructureResetRegistry.isDatapackDefinition(definition.id())
                        ? KEY_SOURCE_DATAPACK : KEY_SOURCE_LEARNED);
        return Component.translatable(KEY_BOSS, boss, source).withStyle(ChatFormatting.GRAY);
    }

    /**
     * True when the bundled mapping table covers this position.
     *
     * <p>That is the case when the selected definition came from a data pack,
     * or when any registered definition names one of the detected structure ids
     * exactly (a tag reference alone does not count: it is not an exact match of
     * the id that was found).</p>
     */
    static boolean listed(ResetDefinition definition, Set<String> detected) {
        // Anything the lookup resolved means the mod knows this structure. That
        // includes a structure record the player scanned or recorded by hand, which
        // used to fall through to the registered definition scan below and be
        // reported as "not covered" even though it was. The line above already says
        // which of the two it was, so no information is lost by answering yes here.
        if (definition != null) {
            return true;
        }
        if (detected == null || detected.isEmpty()) {
            return false;
        }
        for (ResetDefinition registered : StructureResetRegistry.definitions()) {
            for (StructureRef ref : registered.structures()) {
                for (String id : detected) {
                    if (ref.matchesId(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * How many loot containers a confirming click would refill.
     *
     * <p>Every {@code structurereset:refill_loot} action contributes the number
     * of containers inside the definition box that it would touch, and a
     * {@code structurereset:restore_learned_anchor} action contributes the
     * number of containers its learning record remembers. A definition with no
     * such action reports zero. Nothing here is written back to the world.</p>
     */
    static int chestCount(ServerLevel level, BlockPos pos, ResetDefinition definition) {
        if (level == null || pos == null || definition == null) {
            return 0;
        }
        AABB box = ResetEngine.boxFor(definition, pos);
        int total = 0;
        for (ResetActionSpec spec : definition.actions()) {
            if (spec == null || spec.type() == null) {
                continue;
            }
            JsonObject params = spec.params();
            if (RefillLootAction.ID.toString().equals(spec.type())) {
                boolean emptyOnly = !"all".equalsIgnoreCase(stringParam(params, "mode", "empty_only"));
                Set<String> lootTables = new LinkedHashSet<>(stringListParam(params, "loot_tables"));
                total += RefillLootAction.previewCount(level, box, emptyOnly, lootTables);
            } else if (RestoreLearnedAnchorAction.ID.toString().equals(spec.type())) {
                LearnedAnchor anchor = LearnedAnchorData.get(level)
                        .byId(stringParam(params, LearnedAnchorService.PARAM_ANCHOR_ID, ""));
                if (anchor != null) {
                    total += anchor.containers().size();
                }
            }
        }
        return total;
    }

    /** One string parameter of an action, with the same semantics as {@code ResetContext}. */
    private static String stringParam(JsonObject params, String key, String fallback) {
        JsonPrimitive primitive = primitive(params, key);
        if (primitive == null || !primitive.isString()) {
            return fallback;
        }
        return primitive.getAsString();
    }

    /** Every string entry of an array parameter; empty when it is absent. */
    private static List<String> stringListParam(JsonObject params, String key) {
        JsonElement element = params == null ? null : params.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement entry : array) {
            if (entry != null && entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                values.add(entry.getAsString());
            }
        }
        return values;
    }

    private static JsonPrimitive primitive(JsonObject params, String key) {
        JsonElement element = params == null || key == null ? null : params.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsJsonPrimitive();
    }

    /** Joins the lines with {@code "\n"} into the single component that is sent. */
    private static MutableComponent join(List<Component> lines) {
        MutableComponent joined = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                joined.append(Component.literal("\n"));
            }
            joined.append(lines.get(index));
        }
        return joined;
    }
}
