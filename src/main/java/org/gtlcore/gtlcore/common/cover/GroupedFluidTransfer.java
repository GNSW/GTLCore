package org.gtlcore.gtlcore.common.cover;

import com.gregtechceu.gtceu.api.cover.filter.SimpleFluidFilter;
import com.gregtechceu.gtceu.api.transfer.fluid.FluidTransferDelegate;

import com.lowdragmc.lowdraglib.misc.FluidTransferList;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.fluid.forge.FluidTransferWrapper;

import java.util.ArrayList;
import java.util.List;

/** Plans a complete batch using real fluid identities, including their NBT. */
public record GroupedFluidTransfer(List<FluidStack> drained, List<Move> moves, long amount) {

    public static GroupedFluidTransfer plan(IFluidTransfer source, IFluidTransfer target, SimpleFluidFilter filter) {
        if (source == target || !supportsTankInsertion(target)) return null;
        List<FluidStack> drained = new ArrayList<>();
        long total = 0;
        for (FluidStack match : filter.getMatches()) {
            if (match.isEmpty()) continue;
            long remaining = match.getAmount();
            for (int tank = 0; tank < source.getTanks() && remaining > 0; tank++) {
                FluidStack stored = source.getFluidInTank(tank);
                if (stored.isEmpty() || !(filter.isIgnoreNbt() ? match.getFluid() == stored.getFluid() : match.isFluidEqual(stored))) continue;
                FluidStack reserved = drained.stream().filter(stored::isFluidEqual).findFirst().orElse(null);
                long previous = reserved == null ? 0 : reserved.getAmount();
                long available = source.drain(stored.copy(previous + remaining), true).getAmount() - previous;
                long count = Math.min(remaining, available);
                if (count <= 0) continue;
                if (reserved == null) drained.add(stored.copy(count));
                else reserved.grow(count);
                remaining -= count;
                total += count;
            }
            if (remaining > 0) return null;
        }
        if (total == 0) return null;

        FluidStack[] inserted = new FluidStack[target.getTanks()];
        List<Move> moves = new ArrayList<>();
        for (FluidStack stack : drained) {
            // Also honour restrictions implemented by the aggregate handler.
            if (target.fill(stack.copy(), true) != stack.getAmount()) return null;
            long remaining = stack.getAmount();
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int tank = 0; tank < target.getTanks() && remaining > 0; tank++) {
                    FluidStack occupied = inserted[tank] == null ? target.getFluidInTank(tank) : inserted[tank];
                    if (occupied.isEmpty() != (pass == 1)) continue;
                    if (!occupied.isEmpty() && !occupied.isFluidEqual(stack)) continue;
                    if (!target.supportsFill(tank) || !target.isFluidValid(tank, stack)) continue;
                    long previous = inserted[tank] == null ? 0 : inserted[tank].getAmount();
                    long accepted = target.fill(tank, stack.copy(previous + remaining), true, false) - previous;
                    long count = Math.min(remaining, Math.max(0, accepted));
                    if (count == 0) continue;
                    inserted[tank] = stack.copy(previous + count);
                    moves.add(new Move(tank, stack.copy(count)));
                    remaining -= count;
                }
            }
            if (remaining > 0) return null;
        }
        return new GroupedFluidTransfer(drained, moves, total);
    }

    private static boolean supportsTankInsertion(IFluidTransfer target) {
        // Forge's adapter explicitly throws for indexed fill; its snapshot is also a no-op.
        if (target instanceof FluidTransferWrapper) return false;
        if (target instanceof FluidTransferDelegate delegate) return supportsTankInsertion(delegate.delegate);
        if (target instanceof FluidTransferList list) {
            for (IFluidTransfer child : list.transfers) {
                if (!supportsTankInsertion(child)) return false;
            }
        }
        return true;
    }

    public void execute(IFluidTransfer source, IFluidTransfer target) {
        for (FluidStack stack : drained) source.drain(stack.copy(), false, false);
        for (Move move : moves) target.fill(move.tank, move.stack.copy(), false, false);
        source.onContentsChanged();
        target.onContentsChanged();
    }

    public record Move(int tank, FluidStack stack) {}
}
