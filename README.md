![Reapwright Banner](assets/header2.png)

# Reapwright

**Reapwright** is a lightweight farming quality-of-life plugin for **Paper servers**. Right-click a fully grown crop with a diamond or netherite hoe to harvest it and immediately replant it, using a planting item from the harvested drops or, if necessary, your inventory. Configure the harvest area from **1×1 to 9×9**.

## Features

- **One-click harvest and replant:** Right-click a mature crop with a supported hoe; immature crops are ignored.
- **Configurable area harvesting:** Choose a square from 1×1 (one crop) through 9×9 (up to 81 crops).
- **Smart crop selection:** The plugin examines all squares of the configured size containing the clicked crop and chooses the one with the most eligible, connected crops. Selection does not depend on player facing direction.
- **Same-crop adjacency:** Only mature crops of the clicked type, connected by north/south/east/west neighbors **within the selected square**, are harvested. Diagonal-only connections do not count. Crops must be on the same Y level.
- **Automatic replanting:** Uses one planting item from each crop's drops first; optionally falls back to the player's inventory. If neither provides an item, that crop remains unharvested.
- **Fortune:** Uses native block drops with the enchanted hoe. Optionally adds extra **wheat** or **beetroot** produce (0 to Fortune level) on top of vanilla drops; vanilla seed bonuses remain.
- **Durability and Unbreaking:** Each successfully harvested crop attempts to use one durability point, with Unbreaking reducing the chance. Creative mode does not consume durability.
- **Mending:** Not overridden; vanilla Mending still repairs eligible tools when XP is collected. Harvesting does **not** generate XP for Mending.
- **Inventory delivery:** Sends harvested items to inventory by default; overflow drops at the crop. Can be configured to drop all items instead.
- **Harvest feedback:** Green `HAPPY_VILLAGER` particles appear at successfully harvested crops, with one harvesting sound per action. Particles and sound can be toggled independently.
- **Poisonous potato composting:** Compost poisonous potatoes manually or feed them into composters using hoppers, with a configurable success chance (65% by default).
- **Poisonous potato brewing:** Use poisonous potatoes as an alternative to spider eyes when brewing Potions of Poison from Awkward Potions. Vanilla brewing remains available.

## Poisonous potatoes

### Composting

Poisonous potatoes can be added to composters by players **or transferred automatically by hoppers**. Each attempt consumes one poisonous potato and uses the configured chance of increasing the compost level (65% by default). Ordinary compostable items continue to behave normally. Disable this feature with `poisonous-potatoes.composting.enabled: false`.

### Brewing

Place a poisonous potato in a Brewing Stand ingredient slot to brew **Potions of Poison** from up to three Awkward Potions. The custom recipe uses normal brewing fuel and progress feedback; existing vanilla recipes, including spider-eye brewing, remain supported. Disable this feature with `poisonous-potatoes.brewing.enabled: false`.

## Supported crops

| Crop block (`enabled-crops`) | Item used for replanting |
| --- | --- |
| `WHEAT` | `WHEAT_SEEDS` |
| `CARROTS` | `CARROT` |
| `POTATOES` | `POTATO` |
| `BEETROOTS` | `BEETROOT_SEEDS` |
| `NETHER_WART` | `NETHER_WART` |

All supported crops must be fully grown. Other crops are not implemented by the current replanting logic, even if their material names are added to the configuration.

## Requirements

- **Server:** Paper 26.3 (the project targets Paper API `26.3.build.244-stable`)
- **Java:** Java 25 or newer for building and running the target server
- **Build:** Maven
- **Folia:** Not supported (`folia-supported: false`)

## Installation

1. Build the plugin from source (see below), or obtain a compiled release JAR.
2. Stop your Paper server and place the downloaded Reapwright `.jar` file in the `plugins/` directory.
3. Remove any older Reapwright JAR so only one version is installed.
4. Start the server to generate `plugins/Reapwright/config.yml`.
5. Adjust the configuration and run `/reapwright reload` (or restart the server).

## Usage

Hold a **diamond** or **netherite hoe** in your main hand and **right-click a fully grown supported crop**. Reapwright harvests the eligible crops in the selected area, replants each one, and delivers the remaining drops. Successful harvesting also produces optional green particles and a sound.

For example, setting `harvest-area-size: 3` allows harvesting up to nine connected mature crops of the same type in one action. The clicked crop is always included; the chosen 3×3 square may shift toward a denser patch instead of remaining centered on the clicked block. Only crops connected within that square are included.

## Configuration

File: `plugins/Reapwright/config.yml`

```yaml
# Tools that can trigger harvesting.
allowed-tools:
  - DIAMOND_HOE
  - NETHERITE_HOE

# Supported crop blocks to enable.
enabled-crops:
  - WHEAT
  - CARROTS
  - POTATOES
  - BEETROOTS
  - NETHER_WART

# If drops don't contain a planting item, try the player's inventory.
use-inventory-for-replanting: true

# Put remaining drops in inventory; drop overflow on the ground.
insert-drops-into-inventory: true

# Apply one durability attempt per successfully harvested crop.
consume-durability: true

# Add 0..Fortune level bonus wheat/beetroot produce per crop.
fortune-boost-produce: true

# Maximum square side length, clamped to 1..9.
harvest-area-size: 1

# Harvesting feedback (particles at crops; one sound per action).
harvest-effects:
  particles: true
  sound: true

# Additional uses for poisonous potatoes.
poisonous-potatoes:
  composting:
    enabled: true
    chance: 0.65
  brewing:
    enabled: true
```

| Setting | Type | Default | Description |
| --- | --- | --- | --- |
| `allowed-tools` | List of materials | `DIAMOND_HOE`, `NETHERITE_HOE` | Main-hand tools that activate the plugin. |
| `enabled-crops` | List of materials | Wheat, carrots, potatoes, beetroot, nether wart | Crop block types eligible for harvesting. Only the five crops in the supported-crops table have replanting mappings. |
| `use-inventory-for-replanting` | Boolean | `true` | When harvested drops lack a planting item, consume one from the player's inventory. If `false` and drops lack a planting item, leave that crop untouched. |
| `insert-drops-into-inventory` | Boolean | `true` | Add remaining drops to inventory and drop overflow at the crop; if `false`, drop all remaining items at the crop. |
| `consume-durability` | Boolean | `true` | Attempt durability damage for each harvested crop (except in Creative mode). |
| `fortune-boost-produce` | Boolean | `true` | Add a uniformly random 0 through Fortune level extra wheat or beetroot per harvested crop. Vanilla loot drops, including seed bonuses, remain in effect. |
| `harvest-area-size` | Integer | `1` | Side length of the maximum harvesting square, from 1 to 9 inclusive. Out-of-range values are clamped to the nearest limit. |
| `harvest-effects.particles` | Boolean | `true` | Show green particles at successfully harvested crops. |
| `harvest-effects.sound` | Boolean | `true` | Play one harvesting sound per successful harvesting action. |
| `poisonous-potatoes.composting.enabled` | Boolean | `true` | Allow manual and hopper-fed poisonous potato composting. |
| `poisonous-potatoes.composting.chance` | Decimal | `0.65` | Probability (0.0–1.0) of raising the composter level per poisonous potato consumed. |
| `poisonous-potatoes.brewing.enabled` | Boolean | `true` | Allow poisonous potatoes as an alternative brewing ingredient for Potions of Poison. |

### Harvest area examples

| Value | Square | Maximum harvested crops |
| --- | --- | --- |
| `1` | 1×1 | 1 |
| `2` | 2×2 | 4 |
| `3` | 3×3 | 9 |
| `5` | 5×5 | 25 |
| `9` | 9×9 | 81 |

These are **maximums**, not guaranteed harvest counts. Crops must be mature, the same type, and orthogonally connected to the clicked crop inside the chosen square. Each crop must also have an available replanting item.

## Commands and permissions

| Command | Permission | Description |
| --- | --- | --- |
| `/reapwright reload` | `reapwright.admin` | Reloads configuration without a restart. |

| Permission | Default | Description |
| --- | --- | --- |
| `reapwright.use` | `true` (all players) | Allows right-click harvesting with configured hoes. |
| `reapwright.admin` | `op` | Allows use of `/reapwright reload`. |

Permissions can be managed with LuckPerms or another compatible permissions plugin. No external permissions plugin is required.

## Fortune, durability, and Mending details

**Fortune:** The plugin requests the server's native crop drops using the held hoe. Vanilla Fortune primarily increases wheat and beetroot **seeds**, so the optional `fortune-boost-produce` feature adds extra wheat or beetroot as well. For Fortune III, the additional produce is uniformly selected from **0, 1, 2, or 3** per crop (1.5 extra on average). This is *in addition to* native drops, not a replacement for seed bonuses.

**Replanting:** One planting item is reserved before the rest of the drops are given to the player. If drops do not supply one, the plugin can consume an item from the player's inventory. No crop is harvested without a replanting item.

**Unbreaking:** Each successful harvest attempts one durability point. With Unbreaking level *n*, the implemented chance of losing a durability point is `1 / (n + 1)`. The plugin handles this manually; it does not use the full native item-damage pipeline or honor every custom durability modifier.

**Mending:** Reapwright does not directly repair the hoe or grant harvest XP. Mending continues to work through ordinary XP pickup.

## Building from source

From the project root (containing `pom.xml`):

```bash
mvn clean package
```

The resulting JAR should be at `target/reapwright-VERSION.jar`.

Change the JDK path to match your installation. The Maven project compiles for **Java 25**.

## Known limitations and compatibility

- **Protection plugins:** Reapwright respects cancellation of the initial right-click event, but **does not independently verify build/break permissions for every neighboring crop**. Test carefully before using area harvesting in claimed or protected regions.
- **Same level only:** The harvest square does not extend vertically.
- **No diagonal-only connections:** Only north/south/east/west connectivity is counted.
- **No free harvests:** A planting item is required for every harvested crop.
- **Durability handling:** Tool damage is applied manually, so custom durability mechanics and native break effects may not be fully preserved.
- **Folia:** Not supported.
- **No automated Paper integration test suite** is included in the current source package.

## Project information

- **Plugin:** Reapwright
- **Author:** iruixos
- **Platform:** Paper
- **Package:** `com.iruixos.reapwright`
- **Notes:** This project contains AI-generated code, but was tested by a human in a real PaperMC server :)

## License

Reapwright is licensed under the MIT License.
See the [LICENSE](LICENSE) file for details.
