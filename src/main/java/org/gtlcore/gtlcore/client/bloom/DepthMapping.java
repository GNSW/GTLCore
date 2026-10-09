package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import org.joml.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only adapter for the affine depth viewport used by Kappa 5.3's TAAU.
 * Oculus has no public API describing pack-defined vertex projection transforms.
 * Detect the actual loaded source contract, then read applied options and cached jitter.
 * No pack edits, configuration changes, GL interception or shader-program uniform writes.
 */
final class DepthMapping {

    static final DepthMapping IDENTITY = new DepthMapping(1, 0, 0);
    final float scale, offsetX, offsetY;
    private static boolean checked, failed, adapted, taa, shaderPipeline;
    private static Method getPack, getManager, getPipeline, writeUniform;
    private static Object lastPack, lastPipeline, jitter, returned;
    private static Field objectReturn;
    private static float packScale = 1;

    private DepthMapping(float scale, float offsetX, float offsetY) {
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    void apply(GlProgram program) {
        program.vec2("DepthScale", scale, scale);
        program.vec2("DepthOffset", offsetX, offsetY);
    }

    static void reset() {
        lastPack = null;
        lastPipeline = null;
        jitter = null;
        returned = null;
        writeUniform = null;
        objectReturn = null;
        adapted = false;
        shaderPipeline = false;
        failed = false;
        packScale = 1;
    }

    static DepthMapping current() {
        if (!checked) {
            checked = true;
            for (String name : new String[] { "net.irisshaders.iris.Iris", "net.coderbot.iris.Iris" }) {
                try {
                    Class<?> type = Class.forName(name);
                    getPack = type.getMethod("getCurrentPack");
                    getManager = type.getMethod("getPipelineManager");
                    getPipeline = getManager.getReturnType().getMethod("getPipeline");
                    break;
                } catch (ReflectiveOperationException | LinkageError absent) {
                    getPack = null;
                }
            }
        }
        if (getPack == null) return IDENTITY;
        try {
            Object pack = ((Optional<?>) getPack.invoke(null)).orElse(null);
            if (pack != lastPack) {
                lastPack = pack;
                lastPipeline = null;
                failed = false;
                adapted = false;
                shaderPipeline = false;
                jitter = null;
                writeUniform = null;
                packScale = 1;
                if (pack != null) inspect(pack);
            }
            if (!adapted) return IDENTITY;
            if (failed) return null;
            Object manager = getManager.invoke(null);
            Object pipeline = ((Optional<?>) getPipeline.invoke(manager)).orElse(null);
            if (pipeline == null) return IDENTITY;
            if (pipeline != lastPipeline) {
                lastPipeline = pipeline;
                jitter = null;
                shaderPipeline = false;
                Method uniforms;
                try {
                    uniforms = pipeline.getClass().getMethod("getCustomUniforms");
                } catch (NoSuchMethodException vanillaPipeline) {
                    return IDENTITY;
                }
                shaderPipeline = true;
                if (taa) {
                    Object custom = uniforms.invoke(pipeline);
                    jitter = custom.getClass().getMethod("getVariable", String.class).invoke(custom, "taaOffset");
                    Class<?> returnType = Class.forName("kroppeb.stareval.function.FunctionReturn");
                    returned = returnType.getConstructor().newInstance();
                    objectReturn = returnType.getField("objectReturn");
                    writeUniform = jitter.getClass().getMethod("writeTo", returnType);
                }
            }
            if (!shaderPipeline) return IDENTITY;
            float x = 0, y = 0;
            if (taa) {
                if (jitter == null) return IDENTITY; // Shader rendering has been disabled.
                writeUniform.invoke(jitter, returned);
                Vector2f offset = (Vector2f) objectReturn.get(returned);
                x = offset.x * 0.5f;
                y = offset.y * 0.5f;
                if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalStateException("Invalid cached TAA offset");
            }
            return new DepthMapping(packScale, x, y);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError problem) {
            if (!failed) GTLCore.LOGGER.warn("Cannot read shader depth mapping; mapped-pack bloom is paused until shader reload", problem);
            failed = true;
            return adapted ? null : IDENTITY;
        }
    }

    private static void inspect(Object pack) throws ReflectiveOperationException {
        Object options = pack.getClass().getMethod("getShaderPackOptions").invoke(pack);
        Object includes = options.getClass().getMethod("getIncludes").invoke(options);
        Map<?, ?> nodes = (Map<?, ?>) includes.getClass().getMethod("getNodes").invoke(includes);
        boolean contract = false;
        for (var node : nodes.entrySet()) {
            String path = (String) node.getKey().getClass().getMethod("getPathString").invoke(node.getKey());
            if (!path.endsWith("/lib/downscaleTransform.glsl")) continue;
            Object lines = node.getValue().getClass().getMethod("getLines").invoke(node.getValue());
            String source = String.join("\n", (Iterable<? extends CharSequence>) lines).replaceAll("\\s+", "");
            contract = source.contains("returnPosition*ResolutionScale-(1-ResolutionScale);") && source.contains("glPosition.xy=ViewProjectionDownscaling(glPosition.xy/glPosition.w)*glPosition.w;");
        }
        if (!contract) return;
        adapted = true;
        Object values = options.getClass().getMethod("getOptionValues").invoke(options);
        // Invoke the public interface, not an implementation-specific private class.
        Class<?> type = Class.forName(pack.getClass().getPackageName() + ".option.values.OptionValues");
        packScale = Float.parseFloat((String) type.getMethod("getStringValueOrDefault", String.class).invoke(values, "ResolutionScale"));
        taa = (boolean) type.getMethod("getBooleanValueOrDefault", String.class).invoke(values, "taaEnabled");
        if (!Float.isFinite(packScale) || packScale <= 0 || packScale > 1) throw new IllegalStateException("Invalid depth viewport scale " + packScale);
        GTLCore.LOGGER.info("Bloom depth mapping: Kappa affine TAAU, scale={}, TAA={}", packScale, taa);
    }
}
