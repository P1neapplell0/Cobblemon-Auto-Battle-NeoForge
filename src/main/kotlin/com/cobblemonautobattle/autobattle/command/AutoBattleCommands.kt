package com.cobblemonautobattle.autobattle.command

import com.cobblemonautobattle.autobattle.AutoBattle
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

/**
 * Comandos de administração (permissão nível 2). O liga/desliga em si é pela keybind.
 *
 * /autobattle reload — recarrega config/autobattle.json em runtime
 * /autobattle info   — mostra os principais parâmetros atuais
 */
object AutoBattleCommands {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("autobattle")
                .requires { it.hasPermission(2) }
                .then(literal("reload").executes(::reload))
                .then(literal("info").executes(::info))
        )
    }

    private fun reload(ctx: CommandContext<CommandSourceStack>): Int {
        AutoBattle.reloadConfig()
        val config = AutoBattle.config
        ctx.source.sendSuccess({
            Component.translatable(
                "autobattle.command.reload",
                config.enabled, config.searchRadius, config.levelMargin
            ).withStyle(ChatFormatting.GREEN)
        }, true)
        return 1
    }

    private fun info(ctx: CommandContext<CommandSourceStack>): Int {
        val c = AutoBattle.config
        ctx.source.sendSuccess({
            Component.translatable(
                "autobattle.command.info",
                c.enabled, c.searchRadius, c.attackRange, c.levelMargin,
                (c.retreatHealthPercent * 100).toInt(), c.awardXp, c.awardEvs, c.awardDrops
            ).withStyle(ChatFormatting.AQUA)
        }, false)
        return 1
    }
}
