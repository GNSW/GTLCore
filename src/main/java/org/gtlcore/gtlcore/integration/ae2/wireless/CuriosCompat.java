package org.gtlcore.gtlcore.integration.ae2.wireless;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import appeng.items.tools.powered.WirelessTerminalItem;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class CuriosCompat {

    private static final String CURIOS_API_CLASS = "top.theillusivec4.curios.api.CuriosApi";
    private static final String ICURIOS_HELPER_CLASS = "top.theillusivec4.curios.api.type.util.ICuriosHelper";
    private static final String SLOT_RESULT_CLASS = "top.theillusivec4.curios.api.SlotResult";
    private static final String SLOT_CONTEXT_CLASS = "top.theillusivec4.curios.api.SlotContext";

    private static boolean loaded;
    private static boolean checked;

    private static Method getCuriosHelperMethod;
    private static Method findCuriosMethod;
    private static Method slotResultStackMethod;
    private static Method slotResultSlotContextMethod;
    private static Method slotContextIdentifierMethod;
    private static Method slotContextIndexMethod;

    private CuriosCompat() {}

    public static boolean isLoaded() {
        if (!checked) {
            checked = true;
            try {
                Class<?> curiosApiClass = Class.forName(CURIOS_API_CLASS);
                Class<?> helperClass = Class.forName(ICURIOS_HELPER_CLASS);
                Class<?> slotResultClass = Class.forName(SLOT_RESULT_CLASS);
                Class<?> slotContextClass = Class.forName(SLOT_CONTEXT_CLASS);

                getCuriosHelperMethod = curiosApiClass.getMethod("getCuriosHelper");
                findCuriosMethod = helperClass.getMethod("findCurios", LivingEntity.class, Predicate.class);
                slotResultStackMethod = slotResultClass.getMethod("stack");
                slotResultSlotContextMethod = slotResultClass.getMethod("slotContext");
                slotContextIdentifierMethod = slotContextClass.getMethod("identifier");
                slotContextIndexMethod = slotContextClass.getMethod("index");

                loaded = true;
            } catch (ReflectiveOperationException | LinkageError e) {
                loaded = false;
            }
        }
        return loaded;
    }

    public record TerminalSlot(ItemStack stack, String identifier, int index) {}

    public record CurioSlot(ItemStack stack, String identifier, int index) {}

    @SuppressWarnings("unchecked")
    public static @Nullable TerminalSlot findWirelessTerminal(Player player) {
        if (!isLoaded()) {
            return null;
        }

        try {
            Object helper = getCuriosHelperMethod.invoke(null);
            List<Object> results = (List<Object>) findCuriosMethod.invoke(
                    helper,
                    player,
                    (Predicate<ItemStack>) stack -> stack.getItem() instanceof WirelessTerminalItem);
            for (Object result : results) {
                ItemStack stack = (ItemStack) slotResultStackMethod.invoke(result);
                if (stack.isEmpty()) {
                    continue;
                }
                Object slotContext = slotResultSlotContextMethod.invoke(result);
                String identifier = (String) slotContextIdentifierMethod.invoke(slotContext);
                int index = (int) slotContextIndexMethod.invoke(slotContext);
                return new TerminalSlot(stack, identifier, index);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Curios may be unavailable or incompatible; fall back to no curios support.
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<CurioSlot> getCurioSlots(Player player) {
        if (!isLoaded()) return List.of();
        try {
            Object helper = getCuriosHelperMethod.invoke(null);
            List<Object> results = (List<Object>) findCuriosMethod.invoke(
                    helper, player, (Predicate<ItemStack>) stack -> true);
            List<CurioSlot> slots = new ArrayList<>(results.size());
            for (Object result : results) {
                ItemStack stack = (ItemStack) slotResultStackMethod.invoke(result);
                if (stack.isEmpty()) continue;
                Object slotContext = slotResultSlotContextMethod.invoke(result);
                slots.add(new CurioSlot(stack,
                        (String) slotContextIdentifierMethod.invoke(slotContext),
                        (int) slotContextIndexMethod.invoke(slotContext)));
            }
            return slots;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    public static void writeAllCurios(Player player) {
        for (CurioSlot slot : getCurioSlots(player)) {
            writeStackBack(slot.identifier(), slot.index(), player, slot.stack());
        }
    }

    public static List<ItemStack> getCurioStacks(Player player) {
        return getCurioSlots(player).stream().map(CurioSlot::stack).toList();
    }

    public static ItemStack locateItem(String identifier, int index, Player player) {
        if (!isLoaded()) {
            return ItemStack.EMPTY;
        }

        try {
            Object helper = getCuriosHelperMethod.invoke(null);
            List<Object> results = (List<Object>) findCuriosMethod.invoke(
                    helper,
                    player,
                    (Predicate<ItemStack>) stack -> true);
            for (Object result : results) {
                Object slotContext = slotResultSlotContextMethod.invoke(result);
                String resultIdentifier = (String) slotContextIdentifierMethod.invoke(slotContext);
                int resultIndex = (int) slotContextIndexMethod.invoke(slotContext);
                if (identifier.equals(resultIdentifier) && index == resultIndex) {
                    return (ItemStack) slotResultStackMethod.invoke(result);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Curios may be unavailable or incompatible.
        }
        return ItemStack.EMPTY;
    }

    public static void writeStackBack(String identifier, int index, Player player, ItemStack stack) {
        if (!isLoaded()) {
            return;
        }

        try {
            Class<?> curiosApiClass = Class.forName(CURIOS_API_CLASS);
            Object optional = curiosApiClass.getMethod("getCuriosInventory", LivingEntity.class)
                    .invoke(null, player);
            if (optional == null) {
                return;
            }
            Object handler = optional.getClass().getMethod("resolve").invoke(optional);
            if (!(handler instanceof java.util.Optional<?> resolved) || resolved.isEmpty()) {
                return;
            }
            Object curiosItemHandler = resolved.get();
            Object curiosMap = curiosItemHandler.getClass().getMethod("getCurios").invoke(curiosItemHandler);
            if (!(curiosMap instanceof java.util.Map<?, ?> map)) {
                return;
            }
            Object stacksHandler = map.get(identifier);
            if (stacksHandler == null) {
                return;
            }
            Object stacks = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
            stacks.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                    .invoke(stacks, index, stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Curios may be unavailable or incompatible.
        }
    }
}
