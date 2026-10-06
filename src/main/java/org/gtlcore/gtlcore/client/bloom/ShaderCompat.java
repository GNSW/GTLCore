package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import java.lang.reflect.Method;

/** Only consults the public Iris/Oculus API to reject shadow-pass ring submissions. */
final class ShaderCompat {

    private static boolean checked;
    private static Object api;
    private static Method shadow;

    static boolean shadowPass() {
        if (!checked) {
            checked = true;
            for (String name : new String[] { "net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi" }) {
                try {
                    Class<?> type = Class.forName(name);
                    api = type.getMethod("getInstance").invoke(null);
                    shadow = type.getMethod("isRenderingShadowPass");
                    break;
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    api = null;
                    shadow = null;
                }
            }
        }
        if (shadow != null) {
            try {
                return (boolean) shadow.invoke(api);
            } catch (ReflectiveOperationException e) {
                GTLCore.LOGGER.warn("Could not query Iris shadow pass; ring bloom paused", e);
                // Do not capture shadow geometry as visible geometry.
                return true;
            }
        }
        return false;
    }

    private ShaderCompat() {}
}
