package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes chained crafting for the recipe tree.
 *
 * <p>
 * Intermediates are crafted into the player inventory, then the final step is either moved into the
 * inventory or left on the cursor, matching EMI's "craft to inventory / craft to cursor" actions.
 * Steps that belong to a GT machine whose screen is open are transferred into that machine instead
 * (the JEI port of EMI autocraft's machine autocraft), and the chain continues immediately because
 * the machine keeps running the recipe on its own.
 * </p>
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RecipeTreeCrafting {

    private static final int MAX_CRAFT_ACTIONS = 512;
    private static @Nullable Sequence active;
    /**
     * The machine whose screen was opened last, used to route tree steps into it.
     */
    private static @Nullable MetaMachine openedMachine;

    private RecipeTreeCrafting() {}

    static boolean startChain(AbstractContainerScreen<?> screen,
                              IJeiRuntime runtime,
                              MaterialTree tree,
                              boolean finalToCursor) {
        List<Step> steps = buildPlan(tree);
        if (steps.isEmpty()) {
            message("gtlcore.jei.recipe_tree.craft.ready", ChatFormatting.GREEN);
            return false;
        }
        if (finalToCursor && Minecraft.getInstance().player != null &&
                !Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) {
            message("gtlcore.jei.recipe_tree.craft.cursor_busy", ChatFormatting.RED);
            return false;
        }
        active = new Sequence(screen, runtime, steps, finalToCursor);
        if (!active.prepareNextStep()) {
            active = null;
            return false;
        }
        message("gtlcore.jei.recipe_tree.craft.started", ChatFormatting.GRAY);
        return true;
    }

    static boolean startOne(AbstractContainerScreen<?> screen,
                            IJeiRuntime runtime,
                            TreeRecipe recipe,
                            TreeIngredient output,
                            boolean toCursor) {
        return startStep(screen, runtime, recipe, output, 1, toCursor);
    }

    static boolean startStep(AbstractContainerScreen<?> screen,
                             IJeiRuntime runtime,
                             TreeRecipe recipe,
                             TreeIngredient output,
                             long batches,
                             boolean toCursor) {
        if (batches <= 0) {
            return false;
        }
        if (toCursor && Minecraft.getInstance().player != null &&
                !Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) {
            message("gtlcore.jei.recipe_tree.craft.cursor_busy", ChatFormatting.RED);
            return false;
        }
        active = new Sequence(screen, runtime,
                List.of(new Step(recipe, output, batches)), toCursor);
        if (!active.prepareNextStep()) {
            active = null;
            return false;
        }
        return true;
    }

    static boolean isRunning() {
        return active != null;
    }

    private static List<Step> buildPlan(MaterialTree tree) {
        List<Step> steps = new ArrayList<>();
        collectPlan(tree.goal, steps, new ArrayList<>());
        return steps;
    }

    private static void collectPlan(MaterialNode node, List<Step> steps, List<TreeRecipe> used) {
        if (node.recipe != null && node.recipe.isResolution()) {
            if (node.children != null && !node.children.isEmpty()) {
                collectPlan(node.children.get(0), steps, used);
            }
            return;
        }
        if (node.recipe != null && node.children != null) {
            if (used.contains(node.recipe)) {
                return;
            }
            used.add(node.recipe);
            for (MaterialNode child : node.children) {
                collectPlan(child, steps, used);
            }
            used.remove(used.size() - 1);
        }
        if (node.recipe != null && !node.recipe.isResolution() && node.neededBatches > 0) {
            steps.add(new Step(node.recipe, node.ingredient, node.neededBatches));
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) {
            return;
        }
        Sequence sequence = active;
        if (sequence.waitTicks > 0) {
            sequence.waitTicks--;
            return;
        }
        if (sequence.needsFill) {
            boolean machine = fillsMachine(sequence.screen, sequence.currentStep().recipe());
            if (!fill(sequence.screen, sequence.runtime, sequence.currentStep().recipe(), machine)) {
                message(machine ? "gtlcore.jei.recipe_tree.craft.machine_no_batch" :
                        "gtlcore.jei.recipe_tree.craft.insufficient", ChatFormatting.RED);
                active = null;
                return;
            }
            if (machine) {
                // A machine runs the recipe on its own: hand the ingredients over and go on with the
                // next step instead of waiting for a result slot, like EMI autocraft does.
                message("gtlcore.jei.recipe_tree.craft.machine_done", ChatFormatting.GREEN);
                if (!sequence.prepareNextStep()) {
                    active = null;
                }
                return;
            }
            sequence.needsFill = false;
            sequence.waitTicks = craftDelay();
            return;
        }

        Slot result = findResultSlot(sequence);
        if (result == null) {
            if (sequence.resultChecks++ < 3) {
                sequence.waitTicks = 1;
                return;
            }
            message("gtlcore.jei.recipe_tree.craft.no_result", ChatFormatting.RED);
            active = null;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            active = null;
            return;
        }
        boolean cursor = sequence.finalToCursor &&
                sequence.stepIndex == sequence.steps.size() - 1;
        ClickType clickType = cursor ? ClickType.PICKUP : ClickType.QUICK_MOVE;
        minecraft.gameMode.handleInventoryMouseClick(sequence.screen.getMenu().containerId,
                result.index, 0, clickType, player);
        sequence.craftedActions++;
        sequence.currentRemaining--;
        sequence.resultChecks = 0;

        if (sequence.craftedActions >= MAX_CRAFT_ACTIONS) {
            message("gtlcore.jei.recipe_tree.craft.limit", ChatFormatting.YELLOW);
            active = null;
            return;
        }
        if (sequence.currentRemaining > 0) {
            sequence.needsFill = true;
            sequence.waitTicks = 1;
        } else if (!sequence.prepareNextStep()) {
            active = null;
        }
    }

    private static int craftDelay() {
        ConfigHolder config = ConfigHolder.INSTANCE;
        if (config == null || config.jei == null) {
            return 5;
        }
        return Math.max(1, Math.min(100, config.jei.recipeTreeCraftDelayTicks));
    }

    private static boolean fill(AbstractContainerScreen<?> screen,
                                IJeiRuntime runtime,
                                TreeRecipe recipe,
                                boolean maxTransfer) {
        Player player = Minecraft.getInstance().player;
        if (player == null || recipe.category() == null || recipe.recipe() == null) {
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        IRecipeCategory<Object> category = recipe.category();
        IRecipeTransferHandler handler = runtime.getRecipeTransferManager()
                .getRecipeTransferHandler(menu, category)
                .orElse(null);
        if (handler == null) {
            return false;
        }
        IRecipeLayoutDrawable<Object> layout = runtime.getRecipeManager()
                .createRecipeLayoutDrawable(category, recipe.recipe(),
                        runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElse(null);
        if (layout == null) {
            return false;
        }
        IRecipeTransferError error = transfer(handler, menu, recipe.recipe(), layout, player, maxTransfer);
        return error == null;
    }

    /**
     * Mirrors EMI autocraft's {@code AutocraftScreen.GT}: a step is filled into the opened machine
     * when the container is a machine screen and the machine either is the one that was opened or
     * cannot be identified, and the recipe is a GT recipe.
     */
    static boolean fillsMachine(AbstractContainerScreen<?> screen, TreeRecipe recipe) {
        if (!(screen.getMenu() instanceof ModularUIContainer)) {
            return false;
        }
        if (!(recipe.recipe() instanceof GTRecipeWrapper wrapper) || wrapper.recipe == null ||
                wrapper.recipe.recipeType == null) {
            return false;
        }
        MetaMachine machine = openedMachine;
        return machine == null || machineCanRun(machine, wrapper.recipe.recipeType);
    }

    private static boolean machineCanRun(MetaMachine machine, GTRecipeType recipeType) {
        for (GTRecipeType supported : machine.getDefinition().getRecipeTypes()) {
            if (supported != recipeType) {
                continue;
            }
            if (machine instanceof IRecipeLogicMachine logicMachine) {
                return logicMachine.getRecipeType() == recipeType;
            }
            return true;
        }
        return false;
    }

    /**
     * Tracks the machine whose screen the player opened, so tree steps can be told apart from
     * plain crafting-table steps. Mirrors EMI autocraft's {@code openedMachine}.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof IMachineBlockEntity machineBlockEntity) {
            openedMachine = resolveController(machineBlockEntity.getMetaMachine());
        } else {
            openedMachine = null;
        }
    }

    private static MetaMachine resolveController(MetaMachine machine) {
        if (machine instanceof MultiblockPartMachine partMachine && partMachine.isFormed()) {
            List<IMultiController> controllers = partMachine.getControllers();
            if (!controllers.isEmpty()) {
                return controllers.get(0).self();
            }
        }
        return machine;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static IRecipeTransferError transfer(IRecipeTransferHandler handler,
                                                 AbstractContainerMenu menu,
                                                 Object recipe,
                                                 IRecipeLayoutDrawable<Object> layout,
                                                 Player player,
                                                 boolean maxTransfer) {
        return handler.transferRecipe(menu, recipe, layout.getRecipeSlotsView(), player, maxTransfer, true);
    }

    private static @Nullable Slot findResultSlot(Sequence sequence) {
        Step step = sequence.currentStep();
        Optional<ItemStack> target = step.output.displayItemStack() == null ?
                Optional.empty() : Optional.of(step.output.displayItemStack());
        if (target.isEmpty()) {
            return null;
        }
        IIngredientHelper<ItemStack> helper = sequence.runtime.getIngredientManager()
                .getIngredientHelper(VanillaTypes.ITEM_STACK);
        String targetId = helper.getUniqueId(target.get(), UidContext.Ingredient);
        for (Slot slot : sequence.screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (slot instanceof ResultSlot && !stack.isEmpty() &&
                    targetId.equals(helper.getUniqueId(stack, UidContext.Ingredient))) {
                return slot;
            }
        }
        return null;
    }

    private static void message(String key, ChatFormatting color) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(key).withStyle(color), true);
        }
    }

    private record Step(TreeRecipe recipe, TreeIngredient output, long batches) {}

    private static final class Sequence {

        private final AbstractContainerScreen<?> screen;
        private final IJeiRuntime runtime;
        private final List<Step> steps;
        private final boolean finalToCursor;
        private int stepIndex = -1;
        private long currentRemaining;
        private int waitTicks;
        private int resultChecks;
        private int craftedActions;
        private boolean needsFill;

        private Sequence(AbstractContainerScreen<?> screen,
                         IJeiRuntime runtime,
                         List<Step> steps,
                         boolean finalToCursor) {
            this.screen = screen;
            this.runtime = runtime;
            this.steps = steps;
            this.finalToCursor = finalToCursor;
        }

        private Step currentStep() {
            return steps.get(stepIndex);
        }

        private boolean prepareNextStep() {
            while (++stepIndex < steps.size()) {
                Step step = steps.get(stepIndex);
                currentRemaining = step.batches;
                if (currentRemaining > 0) {
                    needsFill = true;
                    waitTicks = 0;
                    resultChecks = 0;
                    return true;
                }
            }
            message("gtlcore.jei.recipe_tree.craft.completed_tree", ChatFormatting.GREEN);
            return false;
        }
    }
}
