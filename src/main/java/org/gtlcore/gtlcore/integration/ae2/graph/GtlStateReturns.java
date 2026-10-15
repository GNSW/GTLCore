package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.api.item.tool.GTLToolType;
import org.gtlcore.gtlcore.mixin.ae2.crafting.AECraftingPatternAccessor;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.item.IGTTool;
import com.gregtechceu.gtceu.api.item.capability.ElectricItem;
import com.gregtechceu.gtceu.api.item.tool.GTAxeItem;
import com.gregtechceu.gtceu.api.item.tool.GTHoeItem;
import com.gregtechceu.gtceu.api.item.tool.GTShovelItem;
import com.gregtechceu.gtceu.api.item.tool.GTSwordItem;
import com.gregtechceu.gtceu.api.item.tool.GTToolItem;
import com.gregtechceu.gtceu.api.item.tool.GTToolType;
import com.gregtechceu.gtceu.api.recipe.ShapedEnergyTransferRecipe;
import com.gregtechceu.gtceu.api.recipe.StrictShapedRecipe;
import com.gregtechceu.gtceu.config.ConfigHolder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AECraftingPattern;
import org.cgse.core.GraphPlan;
import org.cgse.core.PlanningFailure;
import org.cgse.core.StateFamily;
import org.cgse.core.StateReturn;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Typed return rules: inspection never rolls GT's world/player random source. */
public final class GtlStateReturns {

    static final String STATIC = "legacy-static-return/v1";
    private static final int MAX_DAMAGE_OUTCOMES = 256;
    private static final Set<Class<?>> TOOL_CLASSES = Set.of(GTToolItem.class, GTAxeItem.class,
            GTHoeItem.class, GTShovelItem.class, GTSwordItem.class);

    private GtlStateReturns() {}

    public record Capture(StateReturn<AEKey> contract, AEKey nominal, boolean uncertain) {}

    /**
     * Capture a symbolic tool family on the host thread. Worker methods on the
     * result inspect copied NBT and perform arithmetic; they never call GT tool
     * getters, capabilities, recipe callbacks or a world's random source.
     */
    public static StateFamily<AEKey> family(IPatternDetails pattern, IPatternDetails.IInput input, AEKey key) {
        if (!(key instanceof AEItemKey itemKey) || !defaultCraftingReturns(pattern, input) ||
                !((AECraftingPattern) pattern).canSubstitute())
            return null;
        var recipe = ((AECraftingPatternAccessor) pattern).gtlcore$getRecipe();
        // Energy-transfer assembly can change the primary output according to
        // the input's charge. Only independent ordinary ingredient membership
        // and a constant output permit this capacity view.
        if (recipe.getClass() == ShapedEnergyTransferRecipe.class || recipe.isSpecial() ||
                recipe.getIngredients().stream().anyMatch(ingredient -> ingredient.getClass() != Ingredient.class))
            return null;
        ItemStack stack = itemKey.toStack();
        if (!(stack.getItem() instanceof IGTTool tool) || !known(tool, stack)) return null;
        if (stack.getTag() != null && stack.getTag().getBoolean("DisallowContainerItem")) return null;
        var captured = tool(itemKey);
        if (!captured.contract().complete()) return null;
        int damage = tool.getToolStats().getToolDamagePerCraft(stack.copy());
        ItemStack inspected = stack.copy();
        int before = inspected.getDamageValue(), maximum = inspected.getMaxDamage();
        if (before < 0 || maximum < 0 || damage < 0 || maximum > Integer.MAX_VALUE - damage) return null;
        boolean unbreakable = stack.getTag() != null && stack.getTag().getBoolean("Unbreakable");
        long chargeMaximum = 0, chargeCost = 0;
        boolean discharge = false;
        if (tool.isElectric()) {
            var electric = GTCapabilityHelper.getElectricItem(stack.copy());
            if (electric == null || electric.getClass() != ElectricItem.class) return null;
            chargeMaximum = electric.getMaxCharge();
            chargeCost = (long) damage * ConfigHolder.INSTANCE.machines.energyUsageMultiplier;
            if (chargeMaximum < 0 || electric.getCharge() < 0 || chargeCost < 0 || chargeCost > Integer.MAX_VALUE) return null;
            discharge = tool.getElectricTier() >= electric.getTier();
        }
        ItemStack broken = tool.getToolStats().getBrokenStack().copy();
        if (!broken.isEmpty() && broken.getCount() != 1) return null;
        try {
            return new GtlToolStateFamily(itemKey, captured.contract().semantics(), maximum, damage, unbreakable,
                    tool.isElectric(), chargeMaximum, chargeCost, discharge, ConfigHolder.INSTANCE.tools.rngDamageElectricTools,
                    broken.isEmpty() ? null : AEItemKey.of(broken));
        } catch (IllegalArgumentException unsupportedState) {
            return null;
        }
    }

    public static Capture capture(IPatternDetails pattern, IPatternDetails.IInput input, AEKey key) {
        // A processing pattern consumes exactly its encoded input, even if the
        // physical item happens to be a tool. Its machine semantics are explicit IO.
        if (input.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input")) return exact(null);
        if (key instanceof AEItemKey itemKey) {
            ItemStack stack = itemKey.toStack();
            if (stack.getItem() instanceof IGTTool) {
                if (!defaultCraftingReturns(pattern, input)) return unknown("custom-crafting-tool-return", key);
                return tool(itemKey);
            }
            // Vanilla durable items normally have no crafting remainder at all.
            // A modded damageable remainder needs an explicit typed contract.
            if (stack.isDamageableItem() && stack.hasCraftingRemainingItem())
                return unknown("untyped-damageable-return", key);
        }
        // Retain the existing static-container/custom non-tool contract. It is
        // deliberately not used to infer stochastic or damageable tool semantics.
        return exact(input.getRemainingKey(key));
    }

    private static boolean defaultCraftingReturns(IPatternDetails pattern, IPatternDetails.IInput input) {
        if (pattern.getClass() != AECraftingPattern.class ||
                !input.getClass().getName().equals("appeng.crafting.pattern.AECraftingPattern$Input") ||
                !(pattern instanceof AECraftingPatternAccessor access))
            return false;
        Class<?> type = access.gtlcore$getRecipe().getClass();
        // These classes inherit Recipe's Forge default per-item remainder rule.
        // Custom recipes may instead depend on the complete crafting frame.
        return type == ShapedRecipe.class || type == ShapelessRecipe.class ||
                type == StrictShapedRecipe.class || type == ShapedEnergyTransferRecipe.class;
    }

    /** Package-visible for local native rule tests; callers normally use capture. */
    static Capture tool(AEItemKey key) {
        ItemStack original = key.toStack();
        IGTTool tool = (IGTTool) original.getItem();
        if (!known(tool, original))
            return unknown("untyped-gt-tool-definition", key);
        String prefix = "gtceu-tool-return/1.4.4/v1/" + tool.getToolType().name;
        if (original.getTag() != null && original.getTag().getBoolean("DisallowContainerItem"))
            return exact(prefix + "/disabled", null);
        if (original.getTag() != null && original.getTag().getBoolean("Unbreakable"))
            return exact(prefix + "/unbreakable", key);

        int damage = tool.getToolStats().getToolDamagePerCraft(original.copy());
        int unbreaking = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.UNBREAKING, original);
        if (damage < 0 || damage > MAX_DAMAGE_OUTCOMES || unbreaking < 0 || unbreaking == Integer.MAX_VALUE)
            return unknown("unsupported-gt-damage-domain", key);
        ItemStack discharged = original.copy();
        boolean skipPossible = false, damagePossible = true;
        int electricity = ConfigHolder.INSTANCE.machines.energyUsageMultiplier;
        int damageChance = ConfigHolder.INSTANCE.tools.rngDamageElectricTools;
        if (tool.isElectric()) {
            long chargeCost = (long) damage * electricity;
            if (electricity < 0 || chargeCost > Integer.MAX_VALUE) return unknown("unsupported-electric-cost", key);
            var electric = GTCapabilityHelper.getElectricItem(discharged);
            if (electric == null || electric.getClass() != ElectricItem.class) return unknown("untyped-electric-capability", key);
            // The standard capability only mutates this detached stack's Charge.
            // Keep Infinite/MaxCharge behavior identical to GT's real operation.
            electric.discharge(chargeCost, tool.getElectricTier(), true, false, false);
            if (electric.getCharge() > 0) {
                skipPossible = damageChance < 100;
                damagePossible = damageChance > 0;
            }
        }
        String semantics = prefix + "/d=" + damage + "/u=" + unbreaking + "/electric=" + tool.isElectric() +
                "/tier=" + tool.getElectricTier() + "/cost=" + electricity + "/chance=" + damageChance;
        if (damage == 0 || !damagePossible) return exact(semantics, AEItemKey.of(discharged));

        var outcomes = new ArrayList<Map<AEKey, Long>>();
        AEKey nominal = null;
        int minimum = unbreaking > 0 ? 0 : damage;
        for (int wear = minimum; wear <= damage; wear++) {
            Map<AEKey, Long> returned = worn(tool, discharged, wear);
            outcomes.add(returned);
            if (wear == damage) nominal = single(returned, semantics);
        }
        if (skipPossible && minimum != 0) outcomes.add(one(AEItemKey.of(discharged)));
        StateReturn<AEKey> contract = StateReturn.alternatives(semantics, outcomes, true);
        return new Capture(contract, nominal, !contract.deterministic());
    }

    private static Map<AEKey, Long> worn(IGTTool tool, ItemStack discharged, int wear) {
        ItemStack returned = discharged.copy();
        // GT skips the damage/max-damage getters for a zero-loss outcome. Those
        // getters can add lazy NBT fields, so do not normalize that branch.
        if (wear == 0) return one(AEItemKey.of(returned));
        int before = returned.getDamageValue();
        long after = (long) before + wear;
        if (before < 0 || after > Integer.MAX_VALUE) throw new PlanningFailure(GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,
                "gt_tool_damage_overflow");
        returned.setDamageValue((int) after);
        if (after > returned.getMaxDamage()) returned = tool.getToolStats().getBrokenStack().copy();
        if (returned.isEmpty()) return Map.of();
        return Map.of(AEItemKey.of(returned), (long) returned.getCount());
    }

    private static AEKey single(Map<AEKey, Long> returned, String semantics) {
        if (returned.isEmpty()) return null;
        if (returned.size() == 1) {
            var item = returned.entrySet().iterator().next();
            if (item.getValue() == 1L) return item.getKey();
        }
        throw new PlanningFailure(GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,
                "gt_tool_multiple_remainders; semantics=" + semantics + "; returned=" + returned);
    }

    private static Capture exact(AEKey key) {
        return exact(STATIC, key);
    }

    private static Capture exact(String semantics, AEKey key) {
        return new Capture(StateReturn.exact(semantics, one(key)), key, false);
    }

    private static Map<AEKey, Long> one(AEKey key) {
        return key == null ? Map.of() : Map.of(key, 1L);
    }

    private static Capture unknown(String reason, AEKey key) {
        return new Capture(StateReturn.unknown("unsupported/" + reason + "/" + key.getPrimaryKey()), null, true);
    }

    private static boolean known(IGTTool tool, ItemStack stack) {
        return TOOL_CLASSES.contains(stack.getItem().getClass()) && KnownTypes.VALUES.contains(tool.getToolType()) &&
                tool.getToolStats() == tool.getToolType().toolDefinition;
    }

    private static final class KnownTypes {

        static final Set<GTToolType> VALUES = capture();

        private static Set<GTToolType> capture() {
            Set<GTToolType> types = new HashSet<>();
            // Only the audited built-in definitions, not arbitrary addon lambdas
            // implementing broken-stack suppliers or mutable crafting damage.
            for (var field : GTToolType.class.getFields()) {
                if (field.getType() != GTToolType.class || !Modifier.isStatic(field.getModifiers()) ||
                        !Modifier.isFinal(field.getModifiers()))
                    continue;
                try {
                    types.add((GTToolType) field.get(null));
                } catch (IllegalAccessException ignored) { /* Unknown definitions stay unsupported. */ }
            }
            types.add(GTLToolType.VAJRA);
            return Set.copyOf(types);
        }
    }
}
