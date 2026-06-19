# MKB Parity Overview

Where MacroKeybindMod stands against the full Macro/Keybind Mod (MKB) DSL, cross-referenced across
the decompiled source, [ddoerr's docs](https://mkb.ddoerr.com/docs/), and Klacaiba.

Companion catalogs: [`ACTIONS.md`](./ACTIONS.md) · [`VARIABLES.md`](./VARIABLES.md) · [`EVENTS.md`](./EVENTS.md).

The action registry is the source of truth for "what we implement" — it is pinned by
`ActionRegistryTest`, so the numbers below can't silently drift from the code.

## Counts at a glance

| Surface | MKB total | We implement | Notes |
|---|---:|---:|---|
| **Actions** | 127 keywords | **all 127** + 10 engine extras (**137** total) | full keyword coverage; heavy subsystems' live realization layered in the host |
| **Built-in variables** | ~140 | **~48** | player / position / state / world / held-item / equipment / light reads (Fabric provider) |
| **Events** | 21 | **11 of 21 + 5 extras (16)** | change-watchers + presence/death, tick-polled in the bridge |
| **Iterators** | 8 | **6** (`env` `running` `array` `players` `hotbar` `inventory`) | host iterator-provider hook feeds `foreach` |
| **Parameter sigils** | 16 | **16** | full `$$` table: `0-9 ? [ ] i d i:d f u t w h ! <file> [[list]] k m p s` |

## What we implement (all 127 MKB keywords + 10 extras)

- **Control flow:** `if` `elseif` `else` `endif` `do` `loop` `while` `until` `for` `next` `foreach` `break` `unsafe` `endunsafe`
- **String conditionals:** `ifcontains` `ifbeginswith` `ifendswith` `ifmatches`
- **Output:** `log` `echo` `sendmessage` `iif`
- **Variables / arrays:** `set` `assign` `inc` `dec` `unset` `toggle` `push` `pop` `put` `arraysize` `indexof`
- **Strings:** `lcase` `ucase` `length` `replace` `regexreplace` `match` `strip` `encode` `decode` `split` `join`
- **Math / date:** `random` `sqrt` `time` `calcyawto`
- **Task / flow:** `pass` `stop`
- **Input (route to the platform):** `key` `keydown` `keyup` `press` `look` `sprint` `unsprint` `slot` `inventoryup` `inventorydown` `type` `togglekey`
- **Navigation (our addition):** `goto` `stopnav`
- **Timing (resumable interpreter):** `wait` `looks`
- **Output (extended):** `lograw` `logto` `clearchat` `selectchannel`
- **Settings / options:** `fov` `gamma` `sensitivity` `music` `volume` `fog` `camera` `setres` `bind` `reloadresources` `shadergroup` `resourcepacks` + 6 `chat*`
- **World / HUD:** `respawn` `disconnect` `playsound` `placesign` `title` `toast` `popupmessage` `gui`
- **World / inventory reads:** `getslot` `getslotitem` `getid` `getidrel` `trace` `pick` `getiteminfo` `itemid` `itemname` `tileid` `tilename`
- **Task / config:** `store` `storeover` `isrunning` `prompt` `exec` `config` `import` `unimport`
- **Variables (Fabric reads, ~48):** vitals (`%PLAYER%` `%HEALTH%` `%MAXHEALTH%` `%HUNGER%` `%SATURATION%` `%OXYGEN%` `%MAXAIR%` `%ARMOUR%`), xp (`%LEVEL%` `%TOTALXP%`), position (`%XPOS%`/`%YPOS%`/`%ZPOS%` + `F` decimals + `%BLOCKX%`/`%BLOCKY%`/`%BLOCKZ%`), facing (`%YAW%` `%PITCH%`), state (`%FLYING%` `%CANFLY%` `%SHIFT%` `%SPRINTING%` `%ONFIRE%` `%SWIMMING%` `%INWATER%` `%INLAVA%` `%FALLDISTANCE%` `%EYEHEIGHT%`), held / off-hand (`%HELDITEMNAME%` `%HELDITEMID%` `%HELDITEMCOUNT%` `%HELDITEMDAMAGE%` `%HELDITEMMAXDAMAGE%` `%HELDITEMDURABILITY%` `%OFFHANDNAME%` `%OFFHANDID%` `%OFFHANDCOUNT%` `%SLOT%`), world (`%TIME%` `%GAMETIME%` `%LIGHT%` `%RAINING%` `%DIMENSION%` `%DIFFICULTY%`)

!!! note "Engine-complete; Fabric realization, in layers"
    The engine implements + unit-tests all of the above (actions route through platform interfaces;
    the resumable interpreter drives `wait`). **Live in the Fabric host now:** `wait`/`looks` timing
    (tick-driven resume), world/inventory reads (`getid`/`getslot`/`trace`/`pick`, registry ids),
    client settings (`fov`/`gamma`/`sensitivity`/render distance via `OptionInstance`), `playsound`,
    the HUD `title`/`popupmessage`, `respawn`, container `slotclick` (the auto-craft primitive), the
    REPL console + custom-GUI screens (>=1.21), 16 tick-polled events, and ~48 player/world variables.
    **Still surfaced as visible feedback** (churnier / lower-value): custom toasts, `disconnect`,
    `placesign`, `bindgui`, and the higher-level `craft`/`setslotitem` (recipe-arrangement / creative
    subsystems). All 23 versions compile; feedback-only items are recognised + routed, not dropped.

Plus engine plumbing: `%var%` expansion, typed user variables (`#counter` / `&string` / flag),
`@shared` scope, arrays, an `env`-provider hook, the interactive parameter resolver, and 10
non-MKB engine helpers: `calc` `length` `abs` `min` `max` `substr` `trim` `turn` `goto` `stopnav`.

## What's left

All 127 keywords + 16 sigils are implemented; the async runner, iterators, world reads, settings,
sounds, HUD, the REPL + custom-GUI screens, the slot-click crafting primitive, 16 events, and ~48
variables are live in the Fabric host. The honest remainder is narrow:

- **More variables** toward the full ~140: equipment-per-slot, trace/looking-at (`%HIT_*%`),
  per-key input states (`%KEY_<x>%`), team/scoreboard iterators, and latched (`%~VAR%`) values.
  These are pure reads behind churnier registry/Holder APIs (biome, enchantments, effects).
- **Remaining events** (5 of 21): `onModeChange`, `onArmourChange`/`onArmourDurabilityChange`,
  `onItemDurabilityChange`, `onPickupItem`, `onShowGui`, `onConfigChange`, `onAutoCraftingComplete`,
  `onFilterableChat`, `onPlayerJoined` (each needs a specific mixin/callback or a config/task model).
- **Higher-level actions still on feedback:** custom `toast`, `disconnect`, `placesign`, `bindgui`,
  and `craft`/`setslotitem` (full recipe-arrangement / creative placement on top of `slotclick`).

These are increments on a complete core, not architectural gaps. Every in-game realization above is
**compile-verified across all 23 versions**; live behavior needs a running client (not headless-testable).
