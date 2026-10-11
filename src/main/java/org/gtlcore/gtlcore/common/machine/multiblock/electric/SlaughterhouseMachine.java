package org.gtlcore.gtlcore.common.machine.multiblock.electric;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.MachineIO;
import org.gtlcore.gtlcore.utils.Registries;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;

import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.phys.AABB;

import appeng.util.Platform;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class SlaughterhouseMachine extends WorkableElectricMultiblockMachine {

    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            SlaughterhouseMachine.class, WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    private boolean isSpawn;
    private final String[] mobList1 = ConfigHolder.INSTANCE.machines.mobList1;
    private final String[] mobList2 = ConfigHolder.INSTANCE.machines.mobList2;

    public SlaughterhouseMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private void getItem(ServerLevel level, BlockPos Pos) {
        var fp = Platform.getFakePlayer(level, null);
        final DamageSource Source = new DamageSource(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageTypes.GENERIC_KILL), fp);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, new AABB(
                Pos.getX() - 3,
                Pos.getY() - 1,
                Pos.getZ() - 3,
                Pos.getX() + 3,
                Pos.getY() + 6,
                Pos.getZ() + 3));
        for (Entity en : entities) {
            if (!(en instanceof Player)) {
                if (en instanceof LivingEntity livingEntity) {
                    livingEntity.hurt(Source, 10000);
                } else if (en instanceof ItemEntity itemEntity) {
                    MachineIO.outputItem(this, itemEntity.getItem());
                    itemEntity.kill();
                }
            }
            en.kill();
        }
    }

    @Override
    public void afterWorking() {
        final BlockPos pos = getPos();
        final Level level = getLevel();
        BlockPos[][] coordinates = new BlockPos[][] {
                new BlockPos[] { pos.offset(1, 0, 0), pos.offset(3, 1, 0) },
                new BlockPos[] { pos.offset(-1, 0, 0), pos.offset(-3, 1, 0) },
                new BlockPos[] { pos.offset(0, 0, 1), pos.offset(0, 1, 3) },
                new BlockPos[] { pos.offset(0, 0, -1), pos.offset(0, 1, -3) } };
        for (BlockPos[] blockPos : coordinates) {
            if (level instanceof ServerLevel serverLevel &&
                    Objects.equals(Registries.getBlockId(level.getBlockState(blockPos[0]).getBlock()), "gtceu:steel_gearbox")) {
                BlockPos mobPos = blockPos[1];
                final String[] mobList = MachineIO.notConsumableCircuit(this, 1) ? this.mobList1 : this.mobList2;
                if (!this.isSpawn) {
                    getItem(serverLevel, mobPos);
                    for (int am = 0; am <= (getTier() - 2) * 8; am++) {
                        int index = (int) (Math.random() * mobList.length);
                        EntityType.byString("minecraft:" + mobList[index]).get()
                                .spawn(serverLevel, mobPos, MobSpawnType.MOB_SUMMONED)
                                .setYRot(serverLevel.getRandom().nextFloat() * 360F);
                    }
                } else {
                    final LootParams lootparams = new LootParams.Builder(serverLevel)
                            .create(LootContextParamSets.EMPTY);
                    getItem(serverLevel, mobPos);
                    for (int am = 0; am <= (getTier() - 2) * 8; am++) {
                        int index = (int) (Math.random() * mobList.length);
                        ObjectArrayList<ItemStack> loottable = serverLevel.getServer().getLootData()
                                .getLootTable(new ResourceLocation("minecraft:entities/" + mobList[index]))
                                .getRandomItems(lootparams);
                        loottable.forEach(i -> MachineIO.outputItem(this, i));
                    }
                }

            }
        }
    }

    @Override
    public void addDisplayText(@NotNull List<Component> textList) {
        super.addDisplayText(textList);
        if (!this.isFormed) return;
        textList.add(Component.translatable("gtceu.machine.slaughterhouse.is_spawn")
                .append(ComponentPanelWidget.withButton(Component.literal("[")
                        .append(this.isSpawn ?
                                Component.translatable("gtceu.machine.off") :
                                Component.translatable("gtceu.machine.on"))
                        .append(Component.literal("]")), "spawn_switch")));
    }

    @Override
    public void handleDisplayClick(String componentData, ClickData clickData) {
        if (!clickData.isRemote) {
            if (componentData.equals("spawn_switch")) {
                this.isSpawn = !this.isSpawn;
            }
        }
    }
}
