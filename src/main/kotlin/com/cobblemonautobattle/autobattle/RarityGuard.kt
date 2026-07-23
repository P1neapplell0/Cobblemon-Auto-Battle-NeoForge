package com.cobblemonautobattle.autobattle

import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * Decide quais Pokémon selvagens são "raros demais para bater": shinies, espécies com labels
 * protegidas (labels oficiais do Cobblemon como legendary/mythical/ultra_beast/paradox — mods
 * de terceiros podem registrar as próprias) e uma lista extra de espécies na config.
 * Raros nunca viram alvo; em vez disso o jogador é notificado quando um aparece no raio.
 */
object RarityGuard {

    fun isProtected(pokemon: Pokemon, config: AutoBattleConfig): Boolean {
        if (!config.protectRareTargets) return false
        if (config.protectShiny && pokemon.shiny) return true

        // Labels vêm da forma (herdam da espécie): cobre legendary, mythical, ultra_beast, paradox
        // e qualquer label custom que outro mod (ou datapack) tenha adicionado.
        val labels = pokemon.form.labels
        if (config.protectedLabels.any { protected -> labels.any { it.equals(protected, ignoreCase = true) } }) {
            return true
        }

        val speciesName = pokemon.species.name
        return config.protectedSpecies.any { it.equals(speciesName, ignoreCase = true) }
    }
}
