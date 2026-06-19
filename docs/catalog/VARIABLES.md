# MKB Built-in Variable Catalog (Authoritative)

Every built-in `%VARIABLE%` exposed by the Macro/Keybind Mod.

**Sources cross-referenced:**
- **ddoerr** = https://mkb.ddoerr.com/docs/variables (full index, ~140 vars, 13 categories).
- **providers** = decompiled `scripting/variable/providers/*.java` — ground truth for which provider computes each var (`storeVariable("NAME", ...)`).
- **Klacaiba** = https://spthiel.github.io/Klacaiba/ — documents the per-**iterator** variables (Player/Teams/Objective/Score iterators) that ddoerr's pages omit. See bottom section.

**Syntax:** referenced in scripts as `%NAME%`. A leading `~` (e.g. `%~ALT%`) means *"state captured at the moment the script started"* (latched), vs the live value. `<name>` in a var means a parameterised suffix (e.g. `%KEY_W%`, `%HIT_facing%`).

**OUR STATUS:** the Fabric host now provides **~50 built-in variables** through the env-provider hook, exposed under both their MKB names and our descriptive aliases: player vitals / xp / position (+ block-int + decimals) / facing / state, the held and off-hand item (id, name, durability, stack size, max-damage) under MKB names (`%ITEM%`, `%ITEMNAME%`, `%DURABILITY%`, `%STACKSIZE%`, `%OFFHANDITEM%`, …), window size, server ip, current GUI, light level, day, dimension, and difficulty. The churnier remainder is documented per row below: settings volumes, biome, scoreboard / team iterators, trace + `%HIT_*%` reads, per-key input states, and latched `%~VAR%` values (Holder-wrapped or callback-bound APIs). Status column notes the source provider class to guide further porting.

Provider key: **P**=Player, **S**=Settings, **W**=World, **I**=Input, **T**=Trace, **G**=GUI/Player. Vars with no decompiled provider literal (e.g. equipped-armor, server) are computed in helper/bridge code or a newer provider — flagged.

---

## Player (provider: VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%PLAYER%` | String | P | Player's name | done |
| `%DISPLAYNAME%` | String | P | Player's display name | missing |
| `%UUID%` | String | P | UUID of the player | missing |
| `%HEALTH%` | Int | P | Health points (1 heart = 2) | done |
| `%HUNGER%` | Int | P | Hunger points (1 icon = 2) | done |
| `%SATURATION%` | Decimal | P | Saturation level (normally hidden) | done |
| `%ARMOUR%` | Int | P | Armour points (1 icon = 2) | done |
| `%OXYGEN%` | Int | P | Air level (0–300) | done |
| `%LEVEL%` | Int | P | XP level | done |
| `%XP%` | Int | P | Current XP points | missing |
| `%TOTALXP%` | Int | P | Total XP points | done |
| `%GAMEMODE%` | String | P | Game mode as string | missing |
| `%MODE%` | Int | P | Game mode as number | missing |
| `%CANFLY%` | Boolean | P | Whether the player can fly | done |
| `%FLYING%` | Boolean | P | Whether the player is flying | done |
| `%LIGHT%` | Int | P | Light level at current location | done |
| `%VEHICLE%` | String | P | Vehicle type | missing |
| `%VEHICLEHEALTH%` | Int | P | Vehicle health | missing |
| `%SHADERGROUP%` | String | P/S | Selected shader | missing |
| `%SHADERGROUPS[]%` | Array | P | Available shaders | missing |
| `%RESOURCEPACKS[]%` | Array | W | Selected resource packs | missing |

## Position (provider: VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%XPOS%` / `%YPOS%` / `%ZPOS%` | Decimal | P | Position X/Y/Z | done |
| `%XPOSF%` / `%YPOSF%` / `%ZPOSF%` | String | P | Position X/Y/Z, 3 decimals, as string | done |
| `%YAW%` | Decimal | P | Yaw | done |
| `%PITCH%` | Decimal | P | Pitch | done |
| `%CARDINALYAW%` | Int | P | Yaw relative to north (YAW + 180) | done |
| `%DIRECTION%` | String | P | Facing direction, first char (N/S/E/W) | missing |

## Equipped Tool / Held item (provider: helper/bridge — no direct literal)

| Variable | Type | Description | Our Status |
|---|---|---|---|
| `%ITEM%` | String | ID of the equipped item | done |
| `%ITEMNAME%` | String | Display name of the equipped item | done |
| `%ITEMCODE%` | String | Internal code for the equipped item | missing |
| `%ITEMIDDMG%` | String | ID and durability separated by a colon | missing |
| `%ITEMDAMAGE%` | Int | Maximum uses of the equipped item | done |
| `%DURABILITY%` | Int | Durability of the equipped item | done |
| `%STACKSIZE%` | Int | Stack size of the equipped item | done |
| `%ATTACKPOWER%` | Int | Attack power *(provider: Player)* | missing |
| `%ATTACKSPEED%` | Int | Attack speed *(provider: Player)* | missing |
| `%COOLDOWN%` | Int | Cooldown | missing |
| `%BOWCHARGE%` | Int | Bow charging state *(provider: Player)* | missing |
| `%ITEMUSEPCT%` | Decimal | Use time as percent of total *(provider: Player)* | missing |
| `%ITEMUSETICKS%` | Int | Increments once/tick for usable items *(provider: Player)* | missing |
| `%OFFHANDITEM%` | String | ID of the offhand item | done |
| `%OFFHANDITEMNAME%` | String | Display name of the offhand item | done |
| `%OFFHANDITEMCODE%` | String | Internal code for the offhand item | missing |
| `%OFFHANDITEMIDDMG%` | String | Offhand ID and durability (colon) | missing |
| `%OFFHANDITEMDAMAGE%` | Int | Maximum uses of the offhand item | missing |
| `%OFFHANDDURABILITY%` | Int | Durability of the offhand item | done |
| `%OFFHANDSTACKSIZE%` | Int | Stack size of the offhand item | done |
| `%OFFHANDCOOLDOWN%` | Int | Offhand cooldown | missing |

## Equipped Armor (provider: helper/bridge — no direct literal; flagged)

For each of `HELM`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`:

| Variable pattern | Type | Description | Our Status |
|---|---|---|---|
| `%<piece>ID%` | String | ID of the piece | missing |
| `%<piece>NAME%` | String | Display name of the piece | missing |
| `%<piece>DAMAGE%` | Int | Maximum uses of the piece | missing |
| `%<piece>DURABILITY%` | Int | Durability of the piece | missing |

(= 16 variables: HELMID/HELMNAME/HELMDAMAGE/HELMDURABILITY, CHESTPLATE*, LEGGINGS*, BOOTS*.)

## Looking at / target block (provider: VariableProviderPlayer, prefix `HIT_`)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%HIT%` | String | P | Type of thing being looked at | missing |
| `%HITID%` | String | P | ID of the thing | missing |
| `%HITNAME%` | String | P | Display name of the thing | missing |
| `%HITDATA%` | String | P | Metadata of the thing | missing |
| `%HITUUID%` | String | P | UUID of looked-at entity/player | missing |
| `%HITSIDE%` | String | P | Block side (B/T/N/S/W/E) | missing |
| `%HITPROGRESS%` | Decimal | P | Block-breaking progress | missing |
| `%HITX%` / `%HITY%` / `%HITZ%` | Int | P | Block X/Y/Z position | missing |
| `%HIT_<name>%` | String | P | Value of property `<name>` of the looked-at block (via BlockPropertyTracker) | missing |
| `%SIGNTEXT[]%` | Array | P | Lines on a sign being looked at | missing |

## Ray-trace results (provider: VariableProviderTrace; set by the `TRACE` action, prefix `TRACE`)

> Distinct from `%HIT*%`: these are populated **only after running `TRACE(distance)`** and live in the local scope.

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%TRACETYPE%` | String | T | Type of the traced hit | missing |
| `%TRACEID%` | String | T | ID of the traced thing | missing |
| `%TRACENAME%` | String | T | Name of the traced thing | missing |
| `%TRACEDATA%` | String | T | Metadata of the traced thing | missing |
| `%TRACESIDE%` | String | T | Block side hit | missing |
| `%TRACEUUID%` | String | T | UUID of traced entity | missing |
| `%TRACEX%` / `%TRACEY%` / `%TRACEZ%` | Int | T | Traced block X/Y/Z | missing |

## Input (provider: VariableProviderInput)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%SHIFT%` / `%CTRL%` / `%ALT%` | Boolean | I | Whether Shift/Ctrl/Alt is pressed (live) | missing |
| `%~SHIFT%` / `%~CTRL%` / `%~ALT%` | Boolean | I | Whether it was pressed at script start (latched) | missing |
| `%LMOUSE%` / `%RMOUSE%` / `%MIDDLEMOUSE%` | Boolean | I | Mouse button pressed (live) | missing |
| `%~LMOUSE%` / `%~RMOUSE%` / `%~MIDDLEMOUSE%` | Boolean | I | Mouse button pressed at script start | missing |
| `%KEY_<name>%` | Boolean | I | Whether the LWJGL-named key is pressed (live) | missing |
| `%~KEY_<name>%` | Boolean | I | Whether that key was pressed at script start | missing |
| `%KEYID%` | Int | I | Key ID that started this script | missing |
| `%KEYNAME%` | String | I | Key name that started this script | missing |

## GUI / Window (provider: VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%GUI%` | String | P | Name of the currently open GUI (see GUI-name enum below) | done |
| `%SCREEN%` | String | P | Name of the current custom GUI | missing |
| `%SCREENNAME%` | String | P | Display name of the current custom GUI | missing |
| `%INVSLOT%` | Int | P | Selected inventory slot | missing |
| `%CONTAINERSLOTS%` | Int | P | Slots in the opened container | missing |
| `%DISPLAYWIDTH%` | Int | P | Width of the MC window | done |
| `%DISPLAYHEIGHT%` | Int | P | Height of the MC window | done |

> `%GUI%` resolves to one of ~50 screen-name constants from the provider (e.g. `GUICHAT`, `GUICHEST`, `GUICRAFTING`, `GUIINVENTORY`, `GUIMAINMENU`, `GUICONTROLS`, `GUIENCHANTMENT`, `GUIBEACON`, `GUIBREWINGSTAND`, `GUIDISPENSER`, `GUIEDITSIGN`, …). These are values, not separate variables.

## Settings / Volumes (provider: VariableProviderSettings)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%FOV%` | Int | S | Field of View | missing |
| `%FPS%` | Int | S | Frames per second | missing |
| `%GAMMA%` | Decimal | S | Brightness | missing |
| `%SENSITIVITY%` | Decimal | S | Mouse sensitivity | missing |
| `%CAMERA%` | String | S | Current camera mode | missing |
| `%DIFFICULTY%` | String | S/W | World difficulty | missing |
| `%SOUND%` | Int | S | Master volume | missing |
| `%MUSIC%` | Int | S | Music volume | missing |
| `%AMBIENTVOLUME%` | Int | S | Ambient/environment volume | missing |
| `%BLOCKVOLUME%` | Int | S | Blocks volume | missing |
| `%HOSTILEVOLUME%` | Int | S | Hostile creatures volume | missing |
| `%NEUTRALVOLUME%` | Int | S | Friendly creatures volume | missing |
| `%PLAYERVOLUME%` | Int | S | Players volume | missing |
| `%RECORDVOLUME%` | Int | S | Jukebox/noteblocks volume | missing |
| `%WEATHERVOLUME%` | Int | S | Weather volume | missing |

## World (provider: VariableProviderWorld / VariableProviderPlayer)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%BIOME%` | String | P | Current biome | missing |
| `%DIMENSION%` | String | P | Current dimension | done |
| `%SEED%` | String | W | World seed (SP only) | missing |
| `%DAY%` | Int | W | Day number | done |
| `%DAYTIME%` | String | W | In-game time `hh:mm` | missing |
| `%DAYTICKS%` | Int | W | TICKS mod 24000, shifted back 6000 | missing |
| `%TICKS%` | Long | W | World time (static if doDayNightCycle off) | missing |
| `%TOTALTICKS%` | Long | W | Total world time (always increases) | missing |
| `%RAIN%` | Decimal | W | Rain level | missing |
| `%LOCALDIFFICULTY%` | Decimal | P | Local difficulty | missing |
| `%CHUNKUPDATES%` | Int | S | Chunk updates | missing |

## Server (provider: VariableProviderWorld / helper)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%SERVER%` | String | W | Server IP | done |
| `%SERVERNAME%` | String | W | Server name | missing |
| `%SERVERMOTD%` | String | W | Server MOTD | missing |
| `%ONLINEPLAYERS%` | Int | W | Players currently online | missing |
| `%MAXPLAYERS%` | Int | W | Server player cap | missing |

## Time & Date (provider: VariableProviderWorld)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%TIME%` | String | W | Current time `hour:minute:second` | done |
| `%DATE%` | String | W | Current date `year-month-day` | missing |
| `%DATETIME%` | String | W | Current date+time | missing |
| `%TIMESTAMP%` | Int | W | UNIX timestamp | missing |

## Mod related (provider: VariableProviderPlayer / Shared)

| Variable | Type | Provider | Description | Our Status |
|---|---|---|---|---|
| `%CONFIG%` | String | — | Loaded config | missing |
| `%SCREEN%` | String | P | Name of current custom GUI *(also under GUI)* | missing |
| `%SCREENNAME%` | String | P | Display name of current custom GUI | missing |
| `%UNIQUEID%` | String | W | A fresh UUID each access | missing |

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

~**140** built-in `%variables%` (ddoerr index) + ~**20** event/iterator-scoped vars. Decompiled providers confirm the Player/Settings/World/Input/Trace sets verbatim; equipped-armor/tool and server vars are computed in bridge/helper code (no direct `storeVariable` literal). **Our engine implements ~50** of them, live through the Fabric env-provider hook (Player / Position / Held-item / World / Time, under both MKB and descriptive names), plus the `%var%` expansion engine and user-variable sigils. The remainder (settings volumes, biome, trace + `%HIT_*%`, per-key input, scoreboard / team iterators, latched `%~VAR%`) needs churnier registry / Holder / callback adapters and is tracked per row above.
