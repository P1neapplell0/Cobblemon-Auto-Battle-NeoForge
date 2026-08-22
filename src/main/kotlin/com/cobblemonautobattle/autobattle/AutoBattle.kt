package com.cobblemonautobattle.autobattle

import com.cobblemonautobattle.autobattle.command.AutoBattleCommands
import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import com.cobblemonautobattle.autobattle.net.HuntTargetPayload
import com.cobblemonautobattle.autobattle.net.ToggleAutoBattlePayload
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/** Common NeoForge entry point and server-side lifecycle wiring. */
@Mod(AutoBattle.MOD_ID)
class AutoBattle(modBus: IEventBus) {
    init {
        modBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)

        LOGGER.info(
            "Cobblemon Auto-Battle initialized (enabled={}, searchRadius={}, levelMargin={}).",
            config.enabled,
            config.searchRadius,
            config.levelMargin,
        )
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(ToggleAutoBattlePayload.TYPE, ToggleAutoBattlePayload.CODEC) { _, context ->
            context.enqueueWork {
                (context.player() as? ServerPlayer)?.let(AutoBattleManager::toggle)
            }
        }
        registrar.playToServer(HuntTargetPayload.TYPE, HuntTargetPayload.CODEC) { payload, context ->
            context.enqueueWork {
                (context.player() as? ServerPlayer)?.let { player ->
                    AutoBattleManager.huntTarget(player, payload.slot)
                }
            }
        }
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        AutoBattleManager.tick(event.server)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        AutoBattleCommands.register(event.dispatcher)
    }

    companion object {
        const val MOD_ID = "cobblemonautobattle"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)

        var config = AutoBattleConfig.load()
            private set

        fun reloadConfig() {
            config = AutoBattleConfig.load()
        }
    }
}
