package com.structurereset.impl;

import com.mojang.logging.LogUtils;
import com.structurereset.api.BossChecklistIds;
import com.structurereset.config.StructureResetConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * The optional bridge to <em>Boss Checklist</em> ({@code boss_checklist}), used
 * as the last acceptance step of boss recognition.
 *
 * <p>The brief (.owv/notes/boss-tags.md) established that Boss Checklist ships
 * two plain entity id lists and no entity tag at all. This addon is server side,
 * so the file that matters is the data pack one,
 * {@code data/boss_checklist/server_bosses_ids.json}, a JSON array of objects of
 * the form {@code {"id": "minecraft:wither"}}. Nothing here references a Boss
 * Checklist class: the mod is a soft dependency declared
 * {@code mandatory=false} in {@code META-INF/mods.toml}, and every entry point
 * of this bridge answers "empty" the moment
 * {@code ModList.get().isLoaded("boss_checklist")} is false.</p>
 *
 * <p>The list is read <em>only</em> from the server resource manager, never from
 * the class path: {@link #reload(ResourceManager)} is driven by
 * {@link ReloadListener} (registered on {@code AddReloadListenerEvent}, so every
 * data pack reload and every {@code /reload}) and by
 * {@link #onDatapackSync(OnDatapackSyncEvent)} (a player joining), and the parsed
 * ids are then served from a volatile cache. No query touches the disk, so
 * classification can never turn one entity into one file read.</p>
 *
 * <p>Degrading cleanly is the point: an absent mod, an absent file (the mod is
 * documented as client-only/server-optional, so the file may simply not be
 * there) or a malformed one all mean "no hint" - the same answer as a list that
 * does not name the entity. Exactly one warning is logged per change of state,
 * never one per entity.</p>
 */
public final class BossChecklistBridge {

    /** modId of the optional mod this bridge talks to. */
    public static final String BOSS_CHECKLIST_MOD_ID = BossChecklistIds.NAMESPACE;

    /**
     * The server data pack file the boss ids live in.
     *
     * <p>It is a data pack resource, not an asset: the server resource manager
     * resolves this id to {@code data/boss_checklist/server_bosses_ids.json}, the
     * path inside the Boss Checklist jar.</p>
     */
    public static final ResourceLocation SERVER_BOSS_IDS = new ResourceLocation(
            BossChecklistIds.NAMESPACE, BossChecklistIds.RESOURCE_PATH);

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The ids of the last successful load; empty until one happened. */
    private static volatile Set<String> ids = Set.of();
    /** True when the last load read a usable list. */
    private static volatile boolean loaded;
    /** True once the current missing/malformed state has been warned about. */
    private static volatile boolean warned;

    private BossChecklistBridge() {
    }

    /**
     * True when Boss Checklist is installed.
     *
     * <p>A false answer is free: no resource is looked up and no cache is read,
     * so every caller may ask this before anything else.</p>
     */
    public static boolean isPresent() {
        return ModList.get().isLoaded(BOSS_CHECKLIST_MOD_ID);
    }

    /** True when the last load read a usable list; false when it did not. */
    public static boolean isLoaded() {
        return loaded;
    }

    /** How many entity ids the last load read; 0 when nothing was read or the mod is absent. */
    public static int size() {
        return isPresent() ? ids.size() : 0;
    }

    /** The entity ids of the last load, in file order; never null, empty when the mod is absent. */
    public static Set<String> ids() {
        return isPresent() ? ids : Set.of();
    }

    /**
     * True when the optional Boss Checklist list names this entity type.
     *
     * <p>False for a null type, for an unregistered type, when the mod is absent
     * and when the cache is empty: a caller can treat it as the last hint before
     * rejecting a target.</p>
     */
    public static boolean isListedBoss(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return id != null && contains(id);
    }

    /** True when the optional Boss Checklist list names this entity id. */
    public static boolean isListedBoss(ResourceLocation id) {
        return contains(id);
    }

    /**
     * True when the cached list names {@code id}.
     *
     * <p>This is the query every classification calls: it is a set lookup, it
     * returns false immediately when Boss Checklist is not installed, and it
     * never performs IO.</p>
     */
    public static boolean contains(ResourceLocation id) {
        if (id == null || !isPresent()) {
            return false;
        }
        return ids.contains(id.toString());
    }

    /**
     * Re-reads the id list through {@code manager} and replaces the cache.
     *
     * <p>Never throws and never logs more than one warning per change of state: a
     * manager that is null, a file that is absent, unreadable or malformed, and a
     * Boss Checklist that is not installed all leave the cache empty and simply
     * answer "no hint" afterwards.</p>
     *
     * @param manager the resource manager of the reload in flight; null is
     *        treated as "no file"
     */
    public static void reload(ResourceManager manager) {
        if (!isPresent()) {
            // Not installed: no file is consulted at all and the cache is dropped,
            // so a dev environment that removes the mod cannot keep stale ids.
            forget();
            warned = false;
            return;
        }
        boolean found = false;
        String text = null;
        if (manager != null) {
            Optional<Resource> resource = manager.getResource(SERVER_BOSS_IDS);
            if (resource.isPresent()) {
                found = true;
                try (BufferedReader reader = resource.get().openAsReader()) {
                    text = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                } catch (IOException | RuntimeException unreadable) {
                    text = null;
                    LOGGER.debug("[{}] the Boss Checklist id list could not be read from {}",
                            StructureResetConfig.MOD_ID, SERVER_BOSS_IDS, unreadable);
                }
            }
        }
        BossChecklistIds parsed = BossChecklistIds.parse(text);
        if (!found || !parsed.usable()) {
            forget();
            if (!warned) {
                warned = true;
                LOGGER.warn("[{}] Boss Checklist is installed but {} is missing or malformed; "
                                + "its boss list is ignored and recognition continues with the "
                                + "entity tags and the NBT keys",
                        StructureResetConfig.MOD_ID, SERVER_BOSS_IDS);
            }
            return;
        }
        ids = parsed.ids();
        loaded = true;
        warned = false;
        if (parsed.skipped() > 0) {
            LOGGER.debug("[{}] skipped {} malformed entr(y/ies) of {}",
                    StructureResetConfig.MOD_ID, parsed.skipped(), SERVER_BOSS_IDS);
        }
        LOGGER.debug("[{}] loaded {} boss id(s) from {}",
                StructureResetConfig.MOD_ID, parsed.size(), SERVER_BOSS_IDS);
    }

    /** Empties the cache; the single place the two cache fields are cleared. */
    private static void forget() {
        ids = Set.of();
        loaded = false;
    }

    /**
     * The data pack reload listener of the bridge.
     *
     * <p>Registered once on {@code AddReloadListenerEvent}, it re-reads the list
     * with the resource manager of whichever reload is in flight - the initial
     * world load and every {@code /reload} - and it does nothing at all when Boss
     * Checklist is absent.</p>
     */
    public static final class ReloadListener implements PreparableReloadListener {

        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                              ProfilerFiller prepProfiler, ProfilerFiller reloadProfiler,
                                              Executor backgroundExecutor, Executor gameExecutor) {
            // The read is a few kilobytes, so it is done inline before the barrier:
            // waiting first would only delay the answer the barrier hands on.
            BossChecklistBridge.reload(manager);
            return barrier.wait(Unit.INSTANCE).thenRun(() -> {
                // Nothing to apply: the cache was replaced above.
            });
        }

        @Override
        public String getName() {
            return StructureResetConfig.MOD_ID + ":boss_checklist";
        }
    }

    /**
     * Refreshes the cache when a player joins and the server syncs its data packs.
     *
     * <p>Together with {@link ReloadListener} this is the whole refresh story: the
     * list is never read from a tick handler and never from a classification.</p>
     */
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event == null || event.getPlayerList() == null) {
            return;
        }
        MinecraftServer server = event.getPlayerList().getServer();
        reload(server == null ? null : server.getResourceManager());
    }
}
