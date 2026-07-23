package com.cobblemonautobattle.autobattle

import com.cobblemon.mod.common.api.moves.categories.DamageCategories
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import com.cobblemon.mod.common.pokemon.Pokemon
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Resultado da batalha simulada de golpe único (estilo SV).
 *
 * @property hunterWins false = o selvagem nocautearia primeiro: o caçador perde e recua.
 * @property hunterHpCost dano total que o caçador sofre (na derrota, o deixa no limiar de recuo).
 * @property hunterMovePhysical categoria do melhor golpe do caçador (animação physical/special).
 * @property wildMovePhysical categoria do melhor golpe do selvagem (visual do contra-ataque).
 */
data class BattleResult(
    val hunterWins: Boolean,
    val hunterHpCost: Int,
    val hunterMovePhysical: Boolean,
    val wildMovePhysical: Boolean,
)

/**
 * Combate simplificado do auto-battle: nada de instanciar uma PokemonBattle real, mas a
 * simulação usa os GOLPES REAIS dos dois lados — power/tipo/categoria do moveset, STAB real,
 * efetividade pelo tipo do golpe, imunidades de habilidade (Levitate, Flash Fire...) e SPEED
 * decidindo quem abre a troca. Tudo resolve num único golpe coreografado.
 */
object CombatCalculator {

    /** Power do "Struggle" de fallback quando não há golpe ofensivo utilizável. */
    private const val STRUGGLE_POWER = 50.0

    /**
     * Um selvagem é alvo válido se o level dele não passar do seu + [AutoBattleConfig.levelMargin].
     */
    fun isEligibleTarget(attacker: Pokemon, wild: Pokemon, config: AutoBattleConfig): Boolean {
        return wild.level <= attacker.level + config.levelMargin
    }

    /**
     * Simula a batalha inteira: cada lado usa o melhor golpe real do próprio moveset por round.
     * O mais rápido abre a troca (leva um revide a menos e vence empates de rounds). Se o
     * selvagem nocautearia o caçador primeiro, o caçador PERDE — sai com o HP no limiar de
     * recuo e o selvagem sobrevive ([AutoBattleConfig.allowDefeat] desliga isso).
     */
    fun simulateBattle(hunter: Pokemon, wild: Pokemon, config: AutoBattleConfig): BattleResult {
        val myChoice = bestMoveAgainst(hunter, wild, config)
        val theirChoice = bestMoveAgainst(wild, hunter, config)

        val myHit = rolled(myChoice.damage * config.damageScale, config).coerceAtLeast(1)
        val theirHit = rolled(theirChoice.damage, config).coerceAtLeast(1)

        val myRounds = ceil(wild.currentHealth.toDouble() / myHit).toInt().coerceAtLeast(1)
        val theirRounds = ceil(hunter.currentHealth.toDouble() / theirHit).toInt().coerceAtLeast(1)
        val hunterFaster = hunter.getStat(Stats.SPEED) >= wild.getStat(Stats.SPEED)

        val hunterWins = if (!config.allowDefeat) {
            true
        } else if (hunterFaster) {
            myRounds <= theirRounds
        } else {
            myRounds < theirRounds
        }

        val cost = if (hunterWins) {
            // Cada round além do primeiro deixa o selvagem revidar; quem é mais lento também
            // engole o golpe de abertura. Lutas fáceis (1 round, mais rápido) saem de graça.
            val retaliations = if (hunterFaster) myRounds - 1 else myRounds
            (theirHit * retaliations * config.retaliationMultiplier).toInt().coerceAtLeast(0)
        } else {
            // Derrota: cai direto pro limiar de recuo (o manager cuida da mensagem e da volta).
            val retreatFloor = (hunter.maxHealth * config.retreatHealthPercent).toInt().coerceAtLeast(1)
            (hunter.currentHealth - retreatFloor).coerceAtLeast(0)
        }

        return BattleResult(hunterWins, cost, myChoice.physical, theirChoice.physical)
    }

    // --- Seleção de golpe ---

    private class MoveChoice(val damage: Double, val physical: Boolean)

    /**
     * Avalia os golpes reais do moveset do atacante contra o defensor e devolve o de maior
     * dano esperado. Golpes de status são ignorados; golpes anulados por tipo ou habilidade
     * do defensor são descartados. Sem nenhum golpe utilizável → "Struggle" genérico sem tipo.
     */
    private fun bestMoveAgainst(attacker: Pokemon, defender: Pokemon, config: AutoBattleConfig): MoveChoice {
        var best: MoveChoice? = null
        for (move in attacker.moveSet) {
            if (move.damageCategory == DamageCategories.STATUS || move.power <= 0.0) continue
            val physical = move.damageCategory == DamageCategories.PHYSICAL
            val effectiveness = effectivenessAgainst(move.type, defender, config)
            if (effectiveness <= 0.0) continue
            val stab = if (move.type in attacker.types) 1.5 else 1.0
            val damage = baseDamage(attacker, defender, move.power, physical) * stab * effectiveness
            if (best == null || damage > best.damage) {
                best = MoveChoice(damage, physical)
            }
        }
        // Fallback "Struggle": físico, sem tipo (efetividade neutra), sem STAB.
        return best ?: MoveChoice(baseDamage(attacker, defender, STRUGGLE_POWER, physical = true), physical = true)
    }

    /** Esqueleto da fórmula de dano principal das gerações modernas, com a categoria do golpe. */
    private fun baseDamage(attacker: Pokemon, defender: Pokemon, power: Double, physical: Boolean): Double {
        val offense = attacker.getStat(if (physical) Stats.ATTACK else Stats.SPECIAL_ATTACK)
        val defense = defender.getStat(if (physical) Stats.DEFENCE else Stats.SPECIAL_DEFENCE).coerceAtLeast(1)
        return (2.0 * attacker.level / 5.0 + 2.0) * power * offense / defense / 50.0 + 2.0
    }

    private fun effectivenessAgainst(moveType: ElementalType, defender: Pokemon, config: AutoBattleConfig): Double {
        if (config.abilityImmunities) {
            val immuneTypes = abilityImmunities[defender.ability.name.lowercase()]
            if (immuneTypes != null && moveType in immuneTypes) return 0.0
        }
        return TypeChart.multiplier(moveType, defender.types)
    }

    private fun rolled(damage: Double, config: AutoBattleConfig): Int {
        val roll = if (config.randomizeDamage) Random.nextDouble(0.85, 1.0) else 1.0
        return (damage * roll).toInt()
    }

    /**
     * Habilidades que anulam golpes de um tipo (ids showdown, como o Cobblemon armazena).
     * Subconjunto seguro: só imunidades diretas, sem efeitos secundários.
     */
    private val abilityImmunities: Map<String, Set<ElementalType>> = mapOf(
        "levitate" to setOf(ElementalTypes.GROUND),
        "flashfire" to setOf(ElementalTypes.FIRE),
        "waterabsorb" to setOf(ElementalTypes.WATER),
        "stormdrain" to setOf(ElementalTypes.WATER),
        "dryskin" to setOf(ElementalTypes.WATER),
        "voltabsorb" to setOf(ElementalTypes.ELECTRIC),
        "lightningrod" to setOf(ElementalTypes.ELECTRIC),
        "motordrive" to setOf(ElementalTypes.ELECTRIC),
        "sapsipper" to setOf(ElementalTypes.GRASS),
    )
}

/**
 * Tabela de efetividade de tipo (Gen 6+). Só armazenamos os matchups != 1.0; qualquer
 * combinação ausente é neutra (1.0). Mantido interno ao mod de propósito: a versão que
 * existe no Cobblemon está marcada como temporária/sujeita a remoção.
 */
private object TypeChart {

    fun multiplier(attackType: ElementalType, defenderTypes: Iterable<ElementalType>): Double {
        val row = chart[attackType] ?: return 1.0
        var result = 1.0
        for (def in defenderTypes) {
            result *= row[def] ?: 1.0
        }
        return result
    }

    private val chart: Map<ElementalType, Map<ElementalType, Double>> = mapOf(
        ElementalTypes.NORMAL to mapOf(
            ElementalTypes.ROCK to 0.5, ElementalTypes.GHOST to 0.0, ElementalTypes.STEEL to 0.5
        ),
        ElementalTypes.FIRE to mapOf(
            ElementalTypes.FIRE to 0.5, ElementalTypes.WATER to 0.5, ElementalTypes.GRASS to 2.0,
            ElementalTypes.ICE to 2.0, ElementalTypes.BUG to 2.0, ElementalTypes.ROCK to 0.5,
            ElementalTypes.DRAGON to 0.5, ElementalTypes.STEEL to 2.0
        ),
        ElementalTypes.WATER to mapOf(
            ElementalTypes.FIRE to 2.0, ElementalTypes.WATER to 0.5, ElementalTypes.GRASS to 0.5,
            ElementalTypes.GROUND to 2.0, ElementalTypes.ROCK to 2.0, ElementalTypes.DRAGON to 0.5
        ),
        ElementalTypes.ELECTRIC to mapOf(
            ElementalTypes.WATER to 2.0, ElementalTypes.ELECTRIC to 0.5, ElementalTypes.GRASS to 0.5,
            ElementalTypes.GROUND to 0.0, ElementalTypes.FLYING to 2.0, ElementalTypes.DRAGON to 0.5
        ),
        ElementalTypes.GRASS to mapOf(
            ElementalTypes.FIRE to 0.5, ElementalTypes.WATER to 2.0, ElementalTypes.GRASS to 0.5,
            ElementalTypes.POISON to 0.5, ElementalTypes.GROUND to 2.0, ElementalTypes.FLYING to 0.5,
            ElementalTypes.BUG to 0.5, ElementalTypes.ROCK to 2.0, ElementalTypes.DRAGON to 0.5,
            ElementalTypes.STEEL to 0.5
        ),
        ElementalTypes.ICE to mapOf(
            ElementalTypes.FIRE to 0.5, ElementalTypes.WATER to 0.5, ElementalTypes.GRASS to 2.0,
            ElementalTypes.ICE to 0.5, ElementalTypes.GROUND to 2.0, ElementalTypes.FLYING to 2.0,
            ElementalTypes.DRAGON to 2.0, ElementalTypes.STEEL to 0.5
        ),
        ElementalTypes.FIGHTING to mapOf(
            ElementalTypes.NORMAL to 2.0, ElementalTypes.ICE to 2.0, ElementalTypes.POISON to 0.5,
            ElementalTypes.FLYING to 0.5, ElementalTypes.PSYCHIC to 0.5, ElementalTypes.BUG to 0.5,
            ElementalTypes.ROCK to 2.0, ElementalTypes.GHOST to 0.0, ElementalTypes.DARK to 2.0,
            ElementalTypes.STEEL to 2.0, ElementalTypes.FAIRY to 0.5
        ),
        ElementalTypes.POISON to mapOf(
            ElementalTypes.GRASS to 2.0, ElementalTypes.POISON to 0.5, ElementalTypes.GROUND to 0.5,
            ElementalTypes.ROCK to 0.5, ElementalTypes.GHOST to 0.5, ElementalTypes.STEEL to 0.0,
            ElementalTypes.FAIRY to 2.0
        ),
        ElementalTypes.GROUND to mapOf(
            ElementalTypes.FIRE to 2.0, ElementalTypes.ELECTRIC to 2.0, ElementalTypes.GRASS to 0.5,
            ElementalTypes.POISON to 2.0, ElementalTypes.FLYING to 0.0, ElementalTypes.BUG to 0.5,
            ElementalTypes.ROCK to 2.0, ElementalTypes.STEEL to 2.0
        ),
        ElementalTypes.FLYING to mapOf(
            ElementalTypes.ELECTRIC to 0.5, ElementalTypes.GRASS to 2.0, ElementalTypes.FIGHTING to 2.0,
            ElementalTypes.BUG to 2.0, ElementalTypes.ROCK to 0.5, ElementalTypes.STEEL to 0.5
        ),
        ElementalTypes.PSYCHIC to mapOf(
            ElementalTypes.FIGHTING to 2.0, ElementalTypes.POISON to 2.0, ElementalTypes.PSYCHIC to 0.5,
            ElementalTypes.DARK to 0.0, ElementalTypes.STEEL to 0.5
        ),
        ElementalTypes.BUG to mapOf(
            ElementalTypes.FIRE to 0.5, ElementalTypes.GRASS to 2.0, ElementalTypes.FIGHTING to 0.5,
            ElementalTypes.POISON to 0.5, ElementalTypes.FLYING to 0.5, ElementalTypes.PSYCHIC to 2.0,
            ElementalTypes.GHOST to 0.5, ElementalTypes.DARK to 2.0, ElementalTypes.STEEL to 0.5,
            ElementalTypes.FAIRY to 0.5
        ),
        ElementalTypes.ROCK to mapOf(
            ElementalTypes.FIRE to 2.0, ElementalTypes.ICE to 2.0, ElementalTypes.FIGHTING to 0.5,
            ElementalTypes.GROUND to 0.5, ElementalTypes.FLYING to 2.0, ElementalTypes.BUG to 2.0,
            ElementalTypes.STEEL to 0.5
        ),
        ElementalTypes.GHOST to mapOf(
            ElementalTypes.NORMAL to 0.0, ElementalTypes.PSYCHIC to 2.0, ElementalTypes.GHOST to 2.0,
            ElementalTypes.DARK to 0.5
        ),
        ElementalTypes.DRAGON to mapOf(
            ElementalTypes.DRAGON to 2.0, ElementalTypes.STEEL to 0.5, ElementalTypes.FAIRY to 0.0
        ),
        ElementalTypes.DARK to mapOf(
            ElementalTypes.FIGHTING to 0.5, ElementalTypes.PSYCHIC to 2.0, ElementalTypes.GHOST to 2.0,
            ElementalTypes.DARK to 0.5, ElementalTypes.FAIRY to 0.5
        ),
        ElementalTypes.STEEL to mapOf(
            ElementalTypes.FIRE to 0.5, ElementalTypes.WATER to 0.5, ElementalTypes.ELECTRIC to 0.5,
            ElementalTypes.ICE to 2.0, ElementalTypes.ROCK to 2.0, ElementalTypes.STEEL to 0.5,
            ElementalTypes.FAIRY to 2.0
        ),
        ElementalTypes.FAIRY to mapOf(
            ElementalTypes.FIRE to 0.5, ElementalTypes.FIGHTING to 2.0, ElementalTypes.POISON to 0.5,
            ElementalTypes.DRAGON to 2.0, ElementalTypes.DARK to 2.0, ElementalTypes.STEEL to 0.5
        )
    )
}
