package org.gtlcore.gtlcore.integration.jade;

import org.gtlcore.gtlcore.integration.jade.provider.*;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * @author EasterFG on 2024/10/27
 */
@WailaPlugin
public class GTLJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(new EnergyMonitorProvider(), org.gtlcore.gtlcore.integration.gtmt.monitor.EnergyMonitorBlockEntity.class);
        registration.registerItemStorage(QuantumChestItemStorageProvider.INSTANCE, MetaMachineBlockEntity.class);
        registration.registerItemStorage(MEStockingItemStorageProvider.INSTANCE, MetaMachineBlockEntity.class);
        registration.registerFluidStorage(MEStockingFluidStorageProvider.INSTANCE, MetaMachineBlockEntity.class);
        registration.registerBlockDataProvider(new WirelessOpticalDataHatchProvide(), BlockEntity.class);
        registration.registerBlockDataProvider(new TickTimeProvider(), MetaMachineBlockEntity.class);
        registration.registerBlockDataProvider(new BatchProcessingProvider(), MetaMachineBlockEntity.class);
        registration.registerBlockDataProvider(new RecipeMultiplierProvider(), MetaMachineBlockEntity.class);
        registration.registerBlockDataProvider(new MEPatternBufferProvider(), BlockEntity.class);
        registration.registerBlockDataProvider(new MEPatternBufferProxyProvider(), BlockEntity.class);
        registration.registerBlockDataProvider(new MEMAIOProvider(), BlockEntity.class);
        registration.registerBlockDataProvider(new WirelessAeNetworkProvider(), BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new EnergyMonitorProvider(), org.gtlcore.gtlcore.integration.gtmt.monitor.EnergyMonitorBlock.class);
        var meInventoryAmountProvider = new MeInventoryAmountProvider();
        registration.addConfig(meInventoryAmountProvider.getUid(), true);
        registration.registerBlockComponent(meInventoryAmountProvider, Block.class);
        var patternRelayProvider = new PatternRelayJadeProvider();
        registration.addConfig(patternRelayProvider.getUid(), true);
        registration.registerBlockComponent(patternRelayProvider, Block.class);
        registration.registerItemStorageClient(QuantumChestItemStorageProvider.INSTANCE);
        registration.registerItemStorageClient(MEStockingItemStorageProvider.INSTANCE);
        registration.registerFluidStorageClient(MEStockingFluidStorageProvider.INSTANCE);
        registration.registerBlockComponent(new WirelessOpticalDataHatchProvide(), Block.class);
        registration.registerBlockComponent(new TickTimeProvider(), MetaMachineBlock.class);
        registration.registerBlockComponent(new BatchProcessingProvider(), MetaMachineBlock.class);
        registration.registerBlockComponent(new RecipeMultiplierProvider(), MetaMachineBlock.class);
        registration.registerBlockComponent(new MEPatternBufferProvider(), Block.class);
        registration.registerBlockComponent(new MEPatternBufferProxyProvider(), Block.class);
        registration.registerBlockComponent(new MEMAIOProvider(), Block.class);
        registration.registerBlockComponent(new WirelessAeNetworkProvider(), Block.class);
    }
}
