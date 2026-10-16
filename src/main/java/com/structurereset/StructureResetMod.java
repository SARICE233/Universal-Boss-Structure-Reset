package com.structurereset;

import com.mojang.logging.LogUtils;
import com.structurereset.command.StructureResetCommand;
import com.structurereset.config.StructureResetConfig;
import com.structurereset.core.StructureResetApi;
import com.structurereset.impl.AutoLearnHandler;
import com.structurereset.impl.BossChecklistBridge;
import com.structurereset.impl.DefinitionReloadListener;
import com.structurereset.impl.LootCaptureHandler;
import com.structurereset.impl.ResetActivation;
import com.structurereset.impl.ResetAim;
import com.structurereset.impl.ResetConfirmation;
import com.structurereset.impl.action.ClearEntitiesAction;
import com.structurereset.impl.action.MessageAction;
import com.structurereset.impl.action.RefillLootAction;
import com.structurereset.impl.action.ResetBlocksAction;
import com.structurereset.impl.action.RespawnBossAction;
import com.structurereset.impl.action.RestoreLearnedAnchorAction;
import com.structurereset.impl.action.RunCommandAction;
import com.structurereset.registry.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Main entry point of the standalone "Structure Reset" addon.
 *
 * <p>The constructor wires the mod together: the two configuration specs, the
 * data pack driven definition loader, the seven built in reset actions and the
 * {@code /structurereset} command tree.  The Minecraft facing public API lives
 * in {@code com.structurereset.core}, the pure logic it builds on in
 * {@code com.structurereset.api}, and the decision logic in
 * {@code com.structurereset.impl.ResetEngine}.</p>
 */
@Mod(StructureResetMod.MODID)
public class StructureResetMod {

    /** The mod id, matching {@code mods.toml} and every {@code assets/} path. */
    public static final String MODID = "structurereset";

    /** Shared logger of the addon. */
    public static final Logger LOGGER = LogUtils.getLogger();

    public StructureResetMod(FMLJavaModLoadingContext context) {
        // Forge 1.20.1 FMLModContainer.constructMod() only supports a
        // (FMLJavaModLoadingContext) or a no-arg @Mod constructor, so the mod
        // event bus is taken from the constructor-injected context instead of
        // the deprecated static FMLJavaModLoadingContext#get() accessor.
        IEventBus modEventBus = context.getModEventBus();
        LOGGER.info("[{}] loading Structure Reset (Forge 1.20.1 addon)", MODID);
        StructureResetConfig.register();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                AddReloadListenerEvent.class, StructureResetMod::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                RegisterCommandsEvent.class, StructureResetMod::onRegisterCommands);
        // The optional Boss Checklist bridge refreshes its cached id list when the
        // data packs are synced with a joining player. Its reload listener below
        // covers the other half (the initial load and /reload), and both are no-ops
        // while Boss Checklist is not installed: nothing here is per tick.
        MinecraftForge.EVENT_BUS.addListener(BossChecklistBridge::onDatapackSync);
        // A pending two click confirmation is dropped whenever the player
        // leaves, changes dimension or dies: none of those may carry an armed
        // reset into the next context.
        MinecraftForge.EVENT_BUS.addListener(ResetConfirmation::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ResetConfirmation::onPlayerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(ResetConfirmation::onLivingDeath);
        // The activation window of the confirmation needs the same three hooks
        // (they additionally drop the active item model mark), plus one server
        // tick handler that shows the countdown and closes the window.
        MinecraftForge.EVENT_BUS.addListener(ResetActivation::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ResetActivation::onPlayerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(ResetActivation::onLivingDeath);
        MinecraftForge.EVENT_BUS.addListener(ResetActivation::onPlayerTick);
        // The left mouse button captures a structure's loot tables; it is a
        // separate path from the right mouse button reset and shares nothing
        // with it beyond the structure lookup and the loot index store.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                PlayerInteractEvent.LeftClickBlock.class, LootCaptureHandler::onLeftClickBlock);
        // The optional automatic learning layer: off unless the pack turns it on.
        MinecraftForge.EVENT_BUS.addListener(AutoLearnHandler::onEntityJoinLevel);
        MinecraftForge.EVENT_BUS.addListener(AutoLearnHandler::onLivingDeath);
        // /kill and every other removal never fire a death event, so the removal
        // itself is observed as well.
        MinecraftForge.EVENT_BUS.addListener(AutoLearnHandler::onEntityLeaveLevel);
        // A cast bar belongs to one player: leaving the game ends it, so no bar
        // is left behind for the next session.
        // A cast bar is a server side object: when the server stops, every bar it
        // still holds is released here.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppingEvent event) -> {
            com.structurereset.impl.CastBar.stopAll();
            com.structurereset.impl.SpawnWatcher.clear();
            // A cast in flight owns block_display entities; without this they stay in
            // the save file as ordinary entities after a shutdown or a crash.
            com.structurereset.impl.CastEffect.clearAll();
        });
        // Every spawned boss is looked at again a few seconds later, so a boss that
        // was pushed away or that vanished is visible in the log instead of leaving
        // the player with a summon effect and nothing to fight.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent event) -> {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
                com.structurereset.impl.SpawnWatcher.tick(event.getServer());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer leaving) {
                com.structurereset.impl.CastBar.stop(leaving);
                // A cast owns block entities: leaving the game must not leave them
                // circling a spot forever.
                com.structurereset.impl.CastEffect.clear(leaving);
            }
        });
        // The crosshair driven respawn: aiming and casting advance per tick, and a
        // pending aim never survives a logout, a dimension change or death.
        MinecraftForge.EVENT_BUS.addListener(ResetAim::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(ResetAim::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ResetAim::onPlayerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(ResetAim::onLivingDeath);
        registerBuiltInActions();
        ModItems.register(modEventBus);
    }

    /** Loads {@code data/<namespace>/structure_resets/**} on every resource reload. */
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new DefinitionReloadListener());
        LOGGER.debug("[{}] registered the structure reset definition reload listener", MODID);
        // The optional Boss Checklist bridge reads its id list through the resource
        // manager of whichever reload is in flight, so recognition never touches the
        // disk. The listener does nothing at all when the mod is absent.
        event.addListener(new BossChecklistBridge.ReloadListener());
    }

    /** Adds the {@code /structurereset} command tree to every server dispatcher. */
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        StructureResetCommand.register(event.getDispatcher());
        LOGGER.debug("[{}] registered the /structurereset command tree", MODID);
    }

    /**
     * Registers the seven built in action types, all of them in the
     * {@code structurereset} namespace: {@code respawn_boss}, {@code refill_loot},
     * {@code reset_blocks}, {@code clear_entities}, {@code run_command},
     * {@code message} and {@code restore_learned_anchor}.
     */
    public static void registerBuiltInActions() {
        StructureResetApi.registerActionType(RespawnBossAction.ID, new RespawnBossAction());
        StructureResetApi.registerActionType(RefillLootAction.ID, new RefillLootAction());
        StructureResetApi.registerActionType(ResetBlocksAction.ID, new ResetBlocksAction());
        StructureResetApi.registerActionType(ClearEntitiesAction.ID, new ClearEntitiesAction());
        StructureResetApi.registerActionType(RunCommandAction.ID, new RunCommandAction());
        StructureResetApi.registerActionType(MessageAction.ID, new MessageAction());
        StructureResetApi.registerActionType(RestoreLearnedAnchorAction.ID,
                new RestoreLearnedAnchorAction());
        LOGGER.info("[{}] registered {} built in reset action(s): {}",
                MODID, StructureResetApi.registeredActionTypeIds().size(),
                StructureResetApi.registeredActionTypeIds());
    }
}
