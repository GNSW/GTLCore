package com.wcwen.gtmbsockets;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config for GT Multiblock Sockets.
 *
 * <p>The only tunable is how often the global/layer limit counts are refreshed.  It is exposed
 * through the Forge mod list's Config button as well as the normal TOML file.
 */
public final class Config {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue LIMIT_REFRESH_TICKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("GT Multiblock Sockets client settings").push("limits");

        LIMIT_REFRESH_TICKS = builder
                .comment("How often (in client ticks) global/layer hatch-limit counts are refreshed.",
                        "Lower values react faster to placed/broken hatches but scan the target pattern more often.",
                        "Range: 5-200; default: 20 (about once per second).")
                .defineInRange("limitRefreshTicks", 20, 5, 200);

        builder.pop();
        SPEC = builder.build();
    }

    private Config() {
    }

    public static int limitRefreshTicks() {
        try {
            return Math.max(1, LIMIT_REFRESH_TICKS.get());
        } catch (Throwable ignored) {
            return 20;
        }
    }
}
