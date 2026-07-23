package com.cobblemonautobattle.autobattle.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Packet C2S sem payload: "o jogador apertou a tecla de caça direcionada".
 * O servidor faz o raycast do olhar do jogador e manda um caçador atrás do
 * Pokémon apontado — nada de confiar em IDs vindos do client.
 */
class HuntTargetPayload private constructor() : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val INSTANCE = HuntTargetPayload()

        val TYPE = CustomPacketPayload.Type<HuntTargetPayload>(
            ResourceLocation.fromNamespaceAndPath("cobblemonautobattle", "hunt_target")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HuntTargetPayload> =
            StreamCodec.unit(INSTANCE)
    }
}
