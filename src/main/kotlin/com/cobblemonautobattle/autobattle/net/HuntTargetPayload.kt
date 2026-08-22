package com.cobblemonautobattle.autobattle.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Packet C2S contendo o slot selecionado no party quando o jogador apertou a tecla de caça
 * direcionada. O servidor valida o slot contra a própria party antes de liberar o Pokémon.
 */
class HuntTargetPayload(val slot: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<HuntTargetPayload>(
            ResourceLocation.fromNamespaceAndPath("cobblemonautobattle", "hunt_target")
        )

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, HuntTargetPayload> =
            StreamCodec.of(
                { buffer, payload -> buffer.writeVarInt(payload.slot) },
                { buffer -> HuntTargetPayload(buffer.readVarInt()) },
            )
    }
}
