package org.gtlcore.gtlcore.mixin.gtm;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.jei.GTRecipeJeiTiming;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import com.lowdragmc.lowdraglib.Platform;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Mixin(GTRecipeTypeCategory.class)
public abstract class GTRecipeTypeCategoryMixin {

    @Shadow(remap = false)
    @Final
    public static Function<GTRecipeType, RecipeType<GTRecipeWrapper>> TYPES;

    @Inject(method = "registerRecipes", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtlcore$registerRecipes(IRecipeRegistration registration, CallbackInfo ci) {
        if (!gtlcore$isOptimizationEnabled()) {
            return;
        }
        ci.cancel();

        var connection = Objects.requireNonNull(Minecraft.getInstance().getConnection());
        var recipeManager = connection.getRecipeManager();
        int registeredTypes = 0;
        int registeredRecipes = 0;
        long startedNanos = System.nanoTime();
        GTRecipeJeiTiming.reset();
        long wrapperNanos = 0;
        long registrationNanos = 0;

        for (net.minecraft.world.item.crafting.RecipeType<?> vanillaRecipeType : BuiltInRegistries.RECIPE_TYPE) {
            if (!(vanillaRecipeType instanceof GTRecipeType recipeType) || !gtlcore$isVisible(recipeType)) {
                continue;
            }

            List<GTRecipe> recipes = recipeManager.getAllRecipesFor(recipeType);
            List<GTRecipe> representatives = recipeType.isScanner() ? recipeType.getRepresentativeRecipes() : List.of();
            if (recipes.isEmpty() && representatives.isEmpty()) {
                continue;
            }

            long typeStartedNanos = System.nanoTime();
            List<GTRecipeWrapper> wrappers = new ArrayList<>(recipes.size() + representatives.size());
            for (GTRecipe recipe : recipes) {
                wrappers.add(gtlcore$createTimedWrapper(recipe));
            }
            for (GTRecipe recipe : representatives) {
                wrappers.add(gtlcore$createTimedWrapper(recipe));
            }
            long registrationStartedNanos = System.nanoTime();
            registration.addRecipes(TYPES.apply(recipeType), wrappers);
            GTRecipeJeiTiming.record(recipeType, GTRecipeJeiTiming.Phase.REGISTRATION,
                    System.nanoTime() - registrationStartedNanos);

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - typeStartedNanos);
            GTRecipeJeiTiming.Timing timing = GTRecipeJeiTiming.get(recipeType);
            wrapperNanos += timing.wrapperNanos();
            registrationNanos += timing.registrationNanos();
            if (elapsedMillis >= gtlcore$slowTypeWarningMillis()) {
                GTLCore.LOGGER.warn(
                        "Slow GTCEu JEI recipe type registration: type={}, recipes={}, wrapper_ms={}, " +
                                "registration_ms={}, elapsed_ms={}",
                        recipeType.registryName, wrappers.size(), gtlcore$toMillis(timing.wrapperNanos()),
                        gtlcore$toMillis(timing.registrationNanos()),
                        elapsedMillis);
            }
            registeredTypes++;
            registeredRecipes += wrappers.size();
        }

        GTLCore.LOGGER.info(
                "Optimized GTCEu JEI recipe registration: types={}, recipes={}, wrapper_ms={}, " +
                        "registration_ms={}, elapsed_ms={}",
                registeredTypes, registeredRecipes, gtlcore$toMillis(wrapperNanos),
                gtlcore$toMillis(registrationNanos),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    @Inject(method = "registerRecipeCatalysts", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtlcore$registerRecipeCatalysts(IRecipeCatalystRegistration registration, CallbackInfo ci) {
        if (!gtlcore$isOptimizationEnabled()) {
            return;
        }
        ci.cancel();

        int registeredMachines = 0;
        int registeredLinks = 0;
        long startedNanos = System.nanoTime();
        for (MachineDefinition machine : GTRegistries.MACHINES) {
            GTRecipeType[] machineRecipeTypes = machine.getRecipeTypes();
            if (machineRecipeTypes == null || machineRecipeTypes.length == 0) {
                continue;
            }

            Set<GTRecipeType> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<RecipeType<?>> jeiRecipeTypes = new ArrayList<>(machineRecipeTypes.length);
            for (GTRecipeType recipeType : machineRecipeTypes) {
                if (recipeType != null && seen.add(recipeType) && gtlcore$isVisible(recipeType)) {
                    jeiRecipeTypes.add(TYPES.apply(recipeType));
                }
            }
            if (jeiRecipeTypes.isEmpty()) {
                continue;
            }

            RecipeType<?>[] recipeTypes = jeiRecipeTypes.toArray(RecipeType[]::new);
            registration.addRecipeCatalyst(machine.asStack(), recipeTypes);
            registeredMachines++;
            registeredLinks += recipeTypes.length;
        }

        GTLCore.LOGGER.info("Optimized GTCEu JEI catalyst registration: machines={}, links={}, elapsed_ms={}",
                registeredMachines, registeredLinks,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    @Unique
    private static boolean gtlcore$isOptimizationEnabled() {
        return ConfigHolder.INSTANCE == null || ConfigHolder.INSTANCE.performance.optimizeGtceuJeiRegistration;
    }

    @Unique
    private static boolean gtlcore$isVisible(GTRecipeType recipeType) {
        GTRecipeTypeUI recipeUI = recipeType.getRecipeUI();
        return recipeUI != null && (Platform.isDevEnv() || recipeUI.isXEIVisible());
    }

    @Unique
    private static int gtlcore$slowTypeWarningMillis() {
        return ConfigHolder.INSTANCE == null ? ConfigHolder.DEFAULT_GTCEU_JEI_SLOW_RECIPE_TYPE_WARNING_MILLIS :
                ConfigHolder.INSTANCE.diagnostics.debugLogging.gtceuJeiSlowRecipeTypeWarningMillis;
    }

    @Unique
    private static GTRecipeWrapper gtlcore$createTimedWrapper(GTRecipe recipe) {
        long startedNanos = System.nanoTime();
        GTRecipeWrapper wrapper = new GTRecipeWrapper(recipe);
        GTRecipeJeiTiming.record(recipe.recipeType, GTRecipeJeiTiming.Phase.WRAPPER,
                System.nanoTime() - startedNanos);
        return wrapper;
    }

    @Unique
    private static long gtlcore$toMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
