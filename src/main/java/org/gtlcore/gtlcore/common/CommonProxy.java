package org.gtlcore.gtlcore.common;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.*;
import org.gtlcore.gtlcore.common.data.machines.GeneratorMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.InfinityCellGuiHandler;
import org.gtlcore.gtlcore.integration.ae2.storage.CircuitInfinityCellHandler;
import org.gtlcore.gtlcore.integration.ae2.storage.FastInfinityCellHandler;
import org.gtlcore.gtlcore.integration.ae2.storage.InfinityCellHandler;
import org.gtlcore.gtlcore.integration.ae2.wireless.CuriosTerminalLocator;
import org.gtlcore.gtlcore.integration.ae2.wireless.GTLWirelessAeContent;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeNetworkRuntime;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;
import org.gtlcore.gtlcore.integration.ftbu.AreaShape;
import org.gtlcore.gtlcore.network.GTLNetworkHandler;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent;
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialRegistryEvent;
import com.gregtechceu.gtceu.api.data.chemical.material.event.PostMaterialEvent;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import appeng.api.storage.StorageCells;
import appeng.core.AELog;

import static org.gtlcore.gtlcore.api.registries.GTLRegistration.REGISTRATE;

public class CommonProxy {

    public CommonProxy() {
        CommonProxy.init();
        IEventBus eventBus = FMLJavaModLoadingContext.get().getModEventBus();
        REGISTRATE.registerEventListeners(eventBus);
        GTLStats.register(eventBus);
        GTLWirelessAeContent.register(eventBus);
        org.gtlcore.gtlcore.integration.gtmt.monitor.EnergyMonitorContent.register(eventBus);
        WirelessAeNetworkRuntime.register(MinecraftForge.EVENT_BUS);
        WirelessAePackets.register();
        CuriosTerminalLocator.register();
        eventBus.addListener(this::commonSetup);
        eventBus.addListener(this::clientSetup);
        eventBus.addListener(this::addMaterialRegistries);
        eventBus.addListener(this::addMaterials);
        eventBus.addListener(this::modifyMaterials);
        eventBus.addGenericListener(RecipeConditionType.class, this::registerRecipeConditions);
        eventBus.addGenericListener(GTRecipeType.class, this::registerRecipeTypes);
        eventBus.addGenericListener(MachineDefinition.class, this::registerMachines);
    }

    public static void init() {
        GTLCreativeModeTabs.init();
        ConfigHolder.init();
        AreaShape.register();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        StorageCells.addCellHandler(InfinityCellHandler.INSTANCE);
        StorageCells.addCellHandler(FastInfinityCellHandler.INSTANCE);
        StorageCells.addCellHandler(CircuitInfinityCellHandler.INSTANCE);
        StorageCells.addCellGuiHandler(new InfinityCellGuiHandler());
        event.enqueueWork(this::postRegistrationInitialization).whenComplete((res, err) -> {
            if (err != null) {
                AELog.warn(err);
            }
        });
    }

    public void postRegistrationInitialization() {
        GTLItems.InitUpgrades();
        GTLNetworkHandler.INSTANCE.init();
    }

    protected void clientSetup(final FMLClientSetupEvent event) {}

    // You MUST have this for custom materials.
    // Remember to register them not to GT's namespace, but your own.
    private void addMaterialRegistries(MaterialRegistryEvent event) {
        GTCEuAPI.materialManager.createRegistry(GTLCore.MOD_ID);
    }

    // As well as this.
    private void addMaterials(MaterialEvent event) {
        GTLMaterials.init();
    }

    // This is optional, though.
    private void modifyMaterials(PostMaterialEvent event) {}

    private void registerRecipeTypes(GTCEuAPI.RegisterEvent<ResourceLocation, GTRecipeType> event) {
        GTLRecipeTypes.init();
    }

    private void registerRecipeConditions(GTCEuAPI.RegisterEvent<ResourceLocation, RecipeConditionType<?>> event) {
        GTLRecipeConditions.init();
    }

    private void registerMachines(GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition> event) {
        GTLMachines.init();
        GeneratorMachine.LARGE_SEMI_FLUID_GENERATOR.setRecipeTypes(new GTRecipeType[] { GTLRecipeTypes.SEMI_FLUID_GENERATOR_FUELS });
    }
}
