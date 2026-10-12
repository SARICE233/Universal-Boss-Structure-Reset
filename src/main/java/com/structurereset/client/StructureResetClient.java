package com.structurereset.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.structurereset.StructureResetMod;
import com.structurereset.core.ItemAppearanceApi;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Client-only part of the addon: the item appearance overlay.
 *
 * <p>Every override registered through {@link ItemAppearanceApi} (including the
 * ones declared by {@code data/structurereset/item_appearance.json}) is applied
 * while the model bakery hands out its result: the inventory
 * {@link ModelResourceLocation} of the overridden item is re-pointed at the
 * baked model of the requested model id, so any item can be re-skinned by a
 * resource pack or a KubeJS pack without a new item registration.</p>
 */
@Mod.EventBusSubscriber(modid = StructureResetMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StructureResetClient {

    /** Classpath path of the data-driven default overrides. */
    public static final String APPEARANCE_RESOURCE = "/data/" + StructureResetMod.MODID + "/item_appearance.json";

    /** The model variant every item model is baked under. */
    private static final String INVENTORY_VARIANT = "inventory";

    /** Every model path of this mod that carries a glow texture. */
    private static final String CORE_MODEL = "structure_reset_core";

    /** Guards the one-time load of {@link #APPEARANCE_RESOURCE}. */
    private static boolean defaultsLoaded;

    private StructureResetClient() {
    }

    /**
     * True when the holding player may use the core right now.
     *
     * <p>Client side only; used by the tooltip of
     * {@code StructureResetItem}, which never references a client class itself.
     *
     * @param stack the stack being described
     * @param level the level the tooltip is built for, may be {@code null}
     * @return {@code true} when the local player holds an item that is not on cooldown
     */
    public static boolean isReady(ItemStack stack, Level level) {
        if (stack.isEmpty() || level == null || !level.isClientSide()) {
            return false;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return !player.getCooldowns().isOnCooldown(stack.getItem());
    }

    /**
     * Swaps the baked model of every overridden item for the requested model.
     *
     * @param event the Forge model baking event
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        loadDefaults();
        Map<Item, ResourceLocation> overrides = ItemAppearanceApi.modelOverrides();
        if (overrides.isEmpty()) {
            return;
        }
        // Forge types the baked registry by ResourceLocation, while the block and
        // item models inside it are keyed by their "#inventory" ModelResourceLocation
        Map<ResourceLocation, BakedModel> models = event.getModels();
        for (Map.Entry<Item, ResourceLocation> override : overrides.entrySet()) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(override.getKey());
            ResourceLocation targetId = override.getValue();
            if (itemId == null || targetId == null) {
                continue;
            }
            ModelResourceLocation from = new ModelResourceLocation(itemId, INVENTORY_VARIANT);
            ModelResourceLocation to = new ModelResourceLocation(targetId, INVENTORY_VARIANT);
            BakedModel replacement = models.get(to);
            if (replacement == null) {
                StructureResetMod.LOGGER.warn("[{}] appearance override of {} points at {} which is not a registered item with an inventory model; a model file path cannot be borrowed, keeping the original model",
                        StructureResetMod.MODID, itemId, targetId);
                continue;
            }
            models.put(from, replacement);
            StructureResetMod.LOGGER.debug("[{}] appearance override of {} -> {}", StructureResetMod.MODID, itemId, targetId);
        }
        addGlowPass(models);
    }

    /**
     * Gives this mod's own item models an emissive second pass.
     *
     * <p>Every variant is wrapped, because a {@code custom_model_data} override
     * resolves to a model of its own and would otherwise lose its glow. A pack
     * that does not ship the glow texture keeps the plain look: the pass is only
     * added when the texture is really there.</p>
     */
    private static void addGlowPass(Map<ResourceLocation, BakedModel> models) {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Function<ResourceLocation, TextureAtlasSprite> atlas =
                Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        Map<ResourceLocation, BakedModel> wrapped = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation id = entry.getKey();
            if (!StructureResetMod.MODID.equals(id.getNamespace())
                    || !id.getPath().contains(CORE_MODEL)) {
                continue;
            }
            ResourceLocation glow = glowFor(id);
            ResourceLocation file = new ResourceLocation(glow.getNamespace(),
                    "textures/" + glow.getPath() + ".png");
            if (resources.getResource(file).isEmpty()) {
                continue;
            }
            wrapped.put(id, new EmissiveItemModel(entry.getValue(), atlas.apply(glow)));
        }
        models.putAll(wrapped);
        if (!wrapped.isEmpty()) {
            StructureResetMod.LOGGER.debug("[{}] emissive glow pass on {} item model(s)",
                    StructureResetMod.MODID, wrapped.size());
        }
    }

    /** The glow texture of a model: the same path with a {@code _glow} suffix. */
    private static ResourceLocation glowFor(ResourceLocation model) {
        String path = model.getPath();
        return new ResourceLocation(model.getNamespace(),
                (path.startsWith("item/") ? path : "item/" + path) + "_glow");
    }

    /**
     * Reads {@code data/structurereset/item_appearance.json} once and feeds it
     * into {@link ItemAppearanceApi}. A missing or broken file only disables the
     * defaults, it never breaks model baking.
     */
    private static void loadDefaults() {
        if (defaultsLoaded) {
            return;
        }
        defaultsLoaded = true;
        try (InputStream stream = StructureResetClient.class.getResourceAsStream(APPEARANCE_RESOURCE)) {
            if (stream == null) {
                StructureResetMod.LOGGER.warn("[{}] {} is not present on the classpath; no default appearance overrides",
                        StructureResetMod.MODID, APPEARANCE_RESOURCE);
                return;
            }
            JsonElement root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject items = root.getAsJsonObject().getAsJsonObject("items");
            if (items == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                ResourceLocation itemId = ResourceLocation.tryParse(entry.getKey());
                ResourceLocation modelId = ResourceLocation.tryParse(modelNameOf(entry.getValue()));
                if (itemId == null || modelId == null) {
                    StructureResetMod.LOGGER.warn("[{}] ignoring malformed appearance entry {} -> {}",
                            StructureResetMod.MODID, entry.getKey(), entry.getValue());
                    continue;
                }
                Item item = ForgeRegistries.ITEMS.getValue(itemId);
                if (item == null || item == Items.AIR) {
                    StructureResetMod.LOGGER.warn("[{}] appearance entry {} names an item that is not registered",
                            StructureResetMod.MODID, itemId);
                    continue;
                }
                ItemAppearanceApi.setModelOverride(item, modelId);
            }
        } catch (Exception exception) {
            StructureResetMod.LOGGER.warn("[{}] could not read {}: {}",
                    StructureResetMod.MODID, APPEARANCE_RESOURCE, exception.toString());
        }
    }

    /** A plain string entry or a {"model": "..."} object. */
    private static String modelNameOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            JsonElement model = element.getAsJsonObject().get("model");
            if (model != null && model.isJsonPrimitive()) {
                return model.getAsString();
            }
        }
        return null;
    }
}
