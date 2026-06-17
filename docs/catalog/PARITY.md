# MKB Parity Gap Analysis

Executive analysis of our reimplementation vs. the full Macro/Keybind Mod (MKB) DSL, cross-referenced across the decompiled source (`reference/decompiled/**`), ddoerr's modern docs (https://mkb.ddoerr.com/docs/), and spthiel's Klacaiba (https://spthiel.github.io/Klacaiba/).

See companion catalogs: [`ACTIONS.md`](./ACTIONS.md), [`VARIABLES.md`](./VARIABLES.md), [`EVENTS.md`](./EVENTS.md).

---

## 1. Counts at a glance

| Surface | MKB total (union) | We implement | Gap | Notes |
|---|---:|---:|---:|---|
| **Actions** | **127** unique keywords | **31** MKB + 2 non-MKB extras | 96 | xml=119 named, classes=123, ddoerr≈117 |
| **Built-in variables** | ~**140** + ~20 event/iterator-scoped | **0** | ~160 | We have the `%var%` engine + user vars, no MC providers |
| **Events** | **21** public | **0** | 21 | All MC-bound |
| **Iterators** | **8** (7 public + array) | array only (partial) | 7 | `env` is engine-feasible |
| **Parameters / sigils** | **16** prompt sigils | ~**11** | ~5 | We have `$$0-9 ? [ ] i d f u t w h`; missing `$$! $$<file> $$[[list]] $$k $$m $$p $$s` |
| **REPL commands** | **17** | 0 | 17 | Console subsystem, optional |

**Headline:** we have a solid **engine core** (~31 of ~50 engine-agnostic actions) but **0%** of the Minecraft surface (variables, events, all MC actions).

---

## 2. What we implement (DONE — 31 MKB actions + 2 extras)

Control flow: `if` `elseif` `else` `endif` `do` `loop` `while` `until` `for` `foreach`† `next` `break` `unsafe` `endunsafe`
Variables/arrays: `set` `assign` `inc` `dec` `unset` `push` `pop` `put` `arraysize` `indexof`
Strings: `lcase` `ucase` `replace`
Output/control: `log` `echo` `iif` `sendmessage`‡
Non-MKB extras: `calc`, `length` *(our keywords; MKB has neither)*

Plus the engine plumbing: `%var%` expansion, user variables (`#counter` / `&string` / flag sigils), `@shared` scope, arrays, an `env`-provider hook, and the interactive parameter resolver (`$$0-9`, `$$?`, `$$[name]`, `$$i`, `$$d`, `$$f`, `$$u`, `$$t`, `$$w`, `$$h`).

† `foreach`/`next` mechanics exist but there are **no iterator data sources** yet.
‡ Our `sendmessage` is a generic engine sink; MKB's is IMC-specific — semantics differ (see ACTIONS.md).

---

## 3. Engine-level missing — EASY (no Minecraft needed)

These are pure-logic actions we can add to the engine **today** with no Fabric dependency. Highest ROI for closing the gap.

| Priority | Action | Why easy | Effort |
|---|---|---|---|
| P0 | `wait` | scheduler/tick concern, no MC API | S |
| P0 | `stop` | task control over our own runner | S |
| P0 | `toggle` | boolean var flip | XS |
| P0 | `split` / `join` | string↔array, pure logic | S |
| P0 | `ifcontains` / `ifbeginswith` / `ifendswith` | string predicate IFs | S |
| P0 | `ifmatches` / `match` / `regexreplace` | regex (Java/Kotlin stdlib) | M |
| P1 | `strip` | strip `§` formatting codes (pure string) | XS |
| P1 | `encode` / `decode` | base64 (stdlib) | XS |
| P1 | `random` | RNG into a var | XS |
| P1 | `sqrt` | math (already have `calc`) | XS |
| P1 | `time` | date/time formatting (stdlib) | S |
| P2 | `isrunning` | query our own task registry | S |
| P2 | `exec` | run another script file as a task | M |
| P2 | `prompt` | scripted form of our existing resolver | M |
| P3 | `env` iterator | iterate our own variable table | S |
| P3 | `running` iterator | iterate our own task list | S |

**Closing all of P0–P3 brings us to ~50/50 engine-agnostic actions — the entire non-MC DSL.** Recommend doing this first; it's self-contained and fully testable without a game.

Also align two correctness items found during cross-referencing:
- **`iif`** — MKB's also *sends the result as chat*; verify our impl matches (or document the divergence).
- **`calc` / `length`** — non-standard. Keep as extensions, but don't present them as MKB in the docs site.

---

## 4. MC-bound missing — needs Fabric adapters

Everything below requires Minecraft access (client state, input injection, world, GUI, sound). Group by adapter so each can be a self-contained Fabric module.

| Adapter module | Actions | Variables | Events |
|---|---|---|---|
| **Player/World state (read)** | calcyawto, getid, getidrel, itemid/itemname, tileid/tilename, getiteminfo, trace | Player, Position, Equipped-tool/armor, Looking-at (`HIT*`), Trace (`TRACE*`), World, Server, Time, Biome/Dimension | onWorldChange, onWeatherChange, onHealth/Food/Oxygen/Level/XP/Mode/Armour Change |
| **Input injection** | key, keydown, keyup, togglekey, press, type, sprint, unsprint, slot, inventoryup/down, look, looks | Input (`%SHIFT%`, `%KEY_x%`, `%KEYID%`, latched `%~...%`) | onInventorySlotChange |
| **Inventory/Crafting** | pick, getslot, getslotitem, setslotitem, slotclick, craft, craftandwait, clearcrafting | container/slot vars | onAutoCraftingComplete, onPickupItem |
| **GUI/HUD** | gui, showgui, bindgui, popupmessage, title, toast, achievementget, setlabel, getproperty, setproperty | `%GUI%`, `%SCREEN%`, `%SCREENNAME%`, `%DISPLAYWIDTH/HEIGHT%` | onShowGui |
| **Chat** | echo*, log/lograw/logto, clearchat, chatfilter, filter, pass, modify | chat-scoped (`%CHAT%`, `%CHATPLAYER%`…) | onChat, onSendChatMessage, onFilterableChat |
| **Settings/Options** | bind, camera, fov, fog, gamma, sensitivity, music, volume, setres, shadergroup, resourcepacks, reloadresources, chat* (6) | Settings/Volumes vars | onConfigChange |
| **World actions** | placesign, playsound, respawn, disconnect | — | onJoinGame, onPlayerJoined |
| **Mod/config** | config, import, unimport, store, storeover, repl | `%CONFIG%`, `%UNIQUEID%` | onConfigChange |
| **Scoreboard iterators** | (foreach) players, teams, objectives, scores | iterator vars (see Klacaiba section) | — |

---

## 5. Prioritized roadmap to full parity

1. **Phase A — Finish the engine (no MC).** Implement all P0–P3 actions in §3 + the `env`/`running` iterators + scripted `prompt`. Outcome: 100% of the engine-agnostic DSL, fully unit-tested. ~50 actions total.
2. **Phase B — Read-only MC variables.** Stand up the variable-provider framework + Player/World/Settings/Input/Time providers (largest single parity jump: ~140 vars). Pure reads, low risk.
3. **Phase C — Input + look/movement actions.** key/press/type/look/slot/sprint + the Input variables. Enables real keybind macros.
4. **Phase D — Inventory/crafting + GUI/HUD.** pick/slot*/craft* + title/toast/popup + custom-GUI (`showgui`/`bindgui`/`get/setproperty`).
5. **Phase E — Events.** Wire Fabric hooks for the 21 events into the engine's macro runner (chat + change-watchers first; they cover most use cases).
6. **Phase F — Chat filter, scoreboard iterators, REPL, remaining settings/world actions, advanced sigils** (`$$<file>`, `$$[[list]]`, `$$!`, `$$k/m/p/s`).

The engine runner, variable table, expansion, expression evaluator, scopes, and task model are all reusable across every phase — the MC work is almost entirely *adapters feeding the existing core*.

---

## 6. What is Klacaiba?

**Klacaiba** (https://spthiel.github.io/Klacaiba/, by spthiel) is a **tiny static reference page** — not an editor or validator. Architecture: a single `index.html` + `style.css` + a 2.7 KB `main.js`. The JS just calls `addTablerow([...])` to render a flex-table; **all data is hard-coded in `main.js`**. There is no fetch, no JSON, no backend, no script-validation logic.

Its **unique value** (worth mirroring): it is the **only source documenting the per-iterator variables** for the scoreboard iterators — **Player, Teams, Objective, and Score** iterators (names + types) — which ddoerr's `/docs/iterators` page omits. Note `teams`/`objectives`/`scores` aren't even in ddoerr's iterator *list*, so Klacaiba surfaces iterators the modern docs don't mention.

**Caveats:** the data has copy-paste bugs (e.g. `PLAYERNAME` description reads "If the team allows friendly fire."). Trust its names/types, not its prose.

**Ideas to mirror:** (a) capture the 4 scoreboard iterators + their variables (done in VARIABLES.md); (b) the type taxonomy it uses (`String`, `Int`, `Boolean`, `String#JSON`, `String#Enum`, `Collection#String`) is a nice compact type notation for our docs site.

---

## 7. ddoerr features we hadn't captured

Cross-referencing ddoerr's modern docs surfaced several things beyond a raw action list:

- **Newer actions absent from our decompiled `actions/**`:** `chatfilter`, `filter`, `pass`, `modify` (chat-interception; documented in en_gb.xml + ddoerr, no `.java` class in our dump). The `onFilterableChat` event/provider DOES exist in the decompile — version skew.
- **Iterators (7 public):** `controls`, `effects`, `enchantments`, `env`, `players`, `properties`, `running` — with the `controls([layout][:type])` parameterised syntax. (Decompile also has an internal `array` iterator → 8.)
- **REPL / console commands (17):** `BEGIN/END` (multi-line), `CAT`, `CLS`, `EDIT`, `EXIT`, `HELP`, `KILL`, `LIST`, `LIVE` (live mode), `RM`, `RUN`, `SAY`, `SHUTDOWN`, `TASKS`, `VERSION`, `WHOAMI`. A whole console subsystem (`scripting/repl/Repl.java`) we hadn't catalogued.
- **Parameter sigils we lacked:** `$$!` (dump macro to chat for editing), `$$<file.txt>` (inline a file), `$$[[a,b,c]]` (inline literal list), `$$i:d` (item id *with metadata*), `$$k` (resource packs), `$$m` (script files in dir), `$$p` (saved places), `$$s` (shaders). We had the common ones (`$$0-9 ? [ ] i d f u t w h`).
- **`%~`-latched input variables:** every input var has a "state at script start" twin (`%~SHIFT%`, `%~KEY_W%`, …) distinct from the live value — a subtlety easy to miss.
- **`TRACE*` vs `HIT*`:** two parallel looking-at variable sets — passive `%HIT*%` (always live) vs `%TRACE*%` (only after the `TRACE` action). Different providers.
- **`%GUI%` value enum:** resolves to ~50 screen-name constants (GUICHAT, GUICHEST, …) rather than being free text.
- **Per-event permission nodes** (`mod.macros.events.<group>.<event>`) and **version-added tags** (e.g. onHealthChange v0.8.5, onSendChatMessage v0.10.4) — useful metadata for the docs site.

---

## 8. Confidence / what couldn't be fetched

- ddoerr's site **blocks plain `curl`** (returns 0 bytes / 404 and a content-signals `robots.txt`) but **`WebFetch` works**; `/docs` and `/sitemap.xml` 404 because it's a client-routed SPA (real content lives at `/docs/actions`, `/docs/variables`, etc.). All index + key detail pages were retrieved successfully.
- The **most authoritative signatures** come from the decompiled `lang/macros/scripting/en_gb.xml` (the `IDocumentor` source) — used verbatim for ACTIONS.md. Where ddoerr and the XML differ, the XML is canonical for the decompiled build; ddoerr is canonical for the 4 newer actions.
- Klacaiba was fully captured from its raw `main.js` (the entire dataset).
- Not exhaustively fetched (low value, pattern is clear): every one of the 21 individual event detail pages (sampled onChat/onHealthChange/onPickupItem/onSendChatMessage/onPlayerJoined; the rest follow the documented OLD/NEW watcher pattern confirmed in decompiled providers) and every one of the ~120 individual action detail pages (en_gb.xml already provides all signatures + descriptions).
