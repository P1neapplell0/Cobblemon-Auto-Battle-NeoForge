package com.cobblemonautobattle.autobattle

import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource
import com.cobblemon.mod.common.api.pokemon.stats.SidemodEvSource
import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * Aplica as recompensas de uma vitória do auto-battle ao Pokémon participante e ao jogador.
 * Fonte marcada como "sidemod" (id "autobattle") para conviver com eventos de XP/EV do Cobblemon.
 */
object RewardHandler {

    private const val SIDEMOD_ID = "cobblemonautobattle"

    fun onWildDefeated(
        player: ServerPlayer,
        level: ServerLevel,
        winner: Pokemon,
        wild: Pokemon,
        config: AutoBattleConfig,
    ) {
        val xp = if (config.awardXp) awardXp(player, winner, wild, config) else 0
        if (config.awardEvs) awardEvs(winner, wild)
        if (config.awardDrops) awardDrops(player, level, wild)

        if (config.announceRewards) {
            // translatedName localiza até o nome da espécie no idioma do client.
            val name = wild.species.translatedName.copy().withStyle(ChatFormatting.YELLOW)
            val message = if (xp > 0) {
                Component.translatable(
                    "autobattle.victory_xp",
                    name,
                    Component.literal(xp.toString()).withStyle(ChatFormatting.GREEN)
                )
            } else {
                Component.translatable("autobattle.victory", name)
            }
            player.sendSystemMessage(message.withStyle(ChatFormatting.GOLD))
        }
    }

    private fun awardXp(player: ServerPlayer, winner: Pokemon, wild: Pokemon, config: AutoBattleConfig): Int {
        val baseYield = wild.form.baseExperienceYield
        val xp = (baseYield * wild.level / 5.0 * config.xpMultiplier).toInt().coerceAtLeast(1)
        winner.addExperienceWithPlayer(player, SidemodExperienceSource(SIDEMOD_ID), xp)
        return xp
    }

    private fun awardEvs(winner: Pokemon, wild: Pokemon) {
        val source = SidemodEvSource(SIDEMOD_ID, winner)
        for ((stat, value) in wild.form.evYield) {
            if (value != 0) {
                winner.evs.add(stat, value, source)
            }
        }
    }

    /**
     * Resolve a drop table do selvagem e envia os itens DIRETO ao inventário do jogador
     * (o que sobrar, se o inventário estiver cheio, cai aos pés dele). Entradas que não são
     * itens (ex.: comandos) usam seu próprio comportamento de drop, com o jogador como causa.
     */
    private fun awardDrops(player: ServerPlayer, level: ServerLevel, wild: Pokemon) {
        val itemRegistry = level.registryAccess().registryOrThrow(Registries.ITEM)
        val drops = wild.form.drops.getDrops(pokemon = wild)
        for (entry in drops) {
            if (entry is ItemDropEntry) {
                val item = itemRegistry.get(entry.item) ?: continue
                val count = entry.quantityRange?.random() ?: entry.quantity
                if (count <= 0) continue
                val stack = ItemStack(item, count)
                if (!player.addItem(stack)) {
                    // Inventário cheio: dropa o restante perto do jogador para não sumir.
                    player.drop(stack, false)
                }
            } else {
                entry.drop(null, level, player.position(), player)
            }
        }
    }
}
