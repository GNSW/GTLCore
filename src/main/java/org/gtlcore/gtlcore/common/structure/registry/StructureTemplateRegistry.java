package org.gtlcore.gtlcore.common.structure.registry;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Preset;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.Structure;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 结构预设注册表：查询 API + 翻译键构建。
 * <p>
 * 内置数据见 {@link StructureBuiltinPresets}；显示文案通过 {@link #tr} 生成
 * {@link Component}（语言条目已写入 assets/gtlcore/lang）。
 * </p>
 * <p>
 * 结构放置器相关代码移植自 Quantum-Chromodynamic-Charge（GPLv3，作者 maple197），
 * 经作者许可引入 GTLCore。
 * </p>
 */
public final class StructureTemplateRegistry {

    private static final List<Preset> PRESETS = new ArrayList<>();
    private static final List<Preset> VIEW = Collections.unmodifiableList(PRESETS);

    private static final String KEY_PREFIX = "ui.quantum_chromodynamic_charge.structure.";

    static {
        try {
            StructureBuiltinPresets.registerAll();
        } catch (Throwable t) {
            // 避免预设加载异常拖垮整个模组初始化
            GTLCore.LOGGER.error("Failed to register built-in structure presets", t);
        }
    }

    private StructureTemplateRegistry() {}

    // -------------------------------------------------------------------------
    // 翻译
    // -------------------------------------------------------------------------

    /**
     * 构建结构注册用显示文本：稳定翻译键（语言条目随资源打包）。
     *
     * @param key 相对 {@link #KEY_PREFIX} 的后缀，或完整 key（含 {@code ui.} 前缀）
     */
    public static Component tr(String key) {
        String full = key.startsWith("ui.") ? key : KEY_PREFIX + key;
        return Component.translatable(full);
    }

    /**
     * 与旧版签名兼容；{@code cn}/{@code en} 仅用于可读性，实际文案取自语言文件。
     */
    public static Component tr(String key, String cn, String en) {
        return tr(key);
    }

    /** 由英文描述生成 key 后缀。 */
    public static String keyOf(String en) {
        String key = en.toLowerCase(Locale.ROOT)
                .replace("×", "x")
                .replace("·", "_")
                .replace("*", "x")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Invalid structure lang key: " + en);
        }
        return key;
    }

    // -------------------------------------------------------------------------
    // 注册 / 查询
    // -------------------------------------------------------------------------

    public static List<Preset> presets() {
        return VIEW;
    }

    public static void register(Preset preset) {
        if (preset != null) {
            PRESETS.add(preset);
        }
    }

    public static boolean isEmpty() {
        return PRESETS.isEmpty();
    }

    public static int presetCount() {
        return PRESETS.size();
    }

    public static Preset getPreset(int group) {
        if (PRESETS.isEmpty()) {
            throw new IllegalStateException("No structure presets registered");
        }
        return PRESETS.get(Mth.clamp(group, 0, PRESETS.size() - 1));
    }

    public static Structure getStructure(int group, int id) {
        List<Structure> list = getPreset(group).structures();
        return list.get(Mth.clamp(id, 0, list.size() - 1));
    }

    /** 安全取结构；无预设时返回 null。 */
    public static Structure tryGetStructure(int group, int id) {
        if (PRESETS.isEmpty()) return null;
        return getStructure(group, id);
    }

    /** 触发静态初始化。 */
    public static void initLang() {
        presetCount();
    }
}
