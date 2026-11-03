package org.gtlcore.gtlcore.client.terminal;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList.Entry;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList.WorldListEntry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps world creation and world selection consistent with the configured route.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldAccessGuard {

    private static final String SKYBLOCK_PRESET_ID = "skyblockbuilder:skyblock";
    private static final String FLAT_PRESET_ID = "minecraft:flat";
    private static final String SKYBLOCK_GENERATOR_PREFIX = "skyblockbuilder:";
    private static final int LOCKED_TINT = 0x8A666666;
    private static final int LOCKED_SHADE = 0x30000000;
    private static final int LOCK_COLOR = 0xFFE8C264;
    private static final int LOCK_HIGHLIGHT = 0xFFFFF1B8;
    private static final Component LOCKED_BADGE = Component.translatable("gtlcore.terminal.lock.badge");
    private static final Component LOCKED_TOOLTIP = Component.translatable("gtlcore.terminal.lock.tooltip");

    private static final Field SUMMARY_FIELD = findField(WorldListEntry.class, "summary", "f_101695_");
    private static final Method GET_ROW_TOP = findMethod(
            AbstractSelectionList.class,
            "getRowTop",
            "m_7610_",
            int.class);
    private static final Method GET_ROW_BOTTOM = findMethod(
            AbstractSelectionList.class,
            "getRowBottom",
            "m_93485_",
            int.class);
    private static final Map<String, Boolean> SKYBLOCK_WORLD_CACHE = new HashMap<>();
    private static final Map<WorldListEntry, Boolean> LOCKED_ENTRY_CACHE = new IdentityHashMap<>();

    private static boolean skyblockMode;
    private static boolean diagnosticsLogged;

    private WorldAccessGuard() {}

    @SubscribeEvent
    public static void onCreateWorldInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) {
            return;
        }

        skyblockMode = TerminalConfigWriter.isSkyblockMode();
        WorldCreationUiState state = screen.getUiState();
        List<WorldTypeEntry> original = new ArrayList<>();
        original.addAll(state.getNormalPresetList());
        original.addAll(state.getAltPresetList());

        WorldTypeEntry skyblock = findPreset(original, SKYBLOCK_PRESET_ID);
        WorldTypeEntry flat = findPreset(original, FLAT_PRESET_ID);
        if (flat == null) {
            flat = createPresetEntry(state, WorldPresets.FLAT);
        }

        if (skyblockMode) {
            List<WorldTypeEntry> allowed = new ArrayList<>(2);
            if (skyblock != null) {
                allowed.add(skyblock);
            }
            if (flat != null) {
                allowed.add(flat);
            }
            if (allowed.isEmpty()) {
                return;
            }

            replacePresets(state.getNormalPresetList(), allowed);
            replacePresets(state.getAltPresetList(), allowed);
            if (!containsPreset(allowed, state.getWorldType())) {
                state.setWorldType(allowed.get(0));
            }
            return;
        }

        removePresets(state.getNormalPresetList(), SKYBLOCK_PRESET_ID);
        removePresets(state.getAltPresetList(), SKYBLOCK_PRESET_ID);
        if (isPreset(state.getWorldType(), SKYBLOCK_PRESET_ID)) {
            WorldTypeEntry replacement = firstPreset(state.getNormalPresetList(), state.getAltPresetList());
            if (replacement == null) {
                replacement = createPresetEntry(state, WorldPresets.NORMAL);
            }
            if (replacement != null) {
                state.setWorldType(replacement);
            }
        }
    }

    @SubscribeEvent
    public static void onSelectWorldInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof SelectWorldScreen) {
            skyblockMode = TerminalConfigWriter.isSkyblockMode();
            diagnosticsLogged = false;
            SKYBLOCK_WORLD_CACHE.clear();
            LOCKED_ENTRY_CACHE.clear();
        }
    }

    @SubscribeEvent
    public static void onSelectWorldRender(ScreenEvent.Render.Post event) {
        if (skyblockMode || !(event.getScreen() instanceof SelectWorldScreen screen)) {
            return;
        }

        WorldSelectionList list = findWorldList(screen);
        if (list == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        List<Entry> entries = list.children();
        clearLockedSelection(list, minecraft);

        GuiGraphics graphics = event.getGuiGraphics();
        WorldListEntry hovered = null;
        int worldEntryCount = 0;
        int lockedCount = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof WorldListEntry entry)) {
                continue;
            }
            worldEntryCount++;
            if (!isLockedSkyblock(minecraft, entry)) {
                continue;
            }
            lockedCount++;

            int rowTop = invokeInt(GET_ROW_TOP, list, index);
            int rowBottom = invokeInt(GET_ROW_BOTTOM, list, index);
            if (rowTop < 0 || rowBottom < 0 || rowBottom <= list.getTop() || rowTop >= list.getBottom()) {
                continue;
            }

            int left = list.getRowLeft();
            int right = list.getRowRight();
            int clippedTop = Math.max(rowTop, list.getTop());
            int clippedBottom = Math.min(rowBottom, list.getBottom());
            graphics.fill(left, clippedTop, right, clippedBottom, LOCKED_TINT);
            graphics.fill(left, clippedTop, right, clippedBottom, LOCKED_SHADE);
            int badgeWidth = minecraft.font.width(LOCKED_BADGE) + 26;
            int badgeX = right - badgeWidth - 5;
            int badgeY = clippedTop + Math.max(2, (clippedBottom - clippedTop - 16) / 2);
            graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 16, 0xD9101215);
            graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 1, LOCK_COLOR);
            drawLock(graphics, badgeX + 4, badgeY + 2);
            graphics.drawString(minecraft.font, LOCKED_BADGE, badgeX + 18, badgeY + 4, LOCK_HIGHLIGHT, false);

            if (inside(event.getMouseX(), event.getMouseY(), left, rowTop, right - left, rowBottom - rowTop)) {
                hovered = entry;
            }
        }

        if (!diagnosticsLogged && worldEntryCount > 0) {
            diagnosticsLogged = true;
            GTLCore.LOGGER.info(
                    "World access guard active: skyblockMode=false, lockedSkyblockSaves={}, summaryField={}",
                    lockedCount,
                    SUMMARY_FIELD != null);
        }

        if (hovered != null) {
            graphics.renderTooltip(minecraft.font, LOCKED_TOOLTIP, event.getMouseX(), event.getMouseY());
        }
    }

    @SubscribeEvent
    public static void onSelectWorldClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (skyblockMode || event.getButton() != 0 || !(event.getScreen() instanceof SelectWorldScreen screen)) {
            return;
        }

        WorldSelectionList list = findWorldList(screen);
        if (list == null) {
            return;
        }
        if (isLockedRowAt(list, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            list.setSelected((Entry) null);
        }
    }

    @SubscribeEvent
    public static void onSelectWorldKey(ScreenEvent.KeyPressed.Pre event) {
        if (skyblockMode || !(event.getScreen() instanceof SelectWorldScreen screen)) {
            return;
        }
        if (event.getKeyCode() != GLFW.GLFW_KEY_ENTER && event.getKeyCode() != GLFW.GLFW_KEY_KP_ENTER) {
            return;
        }

        WorldSelectionList list = findWorldList(screen);
        if (list == null) {
            return;
        }
        Optional<WorldListEntry> selected = list.getSelectedOpt();
        if (selected.isPresent() && isLockedSkyblock(Minecraft.getInstance(), selected.get())) {
            event.setCanceled(true);
            list.setSelected((Entry) null);
        }
    }

    private static boolean isLockedRowAt(WorldSelectionList list, double mouseX, double mouseY) {
        if (!list.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Entry> entries = list.children();
        int left = list.getRowLeft();
        int right = list.getRowRight();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof WorldListEntry entry) || !isLockedSkyblock(minecraft, entry)) {
                continue;
            }
            int rowTop = invokeInt(GET_ROW_TOP, list, index);
            int rowBottom = invokeInt(GET_ROW_BOTTOM, list, index);
            if (rowTop >= 0 && rowBottom >= 0 &&
                    inside(mouseX, mouseY, left, rowTop, right - left, rowBottom - rowTop)) {
                return true;
            }
        }
        return false;
    }

    private static void clearLockedSelection(WorldSelectionList list, Minecraft minecraft) {
        Optional<WorldListEntry> selected = list.getSelectedOpt();
        if (selected.isPresent() && isLockedSkyblock(minecraft, selected.get())) {
            list.setSelected((Entry) null);
        }
    }

    private static boolean isLockedSkyblock(Minecraft minecraft, WorldListEntry entry) {
        Boolean cached = LOCKED_ENTRY_CACHE.get(entry);
        if (cached != null) {
            return cached;
        }

        LevelSummary summary = summary(entry);
        boolean locked = false;
        if (summary != null) {
            String levelId = summary.getLevelId();
            Boolean cachedWorld = SKYBLOCK_WORLD_CACHE.get(levelId);
            if (cachedWorld == null) {
                cachedWorld = isSkyblockWorld(minecraft, levelId);
                SKYBLOCK_WORLD_CACHE.put(levelId, cachedWorld);
            }
            locked = cachedWorld;
        }
        LOCKED_ENTRY_CACHE.put(entry, locked);
        return locked;
    }

    private static boolean isSkyblockWorld(Minecraft minecraft, String levelId) {
        Path levelDat = minecraft.getLevelSource().getBaseDir().resolve(levelId).resolve("level.dat");
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat.toFile());
            CompoundTag dimensions = root.getCompound("Data")
                    .getCompound("WorldGenSettings")
                    .getCompound("dimensions");
            CompoundTag overworld = dimensions.getCompound("minecraft:overworld");
            String generatorType = overworld.getCompound("generator").getString("type");
            String biomeSourceType = overworld.getCompound("biome_source").getString("type");
            return generatorType.startsWith(SKYBLOCK_GENERATOR_PREFIX) ||
                    "skyblockbuilder:sky".equals(biomeSourceType);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static LevelSummary summary(WorldListEntry entry) {
        try {
            return SUMMARY_FIELD == null ? null : (LevelSummary) SUMMARY_FIELD.get(entry);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static WorldSelectionList findWorldList(Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof WorldSelectionList list) {
                return list;
            }
        }
        return null;
    }

    private static WorldTypeEntry findPreset(List<WorldTypeEntry> entries, String presetId) {
        for (WorldTypeEntry entry : entries) {
            if (isPreset(entry, presetId)) {
                return entry;
            }
        }
        return null;
    }

    private static WorldTypeEntry firstPreset(List<WorldTypeEntry> first, List<WorldTypeEntry> second) {
        if (!first.isEmpty()) {
            return first.get(0);
        }
        return second.isEmpty() ? null : second.get(0);
    }

    private static boolean containsPreset(List<WorldTypeEntry> entries, WorldTypeEntry candidate) {
        return candidate != null && entries.stream().anyMatch(candidate::equals);
    }

    private static boolean isPreset(WorldTypeEntry entry, String presetId) {
        return entry != null && presetId(entry).map(presetId::equals).orElse(false);
    }

    private static Optional<String> presetId(WorldTypeEntry entry) {
        Holder<WorldPreset> preset = entry.preset();
        if (preset == null) {
            return Optional.empty();
        }
        return preset.unwrapKey().map(key -> key.location().toString());
    }

    private static WorldTypeEntry createPresetEntry(WorldCreationUiState state, ResourceKey<WorldPreset> presetKey) {
        try {
            Holder<WorldPreset> holder = state.getSettings()
                    .worldgenLoadContext()
                    .registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(presetKey);
            return new WorldTypeEntry(holder);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void replacePresets(List<WorldTypeEntry> target, List<WorldTypeEntry> replacements) {
        target.clear();
        Map<String, WorldTypeEntry> unique = new LinkedHashMap<>();
        for (WorldTypeEntry entry : replacements) {
            unique.putIfAbsent(presetId(entry).orElse(entry.toString()), entry);
        }
        target.addAll(unique.values());
    }

    private static void removePresets(List<WorldTypeEntry> entries, String presetId) {
        entries.removeIf(entry -> isPreset(entry, presetId));
    }

    private static void drawLock(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 2, y, x + 8, y + 1, LOCK_HIGHLIGHT);
        graphics.fill(x + 1, y + 1, x + 2, y + 6, LOCK_COLOR);
        graphics.fill(x + 8, y + 1, x + 9, y + 6, LOCK_COLOR);
        graphics.fill(x + 2, y + 1, x + 8, y + 2, LOCK_COLOR);
        graphics.fill(x, y + 5, x + 10, y + 13, LOCK_COLOR);
        graphics.fill(x + 1, y + 6, x + 9, y + 12, 0xFF6F5924);
        graphics.fill(x + 4, y + 8, x + 6, y + 11, LOCK_HIGHLIGHT);
    }

    private static int invokeInt(Method method, Object target, int argument) {
        Object value = invoke(method, target, argument);
        return value instanceof Integer result ? result : -1;
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, String officialName, String srgName) {
        try {
            Field field = ObfuscationReflectionHelper.findField(owner, srgName);
            field.setAccessible(true);
            return field;
        } catch (RuntimeException ignored) {
            try {
                Field field = owner.getDeclaredField(officialName);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException fallbackFailure) {
                GTLCore.LOGGER.error("Unable to locate field {} on {}", officialName, owner.getName(), fallbackFailure);
                return null;
            }
        }
    }

    private static Method findMethod(Class<?> owner, String officialName, String srgName, Class<?>... parameters) {
        try {
            Method method = ObfuscationReflectionHelper.findMethod(owner, srgName, parameters);
            method.setAccessible(true);
            return method;
        } catch (RuntimeException ignored) {
            try {
                Method method = owner.getDeclaredMethod(officialName, parameters);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException fallbackFailure) {
                GTLCore.LOGGER.error("Unable to locate method {} on {}", officialName, owner.getName(),
                        fallbackFailure);
                return null;
            }
        }
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
