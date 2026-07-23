package com.cobblemonautobattle.autobattle.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Packet C2S sem payload: apenas sinaliza "o jogador apertou a tecla de auto-battle".
 * Toda a lógica (ligar/desligar, soltar/recolher o Pokémon) roda no servidor.
 */
class ToggleAutoBattlePayload private constructor() : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val INSTANCE = ToggleAutoBattlePayload()

        val TYPE = CustomPacketPayload.Type<ToggleAutoBattlePayload>(
            ResourceLocation.fromNamespaceAndPath("cobblemonautobattle", "toggle")
        )

        // Sem dados na wire: o codec sempre entrega a mesma instância.
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ToggleAutoBattlePayload> =
            StreamCodec.unit(INSTANCE)
    }
}
