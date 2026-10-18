package org.gtlcore.gtlcore.integration.jei.grouping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/** Select one canonical tag per item, without joining overlapping tag sets. */
public final class TagGroupingRules {

    public static final AtomicLong TAG_GENERATION = new AtomicLong();
    private static final Set<String> CATEGORY_ROOTS = Set.of(
            "logs", "planks", "leaves", "saplings", "wool", "wool_carpets", "carpets",
            "flowers", "small_flowers", "tall_flowers", "mushrooms", "sand", "sandstone", "red_sandstone",
            "stone", "stone_bricks", "cobblestone", "deepslate", "terracotta", "glazed_terracotta", "concrete", "concrete_powder",
            "glass", "stained_glass", "glass_panes", "stained_glass_panes", "chests", "barrels", "shulker_boxes",
            "stairs", "wooden_stairs", "slabs", "wooden_slabs", "walls", "fences", "wooden_fences", "fence_gates",
            "doors", "wooden_doors", "trapdoors", "wooden_trapdoors", "buttons", "wooden_buttons", "stone_buttons",
            "pressure_plates", "wooden_pressure_plates", "beds", "banners", "candles", "signs", "hanging_signs",
            "boats", "chest_boats", "swords", "pickaxes", "axes", "shovels", "hoes", "rails",
            "ingots", "hot_ingots", "nuggets", "dusts", "gems", "plates", "rods", "wires", "storage_blocks",
            "ores", "raw_materials", "circuits", "dyes", "seeds", "crops", "eggs", "bookshelves");
    private static final Set<String> METAL_FORMS = Set.of("ingots", "hot_ingots", "nuggets", "dusts", "plates", "foils",
            "rods", "bolts", "screws", "rings", "springs", "wires", "fine_wires", "cables", "gears", "rotors",
            "rounds", "storage_blocks", "frames", "pipes", "tool_heads", "small_dusts", "tiny_dusts", "dense_plates",
            "double_plates", "long_rods", "small_springs", "small_gears");

    private final List<String> preferredTags;
    private final List<Pattern> categoryTags;
    private final List<Pattern> tagBlacklist;
    private final List<Pattern> itemBlacklist;
    private final List<Pattern> ignoredTags;

    public TagGroupingRules(List<String> preferredTags, List<String> tagBlacklist,
                            List<String> itemBlacklist, List<String> ignoredTags) {
        this(preferredTags, List.of(), tagBlacklist, itemBlacklist, ignoredTags);
    }

    public TagGroupingRules(List<String> preferredTags, List<String> categoryTags, List<String> tagBlacklist,
                            List<String> itemBlacklist, List<String> ignoredTags) {
        this.preferredTags = preferredTags;
        this.categoryTags = compile(categoryTags);
        this.tagBlacklist = compile(tagBlacklist);
        this.itemBlacklist = compile(itemBlacklist);
        this.ignoredTags = compile(ignoredTags);
    }

    public String selectTag(String itemId, List<String> tags, ToIntFunction<String> tagSize) {
        return selectTag(itemId, tags, null, tagSize);
    }

    public String selectTag(String itemId, List<String> tags, String itemTypeTag, ToIntFunction<String> tagSize) {
        if (matches(itemBlacklist, itemId) || tags.stream().anyMatch(tag -> matches(tagBlacklist, tag)) ||
                itemTypeTag != null && matches(tagBlacklist, itemTypeTag)) {
            return null;
        }
        // A functional tag shared by a whole armor set must not override its slot.
        // Some mods omit Forge's slot tags, so the source also classifies ArmorItem.
        if (itemTypeTag != null && !matches(ignoredTags, itemTypeTag)) {
            return itemTypeTag;
        }
        for (String preferred : preferredTags) {
            if (tags.contains(preferred) && !matches(ignoredTags, preferred)) {
                return preferred;
            }
        }
        for (Pattern category : categoryTags) {
            // Prefer the tool type over material/tier sub-tags matched by a wildcard.
            String match = tags.stream().filter(tag -> category.matcher(tag).matches() && !matches(ignoredTags, tag))
                    .min(Comparator.comparingInt(TagGroupingRules::depth).thenComparing(Comparator.naturalOrder())).orElse(null);
            if (match != null) return match;
        }
        // Keep material-specific tags even when they contain only one item. Falling back
        // to a parent would combine different metals merely because they are all ingots.
        Comparator<String> order = Comparator.comparingInt(TagGroupingRules::depth).reversed()
                .thenComparingInt(tagSize).thenComparing(Comparator.naturalOrder());
        String standardTag = tags.stream().filter(tag -> isStandardCategory(tag) && !matches(ignoredTags, tag))
                .min(order).orElse(null);
        if (standardTag != null) return standardTag;
        // A tag's existence or namespace does not make it an item category.
        // Unknown/functional tags only participate when explicitly configured.
        return "@" + itemId;
    }

    public List<String> includeConfiguredBlockTags(List<String> itemTags, List<String> blockTags) {
        List<String> tags = new ArrayList<>(itemTags);
        // Some useful categories (notably shulker_boxes in 1.20.1) exist only
        // in the block registry. Import configured categories, not mining tags.
        for (String tag : blockTags) {
            if ((preferredTags.contains(tag) || matches(categoryTags, tag)) && !tags.contains(tag)) tags.add(tag);
        }
        return List.copyOf(tags);
    }

    public static <T> List<Group<T>> group(List<T> elements, java.util.function.Function<T, String> tagOf) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T element : elements) {
            String tag = tagOf.apply(element);
            if (tag != null) groups.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(element);
        }
        List<Group<T>> result = new ArrayList<>();
        for (T element : elements) {
            String tag = tagOf.apply(element);
            if (tag == null) {
                result.add(new Group<>(null, List.of(element)));
            } else {
                List<T> members = groups.remove(tag);
                if (members != null) result.add(new Group<>(tag, List.copyOf(members)));
            }
        }
        return List.copyOf(result);
    }

    public static boolean hasMetalTag(List<String> tags, Set<String> metalNames) {
        return tags.stream().anyMatch(tag -> {
            if (tag.equals("forge:ingots") || tag.equals("forge:nuggets")) return true;
            int slash = tag.indexOf('/');
            return tag.startsWith("forge:") && slash >= 0 && METAL_FORMS.contains(tag.substring("forge:".length(), slash)) &&
                    metalNames.contains(tag.substring(tag.lastIndexOf('/') + 1));
        });
    }

    private static int depth(String tag) {
        return (int) tag.chars().filter(character -> character == '/').count();
    }

    private static boolean isStandardCategory(String tag) {
        if (!tag.startsWith("minecraft:") && !tag.startsWith("forge:")) return false;
        String path = tag.substring(tag.indexOf(':') + 1);
        if (path.startsWith("tools/") || path.startsWith("armors/")) return true;
        int slash = path.indexOf('/');
        return CATEGORY_ROOTS.contains(slash < 0 ? path : path.substring(0, slash));
    }

    private static List<Pattern> compile(List<String> patterns) {
        return patterns.stream().map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> Pattern.compile("\\Q" + value.replace("\\E", "\\E\\\\E\\Q")
                        .replace("*", "\\E.*\\Q") + "\\E"))
                .toList();
    }

    private static boolean matches(List<Pattern> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).matches());
    }

    public record Group<T>(String tag, List<T> elements) {

        public T representative() {
            return elements.get(0);
        }
    }
}
