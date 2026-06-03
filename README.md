# Dark Nights

A Fabric mod for Minecraft 26.1.2 that adds supernatural curse mechanics to survival play — lycanthropy, zombie plague, vampirism, and more. Designed for a family survival server where the moon matters and every night carries risk.

**Required on both client and server.** Single jar, no mixins.

---

## Current Features

### Blood Moon

A rare nightly event (5% chance, never on a full moon) that raises the stakes for anyone caught outside.

- Broadcasts a server-wide warning when it begins and ends
- All outdoor players receive **Weakness I** for the duration
- Nearby hostile mobs gain **Strength I** and **Speed I** every 30 ticks
- Extra zombies and skeletons spawn near outdoor players every 60 ticks
- Client-side: red crimson-spore particles fall on players caught outside
- Lycanthropes also transform during a blood moon (configurable)

### Lycanthropy (Werewolf Curse)

Players can be cursed with lycanthropy. Once cursed, they transform automatically on full-moon nights when outdoors.

**Contracting the curse:**
- Eating **Raw Wolf Meat** (80% chance by default) — drops from wolves at 40%
- Being struck by a transformed werewolf player (15% chance per hit)
- Admin command: `/darknights curse`

**Transformation (full moon or blood moon, outdoors, night):**

| Stat | Change |
|------|--------|
| Max Health | +20 HP |
| Attack Damage | +7 |
| Movement Speed | +0.06 |
| Armor | +12 |
| Armor Toughness | +4 |
| Knockback Resistance | +0.4 |
| Attack Knockback | +2 |
| Jump Strength | +0.3 |
| Attack Speed | +1.0 |

All modifiers are transient — removed cleanly on revert.

**While transformed:**
- Night Vision and Regeneration II sustained throughout the night
- Constant Hunger I drain
- Cannot use weapons, tools, or mine blocks (claw restriction)
- **Howl** (right-click with empty hand): debuffs nearby hostiles with Slowness II + Weakness, buffs owned tame wolves with Strength II + Speed II. 90-second cooldown.

**At dawn (revert):**
- All modifiers removed, health clamped to 20 HP
- Slowness I + Weakness I for 30 seconds
- `lunarAge` increments — tracks how many full moons the player has endured

**Persistence:** Curse state, lunar age, and howl cooldown are stored in `SavedData` and survive server restarts.

### Wolf Familiars

Lycanthrope players automatically attract nearby wild wolves as pack members. Pack cap scales with lunar age: `min(lunarAge + 1, 8)`.

- Wild wolves within 10 blocks of a cursed player are bonded silently (no taming animation)
- Bonded wolves follow, protect, and teleport back if they stray beyond 30 blocks
- **Pack disperses** when the player sleeps — each night is a fresh pack
- If a familiar dies, the owner is notified with the remaining pack count on the action bar
- If `enableFactionCombat` is on, pack wolves automatically target zombie-cursed players within 20 blocks

Familiar bonds persist through server restarts and chunk reloads.

### Zombie Plague

A rival curse. Unlike lycanthropy there is no moon cycle — the zombie form is permanent and always active.

**Contracting the plague:**
- Eating **Rotten Flesh** (5% chance by default)
- Eating an **Infected Brain** (50% chance) — a rare drop from Zombie Villagers and Drowned
- Being struck by a zombie-cursed player (10% chance per hit)
- Admin command: `/darknights plague`

**Zombie form (always active):**

| Stat | Change |
|------|--------|
| Max Health | +10 HP |
| Attack Damage | +4 |
| Movement Speed | −0.02 (shambling) |
| Armor | +6 |
| Knockback Resistance | +0.6 |

**Undead properties:**
- Night Vision always active; Regeneration I at night; Weakness I during the day
- Outdoors in sunlight: 0.5 HP/sec passive burn
- Fire damage heals instead of harming — grants Regeneration II
- Instant Health potions deal damage; Harming potions heal
- Rotten flesh is real food — the Hunger debuff is removed immediately
- Food below 6: extra Weakness II until meat or rotten flesh is eaten

**Faction interactions:**
- Werewolf howl applies Slowness + Weakness to zombie players within range
- Pack wolves target nearby zombie players
- Werewolf kills zombie → food reward
- Zombie kills transformed werewolf → Strength I adrenaline

**Cure:** Eat a **Golden Apple** at any time.

### Admin Commands

All commands require gamemaster (OP level 2) permissions.

| Command | Effect |
|---------|--------|
| `/darknights transform` | Toggle werewolf transform on self (curses first if needed) |
| `/darknights curse` | Apply lycanthropy curse to self |
| `/darknights cleanse` | Remove lycanthropy curse and revert transform |
| `/darknights plague` | Apply zombie plague curse to self |
| `/darknights cure` | Remove zombie plague curse from self |

---

## Planned Features

- **Zombie Familiars** — Zombie players command undead (zombie/drowned) that burn at sunrise
- **Vampire's Curse** — Nocturnal power spike, sunlight damage, blood drain mechanic
- **Runic Attunement** — Passive glyphs that modify curse effects
- **Wild Hunt** — Endgame event pitting lycanthropes against the zombie faction

---

## Installation

1. Download `dark-nights-<version>.jar`
2. Place in the `mods/` folder on **both client and server**
3. Restart — config generates at `config/darknights.json`

**Requirements:**
- Minecraft 26.1.2
- Fabric Loader 0.19.2+
- Fabric API 0.149.1+26.1.2

---

## Configuration (`config/darknights.json`)

| Field | Default | Description |
|-------|---------|-------------|
| `enableBloodMoon` | `true` | Enable blood moon events |
| `bloodMoonChance` | `0.05` | Nightly roll chance (0.0–1.0) |
| `enableLycanthropy` | `true` | Enable werewolf curse |
| `infectionChance` | `0.15` | Bite infection chance |
| `wolfMeatInfectionChance` | `0.80` | Raw wolf meat infection chance |
| `transformOnBloodMoon` | `true` | Lycanthropes also transform on blood moon |
| `maxFamiliarCap` | `8` | Hard cap on familiar count |
| `enableFactionCombat` | `true` | Wolf familiars target zombie-cursed players |
| `enableZombiePlague` | `true` | Enable zombie plague curse |
| `rottenFleshInfectionChance` | `0.05` | Rotten flesh infection chance |
| `infectedBrainInfectionChance` | `0.50` | Infected brain infection chance |
| `zombieInfectionChance` | `0.10` | Bite infection spread chance |

---

## Build

```bash
./gradlew build
# Output: build/libs/dark-nights-<version>.jar
```

Java 25 required.
