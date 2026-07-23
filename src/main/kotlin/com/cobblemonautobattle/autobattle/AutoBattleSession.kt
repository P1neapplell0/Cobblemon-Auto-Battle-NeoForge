package com.cobblemonautobattle.autobattle

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID

/** Fases do golpe único que resolve a batalha (estilo SV: 1 investida = 1 batalha). */
enum class HunterState {
    /** Perseguindo o alvo (ou ocioso seguindo o dono). */
    CHASING,
    /** Animação de ataque em andamento; o impacto acontece quando o timer zera. */
    WINDUP,
    /** Parado no lugar após vencer uma batalha (ritmo pós-vitória, sem "serial killer"). */
    RESTING,
}

/** Como a sessão nasceu — muda quem escolhe os alvos. */
enum class SessionMode {
    /** Tecla R: os caçadores escolhem alvos sozinhos no raio. */
    AUTO,
    /** Tecla V sem sessão ativa: só caça o alvo apontado; ao terminar, a sessão se encerra sozinha. */
    MANUAL,
}

/**
 * Um Pokémon caçador: uma entidade solta do jogador que persegue e enfraquece selvagens.
 *
 * @property pokemon o Pokémon (recebe XP/EVs).
 * @property entity a entidade viva no mundo.
 * @property sentByMod true se foi o mod que soltou este Pokémon (define se ele é recolhido
 *   ao desligar/recuar; os que o jogador soltou na mão ficam no mundo).
 * @property target o selvagem que este caçador está perseguindo/atacando.
 * @property state fase atual do ciclo de ataque (ver [HunterState]).
 * @property stateTicks ticks restantes na fase atual (usado no WINDUP).
 * @property attackCooldown ticks de descanso até poder iniciar a próxima batalha.
 * @property repathCooldown ticks até o próximo recálculo de rota (evita path "indeciso").
 * @property lastTargetDistance distância ao alvo no último tick (para detectar falta de progresso).
 * @property noProgressTicks ticks seguidos sem se aproximar do alvo (anti-stuck).
 */
class Hunter(
    val pokemon: Pokemon,
    val entity: PokemonEntity,
    val sentByMod: Boolean,
) {
    var target: PokemonEntity? = null
    /** true = o alvo atual foi APONTADO pelo jogador (tecla V): ignora o raio de busca e a coleira. */
    var directed: Boolean = false
    var state: HunterState = HunterState.CHASING
    var stateTicks: Int = 0
    /** Resultado pré-calculado no início do WINDUP (a animação usa a categoria do golpe escolhido). */
    var pendingBattle: BattleResult? = null
    var attackCooldown: Int = 0
    var repathCooldown: Int = 0
    var lastTargetDistance: Double = Double.MAX_VALUE
    var noProgressTicks: Int = 0

    /** Reseta o rastreio de perseguição ao trocar de alvo. */
    fun resetChase() {
        state = HunterState.CHASING
        stateTicks = 0
        repathCooldown = 0
        lastTargetDistance = Double.MAX_VALUE
        noProgressTicks = 0
        pendingBattle = null
    }
}

/**
 * Estado do auto-battle de um jogador enquanto está ligado. Suporta VÁRIOS caçadores ao
 * mesmo tempo: todos os Pokémon do jogador que estiverem fora entram como caçadores.
 *
 * @property playerId dono da sessão.
 * @property hunters os caçadores ativos (reconciliados a cada tick com os Pokémon fora).
 * @property pendingModSent UUIDs de Pokémon que o mod acabou de soltar e ainda não viraram
 *   caçador — usado para marcá-los como [Hunter.sentByMod] quando a entidade aparecer.
 */
class AutoBattleSession(
    val playerId: UUID,
    var mode: SessionMode = SessionMode.AUTO,
) {
    val hunters = mutableListOf<Hunter>()
    val pendingModSent = mutableSetOf<UUID>()

    /**
     * Alvo apontado (tecla V) aguardando um caçador: usado quando o líder acabou de ser
     * soltado e a entidade dele ainda não apareceu no mundo.
     */
    var pendingDirected: PokemonEntity? = null

    /** Ticks seguidos de ociosidade numa sessão MANUAL — ao encher, a sessão se encerra sozinha. */
    var manualIdleTicks: Int = 0

    /**
     * UUIDs de Pokémon que recuaram (HP baixo) ou desmaiaram e NÃO devem voltar a caçar enquanto
     * seguem fora — evita que o [AutoBattleManager] os re-adote a cada tick (o que gerava o spam de
     * "recuou" no chat). Limpo quando o Pokémon é recolhido/sai do mundo, permitindo caçar de novo.
     */
    val retreated = mutableSetOf<UUID>()

    /** Entidades raras já anunciadas nesta sessão (uma notificação por indivíduo, sem spam). */
    val notifiedRares = mutableSetOf<UUID>()
}
