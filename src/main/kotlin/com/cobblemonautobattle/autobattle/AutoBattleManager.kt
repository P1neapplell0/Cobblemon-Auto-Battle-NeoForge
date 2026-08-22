package com.cobblemonautobattle.autobattle

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokemon.PokemonFaintedEvent
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.traceFirstEntityCollision
import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.ClipContext
import java.util.UUID

/**
 * Cérebro server-side do auto-battle estilo "Let's Go" de Pokémon SV, no modo VÁRIOS caçadores:
 * todos os Pokémon que o jogador tiver fora caçam ao mesmo tempo, cada um com seu próprio alvo
 * (sem dois batendo no mesmo selvagem). Se nada estiver fora ao ligar, o mod solta o líder.
 *
 * A batalha resolve num GOLPE ÚNICO (como no SV): animação de ataque (windup) → impacto que
 * decide tudo → faint do selvagem + recompensas; o custo em HP do caçador é proporcional à
 * dificuldade da luta e, se ficar fraco, ele recua. Pokémon raros (shiny/lendários/etc.)
 * nunca são atacados — o jogador é notificado quando um aparece ([RarityGuard]).
 */
object AutoBattleManager {

    private val sessions = mutableMapOf<UUID, AutoBattleSession>()

    /**
     * Selvagens derrotados que estão tocando a animação "faint" antes de sumir, com os ticks
     * restantes. Ficam excluídos da seleção de alvos e são descartados quando o timer zera.
     */
    private val dying = LinkedHashMap<PokemonEntity, Int>()

    /**
     * Alvos temporariamente inalcançáveis (anti-stuck): entidade → gameTime de expiração.
     * Um caçador que não progrediu na perseguição marca o alvo aqui e tenta outro.
     */
    private val unreachable = mutableMapOf<UUID, Long>()

    /** Alterna o auto-battle do jogador (chamado pela keybind via packet). */
    fun toggle(player: ServerPlayer) {
        if (sessions.containsKey(player.uuid)) {
            stop(player, recall = true)
            message(player, ChatFormatting.YELLOW, "autobattle.disabled")
        } else {
            start(player)
        }
    }

    fun isActive(player: ServerPlayer) = sessions.containsKey(player.uuid)

    /**
     * Caça direcionada (tecla V): raycast do olhar do jogador até [AutoBattleConfig.directedHuntRange]
     * blocos; se mirar num selvagem válido, o caçador mais próximo vai atrás DELE (ignorando o raio
     * de busca). Só Pokémon que já estejam fora da bola podem aceitar a missão. Raros protegidos
     * continuam intocáveis.
     */
    fun huntTarget(player: ServerPlayer) {
        val config = AutoBattle.config
        if (!config.enabled) {
            message(player, ChatFormatting.RED, "autobattle.disabled_in_config")
            return
        }
        if (player.level() !is ServerLevel) return

        val target = player.traceFirstEntityCollision(
            maxDistance = config.directedHuntRange.toFloat(),
            entityClass = PokemonEntity::class.java,
            collideBlock = ClipContext.Fluid.NONE
        )
        if (target == null || !isWildCandidate(target) || target in dying) {
            message(player, ChatFormatting.GRAY, "autobattle.hunt_no_target")
            return
        }
        val targetName = target.pokemon.species.translatedName.copy().withStyle(ChatFormatting.YELLOW)
        if (RarityGuard.isProtected(target.pokemon, config)) {
            message(player, ChatFormatting.LIGHT_PURPLE, "autobattle.hunt_protected", targetName)
            return
        }

        val existing = sessions[player.uuid]
        val session = existing ?: AutoBattleSession(player.uuid, SessionMode.MANUAL).also {
            sessions[player.uuid] = it
        }
        // Enxerga imediatamente os Pokémon que já estão fora.
        reconcileHunters(player, session)

        // Só aceita a missão com Pokémon acima do limiar de recuo — senão ela nasceria morta
        // (sairia da bola e recuaria no tick seguinte).
        val hunter = session.hunters
            .filter { isBattleReady(it.pokemon, config) }
            .minByOrNull { it.entity.distanceToSqr(target) }
        if (hunter == null) {
            if (existing == null && session.hunters.isEmpty()) sessions.remove(player.uuid)
            message(player, ChatFormatting.RED, "autobattle.no_usable_pokemon")
            return
        }
        val attacker = hunter.pokemon
        if (!CombatCalculator.isEligibleTarget(attacker, target.pokemon, config)) {
            if (existing == null && session.hunters.isEmpty()) sessions.remove(player.uuid)
            message(player, ChatFormatting.RED, "autobattle.hunt_too_strong", targetName, target.pokemon.level)
            return
        }

        val attackerName = attacker.species.translatedName.copy().withStyle(ChatFormatting.AQUA)
        hunter.target = target
        hunter.directed = true
        hunter.resetChase()
        message(player, ChatFormatting.GREEN, "autobattle.hunt_started", attackerName, targetName)
    }

    private fun start(player: ServerPlayer) {
        val config = AutoBattle.config
        if (!config.enabled) {
            message(player, ChatFormatting.RED, "autobattle.disabled_in_config")
            return
        }

        val session = AutoBattleSession(player.uuid)
        sessions[player.uuid] = session

        message(player, ChatFormatting.GREEN, "autobattle.enabled", config.levelMargin)
    }

    fun stop(player: ServerPlayer, recall: Boolean) {
        val session = sessions.remove(player.uuid) ?: return
        if (recall) {
            // Só recolhe os que o MOD soltou; os que o jogador soltou na mão ficam no mundo.
            for (hunter in session.hunters) {
                if (hunter.sentByMod) recall(hunter.entity)
            }
        }
    }

    /** Chamado a cada tick do servidor a partir de [AutoBattle]. */
    fun tick(server: MinecraftServer) {
        BattleEffects.tick()
        tickDying()
        if (sessions.isEmpty()) return
        for (id in sessions.keys.toList()) {
            val session = sessions[id] ?: continue
            val player = server.playerList.getPlayer(id)
            if (player == null) {
                // Jogador saiu: o Cobblemon recolhe os Pokémon no logout, então só limpamos.
                sessions.remove(id)
                continue
            }
            tickPlayer(player, session)
        }
    }

    private fun tickPlayer(player: ServerPlayer, session: AutoBattleSession) {
        val config = AutoBattle.config
        if (!config.enabled) {
            stop(player, recall = true)
            return
        }
        val level = player.level() as? ServerLevel ?: return

        // 1. Sincroniza a lista de caçadores com os Pokémon que o jogador tem fora agora.
        reconcileHunters(player, session)

        // Missão direcionada aguardando um Pokémon que o jogador solte manualmente.
        val pending = session.pendingDirected
        if (pending != null) {
            if (!isWildCandidate(pending) || pending in dying) {
                session.pendingDirected = null
            } else {
                val courier = session.hunters.minByOrNull { it.entity.distanceToSqr(pending) }
                if (courier != null) {
                    courier.target = pending
                    courier.directed = true
                    courier.resetChase()
                    session.pendingDirected = null
                }
            }
        }

        // 2. Valida caçadores/alvos e junta os alvos já "reservados" (para não duplicar).
        val claimed = HashSet<PokemonEntity>()
        val iterator = session.hunters.iterator()
        while (iterator.hasNext()) {
            val hunter = iterator.next()
            val self = hunter.entity
            if (!self.isAlive || self.isRemoved) {
                iterator.remove()
                continue
            }
            val winner = hunter.pokemon
            if (winner.isFainted()) {
                iterator.remove()
                session.retreated.add(winner.uuid)
                if (hunter.sentByMod) recall(self)
                continue
            }
            // Risco: HP baixo → recua (recolhe se foi o mod que soltou; senão só para de caçar).
            val hpRatio = winner.currentHealth.toFloat() / winner.maxHealth.coerceAtLeast(1)
            if (hpRatio <= config.retreatHealthPercent) {
                iterator.remove()
                // Marca como recuado pra não ser re-adotado como caçador no próximo tick (senão spamava o chat).
                // A mensagem sai UMA vez (add retorna false se já estava no set).
                if (session.retreated.add(winner.uuid)) {
                    message(player, ChatFormatting.GOLD, "autobattle.retreat", winner.species.translatedName)
                }
                if (hunter.sentByMod) recall(self)
                continue
            }
            if (hunter.attackCooldown > 0) hunter.attackCooldown--

            if (isTargetValid(player, self, winner, hunter.target, hunter.directed, config)) {
                claimed.add(hunter.target!!)
            } else {
                hunter.target = null
                hunter.directed = false
                if (hunter.state == HunterState.WINDUP) hunter.resetChase() // não bate no ar
            }
        }

        // 3. Cada caçador roda sua state machine: escolher alvo → perseguir → windup → impacto.
        for (hunter in session.hunters) {
            val self = hunter.entity
            if (self.isBattling) continue // entrou numa batalha real do Cobblemon → deixa rolar.

            // Leash (âncora no jogador): longe demais → abandona o alvo e volta; muito além → teleporta.
            // Missões direcionadas (tecla V) não sofrem leash: o jogador mandou explicitamente.
            if (config.anchorToPlayer && !hunter.directed) {
                val distToOwner = self.distanceTo(player).toDouble()
                if (distToOwner > config.leashRadius * 1.5) {
                    self.randomTeleport(player.x, player.y, player.z, false)
                    hunter.target = null
                    hunter.resetChase()
                } else if (distToOwner > config.leashRadius && hunter.target != null) {
                    hunter.target = null
                    hunter.resetChase()
                    self.navigation.moveTo(player, config.moveSpeed)
                    continue
                }
            }

            when (hunter.state) {
                HunterState.WINDUP -> tickWindup(player, level, session, hunter, config)
                HunterState.RESTING -> tickRest(hunter)
                HunterState.CHASING -> tickChase(player, level, session, hunter, claimed, config)
            }
        }

        // 4. Sessão MANUAL (tecla V) se encerra sozinha ao terminar a missão (recolhe o que o mod soltou).
        if (session.mode == SessionMode.MANUAL) {
            val busy = session.pendingDirected != null || session.hunters.any {
                it.target != null || it.state != HunterState.CHASING || it.attackCooldown > 0
            }
            if (busy) {
                session.manualIdleTicks = 0
            } else if (++session.manualIdleTicks >= MANUAL_IDLE_END_TICKS) {
                stop(player, recall = true)
            }
        }
    }

    /** Fase RESTING: parado saboreando a vitória por alguns segundos antes do próximo alvo. */
    private fun tickRest(hunter: Hunter) {
        hunter.entity.navigation.stop()
        if (--hunter.stateTicks <= 0) {
            hunter.resetChase()
        }
    }

    /** Fase WINDUP: a animação de ataque está tocando; quando o timer zera, o impacto decide a batalha. */
    private fun tickWindup(
        player: ServerPlayer,
        level: ServerLevel,
        session: AutoBattleSession,
        hunter: Hunter,
        config: AutoBattleConfig,
    ) {
        val target = hunter.target
        if (target == null || !target.isAlive || target.isRemoved) {
            hunter.resetChase()
            return
        }
        hunter.entity.navigation.stop() // parado durante o golpe: a animação não desliza
        hunter.entity.lookControl.setLookAt(target, 30f, 30f)

        if (--hunter.stateTicks > 0) return

        // IMPACTO: um golpe resolve a batalha inteira (estilo SV), com o resultado pré-calculado
        // no início do windup (golpes reais dos dois lados, speed, imunidades — ver CombatCalculator).
        val winner = hunter.pokemon
        val result = hunter.pendingBattle ?: CombatCalculator.simulateBattle(winner, target.pokemon, config)
        hunter.pendingBattle = null

        // Custo da luta. Nunca desmaia (mín. 1 HP); na derrota o custo já o deixa no limiar de recuo.
        if (result.hunterHpCost > 0) {
            winner.currentHealth = (winner.currentHealth - result.hunterHpCost).coerceAtLeast(1)
        }

        if (result.hunterWins) {
            if (config.battleEffects) BattleEffects.playImpact(level, hunter.entity, target, config)

            // Recompensas primeiro (lê os dados do selvagem), depois a saída de cena — assim os
            // drops vão direto pro jogador em vez de cair no chão pela morte natural.
            RewardHandler.onWildDefeated(player, level, winner, target.pokemon, config)

            // Posta o faint OFICIAL do Cobblemon (sem zerar o HP — isso mataria a entidade pelo
            // caminho vanilla e duplicaria os drops). É o que faz a derrota contar para sistemas
            // que escutam POKEMON_FAINTED, como os Mass Outbreaks do CobblemonDynamicSpawns.
            if (config.fireFaintEvents) {
                CobblemonEvents.POKEMON_FAINTED.post(
                    PokemonFaintedEvent(target.pokemon, Cobblemon.config.defaultFaintTimer)
                )
            }
            if (config.battleEffects) {
                // Cry + animação "faint" real do modelo; o corpo fica caído (tickDying) antes do POOF.
                BattleEffects.playFaint(target, config)
                dying[target] = config.faintAnimationTicks.coerceAtLeast(1)
            } else {
                target.discard()
            }
            if (config.victoryJingle) BattleEffects.playVictoryJingle(player)

            hunter.attackCooldown = config.attackIntervalTicks
            hunter.target = null
            hunter.directed = false
            // Descanso pós-vitória: fica parado alguns segundos antes de partir pro próximo alvo.
            val rest = config.restAfterBattleTicks
            if (rest > 0) {
                hunter.state = HunterState.RESTING
                hunter.stateTicks = rest
            } else {
                hunter.resetChase()
            }
        } else {
            // DERROTA: o selvagem venceu a troca e sobrevive. Visual invertido: o selvagem
            // "responde" com a animação de ataque dele e o impacto estoura no caçador.
            if (config.battleEffects) {
                BattleEffects.playWindup(target, hunter.entity, result.wildMovePhysical)
                BattleEffects.playImpact(level, target, hunter.entity, config)
                BattleEffects.playDefeatSting(player)
            }
            val hunterName = winner.species.translatedName.copy().withStyle(ChatFormatting.AQUA)
            val targetName = target.pokemon.species.translatedName.copy().withStyle(ChatFormatting.YELLOW)
            message(player, ChatFormatting.RED, "autobattle.battle_lost", hunterName, targetName)

            // A derrota oficializa o desmaio e recolhe o Pokémon imediatamente.
            winner.currentHealth = 0
            if (config.fireFaintEvents) {
                CobblemonEvents.POKEMON_FAINTED.post(
                    PokemonFaintedEvent(winner, Cobblemon.config.defaultFaintTimer)
                )
            }
            session.retreated.add(winner.uuid)
            recall(hunter.entity)
            hunter.target = null
            hunter.directed = false
            hunter.resetChase()
        }
    }

    /** Fase CHASING: escolhe alvo (se precisar), persegue com path estável e inicia o windup no alcance. */
    private fun tickChase(
        player: ServerPlayer,
        level: ServerLevel,
        session: AutoBattleSession,
        hunter: Hunter,
        claimed: MutableSet<PokemonEntity>,
        config: AutoBattleConfig,
    ) {
        val self = hunter.entity
        val winner = hunter.pokemon

        if (hunter.target == null && session.mode == SessionMode.AUTO) {
            // No modo MANUAL (tecla V) o caçador nunca escolhe alvos sozinho.
            val newTarget = findTarget(player, level, session, self, winner, config, claimed)
            if (newTarget != null) {
                hunter.target = newTarget
                claimed.add(newTarget)
                hunter.resetChase()
            }
        }

        val target = hunter.target
        if (target == null) {
            // Ocioso: acompanha o dono, como o parceiro do SV.
            if (self.distanceTo(player) > config.followPlayerRadius) {
                self.navigation.moveTo(player, config.moveSpeed)
            }
            return
        }

        self.lookControl.setLookAt(target, 30f, 30f)
        val distance = self.distanceTo(target).toDouble()

        // Alcance efetivo considera o TAMANHO das hitboxes: attackRange mede o vão entre as
        // BORDAS. Pokémon largos (Onix, Snorlax...) nunca aproximam os CENTROS a 2.5 blocos —
        // sem somar os raios das duas caixas, o caçador orbitava o alvo pra sempre.
        val effectiveRange = config.attackRange + (self.bbWidth + target.bbWidth) / 2.0

        if (distance <= effectiveRange) {
            if (hunter.attackCooldown > 0) return // descansando entre batalhas
            // Inicia o golpe único: a batalha é simulada JÁ (a animação usa a categoria do
            // melhor golpe real escolhido); o resultado aterrissa attackWindupTicks depois.
            val result = CombatCalculator.simulateBattle(winner, target.pokemon, config)
            hunter.state = HunterState.WINDUP
            hunter.stateTicks = config.attackWindupTicks.coerceAtLeast(1)
            hunter.pendingBattle = result
            self.navigation.stop()
            if (config.battleEffects) {
                BattleEffects.playWindup(self, target, result.hunterMovePhysical)
            }
            return
        }

        // Perseguição com rota estável: recalcula a cada repathIntervalTicks (anti "andar
        // indeciso") OU imediatamente quando o caminho atual acabou (o alvo se moveu e o
        // caçador ficava parado "olhando" até o próximo recálculo).
        hunter.repathCooldown--
        if (hunter.repathCooldown <= 0 || self.navigation.isDone) {
            self.navigation.moveTo(target, config.moveSpeed)
            hunter.repathCooldown = config.repathIntervalTicks.coerceAtLeast(1)
        }

        // Pulo assistido: esbarrou num degrau/bloco com o chão sob os pés → pula. Algumas
        // espécies não pulam sozinhas com a navegação custom do Cobblemon e ficavam presas.
        if (self.horizontalCollision && self.onGround()) {
            self.jumpControl.jump()
        }

        // Anti-stuck: sem progresso real por stuckTimeoutTicks → marca o alvo como inalcançável e desiste.
        if (distance < hunter.lastTargetDistance - 0.25) {
            hunter.lastTargetDistance = distance
            hunter.noProgressTicks = 0
        } else {
            hunter.noProgressTicks++
            if (hunter.noProgressTicks >= config.stuckTimeoutTicks) {
                unreachable[target.uuid] = level.gameTime + UNREACHABLE_COOLDOWN_TICKS
                claimed.remove(target)
                hunter.target = null
                hunter.directed = false
                hunter.resetChase()
            }
        }
    }

    /** Avança os selvagens caídos (animação "faint") e os remove do mundo quando o timer zera. */
    private fun tickDying() {
        if (dying.isEmpty()) return
        val iterator = dying.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val entity = entry.key
            if (!entity.isAlive || entity.isRemoved) {
                iterator.remove()
                continue
            }
            entity.navigation.stop() // caído não sai andando
            entry.setValue(entry.value - 1)
            if (entry.value <= 0) {
                (entity.level() as? ServerLevel)?.let { BattleEffects.playVanish(it, entity) }
                entity.discard()
                iterator.remove()
            }
        }
    }

    /** Adiciona como caçador qualquer Pokémon manualmente solto que ainda não seja rastreado. */
    private fun reconcileHunters(player: ServerPlayer, session: AutoBattleSession) {
        val party = Cobblemon.storage.getParty(player)
        for (pokemon in party) {
            val entity = pokemon.entity
            if (entity == null || !isOwnedOut(entity, player)) {
                // Não está mais fora (recolhido/sumiu): esquece o "recuado" para poder caçar de novo se for re-solto.
                session.retreated.remove(pokemon.uuid)
                continue
            }
            // Recuou por HP baixo → fica no banco. NÃO re-adiciona como caçador (era isto que gerava o spam).
            if (pokemon.uuid in session.retreated) continue
            if (session.hunters.any { it.entity === entity }) continue
            val sentByMod = session.pendingModSent.remove(pokemon.uuid)
            session.hunters.add(Hunter(pokemon, entity, sentByMod))
        }
    }

    private fun isOwnedOut(entity: PokemonEntity?, player: ServerPlayer): Boolean {
        return entity != null && entity.isAlive && !entity.isRemoved && entity.ownerUUID == player.uuid
    }

    /** Apto a batalhar: não desmaiado e com HP acima do limiar de recuo. */
    private fun isBattleReady(pokemon: Pokemon, config: AutoBattleConfig): Boolean {
        if (pokemon.isFainted()) return false
        val hpRatio = pokemon.currentHealth.toFloat() / pokemon.maxHealth.coerceAtLeast(1)
        return hpRatio > config.retreatHealthPercent
    }

    private fun recall(entity: PokemonEntity) {
        if (entity.isAlive && !entity.isRemoved && !entity.isBattling) {
            entity.recallWithAnimation()
        }
    }

    private fun isWildCandidate(candidate: PokemonEntity): Boolean {
        return candidate.isAlive && !candidate.isRemoved &&
            candidate.pokemon.isWild() && candidate.ownerUUID == null &&
            !candidate.isBattling && candidate.pokemon.currentHealth > 0
    }

    private fun isTargetValid(
        player: ServerPlayer,
        self: PokemonEntity,
        winner: Pokemon,
        target: PokemonEntity?,
        directed: Boolean,
        config: AutoBattleConfig,
    ): Boolean {
        if (target == null) return false
        if (target in dying) return false
        if (!isWildCandidate(target)) return false
        if (!CombatCalculator.isEligibleTarget(winner, target.pokemon, config)) return false
        if (RarityGuard.isProtected(target.pokemon, config)) return false
        if (directed) {
            // Missão apontada pelo jogador: ignora o raio de busca, mas tem um teto generoso.
            return target.distanceTo(player) <= config.leashRadius * 2
        }
        val anchor = if (config.anchorToPlayer) player else self
        return target.distanceTo(anchor) <= config.searchRadius
    }

    /**
     * Escolhe o alvo mais próximo dentro do raio ao redor da âncora (jogador ou o próprio caçador).
     * No mesmo scan, detecta Pokémon raros protegidos e notifica o jogador (uma vez por indivíduo):
     * mensagem no chat + contorno brilhante + sino de alerta.
     */
    private fun findTarget(
        player: ServerPlayer,
        level: ServerLevel,
        session: AutoBattleSession,
        self: PokemonEntity,
        winner: Pokemon,
        config: AutoBattleConfig,
        claimed: Set<PokemonEntity>,
    ): PokemonEntity? {
        val anchor = if (config.anchorToPlayer) player else self
        val box = anchor.boundingBox.inflate(config.searchRadius)
        val now = level.gameTime

        val candidates = level.getEntitiesOfClass(PokemonEntity::class.java, box) { candidate ->
            candidate !== self && isWildCandidate(candidate) && candidate !in dying
        }

        var best: PokemonEntity? = null
        var bestDistance = Double.MAX_VALUE
        for (candidate in candidates) {
            if (RarityGuard.isProtected(candidate.pokemon, config)) {
                notifyRareFound(player, session, candidate, config)
                continue
            }
            if (candidate in claimed) continue
            if (!CombatCalculator.isEligibleTarget(winner, candidate.pokemon, config)) continue
            val expiry = unreachable[candidate.uuid]
            if (expiry != null) {
                if (expiry > now) continue
                unreachable.remove(candidate.uuid)
            }
            val distance = self.distanceToSqr(candidate)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /** Anuncia um raro encontrado: chat colorido + glowing temporário + sino (uma vez por indivíduo). */
    private fun notifyRareFound(
        player: ServerPlayer,
        session: AutoBattleSession,
        rare: PokemonEntity,
        config: AutoBattleConfig,
    ) {
        if (!session.notifiedRares.add(rare.uuid)) return

        val name = rare.pokemon.species.translatedName.copy().withStyle(ChatFormatting.GOLD)
        val key = if (rare.pokemon.shiny) "autobattle.rare_found_shiny" else "autobattle.rare_found"
        player.sendSystemMessage(Component.translatable(key, name).withStyle(ChatFormatting.LIGHT_PURPLE))

        if (config.rareGlowSeconds > 0) {
            rare.addEffect(MobEffectInstance(MobEffects.GLOWING, config.rareGlowSeconds * 20, 0, false, false))
        }
        if (config.rareFoundSound) BattleEffects.playRareAlert(player)
    }

    /**
     * Manda uma mensagem traduzível ao jogador. As chaves ficam em assets/autobattle/lang/
     * (en_us como padrão global, pt_br etc. como traduções) — o client resolve no idioma dele,
     * já que o mod também está instalado lá (obrigatório pela keybind).
     */
    private fun message(player: ServerPlayer, color: ChatFormatting, key: String, vararg args: Any) {
        player.sendSystemMessage(Component.translatable(key, *args).withStyle(color))
    }

    private const val UNREACHABLE_COOLDOWN_TICKS = 200L

    /** Ociosidade (ticks) que encerra sozinha uma sessão MANUAL da tecla V (40 = 2s). */
    private const val MANUAL_IDLE_END_TICKS = 40
}
