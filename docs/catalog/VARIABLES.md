# MKB Built-in Variable Catalog (Authoritative)

Every built-in `%VARIABLE%` exposed by the Macro/Keybind Mod.

**Sources cross-referenced:**
- **ddoerr** = https://mkb.ddoerr.com/docs/variables (full index, ~140 vars, 13 categories).
- **providers** = decompiled `scripting/variable/providers/*.java` — ground truth for which provider computes each var (`storeVariable("NAME", ...)`).
- **Klacaiba** = https://spthiel.github.io/Klacaiba/ — documents the per-**iterator** variables (Player/Teams/Objective/Score iterators) that ddoerr's pages omit. See bottom section.

**Syntax:** referenced in scripts as `%NAME%`. A leading `~` (e.g. `%~ALT%`) means *"state captured at the moment the script started"* (latched), vs the live value. `<name>` in a var means a parameterised suffix (e.g. `%KEY_W%`, `%HIT_facing%`).

**OUR STATUS:** the Fabric host now provides **~108 built-in variables** (of ddoerr's ~140) through the env-provider + trace-action hooks, under both MKB names and descriptive aliases: player vitals / xp / position / facing / state, held + off-hand item, equipped armor (all four pieces), video options + camera + every sound volume, world (biome / time / ticks / rain / day / dimension / difficulty), looking-at `%HIT*%`, ray-trace `%TRACE*%`, live input states (`%SHIFT%` / `%CTRL%` / `%ALT%` / mouse / `%KEY_<name>%`) including latched `%~VAR%`, key-trigger `%KEYID%`/`%KEYNAME%`, vehicle, active `%CONFIG%`, window size, server (name / ip / motd), and current GUI. The rows still marked `missing` were each ground-truthed against MKB + the live client API and fall into four groups, none cleanly client-available: (1) **removed from modern MC** — `%HITDATA%` / `%TRACEDATA%` (block metadata, gone since 1.13); (2) **server-side / not sent to the client** — `%SEED%`, `%MAXPLAYERS%` (MKB itself scrapes the tab-list population string), `%CHUNKUPDATES%` (render-debug internal); (3) **MKB array vars** our scalar `foreach` model doesn't expose per-item — `%SHADERGROUPS[]%` / `%RESOURCEPACKS[]%` / `%SIGNTEXT[]%`; (4) **tied to MKB subsystems we don't reimplement** — `%SCREEN%` / `%SCREENNAME%` (its custom-layout overlay), `%HIT_<name>%` (BlockPropertyTracker), `%SHADERGROUP%` (post-effect pipeline), and `%HITPROGRESS%` (needs a destroy-progress accessor mixin). Each is flagged per row.

Provider key: **P**=Player, **S**=Settings, **W**=World, **I**=Input, **T**=Trace, **G**=GUI/Player. Vars with no decompiled provider literal (e.g. equipped-armor, server) are computed in helper/bridge code or a newer provider — flagged.

---

## Player (provider: VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%PLAYER%` | String | P | Player's name | done |
| `%DISPLAYNAME%` | String | P | Player's display name | done |
| `%UUID%` | String | P | UUID of the player | done |
| `%HEALTH%` | Int | P | Health points (1 heart = 2) | done |
| `%HUNGER%` | Int | P | Hunger points (1 icon = 2) | done |
| `%SATURATION%` | Decimal | P | Saturation level (normally hidden) | done |
| `%ARMOUR%` | Int | P | Armour points (1 icon = 2) | done |
| `%OXYGEN%` | Int | P | Air level (0–300) | done |
| `%LEVEL%` | Int | P | XP level | done |
| `%XP%` | Int | P | XP points into the current level (progress * needed) | done |
| `%TOTALXP%` | Int | P | Total XP points | done |
| `%GAMEMODE%` | String | P | Game mode as string | done |
| `%MODE%` | Int | P | Game mode as number | done |
| `%CANFLY%` | Boolean | P | Whether the player can fly | done |
| `%FLYING%` | Boolean | P | Whether the player is flying | done |
| `%LIGHT%` | Int | P | Light level at current location | done |
| `%VEHICLE%` | String | P | Vehicle type | done |
| `%VEHICLEHEALTH%` | Int | P | Vehicle health | done |
| `%SHADERGROUP%` | String | P/S | Selected shader | missing |
| `%SHADERGROUPS[]%` | Array | P | Available shaders | missing |
| `%RESOURCEPACKS[]%` | Array | W | Selected resource packs | missing |

## Position (provider: VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%XPOS%` / `%YPOS%` / `%ZPOS%` | Decimal | P | Position X/Y/Z | done |
| `%XPOSF%` / `%YPOSF%` / `%ZPOSF%` | String | P | Position X/Y/Z, 3 decimals, as string | done |
| `%YAW%` | Int | P | Yaw, normalised to 0-359 (MKB convention; 0 = south) | done |
| `%PITCH%` | Int | P | Pitch, normalised to 0-359 (looking up = 270-359, level = 0, down = 1-90) | done |
| `%CARDINALYAW%` | Int | P | Yaw relative to north (180° from YAW), normalised to 0-359 | done |
| `%DIRECTION%` | String | P | Facing direction, first char (N/S/E/W) | done |

## Equipped Tool / Held item (provider: helper/bridge — no direct literal)

| Variable | Type | Description | Our Status |
|---|---|---|---|
| `%ITEM%` | String | ID of the equipped item | done |
| `%ITEMNAME%` | String | Display name of the equipped item | done |
| `%ITEMCODE%` | String | Internal code for the equipped item | done |
| `%ITEMIDDMG%` | String | ID and durability separated by a colon | done |
| `%ITEMDAMAGE%` | Int | Maximum uses of the equipped item | done |
| `%DURABILITY%` | Int | Durability of the equipped item | done |
| `%STACKSIZE%` | Int | Stack size of the equipped item | done |
| `%ATTACKPOWER%` | Int | Attack-cooldown recovery 0-100 *(provider: Player)* | done |
| `%ATTACKSPEED%` | Int | Attack-cooldown recovery delay in ticks (5 = unarmed; larger = slower weapon) *(provider: Player)* | done |
| `%COOLDOWN%` | Int | Main-hand item cooldown 0-100 (0 = ready, 100 = just used) *(provider: Player)* | done |
| `%BOWCHARGE%` | Int | Bow draw power 0-100 (non-linear charge curve) *(provider: Player)* | done |
| `%ITEMUSEPCT%` | Int | Item-use progress 0-100 (bow draw / eating) *(provider: Player)* | done |
| `%ITEMUSETICKS%` | Int | Ticks the current item has been in use, increments once/tick *(provider: Player)* | done |
| `%OFFHANDITEM%` | String | ID of the offhand item | done |
| `%OFFHANDITEMNAME%` | String | Display name of the offhand item | done |
| `%OFFHANDITEMCODE%` | String | Internal code for the offhand item | done |
| `%OFFHANDITEMIDDMG%` | String | Offhand ID and durability (colon) | done |
| `%OFFHANDITEMDAMAGE%` | Int | Maximum uses of the offhand item | done |
| `%OFFHANDDURABILITY%` | Int | Durability of the offhand item | done |
| `%OFFHANDSTACKSIZE%` | Int | Stack size of the offhand item | done |
| `%OFFHANDCOOLDOWN%` | Int | Off-hand item cooldown 0-100 (0 = ready, 100 = just used) *(provider: Player)* | done |

## Equipped Armor (provider: helper/bridge — no direct literal; flagged)

For each of `HELM`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`:

| Variable pattern | Type | Description | Our Status |
|---|---|---|---|
| `%<piece>ID%` | String | ID of the piece | done |
| `%<piece>NAME%` | String | Display name of the piece | done |
| `%<piece>DAMAGE%` | Int | Maximum uses of the piece | done |
| `%<piece>DURABILITY%` | Int | Durability of the piece | done |

(= 16 variables: HELMID/HELMNAME/HELMDAMAGE/HELMDURABILITY, CHESTPLATE*, LEGGINGS*, BOOTS*.)

## Looking at / target block (provider: VariableProviderPlayer, prefix `HIT_`)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%HIT%` | String | P | Type of thing being looked at | done |
| `%HITID%` | String | P | ID of the thing | done |
| `%HITNAME%` | String | P | Display name of the thing | done |
| `%HITDATA%` | String | P | Metadata of the thing | missing |
| `%HITUUID%` | String | P | UUID of looked-at entity/player | done |
| `%HITSIDE%` | String | P | Block side (B/T/N/S/W/E) | done |
| `%HITPROGRESS%` | Decimal | P | Block-breaking progress (needs a destroy-progress accessor mixin) | missing |
| `%HITX%` / `%HITY%` / `%HITZ%` | Int | P | Block X/Y/Z position | done |
| `%HIT_<name>%` | String | P | Value of property `<name>` of the looked-at block (via BlockPropertyTracker) | missing |
| `%SIGNTEXT[]%` | Array | P | Lines on a sign being looked at | missing |

## Ray-trace results (provider: VariableProviderTrace; set by the `TRACE` action, prefix `TRACE`)

> Distinct from `%HIT*%`: these are populated **only after running `TRACE(distance)`** and live in the local scope.

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%TRACETYPE%` | String | T | Type of the traced hit | done |
| `%TRACEID%` | String | T | ID of the traced thing | done |
| `%TRACENAME%` | String | T | Name of the traced thing | done |
| `%TRACEDATA%` | String | T | Metadata of the traced thing | missing |
| `%TRACESIDE%` | String | T | Block side hit | done |
| `%TRACEUUID%` | String | T | UUID of traced entity | done |
| `%TRACEX%` / `%TRACEY%` / `%TRACEZ%` | Int | T | Traced block X/Y/Z | done |

## Input (provider: VariableProviderInput)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%SHIFT%` / `%CTRL%` / `%ALT%` | Boolean | I | Whether Shift/Ctrl/Alt is pressed (live) | done |
| `%~SHIFT%` / `%~CTRL%` / `%~ALT%` | Boolean | I | Whether it was pressed at script start (latched) | done |
| `%LMOUSE%` / `%RMOUSE%` / `%MIDDLEMOUSE%` | Boolean | I | Mouse button pressed (live) | done |
| `%~LMOUSE%` / `%~RMOUSE%` / `%~MIDDLEMOUSE%` | Boolean | I | Mouse button pressed at script start | done |
| `%KEY_<name>%` | Boolean | I | Whether the LWJGL-named key is pressed (live) | done |
| `%~KEY_<name>%` | Boolean | I | Whether that key was pressed at script start | done |
| `%KEYID%` | Int | I | Key ID that started this script | done |
| `%KEYNAME%` | String | I | Key name that started this script | done |

## GUI / Window (provider: VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%GUI%` | String | P | Name of the currently open GUI (see GUI-name enum below) | done |
| `%SCREEN%` | String | P | Name of the current custom GUI | missing |
| `%SCREENNAME%` | String | P | Display name of the current custom GUI | missing |
| `%INVSLOT%` | Int | P | Selected hotbar slot, 1-based 1-9 (MKB convention) | done |
| `%CONTAINERSLOTS%` | Int | P | Slots in the opened container | done |
| `%DISPLAYWIDTH%` | Int | P | Width of the MC window | done |
| `%DISPLAYHEIGHT%` | Int | P | Height of the MC window | done |

> `%GUI%` resolves to one of ~50 screen-name constants from the provider (e.g. `GUICHAT`, `GUICHEST`, `GUICRAFTING`, `GUIINVENTORY`, `GUIMAINMENU`, `GUICONTROLS`, `GUIENCHANTMENT`, `GUIBEACON`, `GUIBREWINGSTAND`, `GUIDISPENSER`, `GUIEDITSIGN`, …). These are values, not separate variables.

## Settings / Volumes (provider: VariableProviderSettings)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%FOV%` | Int | S | Field of View | done |
| `%FPS%` | Int | S | Frames per second | done |
| `%GAMMA%` | Decimal | S | Brightness | done |
| `%SENSITIVITY%` | Decimal | S | Mouse sensitivity | done |
| `%CAMERA%` | String | S | Current camera mode | done |
| `%DIFFICULTY%` | String | S/W | World difficulty | done |
| `%SOUND%` | Int | S | Master volume | done |
| `%MUSIC%` | Int | S | Music volume | done |
| `%AMBIENTVOLUME%` | Int | S | Ambient/environment volume | done |
| `%BLOCKVOLUME%` | Int | S | Blocks volume | done |
| `%HOSTILEVOLUME%` | Int | S | Hostile creatures volume | done |
| `%NEUTRALVOLUME%` | Int | S | Friendly creatures volume | done |
| `%PLAYERVOLUME%` | Int | S | Players volume | done |
| `%RECORDVOLUME%` | Int | S | Jukebox/noteblocks volume | done |
| `%WEATHERVOLUME%` | Int | S | Weather volume | done |

## World (provider: VariableProviderWorld / VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%BIOME%` | String | P | Current biome | done |
| `%DIMENSION%` | String | P | Current dimension | done |
| `%SEED%` | String | W | World seed (SP only) | missing |
| `%DAY%` | Int | W | Day number | done |
| `%DAYTIME%` | String | W | In-game time `hh:mm` | done |
| `%DAYTICKS%` | Int | W | TICKS mod 24000, shifted back 6000 | done |
| `%TICKS%` | Long | W | World time (static if doDayNightCycle off) | done |
| `%TOTALTICKS%` | Long | W | Total world time (always increases) | done |
| `%RAIN%` | Decimal | W | Rain level | done |
| `%LOCALDIFFICULTY%` | Int | P | Local/regional difficulty x100 (0-675; F3 effective difficulty; 0 on 1.21.11 where it moved server-side) | done |
| `%CHUNKUPDATES%` | Int | S | Chunk updates | missing |

## Server (provider: VariableProviderWorld / helper)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%SERVER%` | String | W | Server IP | done |
| `%SERVERNAME%` | String | W | Server name | done |
| `%SERVERMOTD%` | String | W | Server MOTD | done |
| `%ONLINEPLAYERS%` | Int | W | Players currently online | done |
| `%MAXPLAYERS%` | Int | W | Server player cap (not reliably client-side; MKB scrapes the tab-list) | missing |

## Time & Date (provider: VariableProviderWorld)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%TIME%` | String | W | Current time `hour:minute:second` | done |
| `%DATE%` | String | W | Current date `year-month-day` | done |
| `%DATETIME%` | String | W | Current date+time | done |
| `%TIMESTAMP%` | Int | W | UNIX timestamp | done |

## Mod related (provider: VariableProviderPlayer / Shared)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%CONFIG%` | String | — | Loaded config | done |
| `%SCREEN%` | String | P | Name of current custom GUI *(also under GUI)* | missing |
| `%SCREENNAME%` | String | P | Display name of current custom GUI | missing |
| `%UNIQUEID%` | String | W | A fresh UUID each access | done |

---

## Event-scoped variables (only valid inside the matching event handler)

These are **not** in ddoerr's variables index; they are set by event providers (`event/providers/*.java`). See EVENTS.md for full mapping.

| Variable | Event | Description |
|---|---|---|
| `%CHAT%` | onChat / onFilterableChat / onSendChatMessage | Raw chat message (with control codes) |
| `%CHATCLEAN%` | onChat | Chat message without control codes |
| `%CHATPLAYER%` | onChat | Player who sent the line |
| `%CHATMESSAGE%` | onChat | Message part of an incoming chat line |
| `%PICKUPITEM%` `%PICKUPID%` `%PICKUPDATA%` `%PICKUPAMOUNT%` | onPickupItem | Picked-up item name/id/data/amount |
| `%JOINEDPLAYER%` | onPlayerJoined | Name of the newly joined player |
| `%OLDINVSLOT%` | onInventorySlotChange | Previous inventory slot |
| `%REASON%` | onAutoCraftingComplete | Completion reason |
| `%OLD<VAR>%` / `%NEW<VAR>%` | onHealthChange, onFoodChange, onLevelChange, onXPChange, onOxygenChange, onModeChange, onArmourChange, onWeatherChange | Old/new value of the watched stat (e.g. `%OLDHEALTH%`/`%NEWHEALTH%`) |

---

## Iterator-scoped variables (from Klacaiba; valid inside `FOREACH`)

Klacaiba is the **only** source that documents these per-iterator variables. ddoerr's iterators page omits them.

> **Most of our iterators are single-var; `effects` is our first multi-var one.** For the single-var iterators `foreach(&v, <iterator>)` binds **one** loop variable to **one** value per element — the primary name: `players` → player name, `teams` → team name, `objectives` → objective name (`hotbar`/`inventory` → the slot's item id, `running` → a running macro's name). The multi-variable Klacaiba columns below (`PLAYERUUID`, `TEAMPREFIX`, `SCOREVALUE`, …) are **not exposed** for those iterators. The exception is **`effects`** (our first MULTI-VAR iterator): it binds the loop var to the effect's display name AND exposes the fixed-name vars `EFFECTID`/`EFFECT`/`EFFECTNAME`/`EFFECTPOWER`/`EFFECTTIME` per element (read in-body as `%EFFECTNAME%` etc.), mirroring MKB's `ScriptedIteratorEffects` — see the table below. MKB's other multi-var iterators (`enchantments`/`properties`) reuse the same engine bundle mechanism and are enqueued. Note that MKB's own `ScriptActionForEach` `players` iterator sets only `PLAYERNAME`; the other five `PLAYER*` rows are Klacaiba-documented *beyond* MKB itself, so our single-name `players` already matches MKB's.

### `effects` iterator (our first multi-var; mirrors MKB `ScriptedIteratorEffects`)
The loop var binds `EFFECTNAME`; all five fixed-name vars are set per active potion effect. Live across all 23 versions (Stonecutter-gated for the 1.20.5 `Holder<MobEffect>` change + the 1.19.3 registry move).
| Variable | Type | Description |
|---|---|---|
| `EFFECTID` | Int | Registry id of the effect |
| `EFFECT` | String | Localized name, uppercased, spaces removed (e.g. `NIGHTVISION`) |
| `EFFECTNAME` | String | Localized name + amplifier suffix (e.g. `Strength II`); also the loop-var value |
| `EFFECTPOWER` | Int | Amplifier + 1 (the effect level) |
| `EFFECTTIME` | Int | Remaining duration in seconds (ticks / 20) |

### `players` iterator
| Variable | Type | Description |
|---|---|---|
| `PLAYERNAME` | String | Player name |
| `PLAYERUUID` | String | UUID with dashes |
| `PLAYERDISPLAYNAME` | String | Display name |
| `PLAYERTEAM` | String#JSON | Scoreboard team as JSON |
| `PLAYERPING` | Int | Ping |
| `PLAYERISLEGACY` | Boolean | Whether a legacy account |

### `teams` iterator (Klacaiba documents this; not in ddoerr's iterator list)
| Variable | Type | Description |
|---|---|---|
| `TEAMNAME` / `TEAMDISPLAYNAME` | String | Team name / display name |
| `TEAMPREFIX` / `TEAMSUFFIX` | String | Prefix / suffix |
| `TEAMCOLOR` | String#Enum | Team color |
| `TEAMALLOWFRIENDLYFIRE` | Boolean | Friendly fire allowed |
| `TEAMCOLLISIONRULE` | String#Enum | Collision rule |
| `TEAMNAMETAGVISIBILITY` | String#Enum | Nametag visibility |
| `TEAMDEATHMESSAGEVISIBILITY` | String#Enum | Death-message visibility |
| `TEAMSEEFRIENDLYINVISIBLES` | String#Enum | See friendly invisibles |
| `TEAMMEMBERS` | Collection#String | Member names |

### `objectives` iterator (Klacaiba)
| Variable | Type | Description |
|---|---|---|
| `OBJECTIVENAME` / `OBJECTIVEDISPLAYNAME` | String | Name / display name |
| `OBJECTIVECRITERIA` | String#Enum | Criteria |
| `OBJECTIVERENDERTYPE` | String#Enum | Render type |

### `scores` iterator (Klacaiba)
| Variable | Type | Description |
|---|---|---|
| `SCOREOBJECTIVENAME` | String | Associated objective name |
| `SCOREPLAYERNAME` | String | Owning player name |
| `SCOREVALUE` | Int | Score value |

> **Note on Klacaiba data quality:** several descriptions in its source are copy-paste errors (e.g. `PLAYERNAME` is described as "If the team allows friendly fire."). Treat Klacaiba's *variable names/types* as accurate but verify *descriptions* against ddoerr.

---

## Summary

~**140** built-in `%variables%` (ddoerr index) + ~**20** event/iterator-scoped vars. Decompiled providers confirm the Player/Settings/World/Input/Trace sets verbatim; equipped-armor/tool and server vars are computed in bridge/helper code (no direct `storeVariable` literal). **Our engine implements ~90** of them, live through the Fabric env-provider + trace-action hooks (Player / Position / Held-item / Armor / Settings / Volumes / World / Time / Looking-at / Trace / Input, under both MKB and descriptive names) plus the `%var%` expansion engine, latched `%~VAR%`, and user-variable sigils. The rows still `missing` are client-unavailable (seed, render internals, UUIDs, block-property tracking) or per-iterator Klacaiba vars (our `foreach` binds one loop var); each is flagged per row above.
