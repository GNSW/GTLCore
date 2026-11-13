package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.pattern.INewMultiblockWorldSavedData;
import org.gtlcore.gtlcore.api.pattern.PatternRetryIndex;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Implements(@Interface(
                       iface = INewMultiblockWorldSavedData.class,
                       prefix = "gTLCore$"))
@Mixin(MultiblockWorldSavedData.class)
public abstract class MultiblockWorldSavedDataMixin {

    @Shadow(remap = false)
    @Final
    private ServerLevel serverLevel;

    @Unique
    private final PatternRetryIndex gtlcore$patternRetries = new PatternRetryIndex();

    @Shadow(remap = false)
    @Final
    public Map<BlockPos, MultiblockState> mapping;
    @Shadow(remap = false)
    @Final
    public Map<ChunkPos, Set<MultiblockState>> chunkPosMapping;
    @Shadow(remap = false)
    @Final
    private CopyOnWriteArrayList<IMultiController> controllers;

    @Shadow(remap = false)
    public abstract void createExecutorService();

    /**
     * @author Dragons
     * @reason A controller needs only one outstanding asynchronous search registration.
     */
    @Overwrite(remap = false)
    public void addAsyncLogic(IMultiController controller) {
        if (controllers.addIfAbsent(controller)) {
            serverLevel.getServer().execute(() -> {
                if (controllers.contains(controller) && !controller.self().isInValid()) gtlcore$patternRetries.watch(controller);
            });
        }
        createExecutorService();
    }

    @Inject(method = "removeAsyncLogic", at = @At("RETURN"), remap = false)
    private void gtlcore$removeRetryListener(IMultiController controller, CallbackInfo ci) {
        serverLevel.getServer().execute(() -> {
            if (!controllers.contains(controller)) gtlcore$patternRetries.remove(controller);
        });
    }

    @Unique
    public PatternRetryIndex gTLCore$getPatternRetries() {
        return gtlcore$patternRetries;
    }

    /**
     * @author screret
     * @reason Performance and thread-safety
     */
    @Overwrite(remap = false)
    public void addMapping(MultiblockState state) {
        this.mapping.put(state.controllerPos, state);
        for (BlockPos blockPos : state.getCache()) {
            chunkPosMapping.computeIfAbsent(new ChunkPos(blockPos), c -> ConcurrentHashMap.newKeySet()).add(state);
        }
    }

    /**
     * @author screret
     * @reason Performance
     */
    @Overwrite(remap = false)
    public void removeMapping(MultiblockState state) {
        this.mapping.remove(state.controllerPos);
        for (Set<MultiblockState> set : chunkPosMapping.values()) {
            set.remove(state);
        }
    }

    /**
     * @author screret
     * @reason Performance
     */
    @Overwrite(remap = false)
    public MultiblockState[] getControllerInChunk(ChunkPos chunkPos) {
        return new MultiblockState[0];
    }

    @Unique
    public Set<MultiblockState> gTLCore$getControllersInChunk(ChunkPos chunkPos) {
        return chunkPosMapping.getOrDefault(chunkPos, Collections.emptySet());
    }
}
