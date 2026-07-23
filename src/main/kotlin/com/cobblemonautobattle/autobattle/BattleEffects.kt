package com.cobblemonautobattle.autobattle

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket
import com.cobblemon.mod.common.util.playSoundServer
import com.cobblemon.mod.common.util.sendParticlesServer
import com.cobblemonautobattle.autobattle.config.AutoBattleConfig
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * Efeitos do auto-battle usando as ANIMAÇÕES REAIS dos modelos (sistema posable/bedrock do
 * Cobblemon) e sons coreografados. A batalha é um golpe único (estilo SV) dividido em duas
 * fases: [playWindup] (animação de ataque + investida) e, ticks depois, [playImpact]
 * (partículas, knockback e som do acerto) — assim o dano aterrissa junto com o swing.
 *
 * O jingle de vitória é uma sequência de notas tocadas SÓ para o dono ([playNotifySound]),
 * agendadas numa fila processada por [tick] a cada tick do servidor.
 */
object BattleEffects {

    /** Mesmo raio que o DamageInstruction do Cobblemon usa ao transmitir o "recoil". */
    private const val ANIMATION_VIEW_DISTANCE = 50.0

    private class QueuedNote(
        var delay: Int,
        val player: ServerPlayer,
        val sound: SoundEvent,
        val volume: Float,
        val pitch: Float,
    )

    private val noteQueue = mutableListOf<QueuedNote>()

    /** Processa a fila de notas agendadas. Chamado a cada tick do servidor pelo manager. */
    fun tick() {
        if (noteQueue.isEmpty()) return
        val iterator = noteQueue.iterator()
        while (iterator.hasNext()) {
            val note = iterator.next()
            if (note.player.isRemoved || note.player.hasDisconnected()) {
                iterator.remove()
                continue
            }
            if (--note.delay <= 0) {
                note.player.playNotifySound(note.sound, SoundSource.PLAYERS, note.volume, note.pitch)
                iterator.remove()
            }
        }
    }

    /**
     * Fase 1 do golpe: o atacante encara o alvo, toca a animação de ataque real do modelo
     * ("physical" ou "special" conforme o lado ofensivo do cálculo, com fallback pra outra
     * categoria) e dá a investida. O impacto vem [AutoBattleConfig.attackWindupTicks] depois.
     */
    fun playWindup(attacker: PokemonEntity, target: PokemonEntity, physicalAttack: Boolean) {
        val dir = horizontalDirection(attacker, target)

        val attackAnimations = if (physicalAttack) setOf("physical", "special") else setOf("special", "physical")
        sendAnimation(attacker, attackAnimations)

        attacker.lookControl.setLookAt(target, 30f, 30f)
        attacker.setDeltaMovement(dir.x * 0.45, 0.18, dir.z * 0.45)
        attacker.hasImpulse = true
    }

    /**
     * Fase 2 do golpe: o impacto que decide a batalha — partículas na altura do peito do alvo,
     * knockback e som de acerto discreto (volume da config, pitch variando pra não enjoar).
     */
    fun playImpact(level: ServerLevel, attacker: PokemonEntity, target: PokemonEntity, config: AutoBattleConfig) {
        val dir = horizontalDirection(attacker, target)
        val hitPos = Vec3(target.x, target.y + target.bbHeight * 0.5, target.z)

        level.sendParticlesServer(ParticleTypes.CRIT, hitPos, 14, Vec3(0.3, 0.3, 0.3), 0.15)
        target.setDeltaMovement(target.deltaMovement.add(dir.x * 0.25, 0.14, dir.z * 0.25))
        target.hasImpulse = true

        if (config.hitSoundVolume > 0f) {
            val pitch = 0.9f + Random.nextFloat() * 0.3f
            level.playSoundServer(hitPos, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, config.hitSoundVolume.toFloat(), pitch)
        }
    }

    /** O selvagem foi derrotado: grito (cry) da espécie + animação "faint" real do modelo. */
    fun playFaint(target: PokemonEntity, config: AutoBattleConfig) {
        if (config.defeatCry) target.cry()
        sendAnimation(target, setOf("faint"))
    }

    /** Jingle de vitória (3 notas ascendentes, estilo chime do SV) — só o dono ouve. */
    fun playVictoryJingle(player: ServerPlayer) {
        // Dó–Mi–Sol agudo em sino: pitches 1.0 / 1.26 / 1.5, espaçadas 3 ticks.
        noteQueue.add(QueuedNote(1, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 1.0f))
        noteQueue.add(QueuedNote(4, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 1.26f))
        noteQueue.add(QueuedNote(7, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.8f, 1.5f))
    }

    /** Sino de alerta (2 notas) quando um Pokémon raro é encontrado — só o dono ouve. */
    fun playRareAlert(player: ServerPlayer) {
        noteQueue.add(QueuedNote(1, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.9f, 1.8f))
        noteQueue.add(QueuedNote(5, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.9f, 1.2f))
    }

    /** Sting de derrota (2 notas descendentes) quando o caçador PERDE a batalha — só o dono ouve. */
    fun playDefeatSting(player: ServerPlayer) {
        noteQueue.add(QueuedNote(1, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 0.9f))
        noteQueue.add(QueuedNote(6, player, SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 0.6f))
    }

    /** Baforada de partículas no momento em que o corpo desmaiado some do mundo. */
    fun playVanish(level: ServerLevel, target: PokemonEntity) {
        val pos = Vec3(target.x, target.y + target.bbHeight * 0.5, target.z)
        level.sendParticlesServer(ParticleTypes.POOF, pos, 20, Vec3(0.3, 0.4, 0.3), 0.02)
    }

    private fun horizontalDirection(from: PokemonEntity, to: PokemonEntity): Vec3 {
        val delta = to.position().subtract(from.position())
        val horizontal = Vec3(delta.x, 0.0, delta.z)
        return if (horizontal.lengthSqr() > 1.0e-4) horizontal.normalize() else Vec3.ZERO
    }

    private fun sendAnimation(entity: PokemonEntity, animations: Set<String>) {
        PlayPosableAnimationPacket(entity.id, animations, emptyList())
            .sendToPlayersAround(entity.x, entity.y, entity.z, ANIMATION_VIEW_DISTANCE, entity.level().dimension())
    }
}
