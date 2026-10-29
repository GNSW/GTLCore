package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.server.level.ChunkHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public interface GenerationSaveFenceAccessor {

    @Invoker("addSaveDependency")
    void gtlcore$addGenerationSaveDependency(String reason, CompletableFuture<?> future);
}
