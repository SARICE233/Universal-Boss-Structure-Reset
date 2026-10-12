package com.structurereset.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The script supplied boss filter of the learning feature.
 *
 * <p>When a filter is registered it is the <em>only</em> boss criterion: it is
 * consulted before the built in "maximum health or configured NBT key" test,
 * and its verdict alone decides whether a target may be taught. With no filter
 * registered the built in test applies, so a plain data pack or vanilla setup
 * behaves exactly as before.</p>
 *
 * <p>The filter receives one {@link JsonObject} describing the candidate (see
 * {@link #describe(LivingEntity)}): at least {@code id}, {@code max_health},
 * {@code health}, {@code nbt_keys} and {@code nbt}. It returns
 * {@code Boolean.TRUE} to accept the target. A filter that returns
 * {@code null}, returns anything else, or throws is treated as "not a boss",
 * and a broken filter can never make teaching fail with an exception.</p>
 */
public final class BossFilterApi {

    /** The registered filter, replaced as one immutable reference. */
    private static volatile Function<JsonObject, Boolean> filter;

    private BossFilterApi() {
    }

    /**
     * Registers (or replaces) the script boss filter.
     *
     * @param newFilter the filter, or {@code null} to clear it
     */
    public static void setBossFilter(Function<JsonObject, Boolean> newFilter) {
        filter = newFilter;
    }

    /** Removes the script boss filter, restoring the built in criterion. */
    public static void clearBossFilter() {
        filter = null;
    }

    /** True when a script boss filter is currently registered. */
    public static boolean hasFilter() {
        return filter != null;
    }

    /**
     * The registered filter.
     *
     * @return the filter, or {@code null} when none is registered
     */
    public static Function<JsonObject, Boolean> current() {
        return filter;
    }

    /**
     * Hands a candidate to the registered filter.
     *
     * <p>A throwing filter is reported as "not a boss" instead of propagating:
     * a broken script must never break teaching.</p>
     *
     * @param candidate the JSON description of the candidate, see
     *        {@link #describe(LivingEntity)}
     * @return {@code null} when no filter is registered, otherwise whether the
     *         filter accepted the candidate
     */
    public static Boolean test(JsonObject candidate) {
        Function<JsonObject, Boolean> current = filter;
        if (current == null) {
            return null;
        }
        try {
            return Boolean.TRUE.equals(current.apply(candidate));
        } catch (RuntimeException failure) {
            return Boolean.FALSE;
        }
    }

    /**
     * The JSON description handed to a registered filter.
     *
     * <p>The object carries the entity registry id, its maximum and current
     * health, the top level keys of its NBT and that NBT rendered as a string.
     * A target whose NBT cannot be read still yields a description: the two NBT
     * fields are then empty instead of throwing.</p>
     *
     * @param target the candidate entity, may be null
     * @return the description, never null
     */
    public static JsonObject describe(LivingEntity target) {
        JsonObject json = new JsonObject();
        JsonArray keys = new JsonArray();
        if (target == null) {
            json.addProperty("id", "");
            json.addProperty("max_health", 0.0D);
            json.addProperty("health", 0.0D);
            json.add("nbt_keys", keys);
            json.addProperty("nbt", "");
            return json;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        json.addProperty("id", id == null ? "" : id.toString());
        json.addProperty("max_health", (double) target.getMaxHealth());
        json.addProperty("health", (double) target.getHealth());
        CompoundTag saved = null;
        try {
            saved = target.saveWithoutId(new CompoundTag());
        } catch (RuntimeException ignored) {
            saved = null;
        }
        if (saved != null) {
            for (String key : saved.getAllKeys()) {
                keys.add(key);
            }
        }
        json.add("nbt_keys", keys);
        json.addProperty("nbt", saved == null ? "" : saved.toString());
        return json;
    }
}
