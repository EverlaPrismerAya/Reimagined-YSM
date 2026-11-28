package com.elfmcys.yesstevemodel.command;

import com.elfmcys.yesstevemodel.command.subcommands.client.CacheCommand;
import com.elfmcys.yesstevemodel.util.YSMMessageFormatter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;


public class ReimaginedYSMClientCommand {

    public static void registerClientCommands(CommandDispatcher<ClientCommandRegistrationEvent.ClientCommandSourceStack> commandDispatcher) {
        LiteralArgumentBuilder<ClientCommandRegistrationEvent.ClientCommandSourceStack> root = LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("reimaginedysm")
                .requires(source -> true);

        root.then(CacheCommand.register());
        root.then(LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("performance").executes(context -> {
            var mc=net.minecraft.client.Minecraft.getInstance();
            String text=net.everla.reimaginedysm.gpu.RenderMetrics.ENABLED?net.everla.reimaginedysm.gpu.RenderMetrics.snapshot().toString():"Performance counters are disabled. Start with -Drysm.renderMetrics=true";
            mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(text));return 1;
        }).then(LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("reset").executes(context->{net.everla.reimaginedysm.gpu.RenderMetrics.clear();return 1;})));
        root.then(LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("keys").executes(context -> {
            var mc=net.minecraft.client.Minecraft.getInstance();
            for(var key:mc.options.keyMappings)mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(key.getName()+" = "+key.getTranslatedKeyMessage().getString()));
            return 1;
        }));

        commandDispatcher.register(root);
    }
}