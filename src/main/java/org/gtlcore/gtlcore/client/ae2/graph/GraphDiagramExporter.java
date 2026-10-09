package org.gtlcore.gtlcore.client.ae2.graph;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.client.gui.Icon;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.cgse.view.DiagramExporter;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Complete diagrams, independent of the viewport. GPU work is sliced; file encoding runs off-thread. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
public final class GraphDiagramExporter {

    public record Node(double x, double y, AEKey icon, String name, String amount, String exact,
                       boolean missing, boolean seed, String initialInput, String reference) {}

    public record Line(double ax, double ay, double bx, double by) {}

    /** A null key denotes the recipe glyph, not an item produced by the recipe. */
    private record Sprite(AEKey key) {}

    private static final Deque<Job> JOBS = new ArrayDeque<>();
    private static final int ICON_SIZE = 32, ICONS_PER_SLICE = 64;

    private GraphDiagramExporter() {}

    public static final class Job {

        private final String title;
        private final List<Node> nodes;
        private final List<Line> lines;
        private final boolean amounts;
        private final Deque<Sprite> pending;
        private final Map<Sprite, BufferedImage> icons = new LinkedHashMap<>();
        private CompletableFuture<Void> writing;
        private boolean done;

        public Job(String title, List<Node> nodes, List<Line> lines, boolean amounts) {
            this.title = title;
            this.nodes = List.copyOf(nodes);
            this.lines = List.copyOf(lines);
            this.amounts = amounts;
            Set<Sprite> unique = new LinkedHashSet<>();
            for (var node : nodes) unique.add(new Sprite(node.icon()));
            pending = new ArrayDeque<>(unique);
            JOBS.addLast(this);
        }

        public boolean done() {
            return done;
        }

        private void advance() {
            if (writing != null) {
                if (writing.isDone()) done = true;
                return;
            }
            try {
                if (!pending.isEmpty()) {
                    List<Sprite> batch = new ArrayList<>();
                    while (!pending.isEmpty() && batch.size() < ICONS_PER_SLICE) batch.add(pending.removeFirst());
                    capture(batch, icons);
                }
                if (pending.isEmpty()) {
                    var minecraft = Minecraft.getInstance();
                    Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
                    String name = "CraftingGraph_" + Util.getFilenameFormattedDateTime() + "_" + UUID.randomUUID().toString().substring(0, 8);
                    writing = CompletableFuture.runAsync(() -> {
                        try {
                            Files.createDirectories(directory);
                            Path png = directory.resolve(name + ".png"), svg = directory.resolve(name + ".svg");
                            write(title, nodes, lines, icons, amounts, png, svg);
                            minecraft.execute(() -> minecraft.gui.getChat().addMessage(Component.translatable("gtlcore.ae.ring.export_saved",
                                    link("PNG", png), link("SVG", svg))));
                        } catch (Exception e) {
                            failure(e);
                        } finally {
                            icons.clear();
                        }
                    }, Util.ioPool());
                }
            } catch (Exception e) {
                done = true;
                icons.clear();
                failure(e);
            }
        }
    }

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
        Job job = JOBS.peekFirst();
        job.advance();
        if (job.done) JOBS.removeFirst();
    }

    private static Component link(String title, Path file) {
        return Component.literal(title).withStyle(ChatFormatting.UNDERLINE).withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.toAbsolutePath().toString())));
    }

    private static void failure(Exception e) {
        GTLCore.LOGGER.warn("Could not export crafting diagram", e);
        var minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.getChat().addMessage(Component.translatable("screenshot.failure", e.getMessage())));
    }

    private static void capture(List<Sprite> keys, Map<Sprite, BufferedImage> icons) {
        var minecraft = Minecraft.getInstance();
        var target = new TextureTarget(256, 256, true, Minecraft.ON_OSX);
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var view = RenderSystem.getModelViewStack();
        view.pushPose();
        try {
            minecraft.renderBuffers().bufferSource().endBatch();
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            view.setIdentity();
            view.translate(0, 0, -10000);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 256, 256, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
            var graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            graphics.pose().scale(2, 2, 2);
            for (int i = 0; i < keys.size(); i++) {
                int x = (i % 8) * 16, y = (i / 8) * 16;
                if (keys.get(i).key() == null) Icon.CRAFT_HAMMER.getBlitter().dest(x, y).blit(graphics);
                else AEKeyRendering.drawInGui(minecraft, graphics, x, y, keys.get(i).key());
            }
            graphics.flush();
            // Screenshot.takeScreenshot forces an opaque alpha channel, which gives
            // every item a black square. Keep the transparent atlas pixels instead.
            try (var pixels = new NativeImage(256, 256, false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                pixels.downloadTexture(0, false);
                pixels.flipY();
                for (int i = 0; i < keys.size(); i++) {
                    var image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < ICON_SIZE; y++) for (int x = 0; x < ICON_SIZE; x++) {
                        int abgr = pixels.getPixelRGBA((i % 8) * ICON_SIZE + x, (i / 8) * ICON_SIZE + y);
                        image.setRGB(x, y, (abgr & 0xFF00FF00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255));
                    }
                    icons.put(keys.get(i), image);
                }
            }
        } finally {
            RenderSystem.setProjectionMatrix(projection, sorting);
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            target.destroyBuffers();
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    /** The desktop module owns file composition; this adapter owns GPU capture and game notifications. */
    private static void write(String title, List<Node> nodes, List<Line> lines, Map<Sprite, BufferedImage> icons,
                              boolean amounts, Path png, Path svg) throws IOException {
        var portableNodes = nodes.stream().map(node -> new DiagramExporter.Node<>(node.x(), node.y(), new Sprite(node.icon()),
                node.name(), node.amount(), node.exact(), node.missing(), node.seed(), node.initialInput(), node.reference())).toList();
        var portableLines = lines.stream().map(line -> new DiagramExporter.Line(line.ax(), line.ay(), line.bx(), line.by())).toList();
        DiagramExporter.write(title, portableNodes, portableLines, icons, amounts, png, svg);
    }
}
