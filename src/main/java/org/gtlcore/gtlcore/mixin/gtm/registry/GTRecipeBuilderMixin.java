package org.gtlcore.gtlcore.mixin.gtm.registry;

import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.nbt.CompoundTag;

import com.google.gson.JsonObject;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.gtlcore.gtlcore.common.data.GTLRecipeTypes.*;

@Mixin(GTRecipeBuilder.class)
@Accessors(chain = true, fluent = true)
public abstract class GTRecipeBuilderMixin {

    @Shadow(remap = false)
    public GTRecipeType recipeType;

    @Shadow(remap = false)
    public int duration;

    @Shadow(remap = false)
    public @NotNull CompoundTag data = new CompoundTag();

    @Shadow(remap = false)
    public GTRecipeBuilder duration(int duration) {
        return null;
    }

    @Unique
    private long gTLCore$eut = 0;

    @Inject(method = "EUt(J)Lcom/gregtechceu/gtceu/data/recipe/builder/GTRecipeBuilder;", at = @At("HEAD"), remap = false)
    private void eu(long eu, CallbackInfoReturnable<GTRecipeBuilder> cir) {
        gTLCore$eut = eu;
        this.data.putInt("euTier", GTUtil.getTierByVoltage(eu > 0 ? eu : -eu));
    }

    @Unique
    private int gTLCore$getDuration() {
        if (ConfigHolder.INSTANCE.content.durationMultiplier == 1 || gTLCore$eut < 0 ||
                recipeType == PRIMITIVE_VOID_ORE_RECIPES ||
                recipeType == GTRecipeTypes.LARGE_BOILER_RECIPES ||
                recipeType == GTRecipeTypes.STEAM_BOILER_RECIPES ||
                recipeType == SLAUGHTERHOUSE_RECIPES ||
                recipeType == DYSON_SPHERE_RECIPES ||
                recipeType == SPACE_ELEVATOR_RECIPES ||
                recipeType == ANNIHILATE_GENERATOR_RECIPES ||
                recipeType == CREATE_AGGREGATION_RECIPES ||
                recipeType == LARGE_NAQUADAH_REACTOR_RECIPES ||
                recipeType == HYPER_REACTOR_RECIPES ||
                recipeType == ADVANCED_HYPER_REACTOR_RECIPES ||
                recipeType == DOOR_OF_CREATE_RECIPES ||
                recipeType == BLOCK_CONVERSION_RECIPES ||
                recipeType == WEATHER_CONTROL_RECIPES) {
            return Math.abs(duration);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.abs(duration * ConfigHolder.INSTANCE.content.durationMultiplier)));
    }

    @Inject(method = "toJson", at = @At("TAIL"), remap = false)
    public void toJson(JsonObject json, CallbackInfo ci) {
        json.addProperty("duration", gTLCore$getDuration());
    }
}
