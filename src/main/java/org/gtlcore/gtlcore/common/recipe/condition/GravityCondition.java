package org.gtlcore.gtlcore.common.recipe.condition;

import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.common.data.GTLRecipeConditions;
import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.IGravityPartMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.Level;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@NoArgsConstructor
public class GravityCondition extends RecipeCondition {

    public static final Codec<GravityCondition> CODEC = RecordCodecBuilder
            .create(instance -> RecipeCondition.isReverse(instance)
                    .and(Codec.BOOL.fieldOf("gravity").forGetter(val -> val.zero))
                    .apply(instance, GravityCondition::new));

    public final static GravityCondition INSTANCE = new GravityCondition();

    private boolean zero = false;

    public GravityCondition(boolean zero) {
        this.zero = zero;
    }

    public GravityCondition(boolean isReverse, boolean zero) {
        super(isReverse);
        this.zero = zero;
    }

    private int heightThreshold = 313; // 默认值表示无高度限制

    public GravityCondition(boolean zero, int heightThreshold) {
        this.zero = zero;
        this.heightThreshold = heightThreshold;
    }

    public GravityCondition(boolean isReverse, boolean zero, int heightThreshold) {
        super(isReverse);
        this.zero = zero;
        this.heightThreshold = heightThreshold;
    }

    @Override
    public RecipeConditionType<?> getType() {
        return GTLRecipeConditions.GRAVITY;
    }

    @Override
    public Component getTooltips() {
        return Component.translatable("gtlcore.condition." + (zero ? "zero_" : "") + "gravity");
    }

    @Override
    public boolean test(@NotNull GTRecipe recipe, @NotNull RecipeLogic recipeLogic) {
        MetaMachine machine = recipeLogic.getMachine();
        if (machine instanceof MultiblockControllerMachine controllerMachine) {
            for (IMultiPart part : controllerMachine.self().getParts()) {
                if (part instanceof IGravityPartMachine gravityPart) {
                    if (gravityPart.getCurrentGravity() == (zero ? 0 : 100)) return true;
                    else RecipeResult.of((IRecipeLogicMachine) machine,
                            RecipeResult.fail(Component.translatable("gtceu.recipe.fail.gravity" + (zero ? ".low" : ".power"))));
                }
            }
        }

        Level level = Objects.requireNonNull(machine.getLevel(), "Machine level cannot be null");
        BlockPos machinePos = machine.getPos(); // 使用getPos()方法获取机器位置

        String dimensionName = level.dimension().location().toString();

        // 检查是否为_orbit维度
        boolean isInOrbitDimension = dimensionName.contains("_orbit");
        // _orbit维度的原有逻辑保持不变
        if (isInOrbitDimension && zero) {
            return true;
        }

        // 检查是否开启空岛模式
        if (ConfigHolder.INSTANCE.content.enableSkyBlokeMode) {
            // 检查是否为主世界并且高度超过阈值
            boolean isInOverworld = dimensionName.equals("minecraft:overworld");
            boolean isInVoid = dimensionName.equals("kubejs:void");
            boolean isInFlat = dimensionName.equals("kubejs:flat");
            boolean exceedsHeightThreshold = machinePos.getY() >= heightThreshold;
            boolean exceedsHeightThresholdA = machinePos.getY() >= 505;
            // 主世界的高度条件检查
            return ((isInOverworld && exceedsHeightThreshold) || ((isInFlat || isInVoid) && exceedsHeightThresholdA)) && zero;
        }
        return false;
    }

    @Override
    public RecipeCondition createTemplate() {
        return new GravityCondition(false, 313); // 或者使用其他默认值
    }

    @NotNull
    @Override
    public JsonObject serialize() {
        JsonObject config = super.serialize();
        config.addProperty("gravity", zero);
        config.addProperty("height_threshold", heightThreshold);
        return config;
    }

    @Override
    public RecipeCondition deserialize(@NotNull JsonObject config) {
        super.deserialize(config);
        zero = GsonHelper.getAsBoolean(config, "gravity", false);
        heightThreshold = GsonHelper.getAsInt(config, "height_threshold", 313);
        return this;
    }

    @Override
    public RecipeCondition fromNetwork(FriendlyByteBuf buf) {
        super.fromNetwork(buf);
        zero = buf.readBoolean();
        heightThreshold = buf.readInt();
        return this;
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf) {
        super.toNetwork(buf);
        buf.writeBoolean(zero);
        buf.writeInt(heightThreshold);
    }
}
