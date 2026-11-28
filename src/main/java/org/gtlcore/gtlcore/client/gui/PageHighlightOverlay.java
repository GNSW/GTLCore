package org.gtlcore.gtlcore.client.gui;

import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;

public final class PageHighlightOverlay {

    private static final long HIGHLIGHT_DURATION_MILLIS = 15_000L;
    private static final long DEDUPE_WINDOW_MILLIS = 500L;
    private static String lastPageTitle;
    private static long lastHighlightAt;

    private PageHighlightOverlay() {}

    public static void highlight(IFancyUIProvider page) {
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null || page == null) {
            return;
        }
        String title = page.getTitle().getString();
        long now = System.currentTimeMillis();
        if (title.equals(lastPageTitle) && now - lastHighlightAt < DEDUPE_WINDOW_MILLIS) {
            return;
        }
        lastPageTitle = title;
        lastHighlightAt = now;
        gtlcore$highlightParts(minecraft, level, page);
        minecraft.setScreen(null);
    }

    private static void gtlcore$highlightParts(Minecraft minecraft, net.minecraft.client.multiplayer.ClientLevel level,
                                               IFancyUIProvider page) {
        if (!(minecraft.screen instanceof ModularUIGuiContainer container)) {
            return;
        }
        Object holder = gtlcore$holder(container);
        if (!(holder instanceof IMultiController controller)) {
            return;
        }
        String title = page.getTitle().getString();
        java.util.List<BlockPos> positions = new java.util.ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (IMultiPart part : controller.getParts()) {
            if (part == null || part.self() == null) {
                continue;
            }
            MetaMachine machine = part.self();
            if (machine.getDefinition() == null || machine.getDefinition().asStack() == null) {
                continue;
            }
            Component name = machine.getDefinition().asStack().getHoverName();
            if (title.equals(name.getString()) || title.contains(name.getString()) || name.getString().contains(title)) {
                BlockPos pos = machine.getPos();
                if (seen.add(pos)) {
                    positions.add(pos);
                    gtlcore$sendPositionMessage(minecraft, name, pos);
                }
            }
        }
        if (!positions.isEmpty()) {
            gtlcore$highlight(minecraft, level, positions);
        }
    }

    private static void gtlcore$highlight(Minecraft minecraft, net.minecraft.client.multiplayer.ClientLevel level,
                                          java.util.List<BlockPos> positions) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
        gtlcore$resetGlodiumQueue();
        org.gtlcore.gtlcore.client.renderer.BlockHighlightHandler.highlight(positions.get(0), null, level.dimension(),
                System.currentTimeMillis() + HIGHLIGHT_DURATION_MILLIS, box);
    }

    private static void gtlcore$resetGlodiumQueue() {
        try {
            Class<?> handler = Class.forName("com.glodblock.github.glodium.client.render.highlight.HighlightHandler");
            Field queueField = handler.getDeclaredField("BLOCK_QUEUE");
            queueField.setAccessible(true);
            Object current = queueField.get(null);
            it.unimi.dsi.fastutil.PriorityQueue<?> fresh = (it.unimi.dsi.fastutil.PriorityQueue<?>) current
                    .getClass().getDeclaredConstructor().newInstance();
            queueField.set(null, fresh);
            Field blocksField = handler.getDeclaredField("BLOCKS");
            blocksField.setAccessible(true);
            if (blocksField.get(null) instanceof it.unimi.dsi.fastutil.objects.ObjectSet<?> blocks) {
                blocks.clear();
            }
        } catch (ReflectiveOperationException ignored) {
            // Glodium absent or its internals changed; fall back to the existing queue.
        }
    }

    private static void gtlcore$sendPositionMessage(Minecraft minecraft, Component name, BlockPos pos) {
        var player = minecraft.player;
        if (player == null) {
            return;
        }
        player.sendSystemMessage(Component.translatable("message.gtlcore.page_highlight",
                name, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static Object gtlcore$holder(ModularUIGuiContainer container) {
        try {
            Field field = ModularUIGuiContainer.class.getDeclaredField("modularUI");
            field.setAccessible(true);
            Object ui = field.get(container);
            if (!(ui instanceof ModularUI modularUI)) {
                return null;
            }
            Field holder = ModularUI.class.getDeclaredField("holder");
            holder.setAccessible(true);
            return holder.get(modularUI);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
