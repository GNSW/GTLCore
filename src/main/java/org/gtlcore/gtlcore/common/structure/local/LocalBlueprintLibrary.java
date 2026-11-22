package org.gtlcore.gtlcore.common.structure.local;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialTable;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Structure;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 本地蓝图库：游戏根目录下的 {@code gtlcore/platforms/}，递归识别受支持的蓝图文件。
 * <p>
 * 扫描<strong>只列文件名与体积</strong>，不解析内容：蓝图在「预览」或「应用」时才真正读取，
 * 因此目录里放多大的投影都不会在扫描阶段吃内存。目录树（含空文件夹）同步给客户端做展示。
 * </p>
 */
public final class LocalBlueprintLibrary {

    /** 相对游戏根目录的蓝图文件夹。 */
    public static final String FOLDER = "gtlcore/platforms";
    /** 导出默认写入的子文件夹。 */
    public static final String DEFAULT_SUBFOLDER = "default";
    /** 递归深度上限。 */
    private static final int MAX_DEPTH = 8;

    private static final String MARKER_NAMESPACE = "gtlcore";
    private static final String MARKER_PREFIX = "local/";

    private static volatile List<Entry> entries = List.of();
    private static volatile List<String> directories = List.of();
    private static volatile String signature = "";

    private LocalBlueprintLibrary() {}

    /** 一条本地蓝图：只记录路径与文件体积，尺寸要等真正读取后才知道。 */
    public record Entry(String path, String name, long bytes) {

        /** 所在目录的相对路径（根目录为空串）。 */
        public String directory() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
    }

    /** 蓝图根目录（不存在时创建，并顺手建好导出用的 {@code default} 子目录）。 */
    public static Path folder() {
        Path dir = FMLPaths.GAMEDIR.get().resolve("gtlcore").resolve("platforms");
        try {
            Files.createDirectories(dir);
            Files.createDirectories(dir.resolve(DEFAULT_SUBFOLDER));
        } catch (Exception e) {
            GTLCore.LOGGER.warn("Unable to create blueprint folder {}", dir, e);
        }
        return dir;
    }

    /** 导出目录：{@code gtlcore/platforms/default/}。 */
    public static Path defaultFolder() {
        Path dir = folder().resolve(DEFAULT_SUBFOLDER);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            GTLCore.LOGGER.warn("Unable to create blueprint folder {}", dir, e);
        }
        return dir;
    }

    /** 相对路径 -> 绝对路径；越界（{@code ..}）返回 null。 */
    @Nullable
    public static Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        Path root = folder();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) return null;
        return target;
    }

    /** 是否是本地蓝图（按 resource 的 {@code gtlcore:local/...} 标记判定）。 */
    public static boolean isLocal(@Nullable ResourceLocation id) {
        return id != null && MARKER_NAMESPACE.equals(id.getNamespace()) && id.getPath().startsWith(MARKER_PREFIX);
    }

    /** 本地结构的资源标记：只用来区分来源，文件按结构名（相对路径）解析。 */
    public static ResourceLocation markerId(String path) {
        String safe = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._/-]", "_");
        if (safe.isEmpty()) safe = "blueprint";
        return new ResourceLocation(MARKER_NAMESPACE, MARKER_PREFIX + safe);
    }

    /** 上次扫描结果（服务端）。 */
    public static List<Entry> entries() {
        return entries;
    }

    /** 目录树（相对路径），空文件夹也会列出。 */
    public static List<String> directories() {
        return directories;
    }

    /** 去掉目录与后缀的显示名。 */
    public static String displayName(String path) {
        return BlueprintIO.baseName(path.substring(path.lastIndexOf('/') + 1));
    }

    /** 蓝图方块数 -> 材料点数（与内置预设共用同一折算规则）。 */
    public static int materialCostOf(int blocks) {
        return StructureMaterialType.pointsForBlocks(blocks);
    }

    /** 用已知的尺寸与方块数构造结构定义（扫描阶段不解析，因此元数据来自应用/预览时读取的结果）。 */
    public static Structure structure(String path, int sizeX, int sizeY, int sizeZ, int blocks) {
        StructureMaterialTable materials = StructureMaterialTable.empty();
        materials.set(StructureMaterialType.FRAME, materialCostOf(blocks));
        return new Structure(path, null,
                Component.literal(displayName(path)),
                Component.translatable("ui.quantum_chromodynamic_charge.structure.blueprint.local_cost",
                        materialCostOf(blocks)),
                Component.translatable("ui.quantum_chromodynamic_charge.structure.blueprint.local_source"),
                markerId(path), markerId(path),
                materials, List.of(),
                sizeX, sizeY, sizeZ);
    }

    /**
     * 扫描蓝图目录：只列文件与目录，<strong>不解析任何蓝图内容</strong>。
     *
     * @return 列表是否发生变化
     */
    public static synchronized boolean refresh() {
        Path root = folder();
        List<Path> files;
        List<Path> dirs;
        List<String> listing = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            List<Path> all = stream.sorted().toList();
            files = all.stream().filter(LocalBlueprintLibrary::isBlueprint).toList();
            dirs = all.stream().filter(p -> Files.isDirectory(p) && !p.equals(root)).toList();
        } catch (Exception e) {
            GTLCore.LOGGER.warn("Unable to list blueprint folder {}", root, e);
            files = List.of();
            dirs = List.of();
        }
        List<String> dirPaths = new ArrayList<>(dirs.size());
        for (Path dir : dirs) {
            String path = relative(root, dir);
            if (path != null) dirPaths.add(path);
        }
        listing.add("D:" + String.join(",", dirPaths));
        List<Entry> list = new ArrayList<>(files.size());
        for (Path file : files) {
            String path = relative(root, file);
            if (path == null) continue;
            long size;
            try {
                size = Files.size(file);
            } catch (Exception e) {
                continue;
            }
            listing.add(path + '|' + size);
            list.add(new Entry(path, displayName(path), size));
        }
        String current = String.join("\n", listing);
        if (current.equals(signature)) return false;
        signature = current;
        directories = List.copyOf(dirPaths);
        list.sort(Comparator.comparing(Entry::path, String.CASE_INSENSITIVE_ORDER));
        entries = List.copyOf(list);
        GTLCore.LOGGER.info("Local blueprints: {} file(s) in {}", list.size(), root);
        return true;
    }

    /** 忽略签名强制重扫（界面「刷新」按钮）。 */
    public static synchronized boolean rescan() {
        signature = "\u0000";
        return refresh();
    }

    /** 在系统文件管理器中打开蓝图根目录（仅客户端调用）。 */
    public static void openFolder() {
        Path dir = folder();
        try {
            Util.getPlatform().openFile(dir.toFile());
        } catch (Throwable failure) {
            GTLCore.LOGGER.warn("Unable to open blueprint folder {}", dir, failure);
        }
    }

    private static boolean isBlueprint(Path path) {
        return BlueprintIO.isSupported(path.getFileName().toString()) && Files.isRegularFile(path);
    }

    /** 文件相对根目录的路径，统一用 {@code /} 分隔；不在根目录下时返回 null。 */
    @Nullable
    private static String relative(Path root, Path file) {
        try {
            String path = root.relativize(file).toString().replace('\\', '/');
            return path.isEmpty() || path.startsWith("..") ? null : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
