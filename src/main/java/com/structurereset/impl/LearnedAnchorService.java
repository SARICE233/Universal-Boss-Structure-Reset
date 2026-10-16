package com.structurereset.impl;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.structurereset.api.LearnedAnchor;
import com.structurereset.api.LearnedAnchorSelector;
import com.structurereset.api.LearnedContainer;
import com.structurereset.api.ResetActionSpec;
import com.structurereset.api.ResetDefinition;
import com.structurereset.api.ResetMode;
import com.structurereset.api.ResetTrigger;
import com.structurereset.api.StructureRef;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.core.BossFilterApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * The learning/teaching feature: a player sneaks and uses the reset core on a
 * mob, and everything that makes the place reset-able again - the structure the
 * mob stands in, the boss entity and the still unlooted containers around it -
 * is remembered as one {@link LearnedAnchor}.
 *
 * <p>The service is completely stateless: the durable memory lives in
 * {@link LearnedAnchorData}, the selection rules in the pure
 * {@link LearnedAnchorSelector} and the matching of a position to an anchor
 * lives here.  Everything is static, exactly like the rest of the engine, so
 * the item, the command and the KubeJS bindings can all call into it.</p>
 *
 * <p>A learned anchor is turned back into a normal {@link ResetDefinition} with
 * {@link #definitionFor(LearnedAnchor)}: the definition carries the recorded
 * structure ids and radius, is {@code GLOBAL} and {@code EITHER} triggered and
 * runs exactly one action, {@link #ACTION_RESTORE}, whose {@code anchor_id}
 * parameter points back at the stored record.  That way the whole existing
 * engine - cooldown, events, item consumption, KubeJS - replays a learned
 * anchor without any special case.</p>
 */
public final class LearnedAnchorService {

    /** The action type a learned anchor replays through. */
    public static final String ACTION_RESTORE = "structurereset:restore_learned_anchor";

    /** The parameter name of the learned anchor id. */
    public static final String PARAM_ANCHOR_ID = "anchor_id";

    /** The structure reference a learned anchor without any structure id falls back to. */
    public static final String PLACEHOLDER_STRUCTURE = "structurereset:learned_anchor";

    /** How close a new teaching may be to an existing record of the same boss before it updates it. */
    public static final int DUPLICATE_RADIUS = 8;

    /** Translation key of a successful teaching. */
    public static final String MESSAGE_RECORDED = "structurereset.message.learning.recorded";
    /** Translation key used when the learning feature is switched off. */
    public static final String MESSAGE_DISABLED = "structurereset.message.learning.disabled";
    /** Translation key used when teaching requires operator permissions. */
    public static final String MESSAGE_NOT_OP = "structurereset.message.learning.not_op";
    /** Translation key used when the teaching player stands inside no structure. */
    public static final String MESSAGE_NO_STRUCTURE = "structurereset.message.learning.no_structure";
    /** Translation key used when the teaching player does not look at the target. */
    public static final String MESSAGE_NOT_LOOKING = "structurereset.message.learning.not_looking";
    /** Translation key used when the target's namespace is excluded from teaching. */
    public static final String MESSAGE_EXCLUDED_NAMESPACE =
            "structurereset.message.learning.excluded_namespace";
    /** Translation key used when the target is on the teaching deny list. */
    public static final String MESSAGE_DENIED = "structurereset.message.learning.denied";
    /**
     * Translation key used when the target matched no boss criterion at all: no
     * script filter accepted it, it is not on the allow list, it carries no boss
     * entity tag, no configured boss NBT key and no entry in the optional Boss
     * Checklist list. Every automatic path treats it as "not a boss"; only an
     * explicit designation records it, and that reply
     * is {@link #MESSAGE_UNRECOGNISED}.
     */
    public static final String MESSAGE_TOO_WEAK = "structurereset.message.learning.too_weak";
    /**
     * Translation key of the warning that an explicit designation recorded a mob
     * no boss criterion recognised. It takes the entity id of the target.
     */
    public static final String MESSAGE_UNRECOGNISED = "structurereset.message.learning.unrecognised";
    /** Translation key of a record registered by the administrative teach-here command. */
    public static final String MESSAGE_ADMIN_RECORDED =
            "structurereset.message.learning.admin_recorded";
    /** Translation key used when the administrative command names an unknown entity id. */
    public static final String MESSAGE_UNKNOWN_ENTITY = "structurereset.message.learning.unknown_entity";
    /** Translation key used when a record is bound by radius instead of by a structure. */
    public static final String MESSAGE_BOUND_BY_RADIUS =
            "structurereset.message.learning.bound_by_radius";
    /** Translation key used when the teaching could not be recorded at all. */
    public static final String MESSAGE_FAILED = "structurereset.message.learning.failed";

    private static final Logger LOGGER = LogUtils.getLogger();

    private LearnedAnchorService() {
    }

    /**
     * The outcome of one teaching attempt.
     *
     * @param success true when an anchor was recorded
     * @param messageKey the translation key of the message, never null
     * @param message the message to show the player, never null
     * @param anchorId the id of the recorded anchor, or an empty string on failure
     * @param containerCount how many containers were recorded
     * @param structureCount how many structure ids were recorded
     * @param noLootTableCount how many containers were seen without a usable
     *        loot table (already looted, or filled by hand); they are counted
     *        for the message but deliberately not stored as learned containers
     */
    public record TeachResult(boolean success, String messageKey, Component message, String anchorId,
                              int containerCount, int structureCount, int noLootTableCount) {
    }

    // ------------------------------------------------------------- teach

    /**
     * Records everything {@code target} stands for, through the strict item
     * path.
     *
     * <p>This is the entry point of the reset core: on top of the shared checks
     * it requires the teaching player to stand inside a recognised structure and
     * to actually look at the target. A refusal reports
     * {@link #MESSAGE_NO_STRUCTURE} or {@link #MESSAGE_NOT_LOOKING}.</p>
     *
     * <p>The boss criterion is the layered classification of {@link #classify}:
     * a registered script filter ({@code com.structurereset.core.BossFilterApi})
     * decides alone when one exists, then the allow list, the excluded
     * namespaces and the deny list apply, then the boss entity tags named by
     * {@code learningBossTags}, then the optional
     * {@code learningBossNbtKeys} and finally the boss id list of the optional
     * Boss Checklist soft dependency ({@link BossChecklistBridge}).</p>
     *
     * <p>An explicit designation wins over that classification: pointing the core
     * at a living entity and sneaking is a deliberate act, so a target that
     * matched no boss criterion is recorded all the same. Its reply is then
     * {@link #MESSAGE_UNRECOGNISED}, a warning that names the entity and says
     * that it matched no boss tag, while a recognised target keeps
     * {@link #MESSAGE_RECORDED}. What a health guess used to decide is now
     * decided by the player.</p>
     *
     * @param player the player doing the teaching, required
     * @param target the mob that is being taught, required
     * @return the result, never null
     */
    public static TeachResult teach(ServerPlayer player, LivingEntity target) {
        if (player == null || target == null) {
            return failure(MESSAGE_FAILED, "a teaching attempt needs a player and a target");
        }
        if (!StructureResetConfig.learningEnabled()) {
            return failure(MESSAGE_DISABLED, "the learning feature is disabled in the configuration");
        }
        if (StructureResetConfig.learningRequireOp() && !player.hasPermissions(2)) {
            return failure(MESSAGE_NOT_OP, "teaching requires operator permissions");
        }
        if (!target.isAlive()) {
            return failure(MESSAGE_FAILED, "the target is not alive");
        }
        if (StructureResetConfig.learningRequireStructure()
                && !standsInsideStructure(player, target)) {
            LOGGER.info("[{}] teaching refused: the player stands inside no structure (player={})",
                    StructureResetConfig.MOD_ID, player.getName().getString());
            return failure(MESSAGE_NO_STRUCTURE, "the player does not stand inside a structure");
        }
        if (!isLookingAt(player, target, StructureResetConfig.learningMaxAngleDegrees())) {
            LOGGER.info("[{}] teaching refused: the player does not look at the target (player={})",
                    StructureResetConfig.MOD_ID, player.getName().getString());
            return failure(MESSAGE_NOT_LOOKING, "the player is not looking at the target");
        }
        // The entity classification, in the documented order: a registered
        // script filter decides alone, then the allow list, the excluded
        // namespaces, the deny list, the boss entity tags, the optional boss NBT
        // keys and the optional Boss Checklist list. It runs on every entry point - only the "stand inside a
        // structure" and "look at the target" gates are specific to the item
        // path. A rejection does not refuse the teaching: the player pointed at
        // this mob on purpose, so the explicit designation wins and the mob is
        // recorded anyway, with a warning instead of the ordinary reply.
        String rejection = classify(target);
        boolean unrecognised = rejection != null;
        if (unrecognised) {
            LOGGER.info("[{}] teaching {} anyway: it matched no boss criterion ({})",
                    StructureResetConfig.MOD_ID, entityIdOf(target), rejection);
        }
        if (!(target.level() instanceof ServerLevel level)) {
            return failure(MESSAGE_FAILED, "the target is not in a server level");
        }
        ResourceLocation bossKey = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (bossKey == null) {
            return failure(MESSAGE_FAILED, "the target entity type has no registry id");
        }
        String bossEntity = bossKey.toString();
        BlockPos pos = target.blockPosition();
        String dimension = level.dimension().location().toString();
        Set<String> structureIds = ResetEngine.structureIdsAt(level, pos);
        int radius = StructureResetConfig.learningRadius();
        // The scan covers the structure the boss stands in when the structure
        // manager knows one: the union of the containing StructureStart bounding
        // boxes is the real extent of a generated structure, whatever its size.
        // Only a hand built arena - a position inside no structure at all - falls
        // back to the configured cube around the boss.
        Optional<BoundingBox> structureBox = ResetEngine.structureBoxAt(level, pos);
        ContainerScan scan = scanContainers(level, pos, radius, structureBox.orElse(null));
        LearnedAnchorData data = LearnedAnchorData.get(level);
        List<LearnedAnchor> existing = data.all();
        LearnedAnchor duplicate = duplicateOf(existing, dimension, bossEntity, pos);
        String id = duplicate == null ? LearnedAnchorData.nextId(existing) : duplicate.id();
        String learnedBy = player.getGameProfile() == null ? "" : player.getGameProfile().getName();
        LearnedAnchor anchor = new LearnedAnchor(id, dimension, new ArrayList<>(structureIds), bossEntity,
                pos.getX(), pos.getY(), pos.getZ(), (double) radius, scan.containers(),
                level.getGameTime(), learnedBy);
        data.add(anchor);
        String structuresText = structureIds.isEmpty() ? "-" : String.join(", ", structureIds);
        if (unrecognised) {
            return new TeachResult(true, MESSAGE_UNRECOGNISED,
                    Component.translatable(MESSAGE_UNRECOGNISED, bossEntity), id,
                    scan.containers().size(), structureIds.size(), scan.noLootTableCount());
        }
        Component message = Component.translatable(MESSAGE_RECORDED, bossEntity,
                scan.containers().size(), structuresText, scan.noLootTableCount());
        return new TeachResult(true, MESSAGE_RECORDED, message, id, scan.containers().size(),
                structureIds.size(), scan.noLootTableCount());
    }

    // ------------------------------------------------------ admin path

    /**
     * The administrative twin of {@link #teach(ServerPlayer, LivingEntity)},
     * used by {@code /structurereset records teach-here <entity_id>}.
     *
     * <p>An operator who explicitly names the boss entity does not have to look
     * at it and does not have to stand inside a structure this mod can see, so
     * exactly those two gates are skipped. Everything else is identical to the
     * item path: the enable switch, the "is that a living entity" test and the
     * whole entity classification (the script filter, the allow list, the
     * excluded namespaces, the deny list, the boss tags and the NBT keys) still
     * run. Naming a boss that no tag knows is an explicit designation just like
     * the item path, so it is recorded too - with {@link #MESSAGE_UNRECOGNISED}
     * instead of {@link #MESSAGE_ADMIN_RECORDED} - and only an id the registry
     * does not know, or a target that is not a living mob at all, is refused.</p>
     *
     * <p>The record itself is exactly the one the item path writes - same
     * dimension, same boss entity id, the operator's block position as the
     * anchor point, the structure ids that could be read there (an empty list
     * binds the record by {@code learningRadius} instead) and the scanned loot
     * containers - so it stays replayable by a right click afterwards. Nothing
     * here ever consumes durability.</p>
     *
     * @param player the operator doing the registration, required
     * @param entityId the entity type id to register, e.g. {@code cataclysm:the_harbinger}
     * @return the result, never null
     */
    public static TeachResult teachEntity(ServerPlayer player, String entityId) {
        if (player == null) {
            return failure(MESSAGE_FAILED, "a teaching attempt needs a player");
        }
        if (!StructureResetConfig.learningEnabled()) {
            return failure(MESSAGE_DISABLED, "the learning feature is disabled in the configuration");
        }
        ResourceLocation id = entityId == null ? null : ResourceLocation.tryParse(entityId.trim());
        EntityType<?> type = id == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) {
            LOGGER.info("[{}] admin teaching refused: unknown entity id {}", StructureResetConfig.MOD_ID,
                    entityId);
            return failure(MESSAGE_UNKNOWN_ENTITY, "unknown entity id '" + entityId + "'");
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return failure(MESSAGE_FAILED, "the player is not in a server level");
        }
        // A named target that is not a mob at all - an item, a projectile, a
        // marker - is still refused: an explicit designation names a living
        // entity, never something that can never be a boss.
        if (!createsLivingEntity(level, type)) {
            LOGGER.info("[{}] admin teaching refused: {} is not a living entity",
                    StructureResetConfig.MOD_ID, id);
            return failure(MESSAGE_TOO_WEAK, "the target is not a living entity");
        }
        // Only the two positional gates are skipped here: the classification is
        // the same one the item path runs and, exactly as there, a rejection
        // does not refuse the teaching - the operator named this entity on
        // purpose, so it is recorded anyway with a warning.
        String rejection = classifyType(level, type);
        boolean unrecognised = rejection != null;
        if (unrecognised) {
            LOGGER.info("[{}] admin teaching {} anyway: it matched no boss criterion ({})",
                    StructureResetConfig.MOD_ID, id, rejection);
        }
        BlockPos point = player.blockPosition();
        Recorded recorded = record(level, player, id.toString(), point);
        if (unrecognised) {
            return new TeachResult(true, MESSAGE_UNRECOGNISED,
                    Component.translatable(MESSAGE_UNRECOGNISED, id.toString()), recorded.anchorId(),
                    recorded.containers().size(), recorded.structureIds().size(),
                    recorded.noLootTableCount());
        }
        Component structures = recorded.structureIds().isEmpty()
                ? Component.translatable(MESSAGE_BOUND_BY_RADIUS, recorded.radius())
                : Component.literal(String.join(", ", recorded.structureIds()));
        Component message = Component.translatable(MESSAGE_ADMIN_RECORDED, id.toString(), structures,
                recorded.containers().size(), recorded.noLootTableCount(),
                point.getX(), point.getY(), point.getZ(), recorded.radius());
        LOGGER.info("[{}] admin teaching recorded {} at {} {} {} (structures={} containers={} radius={})",
                StructureResetConfig.MOD_ID, id, point.getX(), point.getY(), point.getZ(),
                recorded.structureIds(), recorded.containers().size(), recorded.radius());
        return new TeachResult(true, MESSAGE_ADMIN_RECORDED, message, recorded.anchorId(),
                recorded.containers().size(), recorded.structureIds().size(), recorded.noLootTableCount());
    }

    /** What one recording produced, so each entry point can phrase its own reply. */
    private record Recorded(String anchorId, List<LearnedContainer> containers, int noLootTableCount,
                            Set<String> structureIds, BlockPos point, int radius) {
    }

    /**
     * Records one anchor at {@code pos}: the structure ids readable there, the
     * still unlooted containers inside the structure box (or the configured
     * radius cube when the position belongs to no structure) and the boss entity
     * id the caller decided on.
     */
    private static Recorded record(ServerLevel level, ServerPlayer player, String bossEntity, BlockPos pos) {
        Set<String> structureIds = ResetEngine.structureIdsAt(level, pos);
        int radius = StructureResetConfig.learningRadius();
        Optional<BoundingBox> structureBox = ResetEngine.structureBoxAt(level, pos);
        ContainerScan scan = scanContainers(level, pos, radius, structureBox.orElse(null));
        LearnedAnchorData data = LearnedAnchorData.get(level);
        List<LearnedAnchor> existing = data.all();
        String dimension = level.dimension().location().toString();
        LearnedAnchor duplicate = duplicateOf(existing, dimension, bossEntity, pos);
        String id = duplicate == null ? LearnedAnchorData.nextId(existing) : duplicate.id();
        String learnedBy = player.getGameProfile() == null ? "" : player.getGameProfile().getName();
        LearnedAnchor anchor = new LearnedAnchor(id, dimension, new ArrayList<>(structureIds), bossEntity,
                pos.getX(), pos.getY(), pos.getZ(), (double) radius, scan.containers(),
                level.getGameTime(), learnedBy);
        data.add(anchor);
        return new Recorded(id, scan.containers(), scan.noLootTableCount(), structureIds, pos, radius);
    }

    // ------------------------------------------------------ boss criteria

    /**
     * True when the teaching player really stands inside a structure.
     *
     * <p>The check reuses {@link ResetEngine#structureIdsAt(ServerLevel, BlockPos)},
     * so it is the same "which structure is this position inside of?" the reset
     * selection uses. The player's own level is preferred; the target's level is
     * the fallback, and no server level at all means "not inside a structure".</p>
     */
    private static boolean standsInsideStructure(ServerPlayer player, LivingEntity target) {
        ServerLevel level = player.level() instanceof ServerLevel playerLevel
                ? playerLevel
                : (target.level() instanceof ServerLevel targetLevel ? targetLevel : null);
        if (level == null) {
            return false;
        }
        return !ResetEngine.structureIdsAt(level, player.blockPosition()).isEmpty();
    }

    /**
     * True when the player is looking at the target within {@code maxAngleDegrees}.
     *
     * <p>The angle is measured between {@code player.getLookAngle()} and the
     * unit vector from the player's eyes to the target's eyes, falling back to
     * the centre of the target's bounding box when the eyes coincide. A player
     * standing inside the target counts as looking at it.</p>
     *
     * @param player the teaching player
     * @param target the candidate target
     * @param maxAngleDegrees the configured limit; 180 or more accepts anything
     */
    private static boolean isLookingAt(ServerPlayer player, LivingEntity target, int maxAngleDegrees) {
        if (maxAngleDegrees >= 180) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 delta = target.getEyePosition().subtract(eye);
        if (delta.lengthSqr() < 1.0E-6D) {
            AABB box = target.getBoundingBox();
            delta = new Vec3((box.minX + box.maxX) / 2.0D - eye.x,
                    (box.minY + box.maxY) / 2.0D - eye.y,
                    (box.minZ + box.maxZ) / 2.0D - eye.z);
        }
        if (delta.lengthSqr() < 1.0E-6D) {
            return true;
        }
        double dot = player.getLookAngle().normalize().dot(delta.normalize());
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
        return angle <= (double) maxAngleDegrees;
    }

    /**
     * The layered entity classification shared by every entry point.
     *
     * <p>Returns the translation key of the rule that rejected the target, or
     * null when the target is a recognised boss. The order is fixed:
     * a registered script filter alone, then {@code learningEntityAllowlist},
     * then {@code learningExcludedNamespaces}, then
     * {@code learningEntityDenylist}, then the entity tags named by
     * {@code learningBossTags}, then the optional {@code learningBossNbtKeys}
     * and finally, as the last acceptance step before giving up, the boss id
     * list of the optional Boss Checklist soft dependency
     * ({@link BossChecklistBridge#isListedBoss(net.minecraft.world.entity.EntityType)}).
     * A target that passes none of them is not a boss
     * ({@link #MESSAGE_TOO_WEAK}): there is no size or health based fallback any
     * more, and only an explicit designation records such a target.</p>
     */
    private static String classify(LivingEntity target) {
        Boolean scriptVerdict = BossFilterApi.test(BossFilterApi.describe(target));
        if (scriptVerdict != null) {
            return scriptVerdict.booleanValue() ? null : MESSAGE_TOO_WEAK;
        }
        String entityId = entityIdOf(target);
        if (containsIgnoreCase(StructureResetConfig.learningEntityAllowlist(), entityId)) {
            return null;
        }
        String namespace = namespaceOf(entityId);
        for (String excluded : StructureResetConfig.learningExcludedNamespaces()) {
            if (excluded != null && excluded.equalsIgnoreCase(namespace)) {
                return MESSAGE_EXCLUDED_NAMESPACE;
            }
        }
        if (containsIgnoreCase(StructureResetConfig.learningEntityDenylist(), entityId)) {
            return MESSAGE_DENIED;
        }
        if (carriesAnyBossTag(target)) {
            return null;
        }
        if (carriesAnyBossNbtKey(target, StructureResetConfig.learningBossNbtKeys())) {
            return null;
        }
        // The last acceptance step, after the entity tags and after the NBT keys:
        // the optional Boss Checklist soft dependency. It is a hint, never a gate -
        // the bridge answers false at once when the mod is absent, when its list is
        // missing or malformed, and when the list simply does not name this entity -
        // and it never reads the disk here: it serves the cache the reload listener
        // refreshed.
        if (BossChecklistBridge.isListedBoss(target.getType())) {
            return null;
        }
        // Nothing recognised the target: no script filter verdict, no allow list
        // entry, no boss entity tag, no boss NBT key and no Boss Checklist entry.
        // It is not a boss for any automatic path, and only a player designating it
        // explicitly records it.
        return MESSAGE_TOO_WEAK;
    }

    /** The registry id of an entity type, or an empty string when it has none. */
    private static String entityIdOf(LivingEntity target) {
        if (target == null) {
            return "";
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        return id == null ? "" : id.toString();
    }

    /** The namespace part of a registry id, or an empty string. */
    private static String namespaceOf(String entityId) {
        if (entityId == null) {
            return "";
        }
        int colon = entityId.indexOf(':');
        return colon < 0 ? "" : entityId.substring(0, colon);
    }

    /** True when {@code values} contains {@code needle}, ignoring case. */
    private static boolean containsIgnoreCase(List<String> values, String needle) {
        if (values == null || values.isEmpty() || needle == null || needle.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the target's entity type carries one of the configured boss
     * tags.
     *
     * <p>The tag list is a first class criterion - the one that actually
     * recognises a boss: every mod that follows the Forge convention and tags its
     * bosses with {@code forge:bosses} is recognised without any configuration.
     * Entries may be written with or without a leading {@code '#'}.</p>
     */
    private static boolean carriesAnyBossTag(LivingEntity target) {
        if (target == null) {
            return false;
        }
        return bossTagged(target.getType());
    }

    /**
     * True when at least one of {@code keys} is a top level NBT key of the
     * target.
     *
     * <p>The entity NBT is read through {@code saveWithoutMetadata()} and the
     * Forge persistent data of the entity is inspected as well, so a key added
     * by another mod is found in either place. A key that cannot be read never
     * throws: an unreadable NBT simply does not match. An empty or null list
     * matches nothing, which is what makes the shipped default free of any NBT
     * gating.</p>
     */
    private static boolean carriesAnyBossNbtKey(LivingEntity target, List<String> keys) {
        if (target == null || keys == null || keys.isEmpty()) {
            return false;
        }
        CompoundTag saved;
        try {
            saved = target.saveWithoutId(new CompoundTag());
        } catch (RuntimeException ignored) {
            saved = null;
        }
        CompoundTag persistent;
        try {
            persistent = target.getPersistentData();
        } catch (RuntimeException ignored) {
            persistent = null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (saved != null && saved.contains(key)) {
                return true;
            }
            if (persistent != null && persistent.contains(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ records

    /** Every learned anchor of {@code level}, sorted by id; empty when the level is null. */
    public static List<LearnedAnchor> all(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        return LearnedAnchorData.get(level).all();
    }

    /** Forgets one learned anchor; false when the level is null or the id is unknown. */
    public static boolean remove(ServerLevel level, String id) {
        if (level == null) {
            return false;
        }
        return LearnedAnchorData.get(level).remove(id);
    }

    /** Forgets every learned anchor of {@code level}. */
    public static void clear(ServerLevel level) {
        if (level == null) {
            return;
        }
        LearnedAnchorData.get(level).clear();
    }

    // -------------------------------------------------------- definitions

    /**
     * Turns one learned anchor back into a runnable definition.
     *
     * <p>Malformed structure ids are skipped; an anchor that recorded no usable
     * structure id at all falls back to {@link #PLACEHOLDER_STRUCTURE}, so the
     * definition always names something.  The definition is built with the same
     * public builder a data pack uses, so a record that cannot be built yields
     * null instead of an exception.</p>
     *
     * @param anchor the anchor to replay
     * @return the definition, or null when it cannot be built
     */
    public static ResetDefinition definitionFor(LearnedAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        List<StructureRef> refs = new ArrayList<>();
        for (String raw : anchor.structureIds()) {
            StructureRef ref = parseRef(raw);
            if (ref != null) {
                refs.add(ref);
            }
        }
        if (refs.isEmpty()) {
            StructureRef placeholder = parseRef(PLACEHOLDER_STRUCTURE);
            if (placeholder != null) {
                refs.add(placeholder);
            }
        }
        JsonObject params = new JsonObject();
        params.addProperty(PARAM_ANCHOR_ID, anchor.id());
        try {
            return ResetDefinition.builder(anchor.id())
                    .structures(refs)
                    .radius(anchor.radius())
                    .trigger(ResetTrigger.EITHER)
                    .mode(ResetMode.GLOBAL)
                    .cooldownSeconds(StructureResetConfig.defaultCooldownSeconds())
                    .consumeItem(true)
                    .priority(0)
                    .addAction(new ResetActionSpec(ACTION_RESTORE, params))
                    .build();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** The learned definition with {@code id}, or empty when there is none or it cannot be built. */
    public static Optional<ResetDefinition> learnedDefinitionById(ServerLevel level, String id) {
        if (level == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitionFor(LearnedAnchorData.get(level).byId(id)));
    }

    /**
     * The learned definition that applies at {@code pos}, or empty.
     *
     * <p>The anchor is chosen by the pure {@link LearnedAnchorSelector}, which
     * first prefers a record that names one of the structures found at the
     * position and only then a record whose radius covers it.</p>
     */
    public static Optional<ResetDefinition> learnedDefinition(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        List<LearnedAnchor> anchors = LearnedAnchorData.get(level).all();
        if (anchors.isEmpty()) {
            return Optional.empty();
        }
        String dimension = level.dimension().location().toString();
        Set<String> structureIds = ResetEngine.structureIdsAt(level, pos);
        return LearnedAnchorSelector.select(anchors, dimension, structureIds,
                pos.getX(), pos.getY(), pos.getZ())
                .flatMap(anchor -> Optional.ofNullable(definitionFor(anchor)));
    }

    // ------------------------------------------------------------- helpers

    /** Parses one raw structure reference; null when it is malformed. */
    private static StructureRef parseRef(String raw) {
        try {
            return StructureRef.parse(raw);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * The record of the same dimension and boss that sits within
     * {@link #DUPLICATE_RADIUS} of {@code pos}, or null.
     */
    private static LearnedAnchor duplicateOf(List<LearnedAnchor> anchors, String dimension,
                                             String bossEntity, BlockPos pos) {
        double limit = (double) DUPLICATE_RADIUS * (double) DUPLICATE_RADIUS;
        for (LearnedAnchor anchor : anchors) {
            if (anchor == null || !dimension.equals(anchor.dimension())
                    || !bossEntity.equals(anchor.bossEntity())) {
                continue;
            }
            double dx = (double) pos.getX() - (double) anchor.x();
            double dy = (double) pos.getY() - (double) anchor.y();
            double dz = (double) pos.getZ() - (double) anchor.z();
            if (dx * dx + dy * dy + dz * dz < limit) {
                return anchor;
            }
        }
        return null;
    }

    /**
     * The outcome of one container scan: the containers worth replaying and how
     * many containers were seen without a usable loot table.
     *
     * @param containers the learned containers, in chunk iteration order
     * @param noLootTableCount containers that are loot containers but carry no
     *        usable loot table any more
     */
    private record ContainerScan(List<LearnedContainer> containers, int noLootTableCount) {
    }

    /**
     * Every still unlooted loot container inside {@code structureBox}, or inside
     * the cube of {@code radius} around {@code center} when the position belongs
     * to no structure at all.
     *
     * <p>The scan walks the chunks the box touches and then the block entities of
     * each loaded chunk, never the individual blocks: a fixed radius of 64 covers
     * 129^3 blocks, which would be far too many lookups one by one, and a
     * structure bounding box can be far larger than that.  Every scanned position
     * still has to lie inside the box: the chunks only narrow the search down.</p>
     *
     * <p>Only containers that still carry a loot table are recorded; a container
     * whose loot table was already consumed by a player is not worth replaying,
     * and a container a player filled by hand has no learned baseline.  Such a
     * container is counted as {@code noLootTableCount} instead of being stored:
     * no loot table is ever invented for it.</p>
     *
     * @param structureBox the structure bounding box, or null to fall back to
     *        the cube
     */
    private static ContainerScan scanContainers(ServerLevel level, BlockPos center, int radius,
                                                BoundingBox structureBox) {
        List<LearnedContainer> found = new ArrayList<>();
        if (level == null || center == null) {
            return new ContainerScan(found, 0);
        }
        int minX;
        int maxX;
        int minY;
        int maxY;
        int minZ;
        int maxZ;
        if (structureBox != null) {
            // The structure itself defines the extent: the configured radius is
            // only the fallback for positions that belong to no structure.
            minX = structureBox.minX();
            maxX = structureBox.maxX();
            minY = structureBox.minY();
            maxY = structureBox.maxY();
            minZ = structureBox.minZ();
            maxZ = structureBox.maxZ();
        } else {
            if (radius <= 0) {
                return new ContainerScan(found, 0);
            }
            minX = center.getX() - radius;
            maxX = center.getX() + radius;
            minY = center.getY() - radius;
            maxY = center.getY() + radius;
            minZ = center.getZ() - radius;
            maxZ = center.getZ() + radius;
        }
        int noLootTable = 0;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
                        continue;
                    }
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getX() < minX || pos.getX() > maxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    LearnedContainer learned = readContainer(container);
                    if (learned == null) {
                        // A loot container whose table is gone (already looted, or
                        // replaced by hand) cannot be replayed.  It is counted so
                        // the player is told what was seen, but nothing is stored
                        // for it: no loot table is ever fabricated.
                        noLootTable++;
                    } else {
                        found.add(learned);
                    }
                }
            }
        }
        return new ContainerScan(found, noLootTable);
    }

    /**
     * The learned form of one container, or null when it carries no usable loot
     * table.
     *
     * <p>The loot table of {@link RandomizableContainerBlockEntity} is protected
     * and has no public getter, so it is read through the public block entity NBT
     * exactly like the shape the game persists: the loot table must be present as
     * a string and a seed only counts when it is stored as a non-zero long.</p>
     */
    private static LearnedContainer readContainer(RandomizableContainerBlockEntity container) {
        try {
            CompoundTag saved = container.saveWithoutMetadata();
            if (saved == null
                    || !saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG, Tag.TAG_STRING)) {
                return null;
            }
            ResourceLocation table = ResourceLocation.tryParse(
                    saved.getString(RandomizableContainerBlockEntity.LOOT_TABLE_TAG));
            if (table == null) {
                return null;
            }
            boolean hasSeed = saved.contains(RandomizableContainerBlockEntity.LOOT_TABLE_SEED_TAG,
                    Tag.TAG_LONG)
                    && saved.getLong(RandomizableContainerBlockEntity.LOOT_TABLE_SEED_TAG) != 0L;
            long seed = hasSeed
                    ? saved.getLong(RandomizableContainerBlockEntity.LOOT_TABLE_SEED_TAG) : 0L;
            BlockPos pos = container.getBlockPos();
            return new LearnedContainer(pos.getX(), pos.getY(), pos.getZ(), table.toString(), seed, hasSeed);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static TeachResult failure(String messageKey, String detail) {
        return new TeachResult(false, messageKey, Component.translatable(messageKey), "", 0, 0, 0);
    }
    // ------------------------------------------------- structure scan (S2)

    /** Shown when a structure scan recorded a boss. */
    public static final String MESSAGE_SCANNED = "structurereset.message.learning.scanned";
    /** Shown when a structure scan found nothing it could record. */
    public static final String MESSAGE_SCAN_NONE = "structurereset.message.learning.scan_none";
    /**
     * Shown when a structure scan found no boss candidate but did see a crowd of
     * ordinary mobs: this structure appears to contain no boss at all.
     */
    public static final String MESSAGE_SCAN_NO_BOSS = "structurereset.message.learning.scan_no_boss";
    /** Appended to {@link #MESSAGE_SCAN_NO_BOSS}: how to record a boss anyway. */
    public static final String MESSAGE_SCAN_NO_BOSS_HINT =
            "structurereset.message.learning.scan_no_boss_hint";
    /**
     * How many nearby non-boss living entities a structure scan must see before it
     * answers with {@link #MESSAGE_SCAN_NO_BOSS} instead of
     * {@link #MESSAGE_SCAN_NONE}.
     *
     * <p>A structure holding a crowd of ordinary mobs and no boss is a different
     * situation from an empty one: the first very likely has a boss this mod does
     * not recognise, so the answer names the remedy - sneak and right click the
     * creature - instead of only saying that nothing was found. Five is a small
     * fixed number on purpose: it is this one documented constant, not a
     * configuration key, and one spawner room already reaches it.</p>
     */
    public static final int NO_BOSS_CROWD = 5;

    /** True when the shared boss gate accepts {@code entity}. */
    public static boolean isBossCandidate(LivingEntity entity) {
        return entity != null && classify(entity) == null;
    }

    /**
     * Registers the boss of the structure the player stands in, without aiming
     * at anything at all.
     *
     * <p>This exists because aiming at a boss is not reliable: a large or
     * multi part entity (a hydra, a naga that can only be hit on the head) never
     * reaches the server as an interaction, so a right click on it can never be
     * recorded. A scan of the structure the player is standing in does not care
     * about hitboxes, reach or movement.</p>
     *
     * <p>Two sources are used, in this order of strength:</p>
     * <ol>
     *   <li>a block entity whose stored data names a registered entity type -
     *       the "boss respawner" / altar / tombstone pattern. This is the
     *       strongest evidence and it also works before the boss exists;</li>
     *   <li>a living entity inside the structure that passes the boss gate,
     *       ordered by maximum health (a body outranks its own parts).</li>
     * </ol>
     *
     * <p>The strongest candidate wins, and every candidate is written to the
     * log, so an operator can see what the scan saw. Aiming at a specific mob
     * and sneaking still records exactly that mob, and the admin command still
     * overrides everything.</p>
     *
     * <p>A scan that finds no candidate at all distinguishes an empty structure
     * from one that is full of ordinary mobs and no boss: the first answers
     * {@link #MESSAGE_SCAN_NONE}, the second {@link #MESSAGE_SCAN_NO_BOSS} plus
     * {@link #MESSAGE_SCAN_NO_BOSS_HINT} once it saw at least
     * {@link #NO_BOSS_CROWD} nearby non-boss living entities.</p>
     *
     * @param player the player doing the scan, required
     * @return the result, never null
     */
    public static TeachResult scanStructure(ServerPlayer player) {
        if (player == null) {
            return failure(MESSAGE_FAILED, "a structure scan needs a player");
        }
        if (!StructureResetConfig.learningEnabled()) {
            return failure(MESSAGE_DISABLED, "the learning feature is disabled in the configuration");
        }
        if (StructureResetConfig.learningRequireOp() && !player.hasPermissions(2)) {
            return failure(MESSAGE_NOT_OP, "teaching requires operator permissions");
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return failure(MESSAGE_FAILED, "the player is not in a server level");
        }
        BlockPos origin = player.blockPosition();
        Set<String> structureIds = ResetEngine.structureIdsAt(level, origin);
        Optional<BoundingBox> box = ResetEngine.structureBoxAt(level, origin);
        if (structureIds.isEmpty() || box.isEmpty()) {
            LOGGER.info("[{}] structure scan refused: the player stands inside no structure (player={})",
                    StructureResetConfig.MOD_ID, player.getName().getString());
            return failure(MESSAGE_NO_STRUCTURE, "the player stands inside no structure");
        }
        BoundingBox area = box.get();
        int minX = area.minX();
        int minY = area.minY();
        int minZ = area.minZ();
        int maxX = area.maxX();
        int maxY = area.maxY();
        int maxZ = area.maxZ();

        Map<String, BlockPos> candidates = new LinkedHashMap<>();
        List<String> anchorOrder = new ArrayList<>();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getX() < minX || pos.getX() > maxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    for (String value : idStrings(blockEntity.saveWithoutMetadata(), "", new ArrayList<>())) {
                        ResourceLocation id = ResourceLocation.tryParse(value);
                        if (id == null) {
                            continue;
                        }
                        EntityType<?> stored = ForgeRegistries.ENTITY_TYPES.getValue(id);
                        if (stored == null || !anchorCandidateAllowed(level, stored)) {
                            // A block entity may name anything, including an item or a
                            // projectile; only something that passes the boss gate is a
                            // candidate for the boss of this structure.
                            continue;
                        }
                        if (!candidates.containsKey(id.toString())) {
                            anchorOrder.add(id.toString());
                        }
                        candidates.putIfAbsent(id.toString(), pos);
                    }
                }
            }
        }

        AABB bounds = new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
        List<LivingEntity> live = new ArrayList<>();
        int nonBossCrowd = 0;
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds)) {
            if (entity == player || !entity.isAlive()) {
                continue;
            }
            if (!isBossCandidate(entity)) {
                // Alive, not the scanning player and matching no boss criterion:
                // exactly the crowd the "no boss" answer talks about.
                nonBossCrowd++;
                continue;
            }
            live.add(entity);
        }
        live.sort((first, second) -> Float.compare(second.getMaxHealth(), first.getMaxHealth()));
        for (LivingEntity entity : live) {
            String id = entityIdOf(entity);
            if (id != null && !id.isEmpty()) {
                candidates.putIfAbsent(id, entity.blockPosition());
            }
        }

        if (candidates.isEmpty()) {
            LOGGER.info("[{}] structure scan at {} found no boss candidate inside {} "
                            + "({} nearby non-boss living entity/entities)",
                    StructureResetConfig.MOD_ID, origin, structureIds, nonBossCrowd);
            if (nonBossCrowd >= NO_BOSS_CROWD) {
                // A crowd of ordinary mobs and not one recognised boss: this
                // structure appears to contain no boss, and the player is told how
                // to add the one the mod cannot see.
                Component message = Component.translatable(MESSAGE_SCAN_NO_BOSS)
                        .append(Component.literal(" "))
                        .append(Component.translatable(MESSAGE_SCAN_NO_BOSS_HINT));
                return new TeachResult(false, MESSAGE_SCAN_NO_BOSS, message, "", 0, 0, 0);
            }
            return failure(MESSAGE_SCAN_NONE, "no boss candidate inside the structure");
        }
        // A candidate that is a member of a configured boss tag is the strongest
        // signal there is, so it wins over whatever happened to be seen first. A
        // candidate only the optional Boss Checklist list names is the next best
        // hint, and the insertion order decides when neither applies.
        String chosen = null;
        String listed = null;
        for (String candidate : candidates.keySet()) {
            ResourceLocation candidateId = ResourceLocation.tryParse(candidate);
            EntityType<?> candidateType = candidateId == null ? null
                    : ForgeRegistries.ENTITY_TYPES.getValue(candidateId);
            if (candidateType == null) {
                continue;
            }
            if (bossTagged(candidateType)) {
                chosen = candidate;
                break;
            }
            if (listed == null && BossChecklistBridge.isListedBoss(candidateType)) {
                listed = candidate;
            }
        }
        if (chosen == null) {
            chosen = listed != null ? listed : candidates.keySet().iterator().next();
        }
        BlockPos point = candidates.get(chosen);
        Recorded recorded = record(level, player, chosen, point);
        LOGGER.info("[{}] structure scan at {} recorded {} among {} candidate(s) [{}] at {}",
                StructureResetConfig.MOD_ID, origin, chosen, candidates.size(),
                String.join(", ", candidates.keySet()), point);
        Component message = Component.translatable(MESSAGE_SCANNED, chosen, candidates.size(),
                String.join(", ", structureIds), recorded.containers().size());
        return new TeachResult(true, MESSAGE_SCANNED, message, recorded.anchorId(),
                recorded.containers().size(), structureIds.size(), recorded.noLootTableCount());
    }

    /** Key names that really name an entity, not an item or a block. */
    private static final java.util.Set<String> ENTITY_KEYS =
            java.util.Set.of("EntityType", "entity_type", "Entity", "entity");

    /**
     * True when an entity type stored in a block entity may be the boss of a
     * structure: it must be a real mob - never an item, a projectile or a
     * marker - and it must match a boss criterion. An explicitly designated mob
     * does not have to, but an unnamed block entity value has to.
     */
    private static boolean anchorCandidateAllowed(ServerLevel level, EntityType<?> type) {
        return classifyType(level, type) == null;
    }

    /**
     * True when {@code type} really creates a living mob in {@code level}.
     *
     * <p>This is the "non-living target" gate of the administrative
     * {@link #teachEntity(ServerPlayer, String)} command, the only entry point
     * that names a type instead of an instance: an item, a projectile or a
     * marker has the {@code MISC} category, and a type whose instance is not a
     * {@link LivingEntity} is refused as well. The probe instance is discarded
     * again.</p>
     */
    private static boolean createsLivingEntity(ServerLevel level, EntityType<?> type) {
        if (type == null || type.getCategory() == MobCategory.MISC) {
            return false;
        }
        Entity created = type.create(level);
        if (created == null) {
            return false;
        }
        boolean living = created instanceof LivingEntity;
        created.discard();
        return living;
    }

    /**
     * The rejection {@link #classify} reports for a freshly created instance of
     * {@code type}, or null when the type would be accepted.
     *
     * <p>Needed by the two entry points that only have an entity type at hand -
     * the administrative {@link #teachEntity(ServerPlayer, String)} command and
     * the "anchor block entity names this entity" candidate of the structure
     * scan. A temporary instance is created and discarded again because the
     * classification reads the type's tags and its NBT, and because an item, a
     * projectile or a marker is never a boss: those have the {@code MISC}
     * category, which vanilla does not hand out over the boss gates of this
     * mod.</p>
     */
    private static String classifyType(ServerLevel level, EntityType<?> type) {
        if (type == null || type.getCategory() == MobCategory.MISC) {
            return MESSAGE_TOO_WEAK;
        }
        Entity created = type.create(level);
        if (!(created instanceof LivingEntity living)) {
            if (created != null) {
                created.discard();
            }
            return MESSAGE_TOO_WEAK;
        }
        String rejection = classify(living);
        living.discard();
        return rejection;
    }

    /**
     * True when the entity type is a member of one of the configured boss tags.
     *
     * <p>The single implementation of the tag test: both the live entity path
     * ({@link #carriesAnyBossTag(LivingEntity)}) and the "only a type is known"
     * path of the structure scan ask the question here.</p>
     */
    private static boolean bossTagged(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        List<String> tags = StructureResetConfig.learningBossTags();
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String raw : tags) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String text = raw.trim().startsWith("#") ? raw.trim().substring(1) : raw.trim();
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id == null) {
                continue;
            }
            try {
                if (type.is(TagKey.create(Registries.ENTITY_TYPE, id))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // an unknown tag is simply not a match
            LOGGER.debug("structure reset: skipped one malformed entry", ignored);
            }
        }
        return false;
    }

    /** True when a dotted key path names an entity. */
    private static boolean isEntityKeyPath(String path) {
        int dot = path.lastIndexOf('.');
        String tail = dot < 0 ? path : path.substring(dot + 1);
        if (ENTITY_KEYS.contains(tail)) {
            return true;
        }
        return "id".equals(tail)
                && (path.contains("SpawnData") || path.contains("Entity") || path.contains("entity"));
    }

    /**
     * Every string a key that names an entity points at, inside {@code tag}.
     *
     * <p>Only entity naming keys count. A chest full of items stores namespaced
     * ids too, and taking those for bosses is exactly how a structure scan once
     * recorded a piece of loot: the loot table of the hydra lair contains
     * {@code twilightforest:fiery_blood}, which is an item, not a mob.</p>
     */
    private static List<String> idStrings(CompoundTag tag, String path, List<String> out) {
        if (tag == null) {
            return out;
        }
        for (String key : tag.getAllKeys()) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            Tag value = tag.get(key);
            if (value instanceof StringTag text) {
                String raw = text.getAsString();
                if (raw != null && isEntityKeyPath(childPath)) {
                    out.add(raw);
                }
            } else if (value instanceof CompoundTag nested) {
                idStrings(nested, childPath, out);
            } else if (value instanceof ListTag list) {
                for (int index = 0; index < list.size(); index++) {
                    Tag element = list.get(index);
                    if (element instanceof CompoundTag nested) {
                        idStrings(nested, childPath, out);
                    } else if (element instanceof StringTag text) {
                        String raw = text.getAsString();
                        if (raw != null && isEntityKeyPath(childPath)) {
                            out.add(raw);
                        }
                    }
                }
            }
        }
        return out;
    }

}
