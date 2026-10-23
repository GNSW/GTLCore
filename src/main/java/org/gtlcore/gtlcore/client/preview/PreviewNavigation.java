package org.gtlcore.gtlcore.client.preview;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.mixin.ldlib.client.PreviewSceneAccessor;

import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;

import net.minecraft.util.Mth;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Blender-style turntable navigation adapted to Minecraft's Y-up scene coordinates.
 *
 * @see <a href="https://github.com/blender/blender/tree/v4.5.0/source/blender/editors/space_view3d">Blender
 *      navigation</a>
 */
public final class PreviewNavigation {

    // Blender's view_zoom_apply_step scales distance by 1.2 per wheel step.
    private static final double WHEEL_ZOOM_FACTOR = 1.2;
    // SceneWidget's existing zoom limits; preserve larger automatically framed structures.
    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 999;
    private final SceneWidget scene;
    private float orbitDirection;

    public PreviewNavigation(SceneWidget scene) {
        this.scene = scene;
    }

    public void begin() {
        orbitDirection = scene.getRenderer().getWorldUp().y() < 0 ? -1 : 1;
    }

    public void rotate(double dx, double dy) {
        var renderer = scene.getRenderer();
        var back = new Vector3f(renderer.getEyePos()).sub(renderer.getLookAt());
        var forward = new Vector3f(back).negate().normalize();
        var right = new Vector3f(forward).cross(renderer.getWorldUp()).normalize();
        var up = new Vector3f(right).cross(forward).normalize();
        double radiansPerPixel = Math.toRadians(ConfigHolder.INSTANCE.graphics.multiblockPreview.orbitSensitivity);
        // Screen coordinates are already in UI pixels, matching Blender's UI-scaled input.
        // Local-right elevation and world-up orbit keep the horizon stable across the poles.
        var rotation = new Quaternionf().rotationY((float) (-dx * radiansPerPixel * orbitDirection))
                .rotateAxis((float) (-dy * radiansPerPixel), right).normalize();
        rotation.transform(back);
        rotation.transform(up);
        // Keep SceneWidget's public camera state in sync for zoom, pan and scene changes.
        float elevation = (float) Math.toDegrees(Math.atan2(back.y(), Math.hypot(back.x(), back.z())));
        float azimuth = (float) Math.toDegrees(Math.atan2(back.z(), back.x()));
        scene.setCameraYawAndPitch(elevation, azimuth);
        renderer.setCameraLookAt(back.add(scene.getCenter()), scene.getCenter(), up);
    }

    public void pan(double dx, double dy) {
        var renderer = scene.getRenderer();
        var access = (PreviewSceneAccessor) scene;
        double scale = access.gtlcore$ortho() ? 2.0 * access.gtlcore$range() * scene.getZoom() / scene.getSizeWidth() :
                2.0 * renderer.getEyePos().distance(renderer.getLookAt()) *
                        Math.tan(Math.toRadians(((PreviewRendererAccess) renderer).gtlcore$fov()) / 2) / scene.getSizeHeight();
        var offset = PreviewPan.offset(renderer.getEyePos(), renderer.getLookAt(), renderer.getWorldUp(), dx, dy, scale);
        scene.setCenter(new Vector3f(scene.getCenter()).add(offset));
    }

    public void zoom(double delta) {
        // Minecraft has already applied mouse-wheel sensitivity to this event.
        double zoom = scene.getZoom() * Math.pow(WHEEL_ZOOM_FACTOR, -delta);
        scene.setZoom((float) Mth.clamp(zoom, MIN_ZOOM, Math.max(MAX_ZOOM, scene.getZoom())));
    }
}
