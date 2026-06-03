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
| `/darknights vamp` | Apply vampire curse to self |
| `/darknights drain` | Remove vampire curse from self |
| `/darknights hunt` | Trigger Wild Hunt immediately |

---

### Zombie Familiars

Zombie-cursed players automatically bond nearby undead at night. The horde follows, protects, and burns off at sunrise.

**Bonding:** At night, zombies, zombie villagers, drowned, and husks within 10 blocks are pulled into the horde. Cap: `maxHordeCap` (default 4) from config. Piglins excluded.

**Horde behavior:**
- Follow the owner when not in combat; teleport back if they stray beyond 32 blocks
- No-friendly-fire: familiars will not damage their owner
- At sunrise, bonds are not forcibly broken — horde members burn naturally in sunlight and die; death cleans the bond from `SavedData` automatically
- If `enableFactionCombat` is on, horde members target transformed werewolf players within 15 blocks

**Faction interactions:**
- Zombie horde attacks transformed werewolves on sight (within 15 blocks)
- Pack wolves from the werewolf faction return the favor

---

### Vampire's Curse

A third curse path. Vampires are powerful at night but must manage a blood bar to keep their powers.

**Contracting the curse:**
- Eating a **Vampire Fang** (30% chance) — drops from bats at 10%
- Being struck by a vampire-cursed player (10% chance per hit)
- Admin command: `/darknights vamp`

**Night passives (while blood > 0):**
- Night Vision I, Speed II, Strength I — sustained through the night
- Lost entirely if blood reaches 0

**Blood bar:**
- Drains 0.002 per tick (~8 minutes full → empty)
- Displayed on the action bar (❤ ▓▓▓▓▓░░░░░) whenever below 80%
- At 0: all night passives drop and Weakness I applies around the clock

**Feeding:**
- Right-click a passive mob (cow, sheep, pig, etc.) with bare hands
- The mob takes 2 HP damage; blood bar fills +20%
- Killing any player as a vampire restores +40% blood

**Sunlight damage:**
- 1 HP/sec when outdoors during daylight
- Wearing any helmet negates the burn

**Client effect:** Faint red soul-fire particles drift from the player's eye position each tick (self only).

**Cure:** Craft **Holy Water** (glass bottle + gold ingot + glowstone dust), then use it while standing outdoors during the day.

**Faction interactions:**
- Vampire kills any player → blood surge (+40%)

---

### Runic Attunement

Four elemental rune stones, each conferring passive abilities and a right-click active. Attunement is exclusive — switching runes replaces the previous. Runes are standalone and have no curse prerequisite.

**Crafting (cross pattern: material around a core):**

| Rune | Outer | Core |
|------|-------|------|
| Fire Rune | Blaze Powder | Fire Charge |
| Water Rune | Lapis Lazuli | Prismarine Shard |
| Earth Rune | Obsidian | Emerald |
| Air Rune | Feather | Phantom Membrane |

**How to use:** Right-click the rune to attune. Right-click again to trigger the active ability.

| Element | Passive | Active | Cooldown |
|---------|---------|--------|----------|
| Fire | Fire Resistance + +1 Attack Damage | Ignite the entity you're looking at (5 sec) | 30s |
| Water | Water Breathing + Dolphin's Grace when swimming | Cleanse fire, poison, and wither — gain Regeneration I for 10s | 20s |
| Earth | Slow Fall + Haste I when underground (Y < 32) | Shockwave — knock back all entities within 6 blocks | 45s |
| Air | Speed I | Dash 8 blocks in your look direction | 25s |

Attunement and cooldowns survive server restarts.

---

### The Wild Hunt

A rare nightly event (3% chance by default). Players caught outdoors during the 30-second shelter window are claimed and must survive three waves of supernatural enemies.

**Event flow:**
1. Announcement broadcast: *"✦ THE WILD HUNT RIDES TONIGHT ✦"*
2. 30-second shelter window — players indoors when it closes are safe
3. Claimed players face three waves spawning around them in the overworld

**Wave composition:**

| Wave | Enemies | Notes |
|------|---------|-------|
| 1 | 3× Hunt Riders (30 HP skeletons) | Resistance I, speed boost |
| 2 | 2× Hunt Riders (40 HP) + 2× Hunt Casters (40 HP witches) | Poison and slowness potions |
| 3 | ✦ Skeleton Knight ✦ (80 HP, iron armor + sword) + 2× Hunt Riders (50 HP) | Resistance III, boss |

**Reward:** Surviving all 3 waves drops a **Wild Hunt Medallion**. Right-clicking it permanently grants +1 Attack Damage. Stacks up to 3 times (one per Hunt survived).

At dawn, the Hunt ends and any remaining Hunt mobs are removed.

Admin trigger: `/darknights hunt`

---

## Planned Features

- Polish pass: double jump for Air rune, Vampire Cloak item, `/darknights dismiss` familiar command

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
| `enableVampire` | `true` | Enable vampire curse |
| `vampireFangInfectionChance` | `0.30` | Vampire fang infection chance |
| `vampireAttackInfectionChance` | `0.10` | Vampire bite infection spread chance |
| `enableRunicAttunement` | `true` | Enable runic attunement system |
| `enableWildHunt` | `true` | Enable Wild Hunt events |
| `wildHuntChance` | `0.03` | Nightly roll chance for Wild Hunt |

---

## Build

```bash
./gradlew build
# Output: build/libs/dark-nights-<version>.jar
```

Java 25 required.
