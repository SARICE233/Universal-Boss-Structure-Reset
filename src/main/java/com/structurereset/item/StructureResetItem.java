package com.structurereset.item;

import com.structurereset.StructureResetMod;
import com.structurereset.api.ResetDefinition;
import com.structurereset.client.StructureResetClient;
import com.structurereset.core.ItemSettingsRegistry;
import com.structurereset.core.ResetResult;
import com.structurereset.core.StructureResetApi;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.impl.BossBindingService;
import com.structurereset.impl.LearnedAnchorService;
import com.structurereset.impl.ResetActivation;
import com.structurereset.impl.ResetAim;
import com.structurereset.impl.ResetConfirmation;
import com.structurereset.impl.ResetEngine;
import com.structurereset.impl.ResetPrompt;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

/**
 * The structure reset core.
 *
 * <p>Using the item asks {@link StructureResetApi} which reset definition
 * applies to the block the player stands in. The reset itself takes two right
 * clicks: the first one executes nothing at all and only announces what would
 * happen ({@link ResetPrompt}) and opens the activation window
 * ({@code confirmWindowSeconds}, three seconds by default) during which the
 * item shows its active model; the second one inside that window runs the
 * reset, puts the item on cooldown, tells the player what happened and plays a
 * chime. A refused attempt only explains why: it neither runs a reset nor
 * consumes anything.</p>
 *
 * <p>One successful reset costs one durability point, so a freshly crafted core
 * pays for {@link #MAX_USES} resets and then breaks. The item property declares
 * exactly that, and {@link #getMaxDamage(ItemStack)} lets
 * {@code ItemSettingsRegistry} raise or lower the number. The item itself never
 * consumes a whole stack: the durability cost is charged by
 * {@code ResetEngine} after a successful reset and only when the configuration
 * ({@code consumeItem}), the definition ({@code consume_item}) and the creative
 * flag all allow it, so the cost cannot be charged twice. An unbreakable item
 * setting stops that cost completely.</p>
 *
 * <p>Sneaking and using the core on a mob takes the other branch: instead of
 * firing a reset, {@link LearnedAnchorService#teach} records the structure the
 * mob stands in, the boss entity itself and the still unlooted containers around
 * it, and the item only reports the outcome on the action bar. Teaching is free
 * by default; only the {@code teachConsumes} item setting makes a successful
 * teaching cost one durability point. With {@code learningRequiresSneak = false}
 * a plain right click teaches too.</p>
 *
 * <p>No Mixin and no client class is referenced directly: the "is the core
 * ready" tooltip line is delegated to {@link StructureResetClient}, which is
 * only touched on the physical client.</p>
 */
public class StructureResetItem extends Item {

    /** Fallback cooldown (ticks) used when a definition declares none. */
    public static final int DEFAULT_COOLDOWN_TICKS = 20;

    /**
     * How many successful resets one core can pay for.
     *
     * <p>The {@link Item.Properties} below spell the same value out as
     * {@code durability(5)}; this constant is the public mirror scripts and
     * addons can read.</p>
     */
    public static final int MAX_USES = 5;

    /** Upper bound (ticks) for the cooldown taken from a definition. */
    public static final int MAX_COOLDOWN_TICKS = 20 * 60;

    /** The item property set every instance uses: one per stack, five resets per core. */
    public StructureResetItem() {
        this(new Item.Properties().stacksTo(1).durability(5));
    }

    /**
     * @param properties the item properties to use
     */
    public StructureResetItem(Item.Properties properties) {
        super(properties);
    }

    /**
     * The effective number of resets one core pays for.
     *
     * <p>{@code Item.Properties#durability(5)} is only the registration time
     * default; the value really in force comes from
     * {@link ItemSettingsRegistry}, so a data pack or a KubeJS script can raise
     * or lower the number of uses without a new item registration.</p>
     *
     * <p>{@code net.minecraftforge.common.extensions.IForgeItem} declares this
     * as a default method taking the stack, so overriding it here is all that is
     * needed: vanilla reads the item's maximum damage through this method.</p>
     *
     * @param stack the stack whose maximum damage is asked for
     * @return the configured maximum number of uses, always at least 1
     */
    @Override
    public int getMaxDamage(ItemStack stack) {
        return ItemSettingsRegistry.get(this).maxUses();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (serverPlayer.isShiftKeyDown()) {
            // Sneaking is the scan path. A sneak right click while looking at a
            // block arrives through useOn, and one into thin air arrives here;
            // both scan the structure the player is standing in, because a scan
            // does not care where the crosshair points. Sneaking never starts a
            // reset.
            return scan(serverPlayer, serverLevel, stack);
        }

        BlockPos pos = serverPlayer.blockPosition();
        // The lookup also says *why* nothing was chosen: a plain "nothing here"
        // differs from an anchor block whose stored boss entity leaves several
        // candidate definitions tied, which must not silently reset the wrong boss.
        ResetEngine.DefinitionLookup lookup = StructureResetApi.lookupDefinitionAt(serverLevel, pos, serverPlayer);

        // The cooldown a reset triggered by this item starts: the item setting
        // wins, and 0 means "use the definition's own cooldown".
        int itemCooldownSeconds = ItemSettingsRegistry.get(this).cooldownSeconds();

        // The reset is aimed with the crosshair: the first click only announces
        // and starts the aiming phase (particles follow the crosshair, nothing is
        // spawned or consumed), the second click locks that spot and starts the
        // cast, and the boss appears where the cast was pointed at.
        String phase = ResetAim.phase(serverPlayer, serverLevel);
        if (ResetAim.PHASE_CASTING.equals(phase)) {
            // the cast is already running: another click changes nothing
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        ResetDefinition definition = lookup.definition();
        if (ResetAim.PHASE_AIMING.equals(phase)) {
            // The second click locks the spot and starts the cast. The "one boss at a
            // time" gate runs again here: a boss can appear between the two clicks,
            // and a cast that already started could not be taken back.
            java.util.Optional<String> appeared = StructureResetConfig.resetRequiresDefeated()
                    ? BossBindingService.aliveBossId(serverLevel, pos) : java.util.Optional.empty();
            if (appeared.isPresent()) {
                ResetAim.clear(serverPlayer);
                serverPlayer.displayClientMessage(Component.translatable(ResetAim.MESSAGE_DEFEATED_NEEDED,
                        appeared.get()).withStyle(ChatFormatting.RED), true);
                serverLevel.playSound(null, pos, SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.2F);
                return InteractionResultHolder.fail(stack);
            }
            ResetAim.lockAndCast(serverPlayer, serverLevel,
                    definition == null ? null : definition.id());
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (definition == null) {
            // A refused use consumes nothing; the core is only consumed by
            // ResetEngine after a successful reset.
            String refusalKey = ResetEngine.MESSAGE_AMBIGUOUS.equals(lookup.messageKey())
                    ? ResetEngine.MESSAGE_AMBIGUOUS
                    : "item.structurereset.structure_reset_core.no_structure";
            return refuse(serverLevel, pos, serverPlayer, stack, refusalKey);
        }

        // force = false keeps the structure cooldown and the global enable switch in charge.
        // The refusal names its reason: "not ready" alone told the player nothing.
        ResetEngine.ResetBlocker blocker = ResetEngine.blockerFor(serverLevel, pos, serverPlayer,
                definition.id(), false, itemCooldownSeconds);
        if (blocker != null) {
            serverPlayer.displayClientMessage(
                    Component.translatable(blocker.messageKey(), blocker.seconds())
                            .withStyle(ChatFormatting.RED), true);
            serverLevel.playSound(null, pos, SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.2F);
            return InteractionResultHolder.fail(stack);
        }

        // A boss that has not been beaten yet may not be summoned a second time -
        // but only a structure the registry has really observed is refused, so a
        // never visited structure can never be locked out of a reset.
        if (StructureResetConfig.resetRequiresDefeated()) {
            java.util.Optional<String> alive = BossBindingService.aliveBossId(serverLevel, pos);
            if (alive.isPresent()) {
                // Name the boss: after a successful reset the boss is back, and
                // "not beaten yet" alone reads like a bug to the player.
                serverPlayer.displayClientMessage(Component.translatable(ResetAim.MESSAGE_DEFEATED_NEEDED,
                        alive.get()).withStyle(ChatFormatting.RED), true);
                serverLevel.playSound(null, pos, SoundEvents.DISPENSER_FAIL,
                        SoundSource.PLAYERS, 0.7F, 1.2F);
                return InteractionResultHolder.fail(stack);
            }
        }

        // Every refusal is decided above this line, so a structure that cannot be
        // reset right now never prints the prompt at all - least of all the "right
        // click again to lock and cast" line for a click that was going to fail.
        ResetPrompt.send(serverPlayer, serverLevel, pos, lookup);
        if (!ResetAim.beginAiming(serverPlayer, serverLevel, definition.id())) {
            // the crosshair hit nothing usable: nothing was armed, nothing consumed
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        ResetActivation.activate(serverPlayer, serverLevel, definition.id(), stack);
        serverPlayer.displayClientMessage(Component.translatable(ResetAim.MESSAGE_AIMING), true);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * Teaching entry point: sneaking and using the core on a living entity
     * records a learned anchor through {@link LearnedAnchorService}.
     *
     * <p>Without sneaking nothing happens here at all, so the ordinary "use on
     * a mob" interactions of the target keep working.  The client only predicts
     * the swing animation, and the outcome is decided on the server: a refused
     * teaching reports {@code result.messageKey()} in red and plays the failure
     * sound, a recorded one reports {@code result.message()} and plays the
     * familiar amethyst chime.</p>
     *
     * <p>The item path is the strict one: the player has to stand inside a
     * recognised structure and to actually look at the target. The command twin
     * ({@code /structurereset learned teach-here}) skips exactly those two
     * gates through
     * {@link LearnedAnchorService#teachCommand(net.minecraft.server.level.ServerPlayer, net.minecraft.world.entity.LivingEntity)}.</p>
     */
    /**
     * Sneaking and using the core on a block scans the structure the player
     * stands in for its boss, without aiming at anything.
     *
     * <p>This is the answer to bosses a right click can never reach: a large or
     * multi part entity (a hydra, or a naga that only reacts on its head) never
     * produces a server side interaction at all, so the aim based path simply
     * cannot record it. The scan does not care about hitboxes, reach or
     * movement.</p>
     *
     * <p>A plain (non sneaking) right click on a block is left to the two click
     * reset path, so nothing here changes how a reset is started.</p>
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        InteractionResultHolder<ItemStack> outcome =
                scan(serverPlayer, level, serverPlayer.getMainHandItem());
        return outcome.getResult().consumesAction() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    /** Shown when the structure scan itself broke, instead of failing silently. */
    public static final String SCAN_FAILED = "item.structurereset.structure_reset_core.scan.failed";

    /**
     * Runs the structure scan and answers the player, whatever happens.
     *
     * <p>Every failure mode becomes a message: a refusal from the scan itself
     * and also an unexpected exception. A scan that throws must never look like
     * "the click did nothing" - that is exactly how a broken scan was
     * misreported as "shift right click does not work".</p>
     *
     * @param player the scanning player, required
     * @param level the level the player is in, required
     * @param stack the held core, used for the interaction result
     * @return the interaction result for the caller
     */
    private static InteractionResultHolder<ItemStack> scan(ServerPlayer player, ServerLevel level,
                                                           ItemStack stack) {
        LearnedAnchorService.TeachResult result;
        try {
            result = LearnedAnchorService.scanStructure(player);
        } catch (RuntimeException failure) {
            StructureResetMod.LOGGER.warn("[{}] structure scan failed for {}",
                    StructureResetMod.MODID, player.getName().getString(), failure);
            player.displayClientMessage(Component.translatable(SCAN_FAILED)
                    .withStyle(ChatFormatting.RED), true);
            level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL,
                    SoundSource.PLAYERS, 0.7F, 1.2F);
            return InteractionResultHolder.fail(stack);
        }
        if (!result.success()) {
            player.displayClientMessage(
                    Component.translatable(result.messageKey()).withStyle(ChatFormatting.RED), true);
            level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL,
                    SoundSource.PLAYERS, 0.7F, 1.2F);
            return InteractionResultHolder.fail(stack);
        }
        player.displayClientMessage(result.message(), true);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        return InteractionResultHolder.success(stack);
    }

    /**
     * The vanilla item cooldown a reset triggered by this core applies.
     *
     * <p>Exposed so the crosshair driven respawn applies exactly the cooldown
     * the plain click path applies: the item setting wins, and the definition's
     * own cooldown is the fallback.</p>
     *
     * @param definition the definition that was reset, may be null
     * @return the cooldown in ticks
     */
    public static int cooldownTicksFor(ResetDefinition definition) {
        return cooldownTicks(ItemSettingsRegistry.get(
                com.structurereset.registry.ModItems.STRUCTURE_RESET_CORE.get()).cooldownSeconds(),
                definition);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (StructureResetConfig.learningRequiresSneak() && !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        LearnedAnchorService.TeachResult result = LearnedAnchorService.teach(serverPlayer, target);
        if (!result.success()) {
            serverPlayer.displayClientMessage(
                    Component.translatable(result.messageKey()).withStyle(ChatFormatting.RED), true);
            level.playSound(null, serverPlayer.blockPosition(), SoundEvents.DISPENSER_FAIL,
                    SoundSource.PLAYERS, 0.7F, 1.2F);
            return InteractionResult.FAIL;
        }
        // A successful teaching costs one durability point only when the item
        // settings ask for it; with the shipped default it is completely free
        // and therefore unlimited.
        chargeTeaching(serverPlayer, stack, hand);
        serverPlayer.displayClientMessage(result.message(), true);
        level.playSound(null, serverPlayer.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        return InteractionResult.SUCCESS;
    }

    /**
     * Spends one durability point for a successful teaching, when the item
     * settings of this core ask for it.
     *
     * <p>The guards are the same as the ones a reset uses: a creative player
     * consumes nothing, a stack that cannot be damaged or is unbreakable is
     * never touched, and a missing stack is a silent no-op.</p>
     *
     * @param player the player that just taught successfully
     * @param stack the core stack that was used on the target
     * @param hand the hand the core is held in
     */
    private void chargeTeaching(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        if (player == null || !ItemSettingsRegistry.get(this).teachConsumes()) {
            return;
        }
        if (player.isCreative() || stack == null || stack.isEmpty()
                || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.getBoolean("Unbreakable")) {
            return;
        }
        EquipmentSlot slot = hand == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        stack.hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(slot));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.structurereset.structure_reset_core.tooltip")
                .withStyle(ChatFormatting.GRAY));
        boolean ready = FMLEnvironment.dist == Dist.CLIENT && StructureResetClient.isReady(stack, level);
        tooltip.add(Component.translatable(ready
                        ? "item.structurereset.structure_reset_core.ready"
                        : "item.structurereset.structure_reset_core.cooldown")
                .withStyle(ready ? ChatFormatting.GREEN : ChatFormatting.RED));
        if (stack.getMaxDamage() > 0) {
            tooltip.add(Component.translatable("item.structurereset.structure_reset_core.uses",
                            Math.max(0, stack.getMaxDamage() - stack.getDamageValue()),
                            stack.getMaxDamage())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Item cooldown the core applies after a successful reset, clamped to a
     * sane range.
     *
     * @param overrideSeconds the item setting: a positive value replaces the
     *        definition's own cooldown, anything else keeps it
     * @param definition the definition that was just reset
     */
    private static int cooldownTicks(int overrideSeconds, ResetDefinition definition) {
        int seconds = overrideSeconds > 0 ? overrideSeconds : definition.cooldownSeconds();
        if (seconds <= 0) {
            return DEFAULT_COOLDOWN_TICKS;
        }
        return Math.min(seconds * 20, MAX_COOLDOWN_TICKS);
    }

    /** Reports a refused use on the action bar, plays the failure sound and consumes nothing. */
    private static InteractionResultHolder<ItemStack> refuse(ServerLevel level, BlockPos pos,
                                                             ServerPlayer player, ItemStack stack,
                                                             String translationKey) {
        player.displayClientMessage(Component.translatable(translationKey).withStyle(ChatFormatting.RED), true);
        level.playSound(null, pos, SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.2F);
        return InteractionResultHolder.fail(stack);
    }
}
