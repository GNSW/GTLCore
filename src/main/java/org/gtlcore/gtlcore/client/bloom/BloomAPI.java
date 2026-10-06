package org.gtlcore.gtlcore.client.bloom;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Client-only API, usable from KubeJS client_scripts through Java.loadClass. No KubeJS dependency. */
public final class BloomAPI {

    /** Entire model, optionally constrained by state: kubejs:lamp[active=true]. */
    public static void registerBlock(String selector) {
        var rule = BloomRules.BlockRule.parse(selector);
        Minecraft.getInstance().execute(() -> BloomRules.scriptBlock(rule, true));
    }

    public static void unregisterBlock(String selector) {
        var rule = BloomRules.BlockRule.parse(selector);
        Minecraft.getInstance().execute(() -> BloomRules.scriptBlock(rule, false));
    }

    /** Atlas sprite ID, without textures/ prefix or .png suffix. */
    public static void registerTexture(String sprite) {
        var id = new ResourceLocation(sprite);
        Minecraft.getInstance().execute(() -> BloomRules.scriptTexture(id, true));
    }

    public static void unregisterTexture(String sprite) {
        var id = new ResourceLocation(sprite);
        Minecraft.getInstance().execute(() -> BloomRules.scriptTexture(id, false));
    }

    /** Clears only API registrations, leaving resource/config rules and original metadata intact. */
    public static void clearScriptRegistrations() {
        Minecraft.getInstance().execute(BloomRules::clearScripts);
    }

    private BloomAPI() {}
}
