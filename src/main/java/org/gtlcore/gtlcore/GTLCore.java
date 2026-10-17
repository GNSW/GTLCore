package org.gtlcore.gtlcore;

import org.gtlcore.gtlcore.client.ClientProxy;
import org.gtlcore.gtlcore.common.CommonProxy;
import org.gtlcore.gtlcore.utils.StorageManager;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

@Mod(GTLCore.MOD_ID)
public class GTLCore {

    public static final String MOD_ID = "gtlcore";
    public static final Logger LOGGER = LogManager.getLogger();
    public static final String VERSION = "1.0.2";
    public static StorageManager STORAGE_INSTANCE = new StorageManager();

    public static ResourceLocation id(String name) {
        return new ResourceLocation(MOD_ID, name);
    }

    public GTLCore() {
        DistExecutor.unsafeRunForDist(() -> ClientProxy::new, () -> CommonProxy::new);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(GTLCore::worldTick);
    }

    public static void worldTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.START && event.side.isServer()) {
            STORAGE_INSTANCE = StorageManager.getInstance(Objects.requireNonNull(event.level.getServer()));
        }
    }
}
