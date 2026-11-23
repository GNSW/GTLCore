package org.gtlcore.gtlcore.common.command;

import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** Temporary server-side probes for AE2 storage graph work. */
public final class StorageGraphTestCommand {

    private StorageGraphTestCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gtltest")
                .then(Commands.literal("stocks")
                        .executes(context -> stocks(context.getSource())))
                .then(Commands.literal("graph")
                        .executes(context -> graph(context.getSource())))
                .then(Commands.literal("simulate")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> simulate(context.getSource(),
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("insert")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> insert(context.getSource(),
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("reserve")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("lock", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("request", IntegerArgumentType.integer(1))
                                                .executes(context -> reserve(context.getSource(),
                                                        StringArgumentType.getString(context, "item"),
                                                        IntegerArgumentType.getInteger(context, "lock"),
                                                        IntegerArgumentType.getInteger(context, "request"))))))));
    }

    private static MEStorage storage(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(16.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            return null;
        }
        IGridNode node = GridHelper.getExposedNode(player.level(), blockHit.getBlockPos(), blockHit.getDirection());
        if (node == null) {
            return null;
        }
        IGrid grid = node.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    private static int graph(CommandSourceStack source) throws CommandSyntaxException {
        MEStorage storage = storage(source);
        if (storage == null) {
            source.sendFailure(Component.literal("no AE2 grid at the targeted block"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(StorageGraphManager.dump(storage)), false);
        return 1;
    }

    private static int stocks(CommandSourceStack source) throws CommandSyntaxException {
        MEStorage storage = storage(source);
        if (storage == null) {
            source.sendFailure(Component.literal("no AE2 grid at the targeted block"));
            return 0;
        }
        KeyCounter counter = new KeyCounter();
        storage.getAvailableStacks(counter);
        long total = 0L;
        for (var entry : counter) {
            total += entry.getLongValue();
        }
        long sum = total;
        source.sendSuccess(() -> Component.literal("stocks keys=" + counter.size() + " total=" + sum), false);
        return 1;
    }

    private static int simulate(CommandSourceStack source, String item, long amount) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ResourceLocation id = ResourceLocation.tryParse(item);
        if (id == null) {
            source.sendFailure(Component.literal("bad id " + item));
            return 0;
        }
        AEKey key;
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            key = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(id)));
        } else if (BuiltInRegistries.BLOCK.containsKey(id) && !BuiltInRegistries.BLOCK.get(id).asItem().equals(net.minecraft.world.item.Items.AIR)) {
            key = AEItemKey.of(new ItemStack(BuiltInRegistries.BLOCK.get(id).asItem()));
        } else if (ForgeRegistries.FLUIDS.containsKey(id)) {
            key = AEFluidKey.of(new FluidStack(ForgeRegistries.FLUIDS.getValue(id), 1));
        } else {
            source.sendFailure(Component.literal("unknown item/block/fluid " + item));
            return 0;
        }
        MEStorage storage = storage(source);
        if (storage == null) {
            source.sendFailure(Component.literal("no AE2 grid at the targeted block"));
            return 0;
        }
        IActionSource action = IActionSource.ofPlayer(player);
        long available = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, action);
        long simulated = storage.extract(key, amount, Actionable.SIMULATE, action);
        source.sendSuccess(() -> Component.literal("key=" + key + " available=" + available + " simulate(" + amount + ")=" + simulated), false);
        return 1;
    }

    private static int insert(CommandSourceStack source, String item, long amount) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ResourceLocation id = ResourceLocation.tryParse(item);
        if (id == null) {
            source.sendFailure(Component.literal("bad id " + item));
            return 0;
        }
        AEKey key;
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            key = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(id)));
        } else if (BuiltInRegistries.BLOCK.containsKey(id) && !BuiltInRegistries.BLOCK.get(id).asItem().equals(net.minecraft.world.item.Items.AIR)) {
            key = AEItemKey.of(new ItemStack(BuiltInRegistries.BLOCK.get(id).asItem()));
        } else if (ForgeRegistries.FLUIDS.containsKey(id)) {
            key = AEFluidKey.of(new FluidStack(ForgeRegistries.FLUIDS.getValue(id), 1));
        } else {
            source.sendFailure(Component.literal("unknown item/block/fluid " + item));
            return 0;
        }
        MEStorage storage = storage(source);
        if (storage == null) {
            source.sendFailure(Component.literal("no AE2 grid at the targeted block"));
            return 0;
        }
        IActionSource action = IActionSource.ofPlayer(player);
        long inserted = storage.insert(key, amount, Actionable.SIMULATE, action);
        source.sendSuccess(() -> Component.literal("key=" + key + " insert(" + amount + ")=" + inserted), false);
        return 1;
    }

    private static int reserve(CommandSourceStack source, String item, int lock, int request) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        AEKey key = keyOf(source, item);
        if (key == null) {
            return 0;
        }
        MEStorage storage = storage(source);
        if (storage == null) {
            source.sendFailure(Component.literal("no AE2 grid at the targeted block"));
            return 0;
        }
        IActionSource action = IActionSource.ofPlayer(player);
        KeyCounter requested = new KeyCounter();
        requested.add(key, lock);
        ManualCraftingInventoryLock.Reservation reservation = ManualCraftingInventoryLock.tryAcquire(storage,
                requested, action);
        try {
            long available = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, action);
            long allowed = ManualCraftingInventoryLock.limitExtraction(storage, key, request, action);
            long extracted = storage.extract(key, request, Actionable.MODULATE, action);
            source.sendSuccess(() -> Component.literal("available=" + available + " lock=" + lock + " allowed=" + allowed + " extracted=" + extracted), false);
        } finally {
            if (reservation != null) {
                reservation.close();
            }
        }
        return 1;
    }

    private static AEKey keyOf(CommandSourceStack source, String item) {
        ResourceLocation id = ResourceLocation.tryParse(item);
        if (id == null) {
            source.sendFailure(Component.literal("bad id " + item));
            return null;
        }
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            return AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(id)));
        }
        if (BuiltInRegistries.BLOCK.containsKey(id) && !BuiltInRegistries.BLOCK.get(id).asItem().equals(net.minecraft.world.item.Items.AIR)) {
            return AEItemKey.of(new ItemStack(BuiltInRegistries.BLOCK.get(id).asItem()));
        }
        if (ForgeRegistries.FLUIDS.containsKey(id)) {
            return AEFluidKey.of(new FluidStack(ForgeRegistries.FLUIDS.getValue(id), 1));
        }
        source.sendFailure(Component.literal("unknown item/block/fluid " + item));
        return null;
    }
}
