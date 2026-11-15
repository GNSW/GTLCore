package org.gtlcore.gtlcore.common.structure.io;

import net.minecraft.resources.ResourceLocation;

/**
 * platforms 资源路径约定（与 GTO 一致）：
 * {@code platforms/<namespace>/<path>[.mbs|.json]}
 */
public final class StructureResources {

    public static final String ROOT = "platforms";

    private StructureResources() {}

    /** classpath 路径：保证扩展名存在。 */
    public static String classpath(ResourceLocation id, String extension) {
        String path = id.getPath();
        String ext = extension.startsWith(".") ? extension : "." + extension;
        if (!path.endsWith(ext)) {
            path = path + ext;
        }
        return ROOT + "/" + id.getNamespace() + "/" + path;
    }

    public static String mbs(ResourceLocation id) {
        return classpath(id, ".mbs");
    }

    public static String json(ResourceLocation id) {
        return classpath(id, ".json");
    }
}
