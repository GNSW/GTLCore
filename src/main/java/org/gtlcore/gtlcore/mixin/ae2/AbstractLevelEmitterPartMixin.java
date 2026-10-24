package org.gtlcore.gtlcore.mixin.ae2;

import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.parts.IPartItem;
import appeng.parts.automation.AbstractLevelEmitterPart;
import appeng.parts.automation.UpgradeablePart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractLevelEmitterPart.class)
public abstract class AbstractLevelEmitterPartMixin extends UpgradeablePart {

    @Shadow(remap = false)
    protected long lastReportedValue;

    @Shadow(remap = false)
    private long reportingValue;

    public AbstractLevelEmitterPartMixin(IPartItem<?> partItem) {
        super(partItem);
    }

    @Inject(method = "isLevelEmitterOn",
            at = @At(value = "INVOKE",
                     target = "Lappeng/parts/automation/AbstractLevelEmitterPart;getConfigManager()Lappeng/api/util/IConfigManager;"),
            cancellable = true,
            remap = false)
    private void gtlcore$compareLevelWithoutOverflow(CallbackInfoReturnable<Boolean> cir) {
        // Adding one to a saturated inventory amount would wrap to Long.MIN_VALUE.
        boolean belowThreshold = lastReportedValue < reportingValue;
        cir.setReturnValue(getConfigManager().getSetting(Settings.REDSTONE_EMITTER) == RedstoneMode.LOW_SIGNAL ?
                belowThreshold : !belowThreshold);
    }
}
