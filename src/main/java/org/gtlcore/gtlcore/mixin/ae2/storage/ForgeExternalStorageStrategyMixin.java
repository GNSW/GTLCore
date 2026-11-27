package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.SophisticatedStorageIdentity;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.VanillaDoubleChestIdentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.capabilities.Capability;

import appeng.me.storage.ExternalStorageFacade;
import appeng.parts.automation.ForgeExternalStorageStrategy;
import appeng.parts.automation.HandlerStrategy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ForgeExternalStorageStrategy.class)
public abstract class ForgeExternalStorageStrategyMixin {

    @Unique
    private ServerLevel gtlcore$targetLevel;
    @Unique
    private BlockPos gtlcore$targetPos;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtlcore$captureTarget(Capability<?> capability, HandlerStrategy<?, ?> conversion,
                                       ServerLevel level, BlockPos fromPos, Direction fromSide,
                                       CallbackInfo ci) {
        this.gtlcore$targetLevel = level;
        this.gtlcore$targetPos = fromPos.immutable();
    }

    @WrapOperation(method = "createWrapper",
                   remap = false,
                   at = @At(value = "INVOKE", target = "Lappeng/parts/automation/HandlerStrategy;getFacade(Ljava/lang/Object;)Lappeng/me/storage/ExternalStorageFacade;"))
    private ExternalStorageFacade gtlcore$registerDoubleChest(HandlerStrategy<?, ?> conversion,
                                                              Object handler,
                                                              Operation<ExternalStorageFacade> original) {
        ExternalStorageFacade facade = original.call(conversion, handler);
        VanillaDoubleChestIdentity.register(gtlcore$targetLevel, gtlcore$targetPos, handler, facade);
        try {
            SophisticatedStorageIdentity.register(gtlcore$targetLevel, gtlcore$targetPos, handler, facade);
        } catch (LinkageError ignored) {
            // Sophisticated Storage is optional; skip when absent.
        }
        return facade;
    }
}
