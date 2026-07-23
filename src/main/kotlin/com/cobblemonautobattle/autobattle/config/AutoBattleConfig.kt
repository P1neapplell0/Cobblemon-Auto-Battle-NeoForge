package com.cobblemonautobattle.autobattle.config

import com.google.gson.GsonBuilder
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Config do Auto-Battle. Serializada em config/autobattle.json.
 * Replica a mecânica "Let's Go" de Pokémon SV: o Pokémon líder é solto no mundo e
 * automaticamente caça e enfraquece selvagens próximos, ganhando XP/EVs e drops.
 */
class AutoBattleConfig {
    /** Liga/desliga a feature inteira sem remover o mod. */
    var enabled = true

    // --- Alvo e movimento ---
    /**
     * true = a busca por alvos é ancorada NO JOGADOR (estilo SV: caça o que está perto de você).
     * false = ancorada no próprio Pokémon (ele vagueia caçando; combine com leashRadius maior).
     */
    var anchorToPlayer = true
    /** Raio (blocos) ao redor da âncora onde os caçadores procuram selvagens. */
    var searchRadius = 28.0
    /**
     * Distância máxima do jogador: além dela o caçador abandona o alvo e volta;
     * além de 1.5x ele é teleportado para perto de você (estilo pets vanilla).
     */
    var leashRadius = 40.0
    /**
     * Vão (blocos) entre as BORDAS das hitboxes para o golpe conectar. O alcance real soma
     * automaticamente os raios das caixas dos dois Pokémon — alvos grandes (Onix, Snorlax...)
     * são alcançados sem o caçador precisar "entrar" neles.
     */
    var attackRange = 2.5
    /** Multiplicador de velocidade de deslocamento ao perseguir o alvo. */
    var moveSpeed = 1.3
    /** Quando ocioso (sem alvo), segue o dono se estiver mais longe que isto (blocos). */
    var followPlayerRadius = 8.0
    /** Ticks entre recálculos de rota na perseguição (evita o andar "indeciso"). */
    var repathIntervalTicks = 10
    /** Sem progresso em direção ao alvo por este tempo → desiste dele e tenta outro. */
    var stuckTimeoutTicks = 60

    // --- Elegibilidade ---
    /** Só ataca selvagens cujo level seja <= (level do seu Pokémon + esta margem). */
    var levelMargin = 5

    // --- Proteção de raros (notifica em vez de atacar) ---
    /** Liga a proteção: raros nunca são atacados; você é avisado quando um aparece. */
    var protectRareTargets = true
    /** Protege qualquer Pokémon shiny. */
    var protectShiny = true
    /** Labels de espécie protegidas (labels oficiais do Cobblemon; mods podem adicionar as suas). */
    var protectedLabels = mutableListOf("legendary", "mythical", "ultra_beast", "paradox")
    /** Espécies extras protegidas por nome (ex.: "Mewtwo"), case-insensitive. */
    var protectedSpecies = mutableListOf<String>()
    /** Segundos de contorno brilhante (glowing) no raro encontrado, para você achá-lo. 0 desliga. */
    var rareGlowSeconds = 10
    /** Toca um sino de alerta quando um raro é encontrado. */
    var rareFoundSound = true

    // --- Risco (spec: toma dano e recua, nunca desmaia) ---
    /** Recolhe o Pokémon quando o HP cai para <= esta fração (0.2 = 20%). */
    var retreatHealthPercent = 0.2
    /** Escala do dano que seu Pokémon sofre na batalha simulada (0.5 = metade). */
    var retaliationMultiplier = 0.5

    // --- Combate (1 golpe = 1 batalha, estilo SV) ---
    /**
     * A batalha inteira resolve num único golpe, mas a simulação usa os GOLPES REAIS do
     * moveset dos dois lados (power/tipo/categoria, STAB, efetividade, imunidades de
     * habilidade) e a SPEED decide quem abre a troca. O custo em HP do seu Pokémon é
     * proporcional a quantos "rounds" a luta levaria; matchup ruim pode custar a derrota.
     */
    /** Ticks entre o início da animação de ataque e o impacto (sincroniza visual e dano). */
    var attackWindupTicks = 8
    /** Ticks de descanso entre uma batalha e a próxima (30 = 1.5s). */
    var attackIntervalTicks = 30
    /**
     * Ticks que o caçador fica PARADO no lugar após vencer uma batalha (60 = 3s) antes de
     * partir pro próximo alvo — tira o ritmo de "serial killer" da caça.
     */
    var restAfterBattleTicks = 60
    /** Alcance (blocos) do raycast da caça direcionada (tecla V: caça o Pokémon apontado). */
    var directedHuntRange = 32.0
    /**
     * O caçador pode PERDER a batalha: se a simulação diz que o selvagem nocautearia primeiro,
     * ele sai no limiar de recuo e o selvagem sobrevive. false = sempre vence (só o custo varia).
     */
    var allowDefeat = true
    /** Habilidades de imunidade (Levitate, Flash Fire, Water/Volt Absorb...) anulam golpes do tipo. */
    var abilityImmunities = true
    /** Ajuste global de dano do seu Pokémon (1.0 = padrão). */
    var damageScale = 1.0
    /** Aplica variação aleatória de 85%..100% no dano, como nos jogos. */
    var randomizeDamage = true

    // --- Efeitos e sons ---
    /** Liga animações de modelo, partículas, knockback e sons de batalha. */
    var battleEffects = true
    /**
     * Ticks que o selvagem derrotado fica caído tocando a animação "faint" do modelo antes de
     * sumir (40 = 2s). Só se aplica com battleEffects=true.
     */
    var faintAnimationTicks = 40
    /** Jingle de vitória (3 notas, só você ouve) quando um selvagem é derrotado. */
    var victoryJingle = true
    /** O selvagem derrotado dá o grito (cry) da espécie ao cair, como nos jogos. */
    var defeatCry = true
    /** Volume do som de impacto dos golpes (0.0 desliga; o pitch varia sozinho). */
    var hitSoundVolume = 0.35

    // --- Recompensas ---
    /** Concede XP ao Pokémon participante. */
    var awardXp = true
    /** Multiplicador de XP ganho por vitória. */
    var xpMultiplier = 1.0
    /** Concede os EVs do selvagem derrotado. */
    var awardEvs = true
    /** Concede os drops do selvagem — enviados DIRETO para o inventário do jogador. */
    var awardDrops = true
    /** Manda uma mensagem no chat a cada vitória (Pokémon derrotado + XP). */
    var announceRewards = true
    /**
     * Posta o evento oficial POKEMON_FAINTED do Cobblemon a cada derrota do auto-battle.
     * É isso que faz as caçadas CONTAREM para sistemas que escutam faints — como os
     * Mass Outbreaks do CobblemonDynamicSpawns (e qualquer outro mod do gênero).
     */
    var fireFaintEvents = true

    fun save(path: Path) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { GSON.toJson(this, it) }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("cobblemonautobattle")
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun configPath(): Path = FMLPaths.CONFIGDIR.get().resolve("cobblemonautobattle.json")

        fun load(): AutoBattleConfig {
            val path = configPath()
            val config = try {
                if (Files.exists(path)) {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, AutoBattleConfig::class.java) }
                        ?: AutoBattleConfig()
                } else {
                    AutoBattleConfig()
                }
            } catch (e: Exception) {
                LOGGER.error("Falha ao ler config, usando valores padrão", e)
                AutoBattleConfig()
            }
            // Regrava para materializar campos novos após updates do mod.
            try {
                config.save(path)
            } catch (e: Exception) {
                LOGGER.error("Falha ao salvar config", e)
            }
            return config
        }
    }
}
