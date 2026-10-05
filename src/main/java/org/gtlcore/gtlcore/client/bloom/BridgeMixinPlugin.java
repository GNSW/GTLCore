package org.gtlcore.gtlcore.client.bloom;

import net.minecraftforge.fml.loading.FMLLoader;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Audited Oculus 1.7.0 and 1.8.0 atlas hooks; shader-active bloom suppression is independent. */
public final class BridgeMixinPlugin implements IMixinConfigPlugin {

    public static boolean supportedOculus() {
        try {
            var mods = FMLLoader.getLoadingModList();
            if (mods == null) return false;
            var file = mods.getModFileById("oculus");
            return file != null && file.getMods().stream().anyMatch(mod -> mod.getModId().equals("oculus") && supportedVersion(mod.getVersion().toString()));
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Exact releases whose material-loading bytecode has been audited. */
    public static boolean supportedVersion(String version) {
        return "1.7.0".equals(version) || "1.8.0".equals(version);
    }

    public boolean shouldApplyMixin(String target, String mixin) {
        if (!mixin.startsWith("org.gtlcore.gtlcore.mixin.bloom.")) return true;
        var mods = FMLLoader.getLoadingModList();
        if (mods != null && mods.getModFileById("gtlbloom") != null) return false;
        return !mixin.contains(".Oculus") || supportedOculus();
    }

    public void onLoad(String mixinPackage) {}

    public String getRefMapperConfig() {
        return null;
    }

    public void acceptTargets(Set<String> mine, Set<String> others) {}

    public List<String> getMixins() {
        return null;
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
