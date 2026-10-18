package org.gtlcore.gtlcore.integration.jei.grouping;

import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.PropertyKey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.common.Internal;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** A presentation-only view of JEI's already searched, sorted and visible ingredients. */
public final class TagGroupSource implements IIngredientGridSource {

    private static final Logger LOGGER = LogManager.getLogger(TagGroupSource.class);
    private static final ConfigHolder.JeiOptions DEFAULTS = new ConfigHolder.JeiOptions();

    private final IIngredientGridSource delegate;
    private final List<SourceListChangedListener> listeners = new ArrayList<>();
    private final Map<Item, ItemTags> itemTags = new IdentityHashMap<>();
    private final Map<String, Integer> tagSizes = new HashMap<>();
    private final Set<String> metalNames = new HashSet<>();
    private final Map<IElement<?>, TagGroupingRules.Group<IElement<?>>> groups = new IdentityHashMap<>();
    private List<IElement<?>> input;
    private List<IElement<?>> output = List.of();
    private Settings settings;
    private TagGroupingRules rules;
    private long tagGeneration = -1;
    private boolean editing;
    private int revision;

    public TagGroupSource(IIngredientGridSource delegate) {
        this.delegate = delegate;
        delegate.addSourceListChangedListener(() -> {
            input = null;
            revision++;
            for (var listener : List.copyOf(listeners)) listener.onSourceListChanged();
        });
    }

    public static ConfigHolder.JeiOptions options() {
        return ConfigHolder.INSTANCE == null ? DEFAULTS : ConfigHolder.INSTANCE.jei;
    }

    @Override
    public List<IElement<?>> getElements() {
        Settings current = Settings.read();
        long generation = TagGroupingRules.TAG_GENERATION.get();
        boolean editMode = Internal.getClientToggleState().isEditModeEnabled();
        if (!current.equals(settings) || generation != tagGeneration || editing != editMode) {
            settings = current;
            tagGeneration = generation;
            editing = editMode;
            rules = new TagGroupingRules(settings.preferredTags, settings.categoryTags, settings.tagBlacklist, settings.itemBlacklist, settings.ignoredTags);
            itemTags.clear();
            tagSizes.clear();
            metalNames.clear();
            if (settings.blockMetals) {
                for (var material : GTCEuAPI.materialManager.getRegisteredMaterials()) {
                    if (material.hasProperty(PropertyKey.INGOT)) metalNames.add(material.getName());
                }
                BuiltInRegistries.ITEM.getTagNames().map(TagKey::location)
                        .filter(tag -> tag.getNamespace().equals("forge") && tag.getPath().startsWith("ingots/"))
                        .forEach(tag -> metalNames.add(tag.getPath().substring("ingots/".length())));
            }
            input = null;
        }
        List<IElement<?>> elements = delegate.getElements();
        if (elements != input) {
            input = elements;
            groups.clear();
            if (!settings.enabled || editing) {
                output = input;
            } else {
                try {
                    itemTags.clear();
                    var grouped = TagGroupingRules.group(elements, this::tagOf);
                    List<IElement<?>> collapsed = new ArrayList<>(grouped.size());
                    for (var group : grouped) {
                        collapsed.add(group.representative());
                        if (group.elements().size() > 1) groups.put(group.representative(), group);
                    }
                    output = List.copyOf(collapsed);
                } catch (RuntimeException exception) {
                    // Presentation failures must not abort JEI's runtime registration.
                    groups.clear();
                    output = elements;
                    LOGGER.error("Failed to group JEI ingredients; using the original ingredient list", exception);
                }
            }
            revision++;
        }
        return output;
    }

    public int revision() {
        getElements();
        return revision;
    }

    public TagGroupingRules.Group<IElement<?>> group(IElement<?> element) {
        return groups.get(element);
    }

    private String tagOf(IElement<?> element) {
        ItemStack stack = element.getTypedIngredient().getIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) return null;
        ItemTags tags = itemTags.computeIfAbsent(stack.getItem(), item -> {
            List<String> names = item.builtInRegistryHolder().tags().map(tag -> tag.location().toString()).sorted().toList();
            String itemTypeTag = item instanceof ArmorItem armor ? switch (armor.getType()) {
                case HELMET -> "forge:armors/helmets";
                case CHESTPLATE -> "forge:armors/chestplates";
                case LEGGINGS -> "forge:armors/leggings";
                case BOOTS -> "forge:armors/boots";
            } : null;
            if (item instanceof BlockItem blockItem) {
                var block = blockItem.getBlock();
                if (block instanceof StairBlock) itemTypeTag = "minecraft:stairs";
                else if (block instanceof SlabBlock) itemTypeTag = "minecraft:slabs";
                else if (block instanceof WallBlock) itemTypeTag = "minecraft:walls";
                names = rules.includeConfiguredBlockTags(names, blockItem.getBlock().builtInRegistryHolder().tags()
                        .map(tag -> tag.location().toString()).sorted().toList());
            }
            if (settings.blockMetals) {
                if (TagGroupingRules.hasMetalTag(names, metalNames) || TagGroupMetalBlacklist.isMetal(item)) {
                    return new ItemTags(null);
                }
            }
            return new ItemTags(rules.selectTag(BuiltInRegistries.ITEM.getKey(item).toString(), names, itemTypeTag, this::tagSize));
        });
        return tags.groupTag;
    }

    private int tagSize(String name) {
        return tagSizes.computeIfAbsent(name, tag -> BuiltInRegistries.ITEM
                .getTag(TagKey.create(BuiltInRegistries.ITEM.key(), new ResourceLocation(tag)))
                .map(set -> set.size()).orElseGet(() -> BuiltInRegistries.BLOCK
                        .getTag(TagKey.create(BuiltInRegistries.BLOCK.key(), new ResourceLocation(tag)))
                        .map(set -> set.size()).orElse(0)));
    }

    @Override
    public void addSourceListChangedListener(SourceListChangedListener listener) {
        listeners.add(listener);
    }

    private record ItemTags(String groupTag) {}

    private record Settings(boolean enabled, boolean blockMetals, List<String> preferredTags, List<String> categoryTags, List<String> tagBlacklist,
                            List<String> itemBlacklist, List<String> ignoredTags) {

        private static Settings read() {
            var options = options();
            List<String> categories = Stream.of(options.jeiGroupCategoryTags, options.jeiGroupEquipmentTags, options.jeiGroupSupplementaryTags, options.jeiGroupBuildingTags)
                    .flatMap(values -> copy(values).stream()).toList();
            return new Settings(options.jeiTagGrouping, options.jeiGroupMetalBlacklist, copy(options.jeiGroupPreferredTags), categories, copy(options.jeiGroupTagBlacklist),
                    copy(options.jeiGroupItemBlacklist), copy(options.jeiGroupIgnoredTags));
        }

        private static List<String> copy(String[] values) {
            return values == null ? List.of() : Arrays.stream(values).filter(Objects::nonNull).map(String::trim).toList();
        }
    }
}
