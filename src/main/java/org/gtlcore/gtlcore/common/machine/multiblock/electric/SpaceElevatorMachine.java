package org.gtlcore.gtlcore.common.machine.multiblock.electric;

import org.gtlcore.gtlcore.api.machine.IGTLRenderDisabled;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.client.gui.widget.IExtendedClickData;
import org.gtlcore.gtlcore.common.data.GTLBlocks;
import org.gtlcore.gtlcore.utils.MachineUtil;
import org.gtlcore.gtlcore.utils.datastructure.ModuleRenderInfo;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;

import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.*;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import earth.terrarium.adastra.common.menus.base.PlanetsMenuProvider;
import earth.terrarium.botarium.common.menu.MenuHooks;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.gtlcore.gtlcore.common.data.machines.AdvancedMultiBlockMachineA.ASSEMBLER_MODULE;
import static org.gtlcore.gtlcore.common.data.machines.AdvancedMultiBlockMachineA.RESOURCE_COLLECTION;

public class SpaceElevatorMachine extends TierCasingMachine
                                  implements IModularMachineHost<SpaceElevatorMachine>, IMachineLife {

    private final Set<IModularMachineModule<SpaceElevatorMachine, ?>> modules = new ReferenceOpenHashSet<>();
    private int mam = 0;
    private TickableSubscription connectionSubscription;

    public SpaceElevatorMachine(IMachineBlockEntity holder) {
        super(holder, "SEPMTier");
    }

    @Override
    public void onLoad() {
        super.onLoad();
        updateConnectionSubscription();
    }

    @Override
    public void onUnload() {
        stopConnectionSubscription();
        super.onUnload();
    }

    @Override
    public @NotNull Set<IModularMachineModule<SpaceElevatorMachine, ?>> getModuleSet() {
        return modules;
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        safeClearModules();
        stopConnectionSubscription();
    }

    @Override
    public void onMachineRemoved() {
        safeClearModules();
        stopConnectionSubscription();
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        safeClearModules();
        scanAndConnectSpaceElevatorModules("host_structure_formed");
        updateConnectionSubscription();
    }

    /**
     * 主机侧周期性兜底重连：仅当已连接模块数少于槽位数时重扫。
     * 修复「主机已成型后拆除模块再重新放置、或模块事件时序错过」时不再连接的问题
     * （模块成型事件只会在模块自身生命周期触发一次，主机不再重扫则永久掉线）。
     */
    private void updateConnectionSubscription() {
        if (isFormed()) {
            if (connectionSubscription == null) {
                connectionSubscription = subscribeServerTick(this::connectionTick);
            }
        } else {
            stopConnectionSubscription();
        }
    }

    private void stopConnectionSubscription() {
        if (connectionSubscription != null) {
            connectionSubscription.unsubscribe();
            connectionSubscription = null;
        }
    }

    private void connectionTick() {
        if (!isFormed()) {
            stopConnectionSubscription();
            return;
        }
        if (getOffsetTimer() % 20L == 0L && modules.size() < getModuleScanPositions().length) {
            scanAndConnectSpaceElevatorModules("host_periodic_retry");
        }
    }

    @Override
    public BlockPos[] getModuleScanPositions() {
        final Level level = getLevel();
        final BlockPos powerCore = getPowerCore(getPos(), level);
        if (powerCore != null) {
            return new BlockPos[] {
                    powerCore.offset(8, 2, 3),
                    powerCore.offset(8, 2, -3),
                    powerCore.offset(-8, 2, 3),
                    powerCore.offset(-8, 2, -3),
                    powerCore.offset(3, 2, 8),
                    powerCore.offset(-3, 2, 8),
                    powerCore.offset(3, 2, -8),
                    powerCore.offset(-3, 2, -8)
            };
        }
        return MachineUtil.EMPTY_POS_ARRAY;
    }

    @Override
    public @NotNull List<ModuleRenderInfo> getModulesForRendering() {
        return List.of(
                new ModuleRenderInfo(
                        new BlockPos(-3, 0, -5),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.NORTH,
                        Direction.UP,
                        RESOURCE_COLLECTION),
                new ModuleRenderInfo(
                        new BlockPos(-8, 0, 0),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.WEST,
                        Direction.UP,
                        ASSEMBLER_MODULE),
                new ModuleRenderInfo(
                        new BlockPos(-8, 0, 6),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.WEST,
                        Direction.UP,
                        RESOURCE_COLLECTION),
                new ModuleRenderInfo(
                        new BlockPos(-3, 0, 11),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.SOUTH,
                        Direction.UP,
                        ASSEMBLER_MODULE),
                new ModuleRenderInfo(
                        new BlockPos(3, 0, 11),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.SOUTH,
                        Direction.UP,
                        RESOURCE_COLLECTION),
                new ModuleRenderInfo(
                        new BlockPos(8, 0, 6),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.EAST,
                        Direction.UP,
                        ASSEMBLER_MODULE),
                new ModuleRenderInfo(
                        new BlockPos(8, 0, 0),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.EAST,
                        Direction.UP,
                        RESOURCE_COLLECTION),
                new ModuleRenderInfo(
                        new BlockPos(3, 0, -5),
                        Direction.NORTH,
                        Direction.UP,
                        Direction.NORTH,
                        Direction.UP,
                        ASSEMBLER_MODULE));
    }

    private BlockPos getPowerCore(BlockPos pos, Level level) {
        if (level == null) {
            return null;
        }
        BlockPos[] coordinates = new BlockPos[] { pos.offset(3, -2, 0),
                pos.offset(-3, -2, 0),
                pos.offset(0, -2, 3),
                pos.offset(0, -2, -3) };
        for (BlockPos blockPos : coordinates) {
            if (level.getBlockState(blockPos).is(GTLBlocks.POWER_CORE.get())) {
                return blockPos;
            }
        }
        return null;
    }

    private void scanAndConnectSpaceElevatorModules(String trigger) {
        Level level = getLevel();
        if (level == null) {
            return;
        }
        BlockPos powerCore = getPowerCore(getPos(), level);
        BlockPos[] positions = getModuleScanPositions();

        for (BlockPos pos : positions) {
            MetaMachine machine = MetaMachine.getMachine(level, pos);
            boolean formed = machine instanceof IModularMachineModule<?, ?> module && module.isFormed();
            boolean valid = isValidModule(machine);

            if (valid) {
                @SuppressWarnings("unchecked")
                IModularMachineModule<SpaceElevatorMachine, ?> module = (IModularMachineModule<SpaceElevatorMachine, ?>) machine;
                module.connectToHost(this);

            }
        }
    }

    private static String candidateRejection(MetaMachine machine, boolean formed) {
        if (machine == null) {
            return "missing_machine";
        }
        if (!(machine instanceof IModularMachineModule<?, ?>)) {
            return "wrong_machine_type";
        }
        return formed ? "incompatible_host_type" : "module_not_formed";
    }

    private static String machineType(MetaMachine machine) {
        return machine == null ? "none" : machine.getClass().getName();
    }

    private int getMAM() {
        if (getOffsetTimer() % 20 == 0) {
            mam = getFormedModuleCount();
        }
        return mam;
    }

    @Override
    public boolean keepSubscribing() {
        return true;
    }

    @Override
    public boolean onWorking() {
        boolean value = super.onWorking();
        if (getOffsetTimer() % 20 == 0) {
            if (getRecipeLogic().getProgress() > 240) {
                RecipeResult.of(this, RecipeResult.SUCCESS);
                getRecipeLogic().setProgress(120);
            }
        }
        return value;
    }

    @Override
    public @NotNull Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 190, 125);
        group.addWidget((new DraggableScrollableWidgetGroup(4, 4, 182, 117))
                .setBackground(this.getScreenTexture())
                .addWidget(new LabelWidget(4, 5, this.self().getBlockState().getBlock().getDescriptionId()))
                .addWidget((new ComponentPanelWidget(4, 17, this::addDisplayText) {

                    @Override
                    @OnlyIn(Dist.CLIENT)
                    public boolean mouseClicked(double mouseX, double mouseY, int button) {
                        var style = getStyleUnderMouse(mouseX, mouseY);
                        if (style != null) {
                            if (style.getClickEvent() != null) {
                                ClickEvent clickEvent = style.getClickEvent();
                                String componentText = clickEvent.getValue();
                                if (clickEvent.getAction() == ClickEvent.Action.OPEN_URL) {
                                    if (componentText.startsWith("@!")) {
                                        String rawText = componentText.substring(2);
                                        ClickData clickData = new ClickData();
                                        if (clickHandler != null) {
                                            clickHandler.accept(rawText, clickData);
                                        }
                                        writeClientAction(1, buf -> {
                                            clickData.writeToBuf(buf);
                                            buf.writeUtf(rawText);
                                            if (Minecraft.getInstance().player != null) buf.writeUUID(Minecraft.getInstance().player.getUUID());
                                        });
                                    } else if (componentText.startsWith("@#")) {
                                        String rawText = componentText.substring(2);
                                        Util.getPlatform().openUri(rawText);
                                    }
                                    playButtonClickSound();
                                    return true;
                                }
                            }
                        }
                        return super.mouseClicked(mouseX, mouseY, button);
                    }

                    @Override
                    public void handleClientAction(int id, FriendlyByteBuf buffer) {
                        if (id == 1) {
                            ClickData clickData = ClickData.readFromBuf(buffer);
                            String componentData = buffer.readUtf();
                            ((IExtendedClickData) clickData).setUUID(buffer.readUUID());
                            if (clickHandler != null) {
                                clickHandler.accept(componentData, clickData);
                            }
                        } else {
                            super.handleClientAction(id, buffer);
                        }
                    }
                })
                        .textSupplier(Objects.requireNonNull(this.getLevel()).isClientSide ? null : this::addDisplayText).setMaxWidthLimit(150)
                        .clickHandler(this::handleDisplayClick)));
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        super.attachConfigurators(panel);
        IGTLRenderDisabled.attachConfigurator(panel, (IGTLRenderDisabled) this);
    }

    @Override
    public void addDisplayText(@NotNull List<Component> textList) {
        super.addDisplayText(textList);
        if (!this.isFormed) return;
        textList.add(Component.translatable("gtceu.machine.module", getMAM()));
        textList.add(ComponentPanelWidget.withButton(Component.translatable("gtceu.machine.space_elevator.set_out"),
                "set_out"));
    }

    @Override
    public void handleDisplayClick(String componentData, ClickData clickData) {
        if (!(getLevel() instanceof ServerLevel serverLevel)) return;
        if (componentData.equals("set_out") && getRecipeLogic().isWorking()) {
            UUID playerUUID = ((IExtendedClickData) clickData).getUUID();
            if (playerUUID != null) {
                ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerUUID);
                if (player != null) {
                    player.addTag("spaceelevatorst");
                    MenuHooks.openMenu(player, new PlanetsMenuProvider());
                }
            }
        }
    }
}
