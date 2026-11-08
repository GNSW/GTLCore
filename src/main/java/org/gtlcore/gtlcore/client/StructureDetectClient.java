package org.gtlcore.gtlcore.client;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.item.UltimateTerminalBehavior;
import org.gtlcore.gtlcore.network.GTLNetworkHandler;
import org.gtlcore.gtlcore.network.packet.CStructureDetect;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Ctrl + right-click with the ultimate terminal runs the structure detection instead of opening the build settings.
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructureDetectClient {

    private StructureDetectClient() {}

    @SubscribeEvent
    public static void onInteractionKeyMappingTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!UltimateTerminalBehavior.isUltimateTerminal(mc.player.getMainHandItem())) return;
        if (!isControlDown(mc) || mc.player.isShiftKeyDown()) return;
        if (!(mc.hitResult instanceof BlockHitResult hitResult)) return;
        BlockPos pos = hitResult.getBlockPos();
        if (!(MetaMachine.getMachine(mc.level, pos) instanceof IMultiController)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        GTLNetworkHandler.INSTANCE.sendToServer(new CStructureDetect(pos));
    }

    private static boolean isControlDown(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }
}
