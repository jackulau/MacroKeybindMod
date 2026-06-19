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
| **Built-in variables** | ~140 | **~90** | player / position / armor / settings / volumes / world / biome / looking-at / trace / input (+ latched) reads |
| **Events** | 21 | **20 of 21 + 5 extras (25)** | change-watchers + presence / death / pickup / GUI / mode + per-server `onConfigChange` |
| **Iterators** | 8 | **8** (`env` `running` `array` `players` `hotbar` `inventory` `teams` `objectives`) | host iterator-provider hook feeds `foreach` |
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
- **Variables (Fabric reads, ~90):** vitals + xp + position (+ `F` decimals + block-int) + facing + state, held / off-hand item (MKB `%ITEM%` `%DURABILITY%` `%STACKSIZE%` … and `%HELDITEM*%` aliases), equipped armor (`%HELM*%` `%CHESTPLATE*%` `%LEGGINGS*%` `%BOOTS*%`), settings (`%FOV%` `%GAMMA%` `%SENSITIVITY%`) + all sound volumes (`%SOUND%` `%MUSIC%` `%AMBIENTVOLUME%` …), world (`%BIOME%` `%TIME%` `%DAYTIME%` `%TICKS%` `%TOTALTICKS%` `%RAIN%` `%DAY%` `%DIMENSION%` `%DIFFICULTY%` `%LIGHT%`), looking-at (`%HIT%` `%HITID%` `%HITNAME%` `%HITX/Y/Z%` `%HITSIDE%`), ray-trace (`%TRACETYPE%` `%TRACEID%` `%TRACENAME%` `%TRACEX/Y/Z%` `%TRACESIDE%`), input (`%SHIFT%` `%CTRL%` `%ALT%` `%LMOUSE%` `%RMOUSE%` `%MIDDLEMOUSE%` `%KEY_<name>%` + latched `%~VAR%`), window / server / GUI

!!! note "Engine-complete; Fabric realization, in layers"
    The engine implements + unit-tests all of the above (actions route through platform interfaces;
    the resumable interpreter drives `wait`). **Live in the Fabric host now:** `wait`/`looks` timing
    (tick-driven resume), world/inventory reads (`getid`/`getslot`/`trace`/`pick`, registry ids),
    client settings (`fov`/`gamma`/`sensitivity`/render distance via `OptionInstance`), `playsound`,
    the HUD `title`/`popupmessage`, `respawn`, container `slotclick` (the auto-craft primitive), the
    REPL console + custom-GUI screens (>=1.21), the slot-click crafting primitive, 23 tick-polled
    events, 8 iterators, and ~90 player / world / settings / armor / looking-at / trace / input variables.
    **Still surfaced as visible feedback** (churnier / lower-value): custom toasts, `disconnect`,
    `placesign`, `bindgui`, and the higher-level `craft`/`setslotitem` (recipe-arrangement / creative
    subsystems). All 23 versions compile; feedback-only items are recognised + routed, not dropped.

Plus engine plumbing: `%var%` expansion, typed user variables (`#counter` / `&string` / flag),
`@shared` scope, arrays, an `env`-provider hook, the interactive parameter resolver, and 10
non-MKB engine helpers: `calc` `length` `abs` `min` `max` `substr` `trim` `turn` `goto` `stopnav`.

## What's left

All 127 keywords + 16 sigils + 8 iterators are implemented; the async runner, world reads, settings,
sounds, HUD, the REPL + custom-GUI screens, the slot-click crafting primitive, per-server config
switching, the chat-filter pipeline, 25 events, and ~90 variables are live in the Fabric host. The
remainder is genuinely client-unavailable or subsystem-bound:

- **~32 variables** that are not client-readable or are niche: world seed (server-side), `%FPS%` /
  `%CAMERA%` / `%CHUNKUPDATES%` (render internals), `%HIT_<name>%` block-property tracking,
  `%HITPROGRESS%` / `%HITDATA%`, item internals (`%ATTACKPOWER%` / `%COOLDOWN%` / `%BOWCHARGE%`),
  vehicle, shader / resource-pack lists, `%KEYID%` / `%KEYNAME%`, and the per-iterator Klacaiba vars
  (our `foreach` binds one loop var, not a per-item variable set). Flagged per row in VARIABLES.md.
- **1 event** (`onAutoCraftingComplete`) fires from the auto-craft execution subsystem, not a client
  tick — it activates once full auto-craft (recipe arrangement) is live.
- **Higher-level actions still on feedback:** custom `toast`, `disconnect`, `placesign`, `bindgui`,
  `import`/`unimport` (config-*file* loading pending a file-I/O abstraction; both manual `config` switch
  and per-server auto-switch are live), and `craft`/`setslotitem` (full recipe-arrangement on `slotclick`).

These are increments on a complete core, not architectural gaps. Every in-game realization above is
**compile-verified across all 23 versions**; live behavior needs a running client (not headless-testable).
