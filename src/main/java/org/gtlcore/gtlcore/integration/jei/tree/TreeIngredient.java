package org.gtlcore.gtlcore.integration.jei.tree;

import com.gregtechceu.gtceu.api.item.IGTTool;
import com.gregtechceu.gtceu.api.item.tool.GTToolType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JEI-native counterpart of EMI's {@code EmiIngredient}.
 *
 * <p>
 * One tree ingredient covers every alternative that a single JEI recipe slot can display. Ingredients
 * with one alternative behave like a plain stack; ingredients with several alternatives represent
 * tags/ore dictionary entries and are only resolved when the player asks for it.
 * </p>
 */
final class TreeIngredient {

    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#,###.##");

    static final TreeIngredient EMPTY = new TreeIngredient(List.of(), "", 1, 1.0F, null, 0, false);

    private final List<Variant> variants;
    private final String key;
    private final long amount;
    private final float chance;
    private final @Nullable Variant remainder;
    private final long remainderAmount;
    private final boolean catalyst;

    private TreeIngredient(List<Variant> variants,
                           String key,
                           long amount,
                           float chance,
                           @Nullable Variant remainder,
                           long remainderAmount,
                           boolean catalyst) {
        this.variants = variants;
        this.key = key;
        this.amount = amount;
        this.chance = chance;
        this.remainder = remainder;
        this.remainderAmount = remainderAmount;
        this.catalyst = catalyst;
    }

    static TreeIngredient of(IIngredientManager ingredientManager,
                             Collection<? extends ITypedIngredient<?>> ingredients,
                             float chance) {
        return of(ingredientManager, ingredients, chance, false);
    }

    static TreeIngredient of(IIngredientManager ingredientManager,
                             Collection<? extends ITypedIngredient<?>> ingredients,
                             float chance,
                             boolean expandTools) {
        Map<String, Variant> unique = new LinkedHashMap<>();
        for (ITypedIngredient<?> typed : ingredients) {
            if (typed == null) {
                continue;
            }
            Variant variant = Variant.of(ingredientManager, typed, true);
            if (variant != null) {
                unique.putIfAbsent(variant.uid(), variant);
            }
        }
        if (expandTools) {
            for (Variant variant : List.copyOf(unique.values())) {
                if (variant.toolType() != null) {
                    for (Variant sibling : toolsOfType(ingredientManager, variant.toolType())) {
                        unique.putIfAbsent(sibling.uid(), sibling);
                    }
                }
            }
        }
        return ofVariants(List.copyOf(unique.values()), chance);
    }

    private static final Map<GTToolType, List<Variant>> TOOL_VARIANTS = new HashMap<>();

    static void resetCaches() {
        TOOL_VARIANTS.clear();
    }

    private static List<Variant> toolsOfType(IIngredientManager ingredientManager,
                                             GTToolType toolType) {
        List<Variant> cached = TOOL_VARIANTS.get(toolType);
        if (cached != null) {
            return cached;
        }
        List<Variant> variants = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof IGTTool tool && tool.getToolType() == toolType) {
                ItemStack stack = new ItemStack(item);
                ITypedIngredient<ItemStack> typed = ingredientManager
                        .createTypedIngredient(VanillaTypes.ITEM_STACK, stack)
                        .orElse(null);
                Variant variant = typed == null ? null : Variant.of(ingredientManager, typed, false);
                if (variant != null) {
                    variants.add(variant);
                }
            }
        }
        List<Variant> result = List.copyOf(variants);
        TOOL_VARIANTS.put(toolType, result);
        return result;
    }

    static TreeIngredient ofVariants(List<Variant> variants, float chance) {
        if (variants.isEmpty()) {
            return new TreeIngredient(List.of(), "", 1, 1.0F, null, 0, false);
        }
        long amount = Math.max(1, variants.get(0).amount());
        String key = variants.size() == 1 ? variants.get(0).uid() : groupKey(variants);
        Variant remainder = variants.size() == 1 ? variants.get(0).remainder() : null;
        long remainderAmount = variants.size() == 1 ? variants.get(0).remainderAmount() : 0;
        boolean catalyst = variants.stream().allMatch(Variant::catalytic);
        return new TreeIngredient(List.copyOf(variants), key, amount,
                Math.max(0.0F, Math.min(1.0F, chance)), remainder, remainderAmount, catalyst);
    }

    static TreeIngredient single(Variant variant) {
        return ofVariants(List.of(variant), 1.0F);
    }

    private static String groupKey(List<Variant> variants) {
        StringBuilder builder = new StringBuilder("§");
        for (Variant variant : variants) {
            builder.append(variant.uid()).append('§');
        }
        return builder.toString();
    }

    String key() {
        return key;
    }

    List<Variant> variants() {
        return variants;
    }

    Variant displayVariant() {
        return variants.isEmpty() ? Variant.EMPTY : variants.get(0);
    }

    long amount() {
        return amount;
    }

    float chance() {
        return chance;
    }

    @Nullable
    Variant remainder() {
        return remainder;
    }

    long remainderAmount() {
        return remainderAmount;
    }

    boolean catalyst() {
        return catalyst;
    }

    boolean toolLike() {
        if (variants.isEmpty()) {
            return false;
        }
        for (Variant variant : variants) {
            if (!variant.tool()) {
                return false;
            }
        }
        return true;
    }

    boolean isEmpty() {
        return variants.isEmpty();
    }

    TreeIngredient setAmount(long newAmount) {
        return new TreeIngredient(variants, key, Math.max(0, newAmount), chance,
                remainder, remainderAmount, catalyst);
    }

    TreeIngredient setChance(float newChance) {
        return new TreeIngredient(variants, key, amount,
                Math.max(0.0F, Math.min(1.0F, newChance)), remainder, remainderAmount, catalyst);
    }

    boolean matches(TreeIngredient other) {
        if (other == null || !key.equals(other.key)) {
            return false;
        }
        String ownRemainder = remainder == null ? null : remainder.uid();
        String otherRemainder = other.remainder == null ? null : other.remainder.uid();
        return Objects.equals(ownRemainder, otherRemainder);
    }

    void render(GuiGraphics graphics, int x, int y) {
        Variant variant = displayVariant();
        if (variant.display() != null && variant.display().getIngredient() instanceof FluidStack fluid && !fluid.isEmpty()) {
            renderFluid(graphics, fluid, x, y);
            return;
        }
        if (variant.renderer() != null && variant.display() != null) {
            try {
                variant.renderer().render(graphics, variant.display().getIngredient(), x, y);
            } catch (RuntimeException ignored) {
                // A broken ingredient renderer must not take the whole tree screen down.
            }
        }
    }

    private static void renderFluid(GuiGraphics graphics, FluidStack fluid, int x, int y) {
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(extensions.getStillTexture(fluid));
        int tint = extensions.getTintColor(fluid);
        graphics.setColor(((tint >> 16) & 0xFF) / 255F, ((tint >> 8) & 0xFF) / 255F,
                (tint & 0xFF) / 255F, ((tint >> 24) & 0xFF) / 255F);
        graphics.blit(x, y, 0, 16, 16, sprite);
        graphics.setColor(1F, 1F, 1F, 1F);
    }

    List<Component> tooltip() {
        Variant variant = displayVariant();
        if (variant.renderer() == null || variant.display() == null) {
            return List.of();
        }
        return List.copyOf(variant.renderer().getTooltip(variant.display().getIngredient(),
                TooltipFlag.Default.NORMAL));
    }

    @Nullable
    ItemStack displayItemStack() {
        return displayVariant().display() == null ? null :
                displayVariant().display().getItemStack().orElse(null);
    }

    @Nullable
    ITypedIngredient<?> displayTyped() {
        return displayVariant().display();
    }

    static Component amountComponent(TreeIngredient ingredient, long amount, boolean chanced) {
        if (ingredient.isEmpty() || amount <= 0) {
            return Component.empty();
        }
        String text = AMOUNT_FORMAT.format(amount);
        if (ingredient.displayVariant().isFluid()) {
            text = text + " mB";
        }
        Component component = Component.literal(text);
        if (chanced) {
            component = Component.literal("≈").append(component);
            component = component.copy().withStyle(ChatFormatting.GOLD);
        }
        return component;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TreeIngredient ingredient && key.equals(ingredient.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "TreeIngredient[" + key + " x" + amount + "]";
    }

    /**
     * One concrete alternative inside a tree ingredient.
     */
    record Variant(ITypedIngredient<Object> typed,
                   ITypedIngredient<Object> display,
                   IIngredientRenderer<Object> renderer,
                   String uid,
                   String name,
                   long amount,
                   @Nullable Variant remainder,
                   long remainderAmount,
                   @Nullable GTToolType toolType) {

        private static final Variant EMPTY = new Variant(null, null, null, "", "", 1, null, 0, null);

        @SuppressWarnings("unchecked")
        static @Nullable Variant of(IIngredientManager ingredientManager,
                                    ITypedIngredient<?> typed,
                                    boolean allowRemainder) {
            try {
                return create(ingredientManager, (ITypedIngredient<Object>) typed, allowRemainder);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static <T> @Nullable Variant create(IIngredientManager ingredientManager,
                                                    ITypedIngredient<T> typed,
                                                    boolean allowRemainder) {
            IIngredientHelper<T> helper = ingredientManager.getIngredientHelper(typed.getType());
            T ingredient = typed.getIngredient();
            if (!helper.isValidIngredient(ingredient)) {
                return null;
            }
            String uid = helper.getUniqueId(ingredient, UidContext.Ingredient);
            String name = helper.getDisplayName(ingredient);
            long amount = Math.max(1, helper.getAmount(ingredient));

            T copy = helper.copyIngredient(ingredient);
            if (copy instanceof ItemStack stack) {
                stack.setCount(1);
            } else if (copy instanceof FluidStack fluid) {
                fluid.setAmount(1);
            }
            ITypedIngredient<T> display = ingredientManager.createTypedIngredient(typed.getType(), copy)
                    .orElse(typed);

            GTToolType toolType = ingredient instanceof ItemStack toolStack &&
                    toolStack.getItem() instanceof IGTTool tool ? tool.getToolType() : null;
            Variant remainder = null;
            long remainderAmount = 0;
            if (allowRemainder && toolType != null) {
                ITypedIngredient<T> self = ingredientManager.createTypedIngredient(typed.getType(), copy)
                        .orElse(typed);
                remainder = create(ingredientManager, self, false);
                remainderAmount = 1;
            } else if (allowRemainder && ingredient instanceof ItemStack stack) {
                ItemStack remaining = stack.getCraftingRemainingItem();
                if (!remaining.isEmpty()) {
                    ITypedIngredient<ItemStack> remainingTyped = ingredientManager.createTypedIngredient(
                            VanillaTypes.ITEM_STACK, remaining).orElse(null);
                    if (remainingTyped != null) {
                        remainder = of(ingredientManager, remainingTyped, false);
                        remainderAmount = remaining.getCount();
                    }
                }
            }

            return new Variant((ITypedIngredient<Object>) typed,
                    (ITypedIngredient<Object>) display,
                    (IIngredientRenderer<Object>) ingredientManager.getIngredientRenderer(typed.getType()),
                    uid, name, amount, remainder, remainderAmount, toolType);
        }

        boolean tool() {
            return toolType != null;
        }

        boolean catalytic() {
            return tool() || remainder != null && remainder.uid.equals(uid);
        }

        boolean isFluid() {
            return display != null && display.getIngredient() instanceof FluidStack ||
                    typed != null && typed.getIngredient() instanceof FluidStack;
        }
    }
}
