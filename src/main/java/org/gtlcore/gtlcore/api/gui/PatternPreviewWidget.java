package org.gtlcore.gtlcore.api.gui;

import org.gtlcore.gtlcore.api.gui.preview.PreviewBuildTiming;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.pattern.PreviewMatcherTiming;
import org.gtlcore.gtlcore.client.preview.PreviewCandidatePanel;
import org.gtlcore.gtlcore.client.preview.PreviewControls;
import org.gtlcore.gtlcore.client.preview.PreviewHeight;
import org.gtlcore.gtlcore.client.preview.PreviewPicking;
import org.gtlcore.gtlcore.client.preview.PreviewRendererAccess;
import org.gtlcore.gtlcore.client.preview.PreviewScenes;
import org.gtlcore.gtlcore.client.preview.PreviewSettings;
import org.gtlcore.gtlcore.client.preview.SparsePreviewShape;
import org.gtlcore.gtlcore.integration.ae2.pattern.MultiblockPatternEncoding;
import org.gtlcore.gtlcore.utils.datastructure.ModuleRenderInfo;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.config.ConfigHolder;

import com.lowdragmc.lowdraglib.client.scene.WorldSceneRenderer;
import com.lowdragmc.lowdraglib.client.utils.RenderUtils;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.texture.*;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.*;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 部分代码参考自GTM
 * &#064;line <a href="https://github.com/GregTechCEu/GregTech-Modern">...</a>
 */
@SuppressWarnings("deprecation")
@OnlyIn(Dist.CLIENT)
public class PatternPreviewWidget extends WidgetGroup {

    private static TrackedDummyWorld LEVEL;
    private static final int REGION_SIZE = 512;
    private static final float HIGHLIGHT_SCALE = 1.03f;
    private static final float HIGHLIGHT_INTENSITY = 0.6f;
    // Frame the controller at a quarter of the shorter viewport dimension.
    private static final float CONTROLLER_VIEW_FRACTION = 0.25f;
    private static int LAST_OFFSET_INDEX = 0;
    private static final Map<MultiblockMachineDefinition, MBPattern[]> CACHE = new Object2ObjectOpenHashMap<>();
    private final SceneWidget sceneWidget;
    private DraggableScrollableWidgetGroup scrollableWidgetGroup;
    private final MultiblockMachineDefinition controllerDefinition;
    private final MBPattern[] patterns;
    private int index;
    private int layer;
    private final List<SlotWidget> slotWidgets;
    private final List<SlotWidget> candidates;
    private boolean showModules = false;
    private PreviewControls previewControls;
    private final Map<BlockState, BlockInfo> plainBlocks = new IdentityHashMap<>();
    private final Map<BlockPos, AEItemKey> materialReplacements = new HashMap<>();
    private BlockPos selectedMaterialPos;
    private List<BlockPos> replaceablePositions = List.of();
    private boolean replaceablePositionsDirty = true;

    protected PatternPreviewWidget(MultiblockMachineDefinition controllerDefinition) {
        super(0, 0, 160, 160);
        try {
            setClientSideWidget();
            this.controllerDefinition = controllerDefinition;
            layer = -1;
            slotWidgets = new ObjectArrayList<>();
            candidates = new ObjectArrayList<>();

            addWidget(sceneWidget = new SceneWidget(3, 3, 150, 150, LEVEL) {

                @Override
                public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                    // GUI and scene rendering share buffers but use different projection matrices.
                    graphics.flush();
                    super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                }

                @Override
                public void renderBlockOverLay(WorldSceneRenderer renderer) {
                    PoseStack poseStack = new PoseStack();
                    hoverPosFace = null;
                    hoverItem = null;
                    if (isMouseOverElement(currentMouseX, currentMouseY)) {
                        BlockHitResult hit = renderer.getLastTraceResult();
                        if (hit != null) {
                            if (core.contains(hit.getBlockPos())) {
                                hoverPosFace = new BlockPosFace(hit.getBlockPos(), hit.getDirection());
                            } else if (!useOrtho) {
                                Vector3f hitPos = hit.getLocation().toVector3f();
                                Level world = renderer.world;
                                Vec3 eyePos = new Vec3(renderer.getEyePos());
                                hitPos.mul(2); // Double view range to ensure pos can be seen.
                                Vec3 endPos = new Vec3((hitPos.x - eyePos.x), (hitPos.y - eyePos.y), (hitPos.z - eyePos.z));
                                double min = Float.MAX_VALUE;
                                for (var positions = PreviewPicking.candidates(renderer, core); positions.hasNext();) {
                                    BlockPos pos = positions.next();
                                    BlockState blockState = world.getBlockState(pos);
                                    if (blockState.getBlock() == Blocks.AIR) {
                                        continue;
                                    }
                                    hit = world.clipWithInteractionOverride(eyePos, endPos, pos,
                                            blockState.getShape(world, pos), blockState);
                                    if (hit != null && hit.getType() != HitResult.Type.MISS) {
                                        double dist = eyePos.distanceToSqr(hit.getLocation());
                                        if (dist < min) {
                                            min = dist;
                                            hoverPosFace = new BlockPosFace(hit.getBlockPos(), hit.getDirection());
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (hoverPosFace != null) {
                        var state = getDummyWorld().getBlockState(hoverPosFace.pos);
                        hoverItem = state.getBlock().getCloneItemStack(getDummyWorld(), hoverPosFace.pos, state);
                    }
                    BlockPosFace tmp = dragging ? clickPosFace : hoverPosFace;
                    if (selectedPosFace != null || tmp != null) {
                        if (selectedPosFace != null && renderFacing) {
                            drawFacingBorder(poseStack, selectedPosFace, 0xff00ff00);
                        }
                        if (tmp != null && !tmp.equals(selectedPosFace) && renderFacing) {
                            drawFacingBorder(poseStack, tmp, 0xffffffff);
                        }
                    }
                    var materialPos = selectedMaterialPos != null ? selectedMaterialPos :
                            selectedPosFace == null ? null : selectedPosFace.pos;
                    if (previewControls != null && previewControls.highlightingReplaceable()) {
                        renderReplaceableHighlights(poseStack, renderSelect ? materialPos : null);
                    }
                    if (materialPos != null && renderSelect) {
                        RenderUtils.renderBlockOverLay(poseStack, materialPos, HIGHLIGHT_INTENSITY, 0, 0, HIGHLIGHT_SCALE);
                    }

                    if (this.afterWorldRender != null) {
                        this.afterWorldRender.accept(this);
                    }
                }
            }
                    .setOnSelected(this::onPosSelected)
                    .setRenderFacing(false)
                    .setRenderFacing(false));

            refreshScrollableWidget();

            if (ConfigHolder.INSTANCE.client.useVBO) {
                if (!RenderSystem.isOnRenderThread()) {
                    RenderSystem.recordRenderCall(sceneWidget::useCacheBuffer);
                } else {
                    sceneWidget.useCacheBuffer();
                }
            }

            addWidget(new ImageWidget(3, 3, 160, 10,
                    new TextTexture(controllerDefinition.getDescriptionId(), -1)
                            .setType(TextTexture.TextType.ROLL)
                            .setWidth(170)
                            .setDropShadow(true)));

            this.patterns = CACHE.computeIfAbsent(controllerDefinition,
                    definition -> definition.getMatchingShapes().stream()
                            .map(this::initializePattern)
                            .filter(Objects::nonNull)
                            .toArray(MBPattern[]::new));

            addWidget(new ButtonWidget(138, 30, 18, 18, controlTexture(() -> "P:" + index),
                    (x) -> setPage((index + 1 >= patterns.length) ? 0 : index + 1, x))
                    .setHoverBorderTexture(1, -1));

            addWidget(new ButtonWidget(138, 50, 18, 18, controlTexture(() -> layer >= 0 ? "L:" + layer : "ALL"),
                    this::updateLayer)
                    .setHoverBorderTexture(1, -1));

            if (this.patterns.length != 0 && this.patterns[0].hasModule) {
                addWidget(new ButtonWidget(138, 70, 18, 18, controlTexture(() -> showModules ? "M:ON" : "M:OFF"),
                        (x) -> toggleModules())
                        .setHoverBorderTexture(1, -1)
                        .setHoverTooltips(Component.translatable("gui.gtlcore.module.show")));
            }
            setPage(0, null);
            previewControls = new PreviewControls(this, sceneWidget, controllerDefinition, this::controllerHeight, this::dimensions,
                    () -> patterns[index].controllerBase.self().getPos(), this::focusController, this::patternInputs,
                    this::selectPatternMaterial, this::replacePatternMaterial);
        } catch (Exception e) {
            throw new IllegalStateException("The jei preview creation for the Multi Block Machine [" + controllerDefinition.getId().toString() + "] failed! ", e);
        }
    }

    public PreviewControls getPreviewControls() {
        return previewControls;
    }

    private void renderReplaceableHighlights(PoseStack poseStack, @Nullable BlockPos selectedPos) {
        if (index < 0 || index >= patterns.length) return;
        if (replaceablePositionsDirty) {
            var pattern = patterns[index];
            var candidatesByPredicate = new IdentityHashMap<TraceabilityPredicate, Set<AEItemKey>>();
            replaceablePositions = sceneWidget.getCore().stream().filter(pos -> {
                var predicate = pattern.predicateMap.get(pos);
                if (predicate == null) return false;
                var material = patternMaterial(pos);
                // Module-only positions are absent from the host material bill.
                if (material == null) {
                    var state = LEVEL.getBlockState(pos);
                    material = AEItemKey.of(state.getBlock().getCloneItemStack(LEVEL, pos, state));
                }
                if (material == null) return false;
                var alternatives = candidatesByPredicate.computeIfAbsent(predicate, key -> Stream.concat(key.common.stream(), key.limited.stream())
                        .filter(simple -> simple != null && simple.candidates != null)
                        .flatMap(simple -> simple.getCandidates().stream())
                        .filter(stack -> !stack.isEmpty())
                        .map(AEItemKey::of)
                        .collect(Collectors.toSet()));
                return !alternatives.isEmpty() && (alternatives.size() > 1 || !alternatives.contains(material));
            }).toList();
            replaceablePositionsDirty = false;
        }
        if (replaceablePositions.isEmpty()) return;

        // Submit all green overlays together instead of issuing one draw call per block.
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var tessellator = Tesselator.getInstance();
        BufferBuilder buffer = tessellator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (var pos : replaceablePositions) {
            if (pos.equals(selectedPos)) continue;
            poseStack.pushPose();
            poseStack.translate(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            poseStack.scale(HIGHLIGHT_SCALE, HIGHLIGHT_SCALE, HIGHLIGHT_SCALE);
            RenderUtils.renderCubeFace(poseStack, buffer, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
                    0, HIGHLIGHT_INTENSITY, 0, 1);
            poseStack.popPose();
        }
        tessellator.end();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    private IGuiTexture controlTexture(Supplier<String> label) {
        var small = new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture(), new TextTexture(label));
        var fullscreen = new GuiTextureGroup(GuiTextures.BUTTON,
                new TextTexture(label).setColor(ChatFormatting.DARK_GRAY.getColor()).setDropShadow(false));
        return (graphics, mouseX, mouseY, x, y, width, height) -> (previewControls != null && previewControls.fullscreen() ? fullscreen : small)
                .draw(graphics, mouseX, mouseY, x, y, width, height);
    }

    private int controllerHeight() {
        if (index < 0 || index >= patterns.length) return -1;
        var pattern = patterns[index];
        return pattern.controllerBase == null ? -1 :
                pattern.bounds.aboveBottom(pattern.controllerBase.self().getPos().getY(), showModules);
    }

    private PreviewHeight.Dimensions dimensions() {
        return index < 0 || index >= patterns.length ? PreviewHeight.Dimensions.UNKNOWN :
                patterns[index].bounds.dimensions(showModules);
    }

    public List<List<GenericStack>> patternInputs() {
        return patternIngredients(true);
    }

    private List<List<GenericStack>> patternIngredients() {
        return patternIngredients(false);
    }

    private List<List<GenericStack>> patternIngredients(boolean filterDefaults) {
        if (index < 0 || index >= patterns.length) return List.of();
        var pattern = patterns[index];
        var inputs = pattern.parts.stream()
                .map(options -> options.stream().map(GenericStack::fromItemStack).toList())
                .toList();
        if (materialReplacements.isEmpty()) return filterDefaults ? MultiblockPatternEncoding.inputs(inputs) : inputs;
        var amounts = new LinkedHashMap<AEItemKey, Long>();
        for (var options : inputs) {
            var stack = options.get(0);
            amounts.merge((AEItemKey) stack.what(), stack.amount(), Long::sum);
        }
        for (var pos : materialReplacements.keySet()) amounts.merge(pattern.materials.get(pos), -1L, Long::sum);
        if (filterDefaults) {
            var remaining = amounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .map(entry -> List.of(new GenericStack(entry.getKey(), entry.getValue())))
                    .toList();
            amounts.clear();
            for (var options : MultiblockPatternEncoding.inputs(remaining)) {
                var stack = options.get(0);
                amounts.put((AEItemKey) stack.what(), stack.amount());
            }
        }
        // The hatch filter applies to the default bill; explicit choices are always inputs.
        for (var replacement : materialReplacements.values()) amounts.merge(replacement, 1L, Long::sum);
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> List.of(new GenericStack(entry.getKey(), entry.getValue())))
                .toList();
    }

    private AEItemKey patternMaterial(BlockPos pos) {
        return materialReplacements.getOrDefault(pos, patterns[index].materials.get(pos));
    }

    private void selectPatternMaterial(GenericStack stack) {
        if (selectedMaterialPos == null || !stack.what().equals(patternMaterial(selectedMaterialPos))) {
            selectedMaterialPos = patterns[index].materials.keySet().stream()
                    .filter(pos -> stack.what().equals(patternMaterial(pos)))
                    .findFirst().orElse(null);
        }
        if (selectedMaterialPos != null) onPosSelected(selectedMaterialPos, Direction.UP);
        previewControls.showCandidates();
    }

    private void replacePatternMaterial(ItemStack replacement) {
        var key = AEItemKey.of(replacement);
        if (selectedMaterialPos == null || key == null) return;
        materialReplacements.put(selectedMaterialPos, key);
        replaceablePositionsDirty = true;
        refreshMaterialSlots();
        previewControls.refreshPatternInputs();
        previewControls.selectPatternMaterial(key);
    }

    private void focusController() {
        if (index < 0 || index >= patterns.length) return;
        var pattern = patterns[index];
        if (layer != -1) {
            onFormedSwitch(true);
            setupScene(pattern);
        }
        var controller = pattern.controllerBase.self();
        var front = controller.getFrontFacing();
        var renderer = sceneWidget.getRenderer();
        double halfFov = Math.toRadians(((PreviewRendererAccess) renderer).gtlcore$fov()) / 2;
        double aspect = (double) sceneWidget.getSizeWidth() / sceneWidget.getSizeHeight();
        // Measure from the front face, then add half a block to orbit around the block center.
        float distance = (float) (0.5 + 0.5 / (CONTROLLER_VIEW_FRACTION * Math.tan(halfFov) * Math.min(1, aspect)));
        sceneWidget.useOrtho(false);
        sceneWidget.setCenter(Vec3.atCenterOf(controller.getPos()).toVector3f());
        sceneWidget.setZoom(distance);
        // Match SceneWidget's elevation limit to keep its look and up vectors independent.
        float elevation = Mth.clamp(front.getStepY() * 90.0f, -89.9f, 89.9f);
        float azimuth = (float) Math.toDegrees(Math.atan2(front.getStepZ(), front.getStepX()));
        sceneWidget.setCameraYawAndPitch(elevation, azimuth);
    }

    public static void clearSharedPreviewState() {
        CACHE.clear();
        LEVEL = null;
        LAST_OFFSET_INDEX = 0;
    }

    private void updateLayer(ClickData cd) {
        if (index < 0 || index >= patterns.length) return;
        var pattern = patterns[index];
        if (layer + 1 >= -1 && layer + 1 <= pattern.maxY - pattern.minY && !cd.isShiftClick) {
            layer += 1;
            if (pattern.controllerBase.isFormed()) {
                onFormedSwitch(false);
            }
        } else {
            layer = -1;
            if (!pattern.controllerBase.isFormed()) {
                onFormedSwitch(true);
            }
        }
        setupScene(pattern);
    }

    private void setupScene(MBPattern pattern) {
        PreviewScenes.configure(sceneWidget, controllerDefinition, index, layer, showModules);
        var stream = pattern.blockMap.keySet().stream()
                .filter(pos -> (layer == -1 || layer + pattern.minY == pos.getY()) && (showModules || !pattern.moduleOnlyBlocks.contains(pos)));
        if (pattern.controllerBase.isFormed()) {
            LongSet modelDisabled = pattern.controllerBase.getMultiblockState().getMatchContext().getOrDefault(
                    "renderMask",
                    LongSets.EMPTY_SET);
            if (!modelDisabled.isEmpty()) {
                stream = stream.filter(pos -> !modelDisabled.contains(pos.asLong()));
            }
        }
        sceneWidget.setRenderedCore(stream.toList(), null);
        replaceablePositionsDirty = true;
        if (previewControls != null) previewControls.sceneChanged();
    }

    public static PatternPreviewWidget getPatternWidget(MultiblockMachineDefinition controllerDefinition) {
        if (LEVEL == null) {
            if (Minecraft.getInstance().level == null) {
                GTCEu.LOGGER.error("Try to init pattern previews before level load");
                throw new IllegalStateException();
            }
            LEVEL = new TrackedDummyWorld();
        }
        return new PatternPreviewWidget(controllerDefinition);
    }

    public void setPage(int index, ClickData x) {
        if (x != null) {
            if (x.isShiftClick) index = 0;
            else if (x.isCtrlClick) index = patterns.length - 1;
        }
        if (index >= patterns.length || index < 0) return;
        clearCandidates();
        if (this.index != index) materialReplacements.clear();
        selectedMaterialPos = null;
        if (previewControls != null) previewControls.selectPatternMaterial(null);
        this.index = index;
        this.layer = -1;
        MBPattern pattern = patterns[index];
        setupScene(pattern);

        refreshMaterialSlots();
    }

    private void refreshMaterialSlots() {
        refreshScrollableWidget();
        slotWidgets.clear();
        var materials = patternIngredients().stream()
                .map(options -> options.stream().map(stack -> ((AEItemKey) stack.what()).toStack(Math.toIntExact(stack.amount()))).toList())
                .toList();
        var itemHandler = new CycleItemStackHandler(materials);
        for (int i = 0; i < materials.size(); i++) {
            final var itemStack = materials.get(i).get(0);
            final var widget = new SlotWidget(itemHandler, i, 4 + i * 18, 0, false, false)
                    .setBackgroundTexture(ColorPattern.T_GRAY.rectTexture())
                    .setIngredientIO(IngredientIO.INPUT)
                    .setOnAddedTooltips((w, tips) -> tips.add(Component.translatable("gtceu.machine.quantum_chest.items_stored")
                            .withStyle(ChatFormatting.DARK_AQUA)
                            .append(Component.literal(String.valueOf(itemStack.getCount())))));
            slotWidgets.add(widget);
            scrollableWidgetGroup.addWidget(widget);
        }
        if (previewControls != null) previewControls.layout();
    }

    private void refreshScrollableWidget() {
        if (scrollableWidgetGroup != null) removeWidget(scrollableWidgetGroup);
        var result = new DraggableScrollableWidgetGroup(3, 132, 154, 22)
                .setXScrollBarHeight(4)
                .setXBarStyle(GuiTextures.SLIDER_BACKGROUND, GuiTextures.BUTTON)
                .setScrollable(true)
                .setDraggable(true);
        result.setScrollWheelDirection(DraggableScrollableWidgetGroup.ScrollWheelDirection.HORIZONTAL);
        result.setScrollYOffset(0);
        scrollableWidgetGroup = result;
        addWidget(scrollableWidgetGroup);
    }

    private void toggleModules() {
        showModules = !showModules;
        setPage(index, null);
    }

    private void onFormedSwitch(boolean isFormed) {
        var pattern = patterns[index];
        var controllerBase = pattern.controllerBase;
        if (isFormed) {
            this.layer = -1;
            loadControllerFormed(pattern.blockMap.keySet(), controllerBase);
        } else {
            sceneWidget.setRenderedCore(pattern.blockMap.keySet(), null);
            controllerBase.onStructureInvalid();
        }
    }

    private void onPosSelected(BlockPos pos, Direction facing) {
        if (index >= patterns.length || index < 0) return;

        clearCandidates();
        var pattern = patterns[index];
        selectedMaterialPos = pattern.materials.containsKey(pos) ? pos.immutable() : null;
        if (previewControls != null) {
            previewControls.selectPatternMaterial(selectedMaterialPos == null ? null : patternMaterial(pos));
        }
        var predicate = pattern.predicateMap.get(pos);

        if (predicate != null) {
            var predicates = new LinkedHashSet<SimplePredicate>();
            predicates.addAll(predicate.common);
            predicates.addAll(predicate.limited);
            predicates.remove(null);
            predicates.removeIf(p -> p.candidates == null);

            List<List<ItemStack>> candidateStacks = new ObjectArrayList<>();
            List<List<Component>> predicateTips = new ObjectArrayList<>();
            List<PreviewCandidatePanel.Group> groups = new ObjectArrayList<>();
            for (var simplePredicate : predicates) {
                var group = PreviewCandidatePanel.Group.of(simplePredicate, predicate);
                if (!group.stacks().isEmpty()) {
                    groups.add(group);
                    candidateStacks.add(group.stacks());
                    predicateTips.add(group.tooltips());
                }
            }
            if (previewControls != null) previewControls.setCandidates(groups);

            CycleItemStackHandler itemHandler = new CycleItemStackHandler(candidateStacks);
            int maxCol = (160 - (((slotWidgets.size() - 1) / 9 + 1) * 18) - 35) % 18;
            for (int i = 0; i < candidateStacks.size(); i++) {
                int finalI = i;
                final var widget = new SlotWidget(itemHandler, i, 3 + (i / maxCol) * 18, 3 + (i % maxCol) * 18, false,
                        false)
                        .setIngredientIO(IngredientIO.INPUT)
                        .setBackgroundTexture(new ColorRectTexture(0x4fffffff))
                        .setOnAddedTooltips((slot, list) -> list.addAll(predicateTips.get(finalI)));
                candidates.add(widget);
                addWidget(widget);
            }
        }
        if (previewControls != null) previewControls.layout();
    }

    private void clearCandidates() {
        for (var candidate : candidates) removeWidget(candidate);
        candidates.clear();
        if (previewControls != null) previewControls.setCandidates(List.of());
    }

    public static BlockPos locateNextRegion() {
        int currentIndex = LAST_OFFSET_INDEX++;

        // Origin coordinates scaled back to the offset value, from global
        int x = 0, z = 0;
        if (currentIndex > 0) {
            int v = (int) (Mth.sqrt(currentIndex + 0.25f) - 0.5f);
            int nextV = v + 1;
            int spiralBaseIndex = v * nextV;
            // this is 1 or -1 depending on if v is odd or even
            int flipFlop = (v & 1) * 2 - 1;

            int offset = flipFlop * nextV / 2;
            x += offset;
            z += offset;

            int cornerIndex = spiralBaseIndex + nextV;
            if (currentIndex < cornerIndex) {
                x -= flipFlop * (currentIndex - spiralBaseIndex + 1);
            } else {
                x -= flipFlop * nextV;
                z -= flipFlop * (currentIndex - cornerIndex + 1);
            }
        }
        return new BlockPos(x * REGION_SIZE, 50, z * REGION_SIZE);
    }

    @Override
    public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableBlend();
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        if (previewControls != null) previewControls.drawInfo(graphics);
    }

    private BlockInfo reusableBlockInfo(BlockState state) {
        if (!PreviewSettings.enabled() || state.getBlock() instanceof EntityBlock) {
            return BlockInfo.fromBlockState(state);
        }
        return plainBlocks.computeIfAbsent(state, BlockInfo::fromBlockState);
    }

    private static BlockEntity previewController(BlockInfo info, BlockPos pos) {
        if (!PreviewSettings.enabled()) return info.getBlockEntity(pos);
        // Discovery retains controllers only; the dummy world creates parts when needed.
        if (info.getBlockState().getBlock() instanceof IMachineBlock machine &&
                !(machine.getDefinition() instanceof MultiblockMachineDefinition))
            return null;
        return info.hasBlockEntity() ? SparsePreviewShape.copy(info).getBlockEntity(pos) : info.getBlockEntity(pos);
    }

    private MBPattern initializePattern(MultiblockShapeInfo shapeInfo) {
        if (shapeInfo == null) return null;

        Map<BlockPos, BlockInfo> blockMap = new Object2ObjectOpenHashMap<>();
        Set<BlockPos> moduleOnlyBlocks = new ObjectOpenHashSet<>();
        IMultiController controllerBase = null;
        BlockPos controllerPosInShape = null;
        var multiPos = locateNextRegion();

        var blocks = shapeInfo.getBlocks();
        for (int x = 0; x < blocks.length; x++) {
            var aisle = blocks[x];
            for (int y = 0; y < aisle.length; y++) {
                var column = aisle[y];
                for (int z = 0; z < column.length; z++) {
                    var block = column[z];
                    if (block == null) continue;

                    var blockState = block.getBlockState();
                    var pos = multiPos.offset(x, y, z);
                    if (previewController(block, pos) instanceof IMachineBlockEntity holder &&
                            holder.getMetaMachine() instanceof IMultiController controller &&
                            this.controllerDefinition == controller.self().getDefinition()) {
                        holder.getSelf().setLevel(LEVEL);
                        controllerBase = controller;
                        controllerPosInShape = new BlockPos(x, y, z);
                    }
                    blockMap.put(pos, reusableBlockInfo(blockState));
                }
            }
        }

        LEVEL.addBlocks(blockMap);

        Map<BlockPos, BlockInfo> hostBlockMap = new Object2ObjectOpenHashMap<>(blockMap);
        BlockPos controllerWorldPos = multiPos;
        Map<BlockPos, TraceabilityPredicate> predicateMap = new Object2ObjectOpenHashMap<>();
        Map<ItemStackKey, PartInfo> partsMap = new Object2ObjectOpenHashMap<>();
        Map<BlockPos, AEItemKey> materials = new Object2ObjectOpenHashMap<>();

        if (controllerBase != null) {
            LEVEL.setInnerBlockEntity(controllerBase.self().holder.getSelf());
            if (controllerPosInShape != null) {
                controllerWorldPos = multiPos.offset(controllerPosInShape);
            }

            // Form the host pattern FIRST
            loadControllerFormed(blockMap.keySet(), controllerBase);
            Map<BlockPos, TraceabilityPredicate> hostPredicates = controllerBase
                    .getMultiblockState()
                    .getMatchContext()
                    .get("predicates");
            if (hostPredicates != null) {
                predicateMap = new Object2ObjectOpenHashMap<>(hostPredicates);
            }

            partsMap = gatherBlockDrops(blockMap, materials);

            // Form each module pattern and merge their predicateMaps
            if (controllerBase instanceof IModularMachineHost<?> host) {
                List<IMultiController> moduleControllers = initializeModules(host, controllerWorldPos,
                        blockMap, hostBlockMap, moduleOnlyBlocks);

                Set<BlockPos> modulePos = new ObjectOpenHashSet<>();

                for (IMultiController moduleController : moduleControllers) {
                    try {
                        modulePos.add(moduleController.self().getPos());
                        mergeModulePredicates(moduleController, predicateMap);
                    } catch (Exception e) {
                        GTCEu.LOGGER.warn("Failed to merge module predicates in pattern preview", e);
                    }
                }

                hostBlockMap.forEach((pos, block) -> {
                    if (!modulePos.contains(pos)) LEVEL.addBlock(pos, block);
                });
            }
        }

        var parts = partsMap.values().stream()
                .sorted((one, two) -> {
                    if (one.isController) return -1;
                    if (two.isController) return +1;
                    if (one.isTile && !two.isTile) return -1;
                    if (two.isTile && !one.isTile) return +1;
                    if (one.blockId != two.blockId) return two.blockId - one.blockId;
                    return two.amount - one.amount;
                })
                .map(PartInfo::getItemStack)
                .filter(list -> !list.isEmpty())
                .collect(Collectors.toList());

        return controllerBase == null ? null : new MBPattern(blockMap, moduleOnlyBlocks, parts, predicateMap, controllerBase, materials);
    }

    private void loadControllerFormed(Collection<BlockPos> positions, IMultiController controllerBase) {
        try (var operation = PreviewBuildTiming.begin(false)) {
            BlockPattern pattern = controllerBase.getPattern();
            if (pattern != null) {
                boolean matched;
                var state = controllerBase.getMultiblockState();
                var timing = PreviewMatcherTiming.begin(PreviewBuildTiming.identity(controllerBase, false), state);
                try (timing) {
                    matched = pattern.checkPatternAt(state, true);
                    timing.result(matched);
                } finally {
                    PreviewBuildTiming.recordMatcher(timing.elapsedNanos());
                }
                if (matched) PreviewBuildTiming.formController(controllerBase);
            }

            if (controllerBase.isFormed()) {
                LongSet modelDisabled = controllerBase.getMultiblockState().getMatchContext().getOrDefault("renderMask",
                        LongSets.EMPTY_SET);
                if (!modelDisabled.isEmpty()) {
                    positions = new HashSet<>(positions);
                    positions.removeIf(pos -> modelDisabled.contains(pos.asLong()));
                }
                sceneWidget.setRenderedCore(positions, null);
            }
        }
    }

    private static void loadModuleFormed(IMultiController moduleController) {
        try (var operation = PreviewBuildTiming.begin(true)) {
            BlockPattern modulePattern = moduleController.getPattern();
            BlockState controllerState = LEVEL.getBlockState(moduleController.self().getPos());
            Direction controllerFacing = Direction.NORTH;
            Direction controllerUp = Direction.UP;
            if (controllerState.getBlock() instanceof MetaMachineBlock machineBlock) {
                controllerFacing = machineBlock.getFrontFacing(controllerState);
                if (controllerState.hasProperty(IMachineBlock.UPWARDS_FACING_PROPERTY)) {
                    controllerUp = controllerState.getValue(IMachineBlock.UPWARDS_FACING_PROPERTY);
                }
            }

            if (modulePattern != null) {
                boolean matched;
                var state = moduleController.getMultiblockState();
                var timing = PreviewMatcherTiming.begin(PreviewBuildTiming.identity(moduleController, true), state);
                try (timing) {
                    matched = modulePattern.checkPatternAt(state,
                            moduleController.self().getPos(), controllerFacing, controllerUp, false, true);
                    timing.result(matched);
                } finally {
                    PreviewBuildTiming.recordMatcher(timing.elapsedNanos());
                }
                if (matched) PreviewBuildTiming.formController(moduleController);
            }
        }
    }

    private static List<IMultiController> initializeModules(IModularMachineHost<?> host,
                                                            BlockPos hostControllerPos,
                                                            Map<BlockPos, BlockInfo> blockMap,
                                                            Map<BlockPos, BlockInfo> hostBlockMap,
                                                            Set<BlockPos> moduleBlocks) {
        List<ModuleRenderInfo> modules = host.getModulesForRendering();
        if (modules.isEmpty()) return List.of();

        List<IMultiController> moduleControllers = new ObjectArrayList<>();

        // Get host's current facing from world
        Direction hostFront = Direction.NORTH;
        Direction hostUp = Direction.UP;

        if (LEVEL.getBlockEntity(hostControllerPos) instanceof IMachineBlockEntity IMBE && IMBE.getMetaMachine() instanceof MultiblockControllerMachine controllerMachine) {
            hostFront = controllerMachine.getFrontFacing();
            hostUp = controllerMachine.getUpwardsFacing();
        }

        for (ModuleRenderInfo moduleInfo : modules) {
            try {
                MultiblockMachineDefinition moduleDef = moduleInfo.moduleDefinition();
                List<MultiblockShapeInfo> moduleShapes = moduleDef.getMatchingShapes();
                if (moduleShapes.isEmpty()) continue;

                MultiblockShapeInfo moduleShapeInfo = moduleShapes.get(0);

                BlockPos moduleOffset = moduleInfo.calculateModuleOffset(hostFront, hostUp);
                BlockPos moduleWorldPos = hostControllerPos.offset(moduleOffset);

                Direction moduleFront = moduleInfo.calculateModuleFront(hostFront, hostUp);
                Direction moduleUp = moduleInfo.calculateModuleUp(hostFront, hostUp);

                var result = initializeModule(moduleDef, moduleShapeInfo, moduleWorldPos,
                        moduleFront, moduleUp, blockMap, hostBlockMap, moduleBlocks);

                if (result != null) {
                    moduleControllers.add(result);
                }

            } catch (Exception e) {
                GTCEu.LOGGER.warn("Failed to render module in pattern preview", e);
            }
        }

        return moduleControllers;
    }

    private static @Nullable IMultiController initializeModule(MultiblockMachineDefinition moduleDefinition,
                                                               MultiblockShapeInfo moduleShapeInfo,
                                                               BlockPos moduleControllerWorldPos,
                                                               Direction moduleFront,
                                                               Direction moduleUp,
                                                               Map<BlockPos, BlockInfo> blockMap,
                                                               Map<BlockPos, BlockInfo> hostBlockMap,
                                                               Set<BlockPos> moduleOnlyBlocks) {
        var blocks = moduleShapeInfo.getBlocks();
        BlockPos moduleControllerPosInShape = null;
        List<Pair<BlockPos, BlockInfo>> pairs = new ObjectArrayList<>();

        for (int x = 0; x < blocks.length; x++) {
            BlockInfo[][] aisle = blocks[x];
            for (int y = 0; y < aisle.length; y++) {
                BlockInfo[] column = aisle[y];
                for (int z = 0; z < column.length; z++) {
                    var info = column[z];
                    if (info == null) continue;

                    BlockPos localPos = new BlockPos(x, y, z);
                    pairs.add(Pair.of(localPos, info));

                    if (info.getBlockState().getBlock() instanceof MetaMachineBlock machineBlock &&
                            machineBlock.definition == moduleDefinition) {
                        moduleControllerPosInShape = new BlockPos(x, y, z);
                    }
                }
            }
        }

        if (moduleControllerPosInShape == null) return null;

        IMultiController moduleController = null;
        Map<BlockPos, BlockInfo> moduleBlockMap = new Object2ObjectOpenHashMap<>();

        for (Pair<BlockPos, BlockInfo> pair : pairs) {
            BlockPos localPos = pair.left();
            BlockInfo info = pair.right();
            BlockState blockState = info.getBlockState();

            BlockPos offsetLocal = localPos.subtract(moduleControllerPosInShape);
            BlockPos offsetRotated = ModuleRenderInfo.applyGTTransform(offsetLocal, moduleFront, moduleUp);
            BlockPos worldPos = moduleControllerWorldPos.offset(offsetRotated);

            if (blockState.getBlock() instanceof MetaMachineBlock machineBlock) {
                var rotationState = machineBlock.getRotationState();
                if (rotationState != RotationState.NONE) {
                    var originalFace = blockState.getValue(rotationState.property);
                    Direction newFace;

                    if (localPos.equals(moduleControllerPosInShape)) {
                        newFace = moduleFront;
                    } else {
                        newFace = ModuleRenderInfo.calculateBlockFacing(originalFace, moduleFront);
                    }

                    if (rotationState.test(newFace)) {
                        blockState = blockState.setValue(rotationState.property, newFace);
                    }
                }
            }

            if (previewController(info, worldPos) instanceof IMachineBlockEntity holder) {
                holder.getSelf().setLevel(LEVEL);
                if (localPos.equals(moduleControllerPosInShape) &&
                        holder.getMetaMachine() instanceof IMultiController controller)
                    moduleController = controller;
            }

            final var blockInfo = BlockInfo.fromBlockState(blockState);
            moduleBlockMap.put(worldPos, blockInfo);

            if (!hostBlockMap.containsKey(worldPos)) {
                moduleOnlyBlocks.add(worldPos);
                blockMap.put(worldPos, blockInfo);
            }
        }

        LEVEL.addBlocks(moduleBlockMap);

        if (moduleController != null) {
            LEVEL.setInnerBlockEntity(moduleController.self().holder.getSelf());
            loadModuleFormed(moduleController);
        }

        return moduleController;
    }

    private void mergeModulePredicates(IMultiController moduleController,
                                       Map<BlockPos, TraceabilityPredicate> hostPredicateMap) {
        Map<BlockPos, TraceabilityPredicate> modulePredicateMap = moduleController.getMultiblockState().getMatchContext().get("predicates");
        if (modulePredicateMap == null || modulePredicateMap.isEmpty()) return;

        for (Map.Entry<BlockPos, TraceabilityPredicate> entry : modulePredicateMap.entrySet()) {
            hostPredicateMap.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private Map<ItemStackKey, PartInfo> gatherBlockDrops(Map<BlockPos, BlockInfo> blocks, Map<BlockPos, AEItemKey> materials) {
        Map<ItemStackKey, PartInfo> partsMap = new Object2ObjectOpenHashMap<>();
        for (Map.Entry<BlockPos, BlockInfo> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState blockState = LEVEL.getBlockState(pos);
            ItemStack itemStack = blockState.getBlock().getCloneItemStack(LEVEL, pos, blockState);

            if (itemStack.isEmpty() && !blockState.getFluidState().isEmpty()) {
                Fluid fluid = blockState.getFluidState().getType();
                itemStack = fluid.getBucket().getDefaultInstance();
            }

            if (!itemStack.isEmpty()) materials.put(pos, AEItemKey.of(itemStack));
            ItemStackKey itemStackKey = new ItemStackKey(itemStack);
            partsMap.computeIfAbsent(itemStackKey, key -> new PartInfo(key, entry.getValue())).amount++;
        }
        return partsMap;
    }

    private static class PartInfo {

        final ItemStackKey itemStackKey;
        boolean isController = false;
        boolean isTile;
        final int blockId;
        int amount = 0;

        PartInfo(final ItemStackKey itemStackKey, final BlockInfo blockInfo) {
            this.itemStackKey = itemStackKey;
            this.blockId = Block.getId(blockInfo.getBlockState());
            this.isTile = blockInfo.hasBlockEntity();

            if (blockInfo.getBlockState().getBlock() instanceof MetaMachineBlock block) {
                if (block.definition instanceof MultiblockMachineDefinition)
                    this.isController = true;
            }
        }

        public List<ItemStack> getItemStack() {
            return Arrays.stream(itemStackKey.getItemStack())
                    .map(itemStack -> {
                        var item = itemStack.copy();
                        item.setCount(amount);
                        return item;
                    }).filter(item -> !item.isEmpty()).toList();
        }
    }

    private static class MBPattern {

        @NotNull
        final List<List<ItemStack>> parts;
        final Map<BlockPos, AEItemKey> materials;
        @NotNull
        final Map<BlockPos, TraceabilityPredicate> predicateMap;
        @NotNull
        final Map<BlockPos, BlockInfo> blockMap;
        @NotNull
        final Set<BlockPos> moduleOnlyBlocks;
        @NotNull
        final IMultiController controllerBase;
        final int maxY, minY;
        final boolean hasModule;
        final PreviewHeight bounds = new PreviewHeight();

        public MBPattern(@NotNull Map<BlockPos, BlockInfo> blockMap, @NotNull Set<BlockPos> moduleOnlyBlocks, @NotNull List<List<ItemStack>> parts,
                         @NotNull Map<BlockPos, TraceabilityPredicate> predicateMap,
                         @NotNull IMultiController controllerBase, Map<BlockPos, AEItemKey> materials) {
            this.parts = parts;
            this.materials = materials;
            this.blockMap = blockMap;
            this.predicateMap = predicateMap;
            this.controllerBase = controllerBase;
            this.moduleOnlyBlocks = moduleOnlyBlocks;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (BlockPos pos : blockMap.keySet()) {
                min = Math.min(min, pos.getY());
                max = Math.max(max, pos.getY());
            }
            minY = min;
            maxY = max;
            hasModule = controllerBase instanceof IModularMachineHost<?>;
            blockMap.forEach((pos, info) -> {
                if (info != null && !info.getBlockState().isAir()) {
                    bounds.include(pos.getX(), pos.getY(), pos.getZ(), moduleOnlyBlocks.contains(pos));
                }
            });
        }
    }
}
