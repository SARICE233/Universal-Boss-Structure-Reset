package com.structurereset.impl;

import com.structurereset.config.StructureResetConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The summoning spectacle of the cast, chosen by dimension.
 *
 * <ul>
 *   <li><b>overworld</b> - white and pale blue particles spiral upwards around the
 *       spot while the cast runs;</li>
 *   <li><b>nether</b> - embers rise during the cast and a visual only lightning
 *       bolt strikes the spot as the boss appears;</li>
 *   <li><b>any other dimension</b> - three block types taken from the structure
 *       itself orbit the spot as real block entities, inside a whirlwind made of
 *       the conduit swirl and churning cloud, never of rising smoke.</li>
 * </ul>
 *
 * <p>Everything is server side: the particles are broadcast and the block
 * displays are ordinary entities, so there is no client code and no packet of our
 * own. The block types are picked once per cast, from loaded chunks only, so
 * picking them can never stall the server thread.</p>
 */
public final class CastEffect {

    /** The overworld style: spiralling white and blue particles. */
    public static final int STYLE_OVERWORLD = 0;
    /** The nether style: a lightning strike. */
    public static final int STYLE_NETHER = 1;
    /** Every other dimension: blocks of the structure in a whirlwind. */
    public static final int STYLE_OTHER = 2;

    /** How many block types an "other dimension" cast picks. */
    public static final int PICKED_BLOCKS = 3;

    /** How coarse the sampling of the structure's blocks is. */
    private static final int SAMPLE_LIMIT = 12;

    /** How far the displayed blocks orbit the spot. */
    private static final double ORBIT_RADIUS = 2.0D;

    private static final ParticleOptions BLUE =
            new DustParticleOptions(new Vector3f(0.35F, 0.62F, 1.0F), 1.6F);
    private static final ParticleOptions WHITE =
            new DustParticleOptions(new Vector3f(0.95F, 0.97F, 1.0F), 1.4F);

    /** What one running cast owns: the blocks it picked and the entities showing them. */
    private static final class Cast {
        private final List<BlockState> blocks = new ArrayList<>();
        private final List<Display.BlockDisplay> shown = new ArrayList<>();
    }

    private static final Map<UUID, Cast> CASTS = new HashMap<>();

    private CastEffect() {
    }

    /** The style of {@code level}. */
    public static int styleOf(ServerLevel level) {
        if (level == null) {
            return STYLE_OTHER;
        }
        ResourceKey<Level> dimension = level.dimension();
        if (Level.OVERWORLD.equals(dimension)) {
            return STYLE_OVERWORLD;
        }
        if (Level.NETHER.equals(dimension)) {
            return STYLE_NETHER;
        }
        return STYLE_OTHER;
    }

    /** Starts a cast: picks the structure's blocks and shows them when the style needs them. */
    public static void begin(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player == null || level == null || pos == null) {
            return;
        }
        clear(player);
        Cast cast = new Cast();
        CASTS.put(player.getUUID(), cast);
        if (styleOf(level) != STYLE_OTHER) {
            return;
        }
        cast.blocks.addAll(pickBlocks(level, pos));
        for (int index = 0; index < cast.blocks.size(); index++) {
            Display.BlockDisplay display = spawnBlock(level, cast.blocks.get(index));
            if (display == null) {
                continue;
            }
            place(display, level, pos, index, cast.blocks.size(), 0.0D);
            cast.shown.add(display);
        }
    }

    /**
     * Spawns one block display showing {@code state}.
     *
     * <p>The display is built from its own save data, because the block state
     * setter of {@code BlockDisplay} is not public in this mapping while the NBT
     * path is. It carries no gravity and no collision, so it only ever moves
     * where this class puts it.</p>
     */
    private static Display.BlockDisplay spawnBlock(ServerLevel level, BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("Invulnerable", true);
        if (!(EntityType.loadEntityRecursive(tag, level, entity -> entity)
                instanceof Display.BlockDisplay display)) {
            return null;
        }
        display.setNoGravity(true);
        return level.addFreshEntity(display) ? display : null;
    }

    /**
     * Releases every running cast and every block display it owns.
     *
     * <p>Used when the server stops: a cast that is interrupted mid flight would
     * otherwise leave its {@code block_display} entities in the save file, floating
     * over the structure until somebody removes them by hand.</p>
     */
    public static void clearAll() {
        for (Cast cast : new ArrayList<>(CASTS.values())) {
            for (Display.BlockDisplay display : cast.shown) {
                display.discard();
            }
            cast.shown.clear();
        }
        CASTS.clear();
    }

    /** Forgets a cast and removes the blocks it was showing. */
    public static void clear(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Cast cast = CASTS.remove(player.getUUID());
        if (cast == null) {
            return;
        }
        for (Display.BlockDisplay display : cast.shown) {
            display.discard();
        }
        cast.shown.clear();
    }

    /**
     * One frame of the running cast.
     *
     * @param progress how far the cast is, 0 at its start and 1 at its end
     */
    public static void tick(ServerPlayer player, ServerLevel level, BlockPos pos, double progress) {
        if (level == null || pos == null) {
            return;
        }
        double clamped = Math.max(0.0D, Math.min(1.0D, progress));
        switch (styleOf(level)) {
            case STYLE_OVERWORLD -> spiralUp(level, pos, clamped);
            case STYLE_NETHER -> embers(level, pos, clamped);
            default -> whirlwind(player, level, pos, clamped);
        }
    }

    /** The final flourish, played as the boss appears. */
    public static void finish(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        switch (styleOf(level)) {
            case STYLE_NETHER -> strike(level, pos);
            case STYLE_OTHER -> {
                double cx = pos.getX() + 0.5D;
                double cz = pos.getZ() + 0.5D;
                // The shockwave of the wind, then the arcs it throws off.
                level.sendParticles(ParticleTypes.SONIC_BOOM, cx, pos.getY() + 0.8D, cz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                for (int ring = 0; ring < 8; ring++) {
                    double angle = ring * (Math.PI * 2.0D / 8.0D);
                    level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                            cx + Math.cos(angle) * 1.2D, pos.getY() + 0.7D,
                            cz + Math.sin(angle) * 1.2D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                }
                level.sendParticles(ParticleTypes.POOF, cx, pos.getY() + 0.5D, cz, 12, 0.5D, 0.3D, 0.5D, 0.03D);
            }
            default -> {
                level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5D, pos.getY() + 1.0D,
                        pos.getZ() + 0.5D, 24, 0.4D, 0.8D, 0.4D, 0.05D);
                level.sendParticles(BLUE, pos.getX() + 0.5D, pos.getY() + 1.0D,
                        pos.getZ() + 0.5D, 24, 0.4D, 0.8D, 0.4D, 0.05D);
            }
        }
    }

    /** How long a cast runs, in ticks, from the configuration. */
    public static int castTicks() {
        return Math.max(20, StructureResetConfig.aimCastTicks());
    }

    // ------------------------------------------------------------- styles

    /** White and pale blue particles, spiralling upwards as the cast advances. */
    private static void spiralUp(ServerLevel level, BlockPos pos, double progress) {
        int arms = 3;
        for (int arm = 0; arm < arms; arm++) {
            double angle = level.getGameTime() * 0.28D + arm * (Math.PI * 2.0D / arms);
            double radius = 0.5D + progress * 0.7D;
            double y = pos.getY() + 0.2D + progress * 3.2D;
            double x = pos.getX() + 0.5D + Math.cos(angle) * radius;
            double z = pos.getZ() + 0.5D + Math.sin(angle) * radius;
            level.sendParticles(WHITE, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            level.sendParticles(BLUE, x, y - 0.25D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5D, pos.getY() + 0.3D + progress * 3.0D,
                pos.getZ() + 0.5D, 1, 0.15D, 0.05D, 0.15D, 0.0D);
    }

    /** Embers rising towards the spot, where the bolt will land. */
    private static void embers(ServerLevel level, BlockPos pos, double progress) {
        level.sendParticles(ParticleTypes.SMALL_FLAME, pos.getX() + 0.5D, pos.getY() + progress * 2.5D,
                pos.getZ() + 0.5D, 3, 0.35D, 0.1D, 0.35D, 0.01D);
        level.sendParticles(ParticleTypes.LAVA, pos.getX() + 0.5D, pos.getY() + 0.2D,
                pos.getZ() + 0.5D, 1, 0.3D, 0.0D, 0.3D, 0.0D);
    }

    /**
     * The whirlwind: the structure's own blocks circling the spot, carried by a
     * churning ring of cloud and the spiralling conduit particle.
     *
     * <p>The ring is given a tangential velocity, so the cloud really is dragged
     * around the circle instead of drifting upwards, which is what a plain smoke
     * puff does and what this effect deliberately avoids.</p>
     */
    private static void whirlwind(ServerPlayer player, ServerLevel level, BlockPos pos, double progress) {
        Cast cast = player == null ? null : CASTS.get(player.getUUID());
        if (cast != null) {
            for (int index = 0; index < cast.shown.size(); index++) {
                place(cast.shown.get(index), level, pos, index, cast.shown.size(), progress);
            }
        }
        long time = level.getGameTime();
        double cx = pos.getX() + 0.5D;
        double cz = pos.getZ() + 0.5D;
        double cy = pos.getY() + 0.15D;
        int arms = 10;
        double radius = 1.35D + progress * 0.45D;
        for (int arm = 0; arm < arms; arm++) {
            double angle = time * 0.5D + arm * (Math.PI * 2.0D / arms);
            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;
            double vx = -Math.sin(angle) * 0.35D;
            double vz = Math.cos(angle) * 0.35D;
            level.sendParticles(ParticleTypes.CLOUD, x, cy + 0.1D, z, 1, vx, 0.0D, vz, 0.0D);
        }
        // The conduit swirl already spirals outwards on its own, so it supplies the
        // lift that the cloud ring deliberately does not have.
        level.sendParticles(ParticleTypes.NAUTILUS, cx, cy + 0.6D + progress * 1.1D, cz,
                2, 0.15D, 0.15D, 0.15D, 0.02D);
        if (time % 4L == 0L) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, cx, cy + 0.9D, cz, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (time % 5L == 0L) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cx, cy + 0.5D, cz,
                    2, 0.6D, 0.3D, 0.6D, 0.05D);
        }
    }

    /** Puts one displayed block on its orbit, spinning as it goes. */
    private static void place(Display.BlockDisplay display, ServerLevel level, BlockPos pos,
                              int index, int count, double progress) {
        double angle = level.getGameTime() * 0.22D + index * (Math.PI * 2.0D / Math.max(1, count));
        double radius = ORBIT_RADIUS - progress * 0.8D;
        double y = pos.getY() + 0.6D + progress * 1.2D + Math.sin(angle * 1.5D) * 0.35D;
        display.setPos(pos.getX() + 0.5D + Math.cos(angle) * radius, y,
                pos.getZ() + 0.5D + Math.sin(angle) * radius);
        // The entity's own yaw turns the block, so it spins while it orbits.
        display.setYRot((float) Math.toDegrees(angle));
        display.setXRot(-12.0F);
    }

    /** A visual only bolt, so the strike never starts a fire or hurts anyone. */
    private static void strike(ServerLevel level, BlockPos pos) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(Vec3.atBottomCenterOf(pos));
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    /**
     * Up to {@link #PICKED_BLOCKS} distinct block types of the structure.
     *
     * <p>Only chunks that are already loaded are read, and only every
     * {@code stride}-th block of the box, so the pick is bounded work and can
     * never block the server thread on a chunk load.</p>
     */
    public static List<BlockState> pickBlocks(ServerLevel level, BlockPos pos) {
        Optional<BoundingBox> box = ResetEngine.structureBoxAt(level, pos);
        if (box.isEmpty()) {
            return List.of();
        }
        BoundingBox area = box.get();
        int span = Math.max(area.getXSpan(), Math.max(area.getYSpan(), area.getZSpan()));
        int stride = Math.max(1, span / SAMPLE_LIMIT);
        List<BlockState> found = new ArrayList<>();
        for (int x = area.minX(); x <= area.maxX() && found.size() < PICKED_BLOCKS; x += stride) {
            for (int y = area.minY(); y <= area.maxY() && found.size() < PICKED_BLOCKS; y += stride) {
                for (int z = area.minZ(); z <= area.maxZ() && found.size() < PICKED_BLOCKS; z += stride) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) {
                        continue;
                    }
                    BlockPos sample = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(sample);
                    if (state.isAir() || !state.isCollisionShapeFullBlock(level, sample)) {
                        continue;
                    }
                    boolean known = false;
                    for (BlockState seen : found) {
                        if (seen.is(state.getBlock())) {
                            known = true;
                            break;
                        }
                    }
                    if (!known) {
                        found.add(state);
                    }
                }
            }
        }
        return found;
    }
}
