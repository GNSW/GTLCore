/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

/** Contracts for relevant world-mutation hooks; unrelated installed mods do not disable vanilla terrain batching. */
public final class HostIntegration {
    public interface Observer {
        /** Veto only a world implementation whose mutation/storage hooks this observer cannot maintain. */
        default boolean supportsBulk(ServerLevel level) { return true; }
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, int sectionY) {}

        /** Previous states may be queried synchronously during this callback; do not retain the plan. */
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
            afterSectionCleared(level, chunk, previous.sectionY());
        }
    }
    private static final Map<String, Observer> observers = new LinkedHashMap<>();
    private static final Map<String, Predicate<BlockState>> removalProofs = new LinkedHashMap<>();
    private static final Set<String> requiredMutationObservers = new LinkedHashSet<>();
    private static List<Observer> activeObservers = List.of();
    static {
        Observer vanilla = new Observer() {};
        observers.put("minecraft", vanilla); observers.put("forge", vanilla); observers.put("voxelregion_test", vanilla);
        // These integrations install global multiblock/watch hooks even when the removed state is
        // vanilla. The host supplies their real aggregate bridge. UI, recipes and unrelated block
        // providers have no such blanket requirement; their unknown states use native callbacks.
        requiredMutationObservers.add("gtceu"); requiredMutationObservers.add("gtlcore");
        refreshObservers();
    }
    /** Register from mod setup, before a world runs. This asserts that all of that mod's relevant hooks are covered. */
    public static void registerAuditedMod(String modId, Observer observer) {
        if (modId == null || modId.isBlank() || observer == null) throw new IllegalArgumentException("Missing audited integration");
        if (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null)
            throw new IllegalStateException("Bulk integrations must be fixed before starting the server");
        observers.put(modId, observer);
        refreshObservers();
    }
    /** Declare another known global mutation hook; when its mod is loaded an observer is mandatory. */
    public static void requireMutationObserver(String modId) {
        checkSetup(modId);
        requiredMutationObservers.add(modId);
    }
    /**
     * Exact state/class proof for an embedding host's non-emitting terrain blocks. The proof must
     * cover onRemove and all inherited removal hooks, not just namespace or blast resistance.
     * Block entities, POIs, fluids and emitters remain native regardless of this predicate.
     */
    public static void registerStaticRemovalProof(String integrationId, Predicate<BlockState> proof) {
        checkSetup(integrationId);
        if (proof == null) throw new IllegalArgumentException("Missing state proof");
        removalProofs.put(integrationId, proof);
    }
    static boolean provesStaticRemoval(BlockState state) {
        return removalProofs.values().stream().anyMatch(proof -> proof.test(state));
    }
    public static boolean supportsBulk(ServerLevel level) {
        if (!(level.getChunkSource().getLightEngine() instanceof NativeAccess.LightFence)) return false;
        for (String modId : requiredMutationObservers)
            if (ModList.get().isLoaded(modId) && !observers.containsKey(modId)) return false;
        return activeObservers.stream().allMatch(observer -> observer.supportsBulk(level));
    }
    public static void afterSectionCleared(ServerLevel level, LevelChunk chunk, int sectionY) {
        for (var observer : activeObservers) observer.afterSectionCleared(level, chunk, sectionY);
    }
    public static void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
        for (var observer : activeObservers) observer.afterSectionCleared(level, chunk, previous);
    }
    private static void refreshObservers() {
        Set<Observer> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Observer> unique = new ArrayList<>();
        for (Observer observer : observers.values()) if (identities.add(observer)) unique.add(observer);
        activeObservers = List.copyOf(unique);
    }
    private static void checkSetup(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing integration id");
        if (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null)
            throw new IllegalStateException("Bulk integrations must be fixed before starting the server");
    }
    private HostIntegration() {}
}
