package org.gtlcore.gtlcore.common.structure.model;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.structure.io.StructureFileIO;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialTable;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结构领域模型命名空间：{@link Structure} 元数据、{@link Preset} 预设库、{@link ExtraMaterial}。
 * <p>
 * 显示文本使用 {@link Component}（由注册处构建）；几何尺寸在 build 时从 .mbs 读取。
 * </p>
 */
public final class StructureDefinition {

    private StructureDefinition() {}

    public record ExtraMaterial(int amount, ItemStack item) {

        public ExtraMaterial {
            if (amount < 0) throw new IllegalArgumentException("amount < 0");
            Objects.requireNonNull(item, "item");
            item = item.copy();
            item.setCount(1);
        }

        public Item getItem() {
            return item.getItem();
        }
    }

    public record Structure(
                            String name,
                            Component type,
                            Component displayName,
                            Component description,
                            Component source,
                            ResourceLocation resource,
                            ResourceLocation blockMapping,
                            StructureMaterialTable materials,
                            List<ExtraMaterial> extraMaterials,
                            int sizeX,
                            int sizeY,
                            int sizeZ) {

        public Structure {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(blockMapping, "blockMapping");
            Objects.requireNonNull(materials, "materials");
            Objects.requireNonNull(extraMaterials, "extraMaterials");
            materials = materials.copy();
            extraMaterials = ImmutableList.copyOf(extraMaterials);
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("structure size must be positive");
            }
        }

        /** 展示用名称：displayName 优先，否则内部 name。 */
        public Component title() {
            return displayName != null ? displayName : Component.literal(name);
        }

        public int materialCost(StructureMaterialType type) {
            return materials.get(type);
        }

        public static Builder structure(String name) {
            return new Builder(name);
        }

        public static final class Builder {

            private final String name;
            private Component type;
            private Component displayName;
            private Component description;
            private Component source;
            private ResourceLocation resource;
            private ResourceLocation symbolMap;
            private final StructureMaterialTable materials = StructureMaterialTable.empty();
            private final List<ExtraMaterial> extraMaterials = new ArrayList<>();

            public Builder(String name) {
                this.name = name;
            }

            public Builder type(Component type) {
                this.type = type;
                return this;
            }

            public Builder displayName(Component displayName) {
                this.displayName = displayName;
                return this;
            }

            public Builder description(Component description) {
                this.description = description;
                return this;
            }

            public Builder source(Component source) {
                this.source = source;
                return this;
            }

            /** 来源作者等字面量。 */
            public Builder source(String source) {
                this.source = source == null || source.isEmpty() ? null : Component.literal(source);
                return this;
            }

            public Builder resource(ResourceLocation resource) {
                this.resource = resource;
                return this;
            }

            public Builder symbolMap(ResourceLocation symbolMap) {
                this.symbolMap = symbolMap;
                return this;
            }

            /** 已废弃：材料需求统一按方块数折算（只消耗框架组件），这里的点数不再生效。 */
            public Builder materials(StructureMaterialType type, int count) {
                materials.set(type, count);
                return this;
            }

            public Builder materials(int typeIndex, int count) {
                return materials(StructureMaterialType.of(typeIndex), count);
            }

            public Builder materials(Map<StructureMaterialType, Integer> costs) {
                if (costs != null) {
                    costs.forEach(materials::set);
                }
                return this;
            }

            public Builder extraMaterials(String itemId, int count) {
                ResourceLocation id = new ResourceLocation(itemId);
                Item resolved = BuiltInRegistries.ITEM.get(id);
                if (resolved == null || resolved == net.minecraft.world.item.Items.AIR) {
                    GTLCore.LOGGER.error("Unknown extra material item: {}", itemId);
                    return this;
                }
                extraMaterials.add(new ExtraMaterial(count, new ItemStack(resolved)));
                return this;
            }

            public Builder extraMaterials(Item item, int count) {
                extraMaterials.add(new ExtraMaterial(count, new ItemStack(item)));
                return this;
            }

            public Builder extraMaterials(ItemStack stack, int count) {
                ItemStack copy = stack.copy();
                copy.setCount(1);
                extraMaterials.add(new ExtraMaterial(count, copy));
                return this;
            }

            public Structure build() {
                if (name == null || name.isEmpty()) {
                    GTLCore.LOGGER.error("Structure registration error: missing name");
                    return null;
                }
                if (resource == null) {
                    GTLCore.LOGGER.error("Structure registration error: missing resource: {}", name);
                    return null;
                }
                if (symbolMap == null) {
                    GTLCore.LOGGER.error("Structure registration error: missing mapping: {}", name);
                    return null;
                }
                StructureFileIO.PatternData data = StructureFileIO.tryLoadSizes(resource);
                if (data == null) {
                    return null;
                }
                return new Structure(
                        name, type, displayName, description, source,
                        resource, symbolMap,
                        automaticCost(data), extraMaterials,
                        data.sizeX(), data.sizeY(), data.sizeZ());
            }

            /** 材料需求统一按方块数折算（只消耗框架组件），调用方传入的固定点数不再使用。 */
            private static StructureMaterialTable automaticCost(StructureFileIO.PatternData data) {
                int blocks = 0;
                for (String[] aisle : data.pattern()) {
                    for (String row : aisle) {
                        for (int i = 0; i < row.length(); i++) {
                            if (row.charAt(i) != ' ') blocks++;
                        }
                    }
                }
                StructureMaterialTable cost = StructureMaterialTable.empty();
                cost.set(StructureMaterialType.FRAME, StructureMaterialType.pointsForBlocks(blocks));
                return cost;
            }
        }
    }

    public record Preset(
                         String name,
                         Component displayName,
                         Component description,
                         List<Structure> structures) {

        public Preset {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(structures, "structures");
            if (structures.isEmpty()) {
                throw new IllegalArgumentException("structures must not be empty");
            }
            structures = ImmutableList.copyOf(structures);
        }

        public Component title() {
            return displayName != null ? displayName : Component.literal(name);
        }

        public static PresetBuilder preset(String name) {
            return new PresetBuilder(name);
        }

        public static final class PresetBuilder {

            private final String name;
            private Component displayName;
            private Component description;
            private final List<Structure> structures = new ArrayList<>();

            public PresetBuilder(String name) {
                this.name = name;
            }

            public PresetBuilder displayName(Component displayName) {
                this.displayName = displayName;
                return this;
            }

            public PresetBuilder description(Component description) {
                this.description = description;
                return this;
            }

            public PresetBuilder addStructure(Structure structure) {
                if (structure != null) {
                    structures.add(structure);
                }
                return this;
            }

            public Preset build() {
                if (name == null || name.isEmpty()) {
                    GTLCore.LOGGER.error("Preset registration error: missing name");
                    return null;
                }
                if (structures.isEmpty()) {
                    GTLCore.LOGGER.error("Preset registration error: empty structures for {}", name);
                    return null;
                }
                return new Preset(name, displayName, description, structures);
            }
        }
    }
}
