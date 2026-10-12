package com.structurereset.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws an item twice: once normally, and once more with its glow texture on an
 * emissive render type.
 *
 * <p>That second pass is what makes the item look self lit. Vanilla has no
 * emissive item textures at all - the OptiFine style {@code _e} suffix is a
 * block, entity and armour feature and does nothing for an item - but Forge lets
 * a baked model hand out several render passes, and
 * {@link RenderType#entityTranslucentEmissive} ignores the light map. The glow
 * texture carries the pixels that should shine and is fully transparent
 * everywhere else, so only that part of the item lights up.</p>
 */
final class EmissiveItemModel implements BakedModel {

    private final BakedModel base;
    private final BakedModel glow;

    EmissiveItemModel(BakedModel base, TextureAtlasSprite glowSprite) {
        this.base = base;
        this.glow = new GlowLayer(base, glowSprite);
    }

    /** The normal pass followed by the glowing one. */
    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        return List.of(this.base, this.glow);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
        return this.base.getQuads(state, direction, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return this.base.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return this.base.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return this.base.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return this.base.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.base.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return this.base.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return this.base.getOverrides();
    }

    /** The same geometry, re-pointed at the glow texture and drawn unlit. */
    private static final class GlowLayer implements BakedModel {

        private final BakedModel base;
        private final TextureAtlasSprite sprite;

        GlowLayer(BakedModel base, TextureAtlasSprite sprite) {
            this.base = base;
            this.sprite = sprite;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
            List<BakedQuad> source = this.base.getQuads(state, direction, random);
            if (source.isEmpty()) {
                return source;
            }
            List<BakedQuad> out = new ArrayList<>(source.size());
            for (BakedQuad quad : source) {
                out.add(new BakedQuad(quad.getVertices(), quad.getTintIndex(), quad.getDirection(),
                        this.sprite, quad.isShade(), quad.hasAmbientOcclusion()));
            }
            return out;
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return List.of(RenderType.entityTranslucentEmissive(TextureAtlas.LOCATION_BLOCKS));
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return this.base.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return false;
        }

        @Override
        public boolean isCustomRenderer() {
            return this.base.isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return this.base.getParticleIcon();
        }

        @Override
        public ItemTransforms getTransforms() {
            return this.base.getTransforms();
        }

        @Override
        public ItemOverrides getOverrides() {
            return this.base.getOverrides();
        }
    }
}
