package com.cobblemonautobattle.autobattle.client

import com.cobblemonautobattle.autobattle.net.HuntTargetPayload
import com.cobblemonautobattle.autobattle.net.ToggleAutoBattlePayload
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW

/**
 * Lado cliente: registra as teclas do mod e dispara os packets correspondentes.
 * R (padrão) liga/desliga a caça automática em área; V (padrão) manda um caçador
 * atrás do Pokémon selvagem que você está mirando. Ambas remapeáveis em
 * Opções → Controles, categoria "Cobblemon Auto-Battle".
 */
@EventBusSubscriber(modid = "cobblemonautobattle", value = [Dist.CLIENT])
object AutoBattleClient {

    private lateinit var toggleKey: KeyMapping
    private lateinit var huntTargetKey: KeyMapping

    @SubscribeEvent
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        toggleKey = KeyMapping(
            "key.autobattle.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.autobattle",
        )
        huntTargetKey = KeyMapping(
            "key.autobattle.hunt_target",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.autobattle",
        )
        event.register(toggleKey)
        event.register(huntTargetKey)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val client = Minecraft.getInstance()
        while (toggleKey.consumeClick()) {
            if (client.player != null && client.connection != null) {
                PacketDistributor.sendToServer(ToggleAutoBattlePayload.INSTANCE)
            }
        }
        while (huntTargetKey.consumeClick()) {
            if (client.player != null && client.connection != null) {
                PacketDistributor.sendToServer(HuntTargetPayload.INSTANCE)
            }
        }
    }
}
