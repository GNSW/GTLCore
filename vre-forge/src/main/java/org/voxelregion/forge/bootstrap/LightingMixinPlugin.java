/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.bootstrap;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Selects the owner-thread fence without changing virtual worlds' light contract. */
public final class LightingMixinPlugin implements IMixinConfigPlugin {

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        boolean global = !"false".equalsIgnoreCase(System.getProperty("vre.globalLighting"));
        if (simpleName.equals("LightFenceMixin")) return !global;
        if (simpleName.equals("WorldLightEngineMixin") || simpleName.equals("WorldLightFenceMixin")) return global;
        return true;
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
