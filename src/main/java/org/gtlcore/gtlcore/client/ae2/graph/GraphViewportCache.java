package org.gtlcore.gtlcore.client.ae2.graph;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import org.cgse.core.PlanGraphLayout.Box;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.*;

/** World-anchored tiles: moving the viewport never restarts a visible tile's painter. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GraphViewportCache implements AutoCloseable {

    interface Painter {

        boolean advance(GuiGraphics graphics, long deadline);

        int progress();
    }

    interface Factory {

        Painter create(Box bounds, double pixelsPerUnit);
    }

    private static final long SLICE_NANOS = 2_000_000L;
    private static final int TILE_SIZE = 512, GUARD = 2, MAX_TILES = 32, MAX_VISIBLE = 24;
    private static volatile int resourceVersion;
    private int version = -1, cursor;
    private double guiScale;
    private Object content;
    private Box viewport;
    private final Map<Key, Tile> tiles = new LinkedHashMap<>(16, 0.75f, true);
    private List<Key> visible = List.of();

    private record Key(int level, int x, int y, boolean backgroundOnly) {}

    private static double scale(int level, double guiScale) {
        return guiScale * Math.pow(2, level / 2.0);
    }

    private static Box bounds(Key key, double guiScale) {
        double side = TILE_SIZE / scale(key.level(), guiScale);
        return new Box(key.x() * side, key.y() * side, side, side);
    }

    private static Box padded(Box box, double pad) {
        return new Box(box.x() - pad, box.y() - pad, box.width() + 2 * pad, box.height() + 2 * pad);
    }

    private static final class Tile implements AutoCloseable {

        final TextureTarget target;
        final Box bounds, rasterBounds;
        final double pixelsPerUnit;
        final Painter painter;
        boolean complete;

        Tile(Key key, double guiScale, Factory factory) {
            pixelsPerUnit = scale(key.level(), guiScale);
            bounds = bounds(key, guiScale);
            rasterBounds = padded(bounds, GUARD / pixelsPerUnit);
            painter = factory.create(padded(rasterBounds, 32), pixelsPerUnit);
            target = new TextureTarget(TILE_SIZE + 2 * GUARD, TILE_SIZE + 2 * GUARD, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
            target.setClearColor(212 / 255f, 212 / 255f, 215 / 255f, 1);
            target.clear(Minecraft.ON_OSX);
        }

        @Override
        public void close() {
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            target.destroyBuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        }
    }

    @SubscribeEvent
    public static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> resourceVersion++);
    }

    void prepare(GuiGraphics parent, Object nextContent, Box viewport, Box drawingBounds, double zoom, boolean backgroundOnly, Factory factory) {
        double nextScale = Minecraft.getInstance().getWindow().getGuiScale();
        if (version != resourceVersion || guiScale != nextScale || !Objects.equals(content, nextContent)) {
            close();
            version = resourceVersion;
            guiScale = nextScale;
            content = nextContent;
        }
        this.viewport = viewport;
        Box graph = padded(drawingBounds, 32);
        double left = Math.max(viewport.x(), graph.x()), top = Math.max(viewport.y(), graph.y());
        double right = Math.min(viewport.x() + viewport.width(), graph.x() + graph.width());
        double bottom = Math.min(viewport.y() + viewport.height(), graph.y() + graph.height());
        if (right <= left || bottom <= top) {
            visible = List.of();
            return;
        }
        int level = (int) Math.ceil(2 * Math.log(zoom) / Math.log(2));
        int x0, y0, x1, y1;
        do {
            double side = TILE_SIZE / scale(level, guiScale);
            x0 = (int) Math.floor(left / side);
            y0 = (int) Math.floor(top / side);
            x1 = (int) Math.floor(Math.nextDown(right) / side);
            y1 = (int) Math.floor(Math.nextDown(bottom) / side);
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) <= MAX_VISIBLE) break;
            level--;
        } while (true);
        List<Key> requested = new ArrayList<>();
        // Keep both node modes in the same bounded LRU so density changes do not discard ready tiles.
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) requested.add(new Key(level, x, y, backgroundOnly));
        visible = requested;
        Key missing = null;
        boolean work = false;
        for (Key key : visible) {
            Tile tile = tiles.get(key);
            if (tile == null && missing == null) missing = key;
            if (tile != null && !tile.complete) work = true;
        }
        if (!work && missing == null) return;
        parent.flush();
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewportState = new int[4], scissor = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportState);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var view = RenderSystem.getModelViewStack();
        view.pushPose();
        try {
            RenderSystem.disableScissor();
            // Allocate at most one target per frame; retain other tiles for panning and adjacent zooms.
            if (missing != null) {
                if (tiles.size() >= MAX_TILES) {
                    var oldest = tiles.entrySet().iterator();
                    while (oldest.hasNext()) {
                        var entry = oldest.next();
                        if (!visible.contains(entry.getKey())) {
                            entry.getValue().close();
                            oldest.remove();
                            break;
                        }
                    }
                }
                tiles.put(missing, new Tile(missing, guiScale, factory));
            }
            view.setIdentity();
            view.translate(0, 0, -10000);
            RenderSystem.applyModelViewMatrix();
            long deadline = System.nanoTime() + SLICE_NANOS;
            int unfinished = 0;
            for (Key key : visible) {
                Tile tile = tiles.get(key);
                if (tile != null && !tile.complete) unfinished++;
            }
            long tileSlice = SLICE_NANOS / Math.max(1, unfinished);
            // Round-robin work retains partial progress throughout a continuous drag.
            for (int count = 0; count < visible.size() && System.nanoTime() < deadline; count++) {
                Tile tile = tiles.get(visible.get(Math.floorMod(cursor++, visible.size())));
                if (tile == null || tile.complete) continue;
                tile.target.bindWrite(true);
                RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, tile.target.width, tile.target.height, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
                var graphics = new GuiGraphics(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
                graphics.pose().scale((float) tile.pixelsPerUnit, (float) tile.pixelsPerUnit, 1);
                graphics.pose().translate(-tile.rasterBounds.x(), -tile.rasterBounds.y(), 0);
                try {
                    tile.complete = tile.painter.advance(graphics, Math.min(deadline, System.nanoTime() + tileSlice));
                } finally {
                    graphics.flush();
                }
            }
        } finally {
            RenderSystem.setProjectionMatrix(projection, sorting);
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            RenderSystem.viewport(viewportState[0], viewportState[1], viewportState[2], viewportState[3]);
            if (clipped) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            else RenderSystem.disableScissor();
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }

    /** Draw in layout coordinates, underneath the screen's viewport scissor. */
    void draw(GuiGraphics graphics) {
        if (visible.isEmpty()) return;
        graphics.flush();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        int level = visible.get(0).level();
        boolean backgroundOnly = visible.get(0).backgroundOnly();
        if (progress() < 100) {
            for (Key key : visible) {
                Tile tile = tiles.get(key);
                if (tile != null && !tile.complete) draw(graphics, tile);
            }
            for (var entry : tiles.entrySet()) {
                Tile tile = entry.getValue();
                if (entry.getKey().backgroundOnly() == backgroundOnly && entry.getKey().level() != level && tile.complete && tile.bounds.intersects(viewport))
                    draw(graphics, tile);
            }
        }
        for (Key key : visible) {
            Tile tile = tiles.get(key);
            if (tile != null && tile.complete) draw(graphics, tile);
        }
        if (depth) RenderSystem.enableDepthTest();
    }

    private static void draw(GuiGraphics graphics, Tile tile) {
        RenderSystem.setShaderTexture(0, tile.target.getColorTextureId());
        var matrix = graphics.pose().last().pose();
        var box = tile.bounds;
        float left = (float) box.x(), top = (float) box.y();
        float right = (float) (box.x() + box.width()), bottom = (float) (box.y() + box.height());
        float inset = (float) GUARD / (TILE_SIZE + 2 * GUARD);
        var buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.vertex(matrix, left, bottom, 0).uv(inset, inset).endVertex();
        buffer.vertex(matrix, right, bottom, 0).uv(1 - inset, inset).endVertex();
        buffer.vertex(matrix, right, top, 0).uv(1 - inset, 1 - inset).endVertex();
        buffer.vertex(matrix, left, top, 0).uv(inset, 1 - inset).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    int progress() {
        if (visible.isEmpty()) return 100;
        int sum = 0;
        for (Key key : visible) {
            Tile tile = tiles.get(key);
            if (tile != null) sum += tile.complete ? 100 : Math.min(99, tile.painter.progress());
        }
        return sum / visible.size();
    }

    @Override
    public void close() {
        tiles.values().forEach(Tile::close);
        tiles.clear();
        visible = List.of();
        cursor = 0;
        content = null;
    }
}
