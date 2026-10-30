/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.bootstrap;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Native storage hooks and the global chunk-owned provider must never coexist. */
public final class LightingMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> NATIVE_ONLY = Set.of(
            "LightFenceMixin", "VisibleLightEngineMixin", "VisibleLightStorageMixin", "SkySourcesAccessMixin");

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        return !NATIVE_ONLY.contains(simpleName)
                || !ca.spottedleaf.starlight.common.config.GlobalLightConfig.enabled();
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) { }
}
