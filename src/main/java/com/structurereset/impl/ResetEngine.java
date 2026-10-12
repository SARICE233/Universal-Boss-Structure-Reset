package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import com.structurereset.api.DefinitionSelector;
import com.structurereset.api.ResetActionSpec;
import com.structurereset.api.ResetDefinition;
import com.structurereset.api.ResetKey;
import com.structurereset.api.ResetMode;
import com.structurereset.api.StructureRef;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.core.ItemSettingsRegistry;
import com.structurereset.core.ResetActionType;
import com.structurereset.core.ResetContext;
import com.structurereset.core.ResetResult;
import com.structurereset.registry.ModItems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * The runtime engine of Structure Reset: it decides which definition applies at
 * a position and then executes that definition, action by action, under the
 * cooldown rules of {@link ResetStateSavedData}.
 *
 * <p>Selection uses the pure {@link DefinitionSelector} and the pure
 * {@link com.structurereset.api.CooldownPolicy}; everything Minecraft specific
 * (structure lookup, anchor blocks, actions, messaging) is done here.</p>
 *
 * <p>One failing action never aborts the remaining ones: it is recorded, the
 * overall result becomes a failure and the cooldown is not consumed.</p>
 */
public final class ResetEngine {

    /**
     * How far around the origin an anchor block is searched for.
     *
     * <p>This used to be 3, which was far too small: after a boss fight the
     * player is routinely more than three blocks away from the
     * {@code boss_respawner} the boss left behind, so the anchor was never seen,
     * the anchor's stored boss name was never read, and the selection silently
     * fell back to the (unreliable) structure id. The anchor block entity is the
     * primary evidence of which boss belongs to this place, so the search has to
     * be generous enough to find it. Reading block entities stays cheap because
     * only positions whose block is a declared anchor block are inspected.</p>
     */
    public static final int ANCHOR_SCAN_RADIUS = 16;

    /** Translation keys that ship with the mod and are used for player feedback. */
    private static final String LANG_SUCCESS = "item.structurereset.structure_reset_core.success";
    private static final String LANG_COOLDOWN = "item.structurereset.structure_reset_core.cooldown";
    private static final String LANG_NOT_READY = "item.structurereset.structure_reset_core.not_ready";

    /** Message keys carried by the results this engine builds. */
    public static final String MESSAGE_NO_DEFINITION = "structurereset.message.no_definition";
    public static final String MESSAGE_DISABLED = "structurereset.message.disabled";
    public static final String MESSAGE_COOLDOWN = "structurereset.message.cooldown";
    public static final String MESSAGE_INVALID_CONTEXT = "structurereset.message.invalid_context";
    public static final String MESSAGE_AMBIGUOUS = "structurereset.message.ambiguous";

    private static final Logger LOGGER = LogUtils.getLogger();

    private ResetEngine() {
    }

    // ------------------------------------------------------------- lookup

    /**
     * The definition that applies at {@code pos}, or null.
     *
     * @see #lookup(ServerLevel, BlockPos, ServerPlayer)
     */
    public static ResetDefinition findDefinition(ServerLevel level, BlockPos pos, ServerPlayer actor) {
        return lookup(level, pos, actor).definition();
    }

    /**
     * Selects the definition that applies at {@code pos} and reports why a
     * selection was refused.
     *
     * <p>The structure of the position (exact id and tags) and the anchor blocks
     * around it are collected first; definitions whose anchor block is actually
     * present are offered to the selector before the others, so an anchor based
     * trigger and a structure based trigger can coexist. The entity type stored
     * by the anchor block entity travels into the selector, which refuses a
     * definition that would re-spawn a different boss.</p>
     *
     * <p>The returned carrier is {@code (definition, null)} when something was
     * selected, {@code (null, MESSAGE_AMBIGUOUS)} when an anchor block of some
     * registered definition is nearby and still nothing could be chosen - either
     * several candidates tie or none of them re-spawns the stored boss - and
     * {@code (null, null)} when there is simply nothing here.</p>
     */
    public static DefinitionLookup lookup(ServerLevel level, BlockPos pos, ServerPlayer actor) {
        if (level == null || pos == null) {
            return new DefinitionLookup(null, null);
        }
        // An empty registry (no data pack installed at all) must not short circuit
        // the lookup any more: the learn file may still hold anchors, and those
        // synthesise their definition on the fly instead of registering it.
        List<ResetDefinition> definitions = StructureResetRegistry.definitions();
        StructureFacts facts = structureFacts(level, pos);
        Set<String> nearby = nearbyBlockIds(level, pos, ANCHOR_SCAN_RADIUS);
        List<ResetDefinition> anchored = new ArrayList<>();
        List<ResetDefinition> rest = new ArrayList<>();
        boolean anchorNearby = false;
        for (ResetDefinition definition : definitions) {
            if (anchorPresent(definition, nearby)) {
                anchored.add(definition);
                anchorNearby = true;
            } else {
                rest.add(definition);
            }
        }
        LOGGER.info("[{}] selection input: structureIds={} tags={} anchorEntityType={} anchorRadius={} anchored={} rest={}",
                StructureResetConfig.MOD_ID, facts.ids(), facts.tags(), facts.anchorEntityType(),
                ANCHOR_SCAN_RADIUS, anchored.size(), rest.size());
        Optional<ResetDefinition> selected = selectDefinition(anchored, facts, true);
        if (selected.isEmpty()) {
            selected = selectDefinition(rest, facts, false);
        }
        if (selected.isPresent()) {
            LOGGER.info("[{}] selection result: chosen={} respawn={} structureIds={} anchorEntityType={}",
                    StructureResetConfig.MOD_ID, selected.get().id(), selected.get().respawnBossEntity(),
                    facts.ids(), facts.anchorEntityType());
            return new DefinitionLookup(selected.get(), null);
        }
        Optional<ResetDefinition> learned = LearnedAnchorService.learnedDefinition(level, pos);
        if (learned.isPresent()) {
            LOGGER.info("[{}] selection result: learned={} structureIds={} anchorEntityType={}",
                    StructureResetConfig.MOD_ID, learned.get().id(), facts.ids(),
                    facts.anchorEntityType());
            return new DefinitionLookup(learned.get(), null);
        }
        LOGGER.info("[{}] selection result: REFUSED anchorNearby={} structureIds={} anchorEntityType={}",
                StructureResetConfig.MOD_ID, anchorNearby, facts.ids(), facts.anchorEntityType());
        return new DefinitionLookup(null, anchorNearby ? MESSAGE_AMBIGUOUS : null);
    }

    /** The definition chosen at {@code pos}, or null, plus the translation key explaining a refusal. */
    public record DefinitionLookup(ResetDefinition definition, String messageKey) {
    }

    /**
     * Offers the candidates to the pure {@link DefinitionSelector}, which matches one
     * structure id at a time. A position can be covered by several structures, so every
     * collected id is tried: a definition naming an id exactly outranks a tag or anchor
     * match, and a last id-less pass keeps tag only and anchor only definitions reachable.
     */
    private static Optional<ResetDefinition> selectDefinition(List<ResetDefinition> candidates,
                                                              StructureFacts facts,
                                                              boolean anchorMatched) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<ResetDefinition> fallback = Optional.empty();
        for (String structureId : facts.ids()) {
            Optional<ResetDefinition> hit = DefinitionSelector.select(candidates, structureId,
                    facts.tags(), anchorMatched, facts.anchorEntityType());
            if (hit.isEmpty()) {
                continue;
            }
            if (matchesStructureId(hit.get(), structureId)) {
                return hit;
            }
            if (fallback.isEmpty()) {
                fallback = hit;
            }
        }
        if (fallback.isPresent()) {
            return fallback;
        }
        return DefinitionSelector.select(candidates, null, facts.tags(), anchorMatched,
                facts.anchorEntityType());
    }

    /** True when the definition names {@code structureId} itself instead of a tag. */
    private static boolean matchesStructureId(ResetDefinition definition, String structureId) {
        for (StructureRef ref : definition.structures()) {
            if (ref.matchesId(structureId)) {
                return true;
            }
        }
        return false;
    }

    /** True when a trigger at this position would run something right now. */
    public static boolean canReset(ServerLevel level, BlockPos pos, ServerPlayer actor,
                                   String definitionId, boolean force) {
        return canReset(level, pos, actor, definitionId, force, 0);
    }

    /**
     * As {@link #canReset(ServerLevel, BlockPos, ServerPlayer, String, boolean)}
     * with a cooldown override.
     *
     * @param cooldownSecondsOverride the cooldown in seconds an item triggered
     *        reset wants instead of {@code definition.cooldownSeconds()};
     *        {@code 0} (or less) keeps the definition's own cooldown
     */
    public static boolean canReset(ServerLevel level, BlockPos pos, ServerPlayer actor,
                                   String definitionId, boolean force, int cooldownSecondsOverride) {
        if (level == null || pos == null) {
            return false;
        }
        if (!force && !StructureResetConfig.enabled()) {
            return false;
        }
        ResetDefinition definition = resolve(level, pos, actor, definitionId);
        if (definition == null) {
            return false;
        }
        long now = nowMillis(level);
        ResetKey key = keyFor(level, definition, actor);
        return force || ResetStateSavedData.get(level)
                .isReady(key, cooldownOf(definition, cooldownSecondsOverride), now);
    }

    /**
     * Why a reset is refused right now, or null when it would run.
     *
     * <p>{@link #canReset} answers yes or no, which left every refusal reported
     * as the same "this structure cannot be reset right now" line - unhelpful
     * when the only thing wrong is a cooldown that needs a number. This returns
     * the actual reason and, for a cooldown, the seconds left.</p>
     *
     * @param messageKey the translation key of the reason
     * @param seconds the seconds left before a retry, or 0
     */
    public record ResetBlocker(String messageKey, int seconds) {
    }

    /** The reason a reset is refused, or null when it would run now. */
    public static ResetBlocker blockerFor(ServerLevel level, BlockPos pos, ServerPlayer actor,
                                          String definitionId, boolean force,
                                          int cooldownSecondsOverride) {
        if (level == null || pos == null) {
            return new ResetBlocker(MESSAGE_INVALID_CONTEXT, 0);
        }
        if (!force && !StructureResetConfig.enabled()) {
            return new ResetBlocker(MESSAGE_DISABLED, 0);
        }
        ResetDefinition definition = resolve(level, pos, actor, definitionId);
        if (definition == null) {
            return new ResetBlocker(MESSAGE_NO_DEFINITION, 0);
        }
        if (force) {
            return null;
        }
        long now = nowMillis(level);
        ResetKey key = keyFor(level, definition, actor);
        int cooldown = cooldownOf(definition, cooldownSecondsOverride);
        ResetStateSavedData state = ResetStateSavedData.get(level);
        if (state.isReady(key, cooldown, now)) {
            return null;
        }
        long remaining = Math.max(0L, state.remainingMillis(key, cooldown, now));
        return new ResetBlocker(MESSAGE_COOLDOWN, (int) Math.max(1L, (remaining + 999L) / 1000L));
    }

    // ------------------------------------------------------------ trigger

    /** Triggers a reset by definition id (or by selection when the id is null). */
    public static ResetResult triggerReset(ServerLevel level, BlockPos origin, ServerPlayer actor,
                                           String definitionId, boolean force) {
        return triggerReset(level, origin, actor, definitionId, force, null);
    }

    /** As above, with an explicit message key instead of the default one. */
    public static ResetResult triggerReset(ServerLevel level, BlockPos origin, ServerPlayer actor,
                                           String definitionId, boolean force, String messageKey) {
        return triggerReset(level, origin, actor, definitionId, force, messageKey, 0);
    }

    /**
     * As above, with an explicit message key and a cooldown override.
     *
     * @param cooldownSecondsOverride the cooldown in seconds an item triggered
     *        reset wants instead of {@code definition.cooldownSeconds()};
     *        {@code 0} (or less) keeps the definition's own cooldown
     */
    public static ResetResult triggerReset(ServerLevel level, BlockPos origin, ServerPlayer actor,
                                           String definitionId, boolean force, String messageKey,
                                           int cooldownSecondsOverride) {
        if (level == null || origin == null) {
            return ResetResult.fail(MESSAGE_INVALID_CONTEXT, "a reset needs a level and an origin");
        }
        return triggerReset(level, origin, actor, resolve(level, origin, actor, definitionId),
                force, messageKey, cooldownSecondsOverride);
    }

    /**
     * Runs one already resolved definition.
     *
     * <p>This is the workhorse: it builds the box, applies the cooldown, runs
     * every action in order, consumes the required item on success and notifies
     * the acting player when the configuration asks for it.</p>
     */
    public static ResetResult triggerReset(ServerLevel level, BlockPos origin, ServerPlayer actor,
                                           ResetDefinition definition, boolean force, String messageKey) {
        return triggerReset(level, origin, actor, definition, force, messageKey, 0);
    }

    /**
     * As above, with a cooldown override for the persisted reset clock.
     *
     * @param cooldownSecondsOverride the cooldown in seconds an item triggered
     *        reset wants instead of {@code definition.cooldownSeconds()};
     *        {@code 0} (or less) keeps the definition's own cooldown
     */
    public static ResetResult triggerReset(ServerLevel level, BlockPos origin, ServerPlayer actor,
                                           ResetDefinition definition, boolean force, String messageKey,
                                           int cooldownSecondsOverride) {
        if (level == null || origin == null) {
            return ResetResult.fail(MESSAGE_INVALID_CONTEXT, "a reset needs a level and an origin");
        }
        if (definition == null) {
            return ResetResult.fail(MESSAGE_NO_DEFINITION, "no reset definition applies here");
        }
        if (!force && !StructureResetConfig.enabled()) {
            notifyActor(actor, LANG_NOT_READY);
            return ResetResult.fail(MESSAGE_DISABLED, "structure resets are disabled in the configuration");
        }

        AABB box = boxFor(definition, origin);
        ResetKey key = keyFor(level, definition, actor);
        ResetStateSavedData state = ResetStateSavedData.get(level);
        long now = nowMillis(level);
        long remaining = state.remainingMillis(key, cooldownOf(definition, cooldownSecondsOverride), now);
        if (!force && remaining > 0L) {
            notifyActor(actor, LANG_COOLDOWN);
            return ResetResult.fail(MESSAGE_COOLDOWN,
                    "the reset of " + definition.id() + " is cooling down for "
                            + (remaining / 1000L) + "s");
        }

        List<String> actionsRun = new ArrayList<>();
        boolean success = true;
        String firstError = "";
        for (ResetActionSpec spec : definition.actions()) {
            ResourceLocation id = ResourceLocation.tryParse(spec.type());
            ResetActionType action = id == null ? null : StructureResetRegistry.actionType(id);
            if (action == null) {
                success = false;
                if (firstError.isEmpty()) {
                    firstError = "unknown action type '" + spec.type() + "'";
                }
                continue;
            }
            actionsRun.add(spec.type());
            try {
                ResetContext context = new ResetContext(level, origin, box, actor, definition, spec.params());
                ResetResult result = action.run(context);
                if (result == null) {
                    success = false;
                    if (firstError.isEmpty()) {
                        firstError = "action '" + spec.type() + "' returned no result";
                    }
                } else if (!result.success()) {
                    success = false;
                    if (firstError.isEmpty()) {
                        firstError = result.hasError() ? result.error() : ("action '" + spec.type() + "' failed");
                    }
                }
            } catch (RuntimeException failure) {
                success = false;
                if (firstError.isEmpty()) {
                    firstError = "action '" + spec.type() + "' threw " + failure;
                }
                LOGGER.error("[{}] action {} of definition {} failed",
                        StructureResetConfig.MOD_ID, spec.type(), definition.id(), failure);
            }
        }

        String messageKeyOut = messageKey == null || messageKey.isBlank()
                ? (success ? ResetResult.MESSAGE_OK : ResetResult.MESSAGE_FAILED)
                : messageKey;
        if (success) {
            state.markReset(key, now);
            consumeRequiredItem(actor, definition);
        }
        notifyActor(actor, success ? LANG_SUCCESS : LANG_NOT_READY);
        if (StructureResetConfig.debugLogging()) {
            LOGGER.info("[{}] reset {} at {} {} {} ran {} action(s), success={}",
                    StructureResetConfig.MOD_ID, definition.id(),
                    origin.getX(), origin.getY(), origin.getZ(), actionsRun.size(), success);
        }
        return new ResetResult(success, messageKeyOut, actionsRun, firstError);
    }

    // ------------------------------------------------------------- helpers

    /** The area a definition covers around an origin. */
    public static AABB boxFor(ResetDefinition definition, BlockPos origin) {
        double radius = definition == null || !(definition.radius() > 0.0D)
                ? ResetDefinition.DEFAULT_RADIUS : definition.radius();
        return new AABB(origin).inflate(radius);
    }

    /**
     * The storage key of one reset: the global scope for a {@code GLOBAL}
     * definition and the player UUID for a {@code PER_PLAYER} one.
     */
    public static ResetKey keyFor(ServerLevel level, ResetDefinition definition, ServerPlayer actor) {
        String dimension = level.dimension().location().toString();
        if (definition.mode() == ResetMode.PER_PLAYER && actor != null) {
            return ResetKey.of(dimension, ResetMode.PER_PLAYER, actor.getUUID(), definition.id());
        }
        return new ResetKey(dimension, ResetKey.GLOBAL_SCOPE, definition.id());
    }

    /** The world clock in milliseconds, so {@code CooldownPolicy} stays pure. */
    public static long nowMillis(ServerLevel level) {
        return level.getGameTime() * 50L;
    }

    /**
     * Resolves the definition an explicit id names, and falls back to the
     * position based selection when that id is unknown.
     *
     * <p>An id is looked up in the registry first and among the learned anchors
     * second, because a learned anchor synthesises its definition on the fly and
     * therefore never appears in the registry.  When both miss, the position is
     * searched exactly as if no id had been given at all: a stale id coming from
     * a command or a KubeJS script must not silently become "nothing applies
     * here".</p>
     *
     * @param level the level the reset happens in, may be null
     * @param pos the position the reset would happen at, may be null
     * @param actor the acting player, may be null
     * @param definitionId an explicit definition id, or null/blank to select one
     * @return the definition, or null when nothing applies
     */
    public static ResetDefinition resolveDefinition(ServerLevel level, BlockPos pos, ServerPlayer actor,
                                                    String definitionId) {
        if (definitionId != null && !definitionId.isBlank()) {
            ResetDefinition registered = StructureResetRegistry.definition(definitionId);
            if (registered != null) {
                return registered;
            }
            ResetDefinition learned = LearnedAnchorService.learnedDefinitionById(level, definitionId)
                    .orElse(null);
            if (learned != null) {
                return learned;
            }
            return findDefinition(level, pos, actor);
        }
        return findDefinition(level, pos, actor);
    }

    /** Delegates to {@link #resolveDefinition(ServerLevel, BlockPos, ServerPlayer, String)}. */
    private static ResetDefinition resolve(ServerLevel level, BlockPos pos, ServerPlayer actor,
                                           String definitionId) {
        return resolveDefinition(level, pos, actor, definitionId);
    }

    /**
     * Charges the required item of one successful reset.
     *
     * <p>When the required item is this mod's own
     * {@code structurereset:structure_reset_core} the cost is exactly one
     * durability point taken from the core the player carries, so a freshly
     * crafted core survives {@code 5 - used} further resets instead of vanishing
     * at once. Any other required item keeps the original behaviour and has one
     * whole item removed from the first matching inventory stack.</p>
     */
    private static void consumeRequiredItem(ServerPlayer actor, ResetDefinition definition) {
        if (actor == null || actor.isCreative()) {
            return;
        }
        if (!StructureResetConfig.consumeItem() || !definition.consumeItem()) {
            return;
        }
        String required = definition.requiredItem();
        if (required == null || required.isBlank()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(required);
        if (id == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return;
        }
        if (item == ModItems.STRUCTURE_RESET_CORE.get()) {
            if (ItemSettingsRegistry.get(item).unbreakable()) {
                // An unbreakable core consumes nothing at all: no durability
                // point is spent, so the core keeps working forever.
                return;
            }
            damageCore(actor, item);
            return;
        }
        for (int slot = 0; slot < actor.getInventory().getContainerSize(); slot++) {
            ItemStack stack = actor.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                actor.getInventory().setChanged();
                return;
            }
        }
    }

    /**
     * Spends one durability point of the carried reset core.
     *
     * <p>The main hand is tried first, then the off hand, then the first matching
     * inventory slot. When no core is found nothing happens at all: a
     * creative-style or scripted reset must never throw because the cost cannot
     * be charged. An empty stack, a stack that cannot be damaged and an
     * unbreakable one are left untouched as well.</p>
     *
     * @param actor the player that has to pay
     * @param core the resolved item that is this mod's core
     */
    private static void damageCore(ServerPlayer actor, Item core) {
        ItemStack stack = coreStack(actor, core);
        if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return;
        }
        if (isUnbreakable(stack)) {
            return;
        }
        stack.hurtAndBreak(1, actor, broken -> broken.broadcastBreakEvent(EquipmentSlot.MAINHAND));
    }

    /** Main hand first, then off hand, then the first inventory slot holding a core. */
    private static ItemStack coreStack(ServerPlayer actor, Item core) {
        ItemStack main = actor.getMainHandItem();
        if (!main.isEmpty() && main.is(core)) {
            return main;
        }
        ItemStack off = actor.getOffhandItem();
        if (!off.isEmpty() && off.is(core)) {
            return off;
        }
        for (int slot = 0; slot < actor.getInventory().getContainerSize(); slot++) {
            ItemStack stack = actor.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(core)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * The cooldown in seconds a reset applies: the override of the triggering
     * item when it asked for one, otherwise the definition's own value.
     *
     * @param definition the definition that is about to run
     * @param override the item's requested cooldown; {@code 0} (or less) means
     *        "use the definition"
     */
    private static int cooldownOf(ResetDefinition definition, int override) {
        if (override > 0) {
            return override;
        }
        return definition == null ? 0 : definition.cooldownSeconds();
    }

    /** True when the vanilla {@code Unbreakable} tag forbids damaging this stack. */
    private static boolean isUnbreakable(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean("Unbreakable");
    }

    private static void notifyActor(ServerPlayer actor, String langKey) {
        if (actor == null || !StructureResetConfig.notifyPlayer()) {
            return;
        }
        actor.displayClientMessage(Component.translatable(langKey), false);
    }

    // -------------------------------------------------- structure lookup

    /**
     * The exact structure ids present at {@code pos}, as an immutable copy.
     *
     * <p>This is the public, side effect free way to ask "which structure is
     * this position inside of?", used by the teaching feature to remember what a
     * boss belonged to and by the learned anchor selection.</p>
     *
     * @param level the level to look at, may be null
     * @param pos the position to look at, may be null
     * @return the structure ids, empty when the level or the position is null
     */
    public static Set<String> structureIdsAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Set.of();
        }
        Set<String> ids = cheapFacts(level, pos).ids();
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    /**
     * The union of the {@link StructureStart} bounding boxes that really contain
     * {@code pos}, as an {@link Optional}.
     *
     * <p>The teaching feature scans for loot containers inside this box instead
     * of inside a fixed cube around the boss: a generated structure is exactly
     * as large as its bounding box, so a structure of any size is covered while
     * a hand built arena keeps falling back to the configured radius.</p>
     *
     * <p>Only a start whose bounding box contains the position contributes; a
     * structure that merely has a piece near the position does not, for the same
     * reason {@link #isInsideStructure(StructureManager, BlockPos, Structure)}
     * exists: piece boxes leave the courtyards and the gaps between the pieces
     * uncovered.  When several structures overlap, their boxes are merged into
     * the smallest box containing all of them.</p>
     *
     * @param level the level to look at, may be null
     * @param pos the position to look at, may be null
     * @return the merged bounding box, empty when the level, the position or the
     *         set of containing structures is empty
     */
    public static Optional<BoundingBox> structureBoxAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }
        StructureManager manager = level.structureManager();
        var found = manager.getAllStructuresAt(pos);
        if (found == null || found.isEmpty()) {
            return Optional.empty();
        }
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        // Same stable registry id order as collectStructuresAt, so the box does
        // not depend on the identity hashes of the structures either.
        List<Structure> ordered = new ArrayList<>(found.keySet());
        ordered.sort(Comparator.comparing(
                (Structure structure) -> structureKey(registry, structure)));
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (Structure structure : ordered) {
            StructureStart start = manager.getStructureAt(pos, structure);
            if (start == null || !start.isValid()) {
                // A start without any piece - StructureStart.INVALID_START means
                // "there is no structure here" - throws IllegalStateException from
                // getBoundingBox(). Skipping it is the correct reading.
                continue;
            }
            BoundingBox box = start.getBoundingBox();
            if (box == null || !box.isInside(pos)) {
                continue;
            }
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
            any = true;
        }
        if (!any) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** The exact structure ids and structure tags present at one position. */
    private static StructureFacts cheapFacts(ServerLevel level, BlockPos pos) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        collectStructuresAt(level, pos, ids, tags);
        int searchRadius = StructureResetConfig.structureSearchRadius();
        if (ids.isEmpty() && searchRadius > 0) {
            int step = Math.max(4, searchRadius / 2);
            boolean found = false;
            for (int dx = -searchRadius; dx <= searchRadius && !found; dx += step) {
                for (int dy = -searchRadius; dy <= searchRadius && !found; dy += step) {
                    for (int dz = -searchRadius; dz <= searchRadius; dz += step) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos sample = pos.offset(dx, dy, dz);
                        if (!level.isLoaded(sample)) {
                            continue;
                        }
                        collectStructuresAt(level, sample, ids, tags);
                        if (!ids.isEmpty()) {
                            found = true;
                            break;
                        }
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            collectDefinitionStructures(level, pos, ids, tags);
        }
        return new StructureFacts(ids, tags, null);
    }

    /**
     * The facts the definition selection needs: the cheap ones plus the entity
     * the nearest anchor block of the area stores.
     *
     * <p>Only the selection path calls this. The far more frequent
     * {@link #structureIdsAt(ServerLevel, BlockPos)} deliberately does not, so
     * an entity entering the world never touches a block at all.</p>
     */
    private static StructureFacts structureFacts(ServerLevel level, BlockPos pos) {
        StructureFacts base = cheapFacts(level, pos);
        return new StructureFacts(base.ids(), base.tags(), anchorEntityTypeAt(level, pos));
    }

    /**
     * The entity type the nearest anchor block entity in the
     * {@link #ANCHOR_SCAN_RADIUS} cube stores, or null when none is readable.
     *
     * <p>Only positions whose block id is declared as an {@code anchor_blocks}
     * entry by some registered definition are inspected, so an unrelated block
     * entity with an {@code EntityType} tag is never mistaken for a boss
     * respawner. Unloaded positions are skipped, and among the candidates the
     * nearest one that really yields a non-blank value wins.</p>
     */
    private static String anchorEntityTypeAt(ServerLevel level, BlockPos origin) {
        Set<String> anchorIds = declaredAnchorBlockIds();
        if (anchorIds.isEmpty()) {
            return null;
        }
        String nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        // Only the block entities of the loaded chunks around the origin are
        // inspected. Walking every block of a 33x33x33 cube and reading its
        // state is what once stalled the server thread: Level#getBlockState can
        // block waiting for a chunk, and getChunkNow never loads anything.
        int minChunkX = (origin.getX() - ANCHOR_SCAN_RADIUS) >> 4;
        int maxChunkX = (origin.getX() + ANCHOR_SCAN_RADIUS) >> 4;
        int minChunkZ = (origin.getZ() - ANCHOR_SCAN_RADIUS) >> 4;
        int maxChunkZ = (origin.getZ() + ANCHOR_SCAN_RADIUS) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (Math.abs(pos.getX() - origin.getX()) > ANCHOR_SCAN_RADIUS
                            || Math.abs(pos.getY() - origin.getY()) > ANCHOR_SCAN_RADIUS
                            || Math.abs(pos.getZ() - origin.getZ()) > ANCHOR_SCAN_RADIUS) {
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(
                            blockEntity.getBlockState().getBlock());
                    if (id == null || !isAnchorBlockId(anchorIds, id.toString())) {
                        continue;
                    }
                    String entityType = readAnchorEntityType(blockEntity);
                    if (entityType == null) {
                        continue;
                    }
                    double distance = distanceSqr(origin, pos);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = entityType;
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * The {@code EntityType} string tag of the block entity at {@code pos}, or
     * null when there is none, when it is blank or when the read throws.
     *
     * <p>Cataclysm's {@code boss_respawner} block entity writes the boss it
     * belongs to under that key in its {@code saveAdditional}. The value is read
     * through the public 1.20.1 block entity API only - this class never
     * references a Cataclysm class, so the addon keeps working when that mod is
     * absent or moves its packages.</p>
     */
    private static String readAnchorEntityType(BlockEntity blockEntity) {
        try {
            if (blockEntity == null) {
                return null;
            }
            CompoundTag tag = blockEntity.saveWithoutMetadata();
            if (tag == null || !tag.contains("EntityType", Tag.TAG_STRING)) {
                return null;
            }
            String value = tag.getString("EntityType");
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException failure) {
            LOGGER.debug("[{}] could not read an anchor entity type at {}",
                    StructureResetConfig.MOD_ID, blockEntity.getBlockPos(), failure);
            return null;
        }
    }

    /** Every anchor block id some registered definition declares. */
    private static Set<String> declaredAnchorBlockIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ResetDefinition definition : StructureResetRegistry.definitions()) {
            ids.addAll(definition.anchorBlocks());
        }
        return ids;
    }

    /** True when one declared anchor block id matches {@code blockId}. */
    private static boolean isAnchorBlockId(Set<String> anchorIds, String blockId) {
        for (String anchor : anchorIds) {
            if (anchorMatchesBlock(anchor, blockId)) {
                return true;
            }
        }
        return false;
    }

    /** The squared distance between two block positions. */
    private static double distanceSqr(BlockPos from, BlockPos to) {
        double dx = (double) to.getX() - (double) from.getX();
        double dy = (double) to.getY() - (double) from.getY();
        double dz = (double) to.getZ() - (double) from.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Every structure whose bounding box covers {@code pos}, via the structure
     * manager.
     *
     * @see #isInsideStructure(StructureManager, BlockPos, Structure)
     */
    private static void collectStructuresAt(ServerLevel level, BlockPos pos, Set<String> ids,
                                            Set<String> tags) {
        StructureManager manager = level.structureManager();
        var found = manager.getAllStructuresAt(pos);
        if (found == null || found.isEmpty()) {
            return;
        }
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        // getAllStructuresAt hands out a hash map whose iteration order depends on
        // the identity hashes of the structures, so the ids used to be added in an
        // order that changed from run to run. The definitions are matched one id at
        // a time, which decides which of several nearby structures wins, so the
        // structures are visited in a stable registry id order instead.
        List<Structure> ordered = new ArrayList<>(found.keySet());
        ordered.sort(Comparator.comparing(
                (Structure structure) -> structureKey(registry, structure)));
        for (Structure structure : ordered) {
            if (isInsideStructure(manager, pos, structure)) {
                addStructure(registry, structure, ids, tags);
            }
        }
    }

    /** The registry id of a structure as a string, or "" when it has none. */
    private static String structureKey(Registry<Structure> registry, Structure structure) {
        ResourceLocation id = registry.getKey(structure);
        return id == null ? "" : id.toString();
    }

    /**
     * True when {@code pos} really lies inside {@code structure}.
     *
     * <p>The primary test is the structure's own {@link StructureStart}
     * bounding box: {@code start.getBoundingBox().isInside(pos)}.  That box
     * covers the whole structure, including the courtyards, the open ground and
     * the gaps between its pieces.</p>
     *
     * <p>The piece based
     * {@link StructureManager#getStructureWithPieceAt(BlockPos, Structure)} is
     * only consulted after that box test failed, and it can therefore never
     * decide an id on its own - it can only widen a box hit, never replace the
     * box test.  The reason is that a piece bounding box does not cover the
     * parts of a structure no piece actually fills, so a position inside such a
     * courtyard used to be reported as "not in the structure at all": the id set
     * became empty, the selection degraded to picking a definition by structure
     * id order, and that is one of the reasons a position inside
     * {@code cataclysm:soul_black_smith} could summon the ancient_factory boss.</p>
     */
    private static boolean isInsideStructure(StructureManager manager, BlockPos pos,
                                             Structure structure) {
        if (manager == null || pos == null || structure == null) {
            return false;
        }
        StructureStart start = manager.getStructureAt(pos, structure);
        if (start != null && !start.isValid()) {
            // A start without any piece - vanilla uses StructureStart.INVALID_START
            // to mean "there is no structure here" - throws IllegalStateException
            // from getBoundingBox(). It means "not inside", not "broken".
            start = null;
        }
        boolean member = start != null && start.getBoundingBox().isInside(pos);
        if (member) {
            return true;
        }
        // Last resort only: reachable exclusively when the bounding box test
        // above did not already confirm the position.
        return manager.getStructureWithPieceAt(pos, structure) != null;
    }

    /**
     * Fallback that asks the structure manager about the exact structures the
     * registered definitions name, when nothing was found above.
     */
    private static void collectDefinitionStructures(ServerLevel level, BlockPos pos, Set<String> ids,
                                                    Set<String> tags) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        StructureManager manager = level.structureManager();
        for (ResetDefinition definition : StructureResetRegistry.definitions()) {
            for (StructureRef ref : definition.structures()) {
                ResourceLocation id = ResourceLocation.tryParse(ref.id());
                if (id == null) {
                    continue;
                }
                Structure structure = registry.get(id);
                if (structure == null) {
                    continue;
                }
                if (isInsideStructure(manager, pos, structure)) {
                    addStructure(registry, structure, ids, tags);
                }
            }
        }
    }

    private static void addStructure(Registry<Structure> registry, Structure structure,
                                     Set<String> ids, Set<String> tags) {
        ResourceLocation id = registry.getKey(structure);
        if (id != null) {
            ids.add(id.toString());
        }
        registry.getResourceKey(structure).flatMap(registry::getHolder)
                .ifPresent(holder -> holder.tags()
                        .forEach(tag -> tags.add(tag.location().toString())));
    }

    /** The block ids present in a cube around {@code origin}. */
    private static Set<String> nearbyBlockIds(ServerLevel level, BlockPos origin, int radius) {
        Set<String> ids = new LinkedHashSet<>();
        if (radius <= 0) {
            return ids;
        }
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            Block block = level.getBlockState(pos).getBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

    private static boolean anchorPresent(ResetDefinition definition, Set<String> nearbyBlockIds) {
        if (definition.anchorBlocks().isEmpty() || nearbyBlockIds.isEmpty()) {
            return false;
        }
        for (String anchor : definition.anchorBlocks()) {
            if (anchorsMatch(anchor, nearbyBlockIds)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anchorsMatch(String anchor, Set<String> nearbyBlockIds) {
        if (anchor == null || anchor.isBlank() || nearbyBlockIds.isEmpty()) {
            return false;
        }
        for (String blockId : nearbyBlockIds) {
            if (anchorMatchesBlock(anchor, blockId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code blockId} satisfies one anchor id under the namespace rule
     * shared by the whole class: an id without a namespace also matches the
     * {@code minecraft:} one.
     */
    private static boolean anchorMatchesBlock(String anchor, String blockId) {
        if (anchor == null || anchor.isBlank() || blockId == null || blockId.isBlank()) {
            return false;
        }
        return anchor.equals(blockId)
                || (!anchor.contains(":") && blockId.equals("minecraft:" + anchor));
    }

    /** The structures and tags found at one position, plus the anchor block's stored entity type. */
    private record StructureFacts(Set<String> ids, Set<String> tags, String anchorEntityType) {
    }
}
