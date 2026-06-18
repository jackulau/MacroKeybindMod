# MKB Parity Overview

Where MacroMod stands against the full Macro/Keybind Mod (MKB) DSL, cross-referenced across
the decompiled source, [ddoerr's docs](https://mkb.ddoerr.com/docs/), and Klacaiba.

Companion catalogs: [`ACTIONS.md`](./ACTIONS.md) · [`VARIABLES.md`](./VARIABLES.md) · [`EVENTS.md`](./EVENTS.md).

The action registry is the source of truth for "what we implement" — it is pinned by
`ActionRegistryTest`, so the numbers below can't silently drift from the code.

## Counts at a glance

| Surface | MKB total | We implement | Notes |
|---|---:|---:|---|
| **Actions** | 127 keywords | **112** + 10 engine extras (**122** total) | all but the deferred subsystems (custom-GUI builder, auto-crafting, chat-filter, REPL) |
| **Built-in variables** | ~140 | **~30** | player / position / state / world / held-item reads (Fabric provider) |
| **Events** | 21 | 3 (`onTick`, `onChat`, `onSendChatMessage`) | wired in the Fabric bridge |
| **Iterators** | 8 | array only (partial) | `foreach`/`next` work; no data sources yet |
| **Parameter sigils** | 16 | ~11 | have `$$0-9 ? [ ] i d f u t w h`; missing `$$! $$<file> $$[[list]] $$k $$m $$p $$s` |

## What we implement (112 MKB keywords + 10 extras)

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
- **Variables (Fabric reads, ~30):** `%PLAYER%` `%HEALTH%` `%HUNGER%` `%SATURATION%` `%OXYGEN%` `%ARMOUR%` `%LEVEL%` `%TOTALXP%` `%XPOS%`/`%YPOS%`/`%ZPOS%` (+ `F` decimals) `%YAW%` `%PITCH%` `%FLYING%` `%CANFLY%` `%SHIFT%` `%SPRINTING%` `%ONFIRE%` `%HELDITEMNAME%` `%HELDITEMCOUNT%` `%TIME%` `%RAINING%` `%DIMENSION%` `%DIFFICULTY%`

!!! note "Engine-complete, Fabric realization in layers"
    The engine implements + unit-tests all of the above (actions route through platform interfaces;
    the resumable interpreter drives `wait`). The Fabric host applies the cross-version-stable effects
    now and surfaces the rest as visible feedback; richer live mutation (option changes, sounds, live
    world/inventory reads, custom toasts) is the next Fabric pass. All 23 versions compile.

Plus engine plumbing: `%var%` expansion, typed user variables (`#counter` / `&string` / flag),
`@shared` scope, arrays, an `env`-provider hook, the interactive parameter resolver, and 10
non-MKB engine helpers: `calc` `length` `abs` `min` `max` `substr` `trim` `turn` `goto` `stopnav`.

## What's left

**Engine-agnostic (the small remainder):**

- `wait` / `looks` — need a tick-yielding async runner (the interpreter currently runs each script
  synchronously to completion); this is the one architectural gap, not a missing action.
- Iterator data sources — `foreach` mechanics exist; `env` / `running` iterators need a provider hook.
- Task / config actions — `exec` `isrunning` `prompt` `store` `repl` (need a task + config model).

**MC-bound (needs Fabric adapters) — 72 keywords**, grouped by the adapter that unlocks them:

| Adapter | Representative actions |
|---|---|
| Player/world reads | `getid` `getidrel` `calcyawto` `itemid` `itemname` `tileid` `trace` |
| Input (extended) | `togglekey` `type` `slot` `inventoryup`/`down` `looks` |
| Inventory / crafting | `pick` `getslot` `setslotitem` `slotclick` `craft` `craftandwait` `clearcrafting` |
| GUI / HUD | `gui` `showgui` `bindgui` `title` `toast` `popupmessage` `get`/`setproperty` |
| Chat | `lograw` `logto` `clearchat` `chatfilter` `filter` `pass` `modify` |
| Settings / options | `bind` `fov` `gamma` `sensitivity` `music` `volume` `setres` + chat-* |
| World actions | `placesign` `playsound` `respawn` `disconnect` |
| Mod / config | `config` `import` `unimport` `store` `repl` |

## Roadmap to fuller parity

1. **Variable providers** — Player/World/Settings/Input/Time (the largest single jump, ~140 vars; pure reads).
2. **Inventory / crafting + GUI/HUD actions** on top of the existing input layer.
3. **More events** — wire the remaining Fabric hooks (change-watchers, join/leave) into the macro runner.
4. **Async runner** — unlock `wait`/`looks` and time-based actions.
5. **Iterators, chat-filter, REPL, advanced sigils.**

The engine core (runner, variable table, expansion, expression evaluator, scopes) is reusable across
every phase — the remaining work is almost entirely *adapters feeding the existing core*.
