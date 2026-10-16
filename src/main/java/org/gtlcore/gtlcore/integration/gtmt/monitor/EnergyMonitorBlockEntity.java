package org.gtlcore.gtlcore.integration.gtmt.monitor;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.hepdd.gtmthings.api.misc.GlobalVariableStorage;
import com.hepdd.gtmthings.utils.TeamUtil;

import java.math.*;
import java.util.*;

public final class EnergyMonitorBlockEntity extends BlockEntity {

    private UUID owner;
    private boolean formed;
    private CompoundTag display = new CompoundTag();

    public EnergyMonitorBlockEntity(BlockPos p, BlockState s) {
        super(EnergyMonitorContent.ENTITY.get(), p, s);
    }

    public void setOwner(UUID id) {
        owner = id;
        setChanged();
    }

    public UUID account() {
        return owner == null ? null : TeamUtil.getTeamUUID(owner);
    }

    public UUID ownerId() {
        return owner;
    }

    public boolean formed() {
        return formed;
    }

    public CompoundTag display() {
        return display;
    }

    public boolean validStructure() {
        if (level == null || owner == null) return false;
        Direction side = getBlockState().getValue(EnergyMonitorBlock.FACING).getClockWise();
        UUID account = account();
        for (int y = -3; y <= 3; y++) for (int x = -3; x <= 3; x++) {
            if (Math.abs(x) == 3 && Math.abs(y) == 3) continue;
            BlockPos pos = worldPosition.relative(side, x).above(y);
            if (!level.hasChunkAt(pos)) return false;
            boolean inside = Math.abs(x) <= 2 && Math.abs(y) <= 2;
            BlockState state = level.getBlockState(pos);
            if (inside) {
                if (state.getBlock() != getBlockState().getBlock() || state.getValue(EnergyMonitorBlock.FACING) != getBlockState().getValue(EnergyMonitorBlock.FACING) || !(level.getBlockEntity(pos) instanceof EnergyMonitorBlockEntity be) || !Objects.equals(account, be.account())) return false;
            } else if (state.getBlock() == getBlockState().getBlock()) return false;
        }
        return true;
    }

    public EnergyMonitorBlockEntity center() {
        if (level == null) return null;
        Direction side = getBlockState().getValue(EnergyMonitorBlock.FACING).getClockWise();
        for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) {
            BlockPos p = worldPosition.relative(side, x).above(y);
            if (level.hasChunkAt(p) && level.getBlockEntity(p) instanceof EnergyMonitorBlockEntity b && b.formed) return b;
        }
        return null;
    }

    public static void tick(Level l, BlockPos p, BlockState s, EnergyMonitorBlockEntity b) {
        if (l.getGameTime() % 20 == 0) {
            boolean valid = b.validStructure();
            if (valid || b.formed != valid) {
                b.formed = valid;
                b.refresh();
            }
        }
    }

    private void refresh() {
        if (!(level instanceof ServerLevel sl)) return;
        display = new CompoundTag();
        if (formed && account() != null) {
            long now = sl.getServer().overworld().getGameTime();
            now -= now % 20;
            EnergyHistory h = WirelessEnergyHistoryData.get(sl.getServer()).track(account());
            BigInteger current = GlobalVariableStorage.GlobalEnergy.getOrDefault(account(), BigInteger.ZERO);
            h.sample(now, current);
            display.putString("total", current.toString());
            display.putLong("now", now);
            try {
                var ownerName = TeamUtil.GetName(sl, owner);
                if (ownerName != null) display.putString("ownerName", net.minecraft.network.chat.Component.Serializer.toJson(ownerName));
            } catch (RuntimeException ignored) {}
            for (int i = 0; i < 4; i++) {
                BigDecimal avg = h.average(now, EnergyHistory.WINDOWS[i]);
                if (avg != null) display.putString("power" + i, avg.toPlainString());
                if (i != 3) continue;
                ListTag points = new ListTag();
                for (EnergyHistory.Point point : h.fullChart(now)) {
                    CompoundTag t = new CompoundTag();
                    t.putLong("tick", point.tick());
                    t.putString("energy", point.energy().toString());
                    points.add(t);
                }
                if (i == 3) display.put("points", points);
            }
            ListTag changes = new ListTag();
            BigInteger baseline = h.energyAt(now - EnergyHistory.WINDOWS[1]);
            if (baseline != null) {
                for (EnergyHistory.Point point : h.chart(now, EnergyHistory.WINDOWS[1])) {
                    CompoundTag sample = new CompoundTag();
                    sample.putLong("tick", point.tick());
                    sample.putString("energy", point.energy().subtract(baseline).toString());
                    changes.add(sample);
                }
            }
            display.put("changes5m", changes);
            BigDecimal rate = h.average(now, EnergyHistory.WINDOWS[0]);
            display.putString("drainStatus", rate == null ? "collecting" : "not_draining");
            if (rate != null && rate.signum() < 0 && current.signum() > 0) display.putString("drainSeconds", new BigDecimal(current).divide(rate.abs().multiply(BigDecimal.valueOf(20)), 0, RoundingMode.CEILING).toPlainString());
            if (current.signum() <= 0) display.putString("drainSeconds", "0");
            ListTag rows = new ListTag();
            var machines = WirelessEnergyMachineStats.rows(sl.getServer(), account(), 0);
            display.putInt("machines", machines.size());
            for (var row : machines.stream().limit(128).toList()) {
                CompoundTag t = new CompoundTag();
                t.putString("name", net.minecraft.network.chat.Component.Serializer.toJson(row.name()));
                t.putString("dimension", row.dimension());
                t.putString("pos", row.pos().toShortString());
                t.putString("power", row.power().toPlainString());
                rows.add(t);
            }
            display.put("rows", rows);
        }
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (owner != null) tag.putUUID("owner", owner);
    }

    public void load(CompoundTag tag) {
        super.load(tag);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        formed = tag.getBoolean("formed");
        display = tag.getCompound("display");
    }

    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.put("display", display);
        return tag;
    }

    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(3);
    }
}
