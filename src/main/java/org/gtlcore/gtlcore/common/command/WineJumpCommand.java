package org.gtlcore.gtlcore.common.command;

import org.gtlcore.gtlcore.common.plushie.PlushieHeadJump;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** /winejump toggles the bounce of the plushie worn on the player's head. */
public final class WineJumpCommand {

    private WineJumpCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("winejump")
                .executes(context -> execute(context.getSource(), null))
                .then(Commands.literal("bgm")
                        .executes(context -> execute(context.getSource(), true))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> execute(context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled"))))));
    }

    private static int execute(CommandSourceStack source, Boolean bgm) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!PlushieHeadJump.isWearingPlushie(player)) {
            source.sendFailure(Component.translatable("message.gtlcore.winejump.no_plushie"));
            return 0;
        }

        if (!PlushieHeadJump.isJumping(player)) {
            boolean playBgm = Boolean.TRUE.equals(bgm);
            PlushieHeadJump.start(player, playBgm);
            source.sendSuccess(() -> Component.translatable(playBgm ?
                    "message.gtlcore.winejump.started_bgm" : "message.gtlcore.winejump.started"), true);
            return 1;
        }

        if (bgm == null) {
            PlushieHeadJump.stop(player);
            source.sendSuccess(() -> Component.translatable("message.gtlcore.winejump.stopped"), true);
            return 1;
        }

        PlushieHeadJump.setBgm(player, bgm);
        source.sendSuccess(() -> Component.translatable(bgm ?
                "message.gtlcore.winejump.bgm_on" : "message.gtlcore.winejump.bgm_off"), true);
        return 1;
    }
}
