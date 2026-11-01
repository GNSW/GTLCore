package org.gtlcore.gtlcore.network;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.network.packet.CSetNoClip;
import org.gtlcore.gtlcore.network.packet.SSetNoClip;
import org.gtlcore.gtlcore.network.packet.SStructureDetectHighlight;

import com.glodblock.github.glodium.network.NetworkHandler;

public class GTLNetworkHandler extends NetworkHandler {

    public static final GTLNetworkHandler INSTANCE = new GTLNetworkHandler();

    public GTLNetworkHandler() {
        super(GTLCore.MOD_ID);
    }

    public void init() {
        registerPacket(CSetNoClip.class, CSetNoClip::new);
        registerPacket(SSetNoClip.class, SSetNoClip::new);
        registerPacket(SStructureDetectHighlight.class, SStructureDetectHighlight::new);
    }
}
