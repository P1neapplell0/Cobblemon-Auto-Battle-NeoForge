package com.cobblemonautobattle.autobattle

import com.cobblemon.mod.common.api.drop.ItemDropEntry
import com.cobblemon.mod.common.api.pokemon.stats.SidemodEvSource
import com.cobblemon.mod.common.api.tags.CobblemonItemTags
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor
import com.cobblemon.mod.common.battles.ai.RandomBattleAI
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.pokemon.Pokemon
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * Aplica as recompensas de uma vitória do auto-battle ao Pokémon participante e ao jogador.
 * XP is awarded through Cobblemon's battle path so held items and sidemods can observe it.
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
            val winnerName = winner.species.translatedName.copy().withStyle(ChatFormatting.AQUA)
            val message = if (xp > 0) {
                Component.translatable(
                    "autobattle.victory_xp",
                    winnerName,
                    name,
                    Component.literal(xp.toString()).withStyle(ChatFormatting.GREEN)
                )
            } else {
                Component.translatable("autobattle.victory", winnerName, name)
            }
            player.sendSystemMessage(message.withStyle(ChatFormatting.GOLD))
        }
    }

    private fun awardXp(player: ServerPlayer, winner: Pokemon, wild: Pokemon, config: AutoBattleConfig): Int {
        val party = Cobblemon.storage.getParty(player)
        val partyPokemon = party.toList()
        val battleParty = partyPokemon.map(BattlePokemon.Companion::playerOwned)
        val winnerIndex = partyPokemon.indexOfFirst { it.uuid == winner.uuid }

        // A PlayerBattleActor must contain the whole party. Exp Share/Exp All integrations
        // inspect that actor when Cobblemon awards the participant's battle experience.
        if (winnerIndex < 0) return 0

        val defeated = BattlePokemon.Companion.playerOwned(wild)
        val playerActor = PlayerBattleActor(player.uuid, battleParty)
        val wildActor = PokemonBattleActor(wild.uuid, defeated, 0F, RandomBattleAI())
        PokemonBattle(
            BattleFormat.Companion.GEN_9_SINGLES,
            BattleSide(playerActor),
            BattleSide(wildActor),
        )

        val participant = battleParty[winnerIndex]
        participant.facedOpponents.add(defeated)
        defeated.facedOpponents.add(participant)

        var participantXp = 0
        for (battlePokemon in battleParty) {
            val pokemon = battlePokemon.effectedPokemon
            if (pokemon.isFainted()) continue

            val multiplier = when {
                battlePokemon === participant -> config.xpMultiplier
                pokemon.heldItem().`is`(CobblemonItemTags.EXPERIENCE_SHARE) ->
                    Cobblemon.config.experienceShareMultiplier * config.xpMultiplier
                else -> continue
            }
            val xp = Cobblemon.experienceCalculator.calculate(battlePokemon, defeated, multiplier)
            if (xp <= 0) continue

            if (battlePokemon === participant) participantXp = xp
            playerActor.awardExperience(battlePokemon, xp)
        }
        return participantXp
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
