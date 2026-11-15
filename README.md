![Reapwright Banner](assets/header3.png)

# Reapwright

**Reapwright** is a lightweight, near-vanilla farming, forestry, and mining quality-of-life plugin for **Paper servers**. Harvest mature crops with a configured hoe and automatically replant them, or optionally fell natural trees, huge mushrooms, and Nether fungi with an axe. Crop harvesting supports configurable areas from **1×1 to 9×9**; Tree Feller and Mining are both **disabled by default**.

> [!IMPORTANT]
> **2.1.0-RC1 (Release Candidate).** Intended for controlled testing. Back up your world and inventories before testing Tree Feller. WorldGuard/CoreProtect integrations and recovery from unexpected mid-harvest failures have not been fully verified.

## Features

### Crop harvesting

- **One-click harvest and replant:** Right-click a fully mature supported crop with a configured hoe; immature crops are ignored.
- **Configurable area:** Select a square from 1×1 to 9×9 (up to 81 blocks).
- **Smart crop selection:** Evaluates eligible squares containing the clicked crop and favours the densest eligible connected group, regardless of player facing direction.
- **Same-crop adjacency:** Only mature crops of the same kind, linked by north/south/east/west adjacency **within the chosen square** and at the same Y level, are harvested.
- **Resource-backed replanting:** Reserves a planting item from harvested drops, then optionally the player's inventory. Without a planting item, that crop is not harvested.
- **Fortune:** Requests native crop drops with the hoe; optionally adds 0 through Fortune level extra wheat or beetroot produce. Vanilla seed bonuses remain.
- **Durability and Unbreaking:** One damage attempt per harvested crop, modified by Unbreaking. Creative crop harvesting does not consume durability.
- **Mending:** Works through normal XP collection; crop harvesting does not generate extra Mending XP.
- **Inventory handling:** Delivers drops to inventory by default, with overflow dropped at the crop; can instead drop all items at the crop.
- **Visual feedback:** Optional `HAPPY_VILLAGER` particles and one sound per harvesting action.
- **Poisonous potatoes:** Optional manual/hopper composting and brewing of Poison Potions from Awkward Potions.

### Tree Feller (opt-in)

- **All vanilla axe tiers:** Wooden, Stone, Copper, Iron, Golden, Diamond, and Netherite axes can fell trees when eligible and sufficiently durable.
- **Natural tree support:** Oak (including large/Fancy Oak), Birch, Spruce (including giant forms), Jungle (including giant forms), Acacia, Dark Oak, Pale Oak, Cherry, Poplar, Azalea/Flowering Azalea, and Mangrove (including tall forms).
- **Offset trunks:** Detection follows staggered Acacia and Azalea trunks, including consecutive diagonal steps in Acacia.
- **Other large fungi:** Huge Red and Brown Mushrooms, Crimson fungi, and Warped fungi.
- **Tree-aware harvesting:** Attempts to keep nearby trees and player-built structures separate; handles appropriate foliage, vines, moss, roots, snow, Cocoa Pods, Shelf Mushrooms, hanging Propagules, Nether growth, and Shroomlights.
- **Optional Domino:** Can additionally fell nearby separate eligible trees; off by default.
- **Replanting:** Optional sapling replanting using eligible drops then inventory, including 2×2 planting. A configurable Silk Touch exception allows free sapling placement (enabled by default); huge Nether fungi use their separate free-replant logic.
- **Sapling rolls:** Per-harvested-leaf rates; standard tree rates default to 5%, with Mangrove at **1.0%**. Fortune modifies applicable rates. Naturally hanging Propagules have separate maturity-based drops.
- **Bonus loot:** Independent item-specific percentage rolls once per *completely harvested tree*, for **Diamond or Netherite axes**. Fortune scales the *quantity* on a successful roll, not its success chance. Species-specific tables are fully configurable.
- **Durability safeguards:** Unbreaking rolls for log/stem wear; insufficient remaining durability triggers a clickable **Break Axe Anyway** acknowledgement. Accepting can break the axe and leave a partial tree, without full-tree bonus loot or automatic replanting.
- **Bee Nests and Beehives:** With Silk Touch, the hive item retains resident bees and honey level; without Silk Touch, honeycombs drop and resident bees emerge angry. Nearby bees associated with the hive become aggressive either way.
- **Creaking Hearts:** Silk Touch preserves the Heart item; otherwise the Heart drops Fortune-scaled Resin Clumps, with natural-generation XP handling.
- **State-sensitive drops:** Cocoa Pods, Shelf Mushrooms, and Mangrove Propagules are harvested according to their growth and attachment states.
- **Chat quality of life:** White brackets and light-green `Reapwright` prefix; concise axe-break notifications; a **10-second** default per-player cooldown for repeated identical notices. Detailed diagnostics are off by default.

### Mining (opt-in)

- **Per-block bonus loot:** Eligible breaks using Diamond or Netherite Pickaxes without Silk Touch roll each configured reward independently. Fortune increases the quantity of successful rewards.
- **Separate stone and ore tables:** Configure rewards by dimension or replace the dimension tables for a named world.
- **Veinminer support:** Optional integration with Veinminer 2.14.0 for additional blocks, including servers using the Veinminer Enchantment companion.

## Poisonous potatoes

### Composting

Poisonous potatoes can be added to composters manually or transferred by hoppers. Each attempt consumes a poisonous potato and has a configurable chance (65% by default) of raising the compost level. Normal compostable items remain unaffected. Set `poisonous-potatoes.composting.enabled: false` to disable.

### Brewing

Use a poisonous potato in a Brewing Stand ingredient slot to turn up to three Awkward Potions into **Potions of Poison**. This uses normal Blaze Powder fuel and a 400-tick (20-second) brewing process. Vanilla spider-eye brewing remains available. Set `poisonous-potatoes.brewing.enabled: false` to disable.

## Supported crops

| Crop block (`enabled-crops`) | Replanting item |
| --- | --- |
| `WHEAT` | `WHEAT_SEEDS` |
| `CARROTS` | `CARROT` |
| `POTATOES` | `POTATO` |
| `BEETROOTS` | `BEETROOT_SEEDS` |
| `NETHER_WART` | `NETHER_WART` |

All crops must be fully mature. Adding arbitrary material names to `enabled-crops` does not add new replanting mappings.

## Requirements

- **Server:** Paper 26.3 (project targets Paper API `26.3.build.244-stable`)
- **Java:** JDK/Java 25
- **Build:** Maven (3.9+ recommended)
- **Folia:** Not supported (`folia-supported: false`)
- **Optional integrations:** Veinminer 2.14.0 for Mining. WorldGuard and CoreProtect have experimental Tree Feller integrations.

## Installation

1. Download or build `reapwright-2.1.0-RC1.jar`.
2. Stop Paper, remove the older Reapwright JAR, and place the new JAR in `plugins/`.
3. Start Paper. Reapwright creates missing config files in `plugins/Reapwright/` and migrates supported legacy configs. See [Configuration](#configuration) for upgrade details.
4. Enable Tree Feller in `config-treefeller.yml` or Mining in `config-mining.yml` as desired. Review the Tree Feller integration settings if your server uses WorldGuard or CoreProtect.
5. Run `/reapwright reload` after editing the YAML files.


## Usage

### Crops

Hold a configured **Diamond or Netherite Hoe** in your main hand and right-click a mature supported crop. The default 1×1 area harvests just that crop; `harvest-area-size: 3` can harvest up to nine same-type, connected mature crops within the selected 3×3 area.

### Tree Feller

When `tree-feller.enabled: true`, use a supported axe to break a natural tree trunk or other supported fungal stem/base. Reapwright evaluates the tree, its attachments, terrain, permissions and axe durability before applying automated harvesting. Tree detection may conservatively refuse ambiguous or player-modified trees; if it skips a harvest, the targeted block can be mined normally.

If the axe would break during the planned harvest, the player can choose the clickable **Break Axe Anyway** acknowledgement (expires after 15 seconds). The axe may be consumed and the remaining tree left standing; no full-tree bonus loot or automatic replanting is awarded for an incomplete harvest.

### Example Tree Feller Fortune bonus-loot scaling

For a successfully rolled bonus entry with a base quantity of 4 items, Fortune III's default +75% quantity bonus yields 7 items. The **configured chance** of that entry remains unchanged. Fortune 0/I/II/III quantity multipliers are 1.00× / 1.25× / 1.50× / 1.75×. Fractional bonus quantities use randomized rounding.

## Configuration

Reapwright keeps these files under `plugins/Reapwright/`. The linked source files contain the complete defaults.

| File | Settings |
| --- | --- |
| [`config.yml`](src/main/resources/config.yml) | Crop harvesting and poisonous-potato composting and brewing. |
| [`config-treefeller.yml`](src/main/resources/config-treefeller.yml) | Tree Feller, replanting, sapling rates, bonus loot, and integration settings. |
| [`config-mining.yml`](src/main/resources/config-mining.yml) | Mining eligibility, Fortune, and stone and ore reward tables. |

**Upgrading from an older build.** Reapwright backs up the original main config as `config.yml.before-treefeller-migration.bak` before moving a legacy `tree-feller:` section to `config-treefeller.yml`. Values already in the dedicated file take precedence. Missing Tree Feller settings are filled from the legacy section and then the bundled defaults. Custom sapling rates and bonus tables, including explicitly empty tables, are treated as complete. When the corresponding dedicated file is missing, Reapwright can also copy `reapwright-module-treefeller.yml`, `reapwright-module-mining.yml`, or `mining-drops.yml`. Existing module settings remain in effect after upgrading, including an administrator's saved `enabled` value.

**Version comments.** The top of each config records the Reapwright version that created it and the version that last wrote it. An unversioned older file shows `unknown (legacy file)` as its creation version. Edits by an administrator leave those comments as they are.

### Crop harvesting and poisonous potatoes

```yaml
allowed-tools:
  - DIAMOND_HOE
  - NETHERITE_HOE
enabled-crops:
  - WHEAT
  - CARROTS
  - POTATOES
  - BEETROOTS
  - NETHER_WART
use-inventory-for-replanting: true
insert-drops-into-inventory: true
consume-durability: true
fortune-boost-produce: true
harvest-area-size: 1
harvest-effects:
  particles: true
  sound: true
poisonous-potatoes:
  composting:
    enabled: true
    chance: 0.65
  brewing:
    enabled: true
```

| Setting | Default | Description |
| --- | --- | --- |
| `allowed-tools` | Diamond/Netherite Hoe | Crop-harvesting tools (distinct from Tree Feller axes). |
| `enabled-crops` | Five listed crops | Only supported mature crops have functioning replant mappings. |
| `use-inventory-for-replanting` | `true` | Consume a planting item from inventory if crop drops lack one. |
| `insert-drops-into-inventory` | `true` | Insert drops; spill overflow. Set `false` to drop them at the crop. |
| `consume-durability` | `true` | Apply crop-harvesting durability attempts. |
| `fortune-boost-produce` | `true` | Add extra wheat/beetroot beyond native drops. |
| `harvest-area-size` | `1` | Square side length, 1–9. |
| `harvest-effects.particles` | `true` | Show green harvest particles. |
| `harvest-effects.sound` | `true` | Play one harvest sound per action. |
| `poisonous-potatoes.composting.enabled` | `true` | Enable poisonous potato composting, including hoppers. |
| `poisonous-potatoes.composting.chance` | `0.65` | Chance (0.0–1.0) to increase compost level. |
| `poisonous-potatoes.brewing.enabled` | `true` | Enable poisonous-potato brewing. |

### Tree Feller

```yaml
# Existing full configuration contains species-specific sapling rates and bonus tables.
tree-feller:
  enabled: false
  integrations:
    worldguard: false
    coreprotect: false
  auto-replant: true
  free-silk-touch-replant: true
  break-leaves: true
  orphan-leaf-cleanup: true
  mangrove-debug: false
  debug-messages: false
  message-repeat-cooldown-seconds: 10
  domino:
    enabled: false
    max-additional-trees: 2
  sapling-drops:
    enabled: true
    rates:
      OAK: 5.0
      BIRCH: 5.0
      SPRUCE: 5.0
      JUNGLE: 5.0
      ACACIA: 5.0
      DARK_OAK: 5.0
      PALE_OAK: 5.0
      CHERRY: 5.0
      POPLAR: 5.0
      AZALEA: 5.0
      FLOWERING_AZALEA: 5.0
      MANGROVE: 1.0
  bonus-loot:
    enabled: true
    fortune-quantity-bonus-per-level: 0.25
    eligible-tools:
      diamond: true
      netherite: true
    # Preserve your existing species and item tables under bonus-loot.trees.
```

| Setting | Default | Description |
| --- | --- | --- |
| `tree-feller.enabled` | `false` | Master opt-in for automated tree harvesting. |
| `tree-feller.integrations.worldguard` | `false` | Enable experimental WorldGuard harvest preflight; see safety notes below. |
| `tree-feller.integrations.coreprotect` | `false` | Enable experimental CoreProtect block-change logging; see safety notes below. |
| `tree-feller.auto-replant` | `true` | Replant harvested trees where viable and permitted. |
| `tree-feller.free-silk-touch-replant` | `true` | Free replacement saplings for eligible Silk Touch axe harvests; creates no inventory items. |
| `tree-feller.break-leaves` | `true` | Include eligible leaves/foliage in harvesting. |
| `tree-feller.orphan-leaf-cleanup` | `true` | Run bounded delayed leaf/vine cleanup passes. |
| `tree-feller.mangrove-debug` | `false` | Enable specialized Mangrove diagnostics. |
| `tree-feller.debug-messages` | `false` | Display verbose per-harvest chat diagnostics. |
| `tree-feller.message-repeat-cooldown-seconds` | `10` | Suppress identical notices per player for 0–60 seconds; `0` disables. Interactive axe confirmation is not suppressed. |
| `tree-feller.domino.enabled` | `false` | Allow follow-on harvesting of nearby separate trees. |
| `tree-feller.domino.max-additional-trees` | `2` | Upper limit for additional trees when Domino is enabled. |
| `tree-feller.sapling-drops.enabled` | `true` | Use Reapwright's per-leaf planting-item rates; `false` falls back to vanilla sapling drops. |
| `tree-feller.sapling-drops.rates.<SPECIES>` | Mostly `5.0`; Mangrove `1.0` | Percentage for each eligible harvested leaf, prior to Fortune scaling. |
| `tree-feller.bonus-loot.enabled` | `true` | Enable independent per-tree bonus-loot rolls on completed harvests. |
| `tree-feller.bonus-loot.eligible-tools.diamond` | `true` | Allow all Diamond Axes to receive bonus loot (Fortune III not required). |
| `tree-feller.bonus-loot.eligible-tools.netherite` | `true` | Allow all Netherite Axes to receive bonus loot. |
| `tree-feller.bonus-loot.fortune-quantity-bonus-per-level` | `0.25` | Additional quantity multiplier per Fortune level, 0.0–1.0; drop chance stays unchanged. |
| `tree-feller.bonus-loot.trees.<SPECIES>.<ITEM>` | Species-specific | Each entry has `chance` (percent), `min` and `max` (inclusive); independent once per completed tree. |

**Harvest area examples:** `1` = 1×1 (1 crop), `2` = 2×2 (4), `3` = 3×3 (9), `5` = 5×5 (25), and `9` = 9×9 (81). These are maximums, not guaranteed harvest counts.

**Sapling scaling:** Fortune I / II / III multiplies Reapwright's configured leaf-based planting-item chances by 1.25× / approximately 1.667× / 2×, respectively. A hanging Mangrove Propagule's mature-block drop is independent of the 1.0% leaf rate.

**Bonus loot entries** are keyed by species such as `OAK`, `JUNGLE`, `MANGROVE`, `RED_MUSHROOM`, `BROWN_MUSHROOM`, `CRIMSON` and `WARPED`. Each configured item is rolled separately once per completely harvested tree. For example:

```yaml
# Example entry (the complete defaults contain additional species/items):
tree-feller:
  bonus-loot:
    trees:
      OAK:
        HONEYCOMB: {chance: 15.0, min: 1, max: 5}
```

### Mining

In `config-mining.yml`, set `enabled: true` and run `/reapwright reload`. The `bonus-loot.enabled` switch controls rewards separately. A material must be in `enabled-blocks` and have a reward in its active table.

| Setting | Purpose |
| --- | --- |
| `drops.overworld`, `drops.nether`, `drops.end` | Stone and related block rewards by dimension. Netherrack and End Stone start with empty tables. |
| `ore-drops.overworld`, `ore-drops.nether` | Ore reward tables. The bundled tables cover standard and deepslate ores, Nether Gold Ore, and Nether Quartz Ore. |
| `world-drops.<world>` | Replaces both dimension tables for the exact world name. Use an empty mapping to disable rewards for that world. |
| `fortune-quantity-multipliers` | Mining defaults are Fortune I ×1.25, II ×1.667, and III ×2.0. |

Each reward uses `chance` (percent), `min`, and `max`. Paper or Veinminer supplies the usual block drops and XP. Silk Touch skips Reapwright bonuses. Ancient Debris also skips bonuses, including rewards specified in older configs. Player-placed or generator-produced stone can qualify by material.

**Veinminer.** The starting block uses Paper's normal drop event. Each eligible additional block rolls its bonus using the starting pickaxe's Fortune and Veinminer's drop location. The starting break must be identifiable within five minutes of server ticks. Configure the block in Veinminer as well as Reapwright. If Veinminer Enchantment is installed, its enchantment requirement still governs veinmining.

## Commands and permissions

| Command | Permission | Description |
| --- | --- | --- |
| `/reapwright reload` | `reapwright.admin` | Reload all three Reapwright config files without a restart. |
| `/reapwright tree debug` | `reapwright.admin` | In-game tree inspection of the block being looked at (within 12 blocks); Tree Feller must be enabled. |
| `/reapwright tree bypass <token>` | Single-use confirmation token | Internal command invoked by the **Break Axe Anyway** chat link; player, tool, tree and expiration are validated. It is not a general administrative bypass. |

| Permission | Default in `plugin.yml` | Description |
| --- | --- | --- |
| `reapwright.use` | `true` (all players) | Harvest supported crops with configured hoes. |
| `reapwright.treefeller` | `true` (all players) | Use Tree Feller when globally enabled and safety checks pass. |
| `reapwright.mining` | `true` (all players) | Receive Mining bonus loot when the module and rewards are enabled. |
| `reapwright.admin` | `op` | Reload configuration and use the tree inspection command. |

Manage permissions with LuckPerms or another Bukkit-compatible permissions plugin, if desired. For example:

```text
/lp group default permission set reapwright.use true
/lp group default permission set reapwright.treefeller true
/lp group admin permission set reapwright.admin true
```

The permission defaults above mean ordinary players already have `reapwright.use` and `reapwright.treefeller` unless another permissions configuration denies them. Granting `reapwright.treefeller` does **not** override `tree-feller.enabled: false`, WorldGuard checks, or the integration safeguards.

## Fortune, Silk Touch, durability and Mending

**Crop Fortune:** Native crop drops are calculated using the held hoe. `fortune-boost-produce` optionally adds extra wheat/beetroot, without replacing vanilla seed bonuses.

**Tree Fortune:** Adjusts configured sapling-drop chances and increases successful bonus-loot quantities. It does **not** raise the configured bonus-loot entry probabilities. Creaking Heart Resin Clump maximums also scale with Fortune.

**Silk Touch:** Affects the special-block drops of Bee Nests/Beehives and Creaking Hearts; a configurable exception can replant eligible saplings for free. Silk Touch takes precedence over Fortune for Heart item preservation.

**Tree durability and Unbreaking:** One Unbreaking wear roll per removed log/stem; foliage and attachments do not add axe wear. Without acknowledgement, an axe that would break is protected. With **Break Axe Anyway**, the axe can break partway through the tree. Chance of wear per log/stem at Unbreaking 0/I/II/III is 100% / 50% / 33.3% / 25%.

**Mending:** Reapwright does not artificially repair tools. Mending works through normal XP-orb pickup (including any applicable naturally generated Creaking Heart XP).

## WorldGuard and CoreProtect compatibility

Tree Feller's optional integrations are experimental. Live region-boundary, logging, and rollback tests are pending. Both options default to `false` in `config-treefeller.yml`.

```yaml
tree-feller:
  integrations:
    worldguard: false
    coreprotect: false
```

**Fail-closed default:** If WorldGuard or CoreProtect is installed but its corresponding integration is `false`, Reapwright refuses automated Tree Feller harvesting rather than proceeding without the expected protection or audit trail. If neither plugin is installed, no integration opt-in is needed. Crop harvesting is separate and remains available.

If testing with one of these plugins installed, back up the world, explicitly enable the matching toggle, reload Reapwright, and test in a disposable area. WorldGuard is intended to validate all planned block changes; CoreProtect is intended to attribute changed blocks and replanting to the initiating player. Neither integration should be considered production-certified until tested on your server.

**Important crop caveat:** Area crop harvesting does not independently verify every neighbouring crop against a region-management plugin; the initial interaction's cancellation is honoured, but claims/protection setups require additional testing.

**Mining compatibility.** Mining compatibility with WorldGuard is untested. Use at your own risk. CoreProtect block rollbacks do not undo awarded Mining bonuses.

## Known limitations

- **Partial failures:** Unexpected mid-harvest errors do not have atomic or best-effort rollback; partial world changes are possible.
- **Voluntary axe breaking:** Acknowledged axe consumption intentionally permits incomplete trees and suppresses full-tree rewards/replanting.
- **Region/audit integrations:** WorldGuard and CoreProtect runtime validation and rollback tests have been deferred.
- **Conservative detection:** Closely grown trees, atypical branches, structures and neighbouring foliage can be ambiguous; Tree Feller may decline harvesting or leave isolated blocks.
- **Terrain and replanting:** Replanting depends on valid terrain and planting positions; 2×2 trees cannot always be replanted on uneven ground.
- **Custom durability mechanics:** Reapwright applies its own wear logic; third-party durability-modification plugins may behave differently.
- **Folia:** Not supported.
- **Release verification:** The Java 25 Maven build and Paper 26.3 startup and reload smoke tests passed. Connected-client Veinminer and live WorldGuard/CoreProtect integration tests are outside that verification.

## Building from source

Run from the project root containing `pom.xml`:

```bash
mvn clean package
```

Expected output: `target/reapwright-2.1.0-RC1.jar`.

The project targets **Java 25** and Paper API `26.3.build.244-stable`. WorldGuard and CoreProtect are optional compile-time `provided` dependencies; they are not bundled with Reapwright. Refer to [`RELEASE-NOTES-2.0.0-RC1.md`](RELEASE-NOTES-2.0.0-RC1.md) for the earlier 2.0.0 release candidate summary and [`docs/development/`](docs/development/) for historical development notes.

## Project information

- **Plugin:** Reapwright
- **Version:** 2.1.0-RC1
- **Author:** iruixos
- **Platform:** Paper
- **Package:** `com.iruixos.reapwright`
- **Notes:** This project contains AI-generated code and has been tested by a human on a real PaperMC server :)

## License

Reapwright is licensed under the MIT License. See the project's [LICENSE](LICENSE) file for details.
