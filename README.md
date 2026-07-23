# Cobblemon Auto-Battle

> This repository is the NeoForge 1.21.1 port of [viniciusmorenorogato-crypto/CobblemonAutoBattle](https://github.com/viniciusmorenorogato-crypto/CobblemonAutoBattle) v1.4.0. The original author and MIT copyright notice are retained.

**Pokémon Scarlet/Violet "Let's Go" style auto-battles for [Cobblemon](https://cobblemon.com/).**

Send out your Pokémon, press a key, and watch them hunt nearby wild Pokémon on their own — earning XP, EVs and item drops for you, just like the Let's Go mechanic from Pokémon SV. Or aim at a specific wild Pokémon and send your partner after that exact one.

![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-green) ![NeoForge](https://img.shields.io/badge/Loader-NeoForge-orange) ![Cobblemon 1.7.3](https://img.shields.io/badge/Cobblemon-1.7.3-red) ![License MIT](https://img.shields.io/badge/License-MIT-lightgrey)

## Features

- **One-key auto-battle** — press **R** (rebindable) to toggle. If no Pokémon is out, your lead is sent out automatically.
- **Directed hunts** — aim at a wild Pokémon and press **V** (rebindable): the nearest hunter goes after *that one*. Works even with auto-battle off — your lead pops out, completes the mission, and returns to its Poké Ball by itself.
- **Multi-hunter** — *every* Pokémon you have sent out hunts at the same time, each picking its own target (no two hunters gang up on the same wild).
- **SV-style single-strike battles** — one choreographed strike resolves each battle: attack animation wind-up → synchronized impact → the defeated wild plays its real `faint` animation and cries, then despawns.
- **Real movesets matter** — both sides pick their best *actual* move (real power, type and physical/special category, real STAB, full type chart). Speed decides who opens the exchange, and immunity abilities (Levitate, Flash Fire, Water/Volt Absorb, Sap Sipper...) cancel moves of their type.
- **You can lose** — if the simulation says the wild would knock your Pokémon out first, it loses the exchange: the wild counter-attacks, your Pokémon retreats hurt, and the wild survives. Type coverage in your movesets suddenly matters (configurable).
- **Rare Pokémon protection** — shinies, legendaries, mythicals, ultra beasts, paradox forms (plus any custom labels/species you configure) are **never attacked**. Instead you get a chat alert, a bell chime, and the rare glows for a few seconds so you can find it.
- **Victory feedback** — a short 3-note chime only you can hear (SV-style), the defeated species' cry, and quiet, pitch-varied hit sounds.
- **Relaxed pacing** — hunters rest in place for a few seconds after each victory instead of instantly sprinting to the next target.
- **Smart navigation** — target search is anchored to the *player* (configurable), with a leash that brings wandering hunters back (teleporting them if they fall far behind), stable path recalculation, and an anti-stuck system that gives up on unreachable targets.
- **Real model animations** — attackers play their model's `physical`/`special` animation; everything reuses Cobblemon's own posable animation system.
- **Fair targeting** — only wild Pokémon up to your Pokémon's level + a configurable margin are attacked; low-HP hunters retreat (they never faint from auto-battling).
- **Rewards** — XP and EVs (proper species EV yields) for the winner; item drops go **straight to your inventory**.
- **Fully configurable & localized** — 30+ options in `config/autobattle.json`, hot-reloadable in game. English and Português (Brasil); translations welcome!

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | ≥ 21.1 |
| Kotlin for Forge | ≥ 5.12.0 |
| Cobblemon | ≥ 1.7.3 < 1.8.0 |

Install on the client (required for the keybinds). On servers, install on both sides.

## Usage

| Key (rebindable) | Action |
|---|---|
| **R** | Toggle area auto-battle. Sent-out Pokémon hunt eligible wilds near you; press again to stop (Pokémon the mod sent out are recalled). |
| **V** | Directed hunt. Aim at a wild Pokémon (up to 32 blocks) and press: the nearest hunter goes after it. With auto-battle off, this is a one-shot mission — your lead returns by itself afterwards. |

Hunters retreat and sit out when their HP gets low, until recalled and sent out again. Rare/protected Pokémon are announced instead of attacked — both in area mode and when you aim at them directly.

## Commands (permission level 2)

| Command | Effect |
|---|---|
| `/autobattle info` | Show the current configuration |
| `/autobattle reload` | Reload `config/autobattle.json` without restarting |

## Configuration

`config/autobattle.json` is created on first launch and self-heals missing fields after updates.

### Targeting & navigation
| Field | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch |
| `anchorToPlayer` | `true` | Search for targets around the *player* (SV style); `false` anchors to each hunter (free roam) |
| `searchRadius` | `28.0` | Radius (blocks) around the anchor scanned for targets |
| `leashRadius` | `40.0` | Hunters farther than this from you drop their target and return; beyond 1.5× they teleport to you |
| `attackRange` | `2.5` | Gap between hitbox *edges* for the strike to start (both Pokémon's sizes are added automatically) |
| `moveSpeed` | `1.3` | Chase speed multiplier |
| `followPlayerRadius` | `8.0` | Idle hunters follow you beyond this distance |
| `repathIntervalTicks` | `10` | Ticks between path recalculations (prevents jittery walking) |
| `stuckTimeoutTicks` | `60` | No progress toward the target for this long → give up on it |
| `levelMargin` | `5` | Wilds up to `hunter level + margin` are eligible |
| `directedHuntRange` | `32.0` | Max aiming distance for the V key |

### Rare protection
| Field | Default | Description |
|---|---|---|
| `protectRareTargets` | `true` | Master switch for rare protection |
| `protectShiny` | `true` | Never attack shinies |
| `protectedLabels` | `legendary, mythical, ultra_beast, paradox` | Species labels that are protected (other mods' labels work too) |
| `protectedSpecies` | `[]` | Extra protected species by name |
| `rareGlowSeconds` | `10` | Glowing outline on found rares (0 disables) |
| `rareFoundSound` | `true` | Alert bell when a rare is found |

### Battle
| Field | Default | Description |
|---|---|---|
| `attackWindupTicks` | `8` | Delay between attack animation and impact |
| `attackIntervalTicks` | `30` | Minimum ticks between battles |
| `restAfterBattleTicks` | `60` | Hunter stands still this long after each victory (3s) |
| `retreatHealthPercent` | `0.2` | Retreat when HP ratio drops to this value |
| `retaliationMultiplier` | `0.5` | Scale of the battle-cost damage hunters take |
| `allowDefeat` | `true` | Bad matchups can lose the battle (hunter retreats, wild survives) |
| `abilityImmunities` | `true` | Levitate, Flash Fire, Water/Volt Absorb etc. cancel moves of their type |
| `damageScale` | `1.0` | Global damage multiplier |
| `randomizeDamage` | `true` | Apply the classic 85–100% damage roll |

### Effects, sounds & rewards
| Field | Default | Description |
|---|---|---|
| `battleEffects` | `true` | Model animations, particles, knockback and sounds |
| `faintAnimationTicks` | `40` | How long defeated wilds stay down before despawning |
| `victoryJingle` | `true` | 3-note victory chime (only you hear it) |
| `defeatCry` | `true` | Defeated species cries as it faints |
| `hitSoundVolume` | `0.35` | Impact sound volume (0 disables) |
| `awardXp` / `xpMultiplier` | `true` / `1.0` | XP rewards |
| `awardEvs` | `true` | EV rewards (species EV yield) |
| `awardDrops` | `true` | Send the wild's drops to your inventory |
| `announceRewards` | `true` | Chat message per victory |

## Building from source

```bash
./gradlew build
```

Requires JDK 21. The jar is produced in `build/libs/`. For development, `./gradlew runClient` launches a client with Cobblemon already loaded (IntelliJ run configs are generated after a Gradle sync).

The build uses `libs/Cobblemon-neoforge-1.7.3+1.21.1.jar` and `libs/kotlinforforge-5.12.0-all.jar`; place those exact dependencies in `libs/` when building from a fresh clone.

## How it works (technical)

- Server-side brain on NeoForge `ServerTickEvent.Post`; the client only registers keybinds and sends toggle/hunt packets. Directed hunts raycast server-side (no trusting client entity IDs).
- Battles resolve in a single strike: a lightweight simulation picks each side's best *real* move (power/type/category from the actual moveset, real STAB, full type chart, ability immunities), factors Speed into who opens the exchange, and computes how many "rounds" the fight would take — charging the hunter proportional HP, or handing it the loss when the wild would win first. No real `PokemonBattle` is instantiated, so dozens of hunters stay cheap.
- Animations reuse Cobblemon's posable animation packets — the same mechanism official battles use for `recoil` — plus the species' real `faint` animation and cry on defeat.
- Rewards go through Cobblemon's own APIs (`addExperienceWithPlayer`, EV yields, species drop tables) with sidemod-tagged sources, so other mods' event hooks keep working.
- Rare detection uses Cobblemon's official species labels, so datapacks and other mods that label their species are protected automatically.

## License

[MIT](LICENSE) — não afiliado à Cobblemon, Mojang ou The Pokémon Company.

---

## Português (Brasil)

Auto-battle no estilo "Let's Go" de Pokémon Scarlet/Violet para o Cobblemon: aperte **R** e seus Pokémon fora da pokébola caçam selvagens próximos sozinhos, ganhando XP, EVs e drops (direto no seu inventário) — ou mire num selvagem específico e aperte **V** para mandar seu parceiro atrás daquele exato (funciona até com o auto-battle desligado: ele sai, cumpre a missão e volta sozinho pra pokébola).

Cada batalha resolve num único golpe coreografado (animação → impacto → o derrotado grita e cai com a animação real de faint), mas a simulação usa os golpes REAIS do moveset dos dois lados (power/tipo/categoria, STAB de verdade, imunidades de habilidade como Levitate e Flash Fire) e a Speed decide quem abre a troca — matchup ruim pode custar a derrota: seu Pokémon volta machucado e o selvagem sobrevive. Jingle de vitória que só você ouve e descanso de alguns segundos entre abates. Pokémon raros (shiny, lendários, míticos, ultra beasts, paradox e listas customizáveis) **nunca são atacados** — você é avisado no chat, ouve um sino e o raro fica brilhando pra você encontrá-lo. Navegação ancorada em você com coleira anti-fuga e sistema anti-travamento. Configuração completa em `config/autobattle.json` (recarregável com `/autobattle reload`) e mensagens totalmente traduzidas para pt-BR.
