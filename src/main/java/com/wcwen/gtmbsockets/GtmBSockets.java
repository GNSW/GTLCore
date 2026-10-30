package com.wcwen.gtmbsockets;

import net.minecraftforge.fml.common.Mod;

/**
 * GT Multiblock Sockets.
 *
 * <p>A small, standalone client-side companion for GregTech CEu and GTLCore.  While the player
 * holds a GregTech multiblock part (hatch, bus, maintenance hatch, muffler, ...), every socket in
 * the nearby multiblock patterns that accepts that part is outlined in the world: cyan for a free
 * socket, orange for a socket that is already occupied or blocked.
 *
 * <p>The mod contains no GregTech code and does not alter any GregTech class.  It only reads the
 * public structure-pattern API and uses one Mixin accessor to reach two package-private fields of
 * {@code BlockPattern}.
 */
@Mod(GtmBSockets.MOD_ID)
public final class GtmBSockets {

    public static final String MOD_ID = "gtmbsockets";

    public GtmBSockets() {
    }
}
