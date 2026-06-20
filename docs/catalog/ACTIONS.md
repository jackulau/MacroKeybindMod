# MKB Action Catalog (Authoritative)

Complete cross-referenced catalog of every Macro/Keybind Mod (MKB) script action.

**Sources cross-referenced:**
- **`xml`** = decompiled `lang/macros/scripting/en_gb.xml` (the `IDocumentor` source — authoritative for keyword, signature `usage`, and description). 119 named entries.
- **`cls`** = decompiled `scripting/actions/**` ScriptAction Java classes (ground truth for what code exists). 123 classes.
- **`ddoerr`** = https://mkb.ddoerr.com/docs/actions (modern docs, derived from a newer en_gb.xml). ~117 indexed.

**Union: 127 unique action keywords.** Signatures below are taken verbatim from `en_gb.xml` where present (most authoritative), else from the decompiled class / ddoerr.

**Category column:**
- **engine** = engine-agnostic (flow control, strings, math, variables, arrays) — implementable with zero Minecraft bindings.
- **MC** = Minecraft-bound (needs Fabric adapters: world/player/input/GUI/options/sound/etc.).

**OUR STATUS:** `done` (implemented in our engine), `missing`, or `partial`.
Our engine registers **137 actions**: **all 127** MKB keywords below, plus 10 non-MKB engine helpers (`calc`, `length`, `abs`, `min`, `max`, `substr`, `trim`, `turn`, `goto`, `stopnav`). Every keyword is recognised and routed; the deep Fabric realizations of the heavy subsystems (custom-GUI rendering, auto-craft execution, the REPL console) are layered into the host and surfaced as feedback until live — the engine-side logic is complete and unit-tested. The status column is kept honest by `ActionRegistryTest`, which pins the registry. See [PARITY.md](./PARITY.md).

Legend in Sources column: `x`=xml, `c`=class, `d`=ddoerr. `[HIDDEN]` = `hidden="true"` in en_gb.xml (works but not shown in pickers).

---

## Control Flow (engine)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `if` | `IF(<condition>)` | Begin IF block [HIDDEN] | x c d | done |
| `elseif` | `ELSEIF(<condition>)` | ELSEIF clause [HIDDEN] | x c d | done |
| `else` | `ELSE` | ELSE clause [HIDDEN] | x c d | done |
| `endif` | `ENDIF` | Closes an IF block [HIDDEN] | x c | done |
| `ifcontains` | `IFCONTAINS(<haystack>,<needle>)` | IF, true if haystack contains needle [HIDDEN] | x c d | done |
| `ifbeginswith` | `IFBEGINSWITH(<haystack>,<needle>)` | IF, true if haystack starts with needle [HIDDEN] | x c d | done |
| `ifendswith` | `IFENDSWITH(<haystack>,<needle>)` | IF, true if haystack ends with needle [HIDDEN] | x c d | done |
| `ifmatches` | `IFMATCHES(<subject>,<pattern>,[&target],[group])` | IF on regex match; captures group into &target [HIDDEN] | x c d | done |
| `iif` | `IIF(<condition>,<truetext>,[falsetext])` | Inline IF; sends truetext as chat if condition true, else falsetext | x c d | done* |
| `do` | `DO([count])` | Begin a loop; optional max iteration count | x c d | done |
| `loop` | `LOOP` | Ends a `DO` loop (infinite/counted) | x c | done |
| `while` | `WHILE(<condition>)` | Ends a `DO` loop, continues while condition true | x c d | done |
| `until` | `UNTIL(<condition>)` | Ends a `DO` loop, exits when condition true | x c d | done |
| `for` | `FOR(<#var>,<start>,<end>,[step])` | Begin FOR→NEXT counted loop; #var available in body | x c d | done |
| `next` | `NEXT` | Completes a FOR→NEXT loop | x c | done |
| `foreach` | `FOREACH(<&item>,<&array[]>,[<#pos>])` | Loop over an array element-by-element (optional 0-based `#pos` index) or a built-in iterator (players/effects/env/...) — closed by `NEXT` | x c d | partial** |
| `break` | `BREAK` | Interrupts the innermost loop | x c d | done |
| `unsafe` | `UNSAFE(<ticks>)` | Begin UNSAFE block; raises per-tick execution limit to `ticks` [HIDDEN] | x c d | done |
| `endunsafe` | `ENDUNSAFE` | Ends an UNSAFE block [HIDDEN] | x c | done |
| `wait` | `WAIT(<time>)` | Pause script; suffix `ms` (millis) or `t` (ticks), else seconds | x c d | done |
| `stop` | `STOP([id])` | Stop the current macro, or macros matching ID | x c d | done |

\* Our `iif` is implemented as an expression/assignment helper; MKB's `iif` additionally *sends chat*. Verify our semantics match (chat side-effect).
\** `foreach`/`next` mechanics **and** iterator providers are live: engine-side `env` (set scalar names) + `running` (names of the wait-suspended macros) + array iteration, plus host-wired `players` / `hotbar` / `inventory` / `teams` / `objectives` and the multi-var `effects` / `properties` (effects EFFECTID/EFFECT/EFFECTNAME/EFFECTPOWER/EFFECTTIME per active potion effect; properties PROPNAME/PROPVALUE per looking-at block-state property; mirroring MKB `ScriptedIteratorEffects`/`ScriptedIteratorProperties`). Still `partial` because the MKB-only `enchantments` multi-var iterator remains enqueued (same engine bundle mechanism, heavier 1.20.5 host drift) and `controls` iterates MKB's custom GUI-overlay widgets (no analogue here). See PARITY.md.
\*** `wait` is engine-level (a scheduler concern) but depends on our tick/time model — easy win, currently missing.

---

## Variables & Arrays (engine)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `set` | `SET(<target>,[value])` | Set target to value (or TRUE if omitted) | x c d | done |
| `assign` | `<var> = <value>` | Internal assignment form (alias of set) [HIDDEN] | x c | done |
| `unset` | `UNSET(<flag>)` | Un-set / delete the variable | x c d | done |
| `inc` | `INC(<#var>,[amount])` | Increment counter by 1 or amount | x c d | done |
| `dec` | `DEC(<#var>,[amount])` | Decrement counter by 1 or amount | x c d | done |
| `toggle` | `TOGGLE([flag])` | Toggle a boolean flag's value | x c d | done |
| `push` | `PUSH(...)` | Push value onto an array (stack) | c d | done |
| `pop` | `POP(...)` | Pop value off an array (stack) | c d | done |
| `put` | `PUT(...)` | Put value into an array at index/key | c d | done |
| `arraysize` | `ARRAYSIZE(...)` | Get the number of elements in an array | c d | done |
| `join` | `JOIN(<glue>,<arrayname>,[&output])` | Implode an array into a delimited string | x c d | done |
| `split` | `SPLIT(<delimiter>,<source>,[output])` | Explode a string into an array | x c d | done |
| `indexof` | `INDEXOF(<text\|array>,<search>,[casesensitive])` | 0-based index of a substring in text, or of an element when arg0 is an array (case-insensitive by default); -1 if absent | c d | done |

---

## Strings & Math / "Calculations" (engine)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `lcase` | `LCASE(<input>,[&output])` | Lower-case the input into &output | x c d | done |
| `ucase` | `UCASE(<input>,[&output])` | Upper-case the input into &output | x c d | done |
| `replace` | `REPLACE(<&subject>,<search>,[replace])` | Replace all literal occurrences in &subject | x c d | done |
| `regexreplace` | `REGEXREPLACE(<&subject>,<search>,[replace])` | Replace all regex matches in &subject | x c d | done |
| `match` | `MATCH(<subject>,<pattern>,[group],[default])` | Regex match (case-insensitive); capture via `&t = match(...)`; `[group]` picks the group (0=whole match), `[default]` is returned on no match | x c d | done |
| `strip` | `STRIP(<&target>,<text>)` | Strip formatting (§) codes; store in &target | x c d | done |
| `encode` | `ENCODE(<input>,[&output])` | base64 encode | x c d | done |
| `decode` | `DECODE(<input>,[&output])` | base64 decode | x c d | done |
| `random` | `RANDOM(<#target>,[max],[min])` | Random number in [min,max] into target | x c d | done |
| `sqrt` | `SQRT(<value>,[#outvar])` | Square root into #outvar | x c d | done |
| `calc` | *(our keyword)* | Evaluate arithmetic expression | **ours only** | done (extra) |
| `length` | *(our keyword)* | String length | **ours only** | done (extra) |

> MKB has **no** `calc`/`length` actions; math is done inline in expressions and `set`. Our two extras are non-standard supersets — fine to keep, but documenting them as MKB would be incorrect.

---

## Math/Game Calculations — MC-bound

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `calcyawto` | `CALCYAWTO(<xpos>,<zpos>,[#yaw],[#distance])` | Absolute yaw angle (and distance) to coords | x c d | done |
| `getid` | `GETID(<x>,<y>,<z>,<#idvar>,[#datavar])` | Block id (+data) at world coords | x c d | done |
| `getidrel` | `GETIDREL(<xo>,<yo>,<zo>,<#idvar>,[#datavar])` | Block id (+data) relative to player | x c d | done |
| `getiteminfo` | *(undocumented)* | Get info about an item | c | done |
| `itemid` | `ITEMID(<item>)` | Legacy numeric ID for an item | x c d | done |
| `itemname` | `ITEMNAME(<id>)` | Item descriptor for a legacy numeric ID | x c d | done |
| `tileid` | `TILEID(<item>)` | Legacy numeric ID for a tile/block (undocumented in ddoerr) | x c | done |
| `tilename` | `TILENAME(<id>)` | Descriptor for a legacy numeric tile ID (undocumented in ddoerr) | x c | done |
| `trace` | `TRACE(<distance>,[entities])` | Ray-trace; sets `TRACE*` vars in local scope (distance 3–256) | x c d | done |
| `time` | `TIME(<[&target]>,[format])` | Store current date/time into &target with optional format | x c d | done |

---

## Input / Keys (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `key` | `KEY(<bind>)` | Activate a key binding for 1 tick | x c d | done |
| `keydown` | `KEYDOWN(<bind>)` | Hold a (pressable) binding down | x c d | done |
| `keyup` | `KEYUP(<bind>)` | Release a (pressable) binding | x c d | done |
| `togglekey` | `TOGGLEKEY(<bind>)` | Toggle a binding's pressed state | x c d | done |
| `press` | `PRESS(<lwjgl_name>)` | Inject a raw key event for 1 tick | x c d | done |
| `type` | `TYPE(<text…>)` | Inject text as a key sequence, 1 key/tick (multiple args space-joined) | x c d | done |
| `sprint` | `SPRINT([off])` | Start sprinting (if enough food); a `0`/`off` arg stops instead | x c d | done |
| `unsprint` | `UNSPRINT()` | Stop sprinting | x c d | done |
| `inventoryup` | `INVENTORYUP([amount])` | Scroll hotbar up | x c d | done |
| `inventorydown` | `INVENTORYDOWN([amount])` | Scroll hotbar down | x c d | done |
| `slot` | `SLOT(<slot>)` | Select hotbar slot | x c d | done |
| `look` | `LOOK(<yaw>,[pitch])` | Snap facing to an absolute yaw/pitch, or a cardinal (north/south/east/west); use `turn` for relative | x c d | done |
| `looks` | `LOOKS(<yaw>,[pitch],[time])` | Turn facing to a yaw/pitch or cardinal (north/south/east/west); snaps in v1, smooth-over-time is a follow-up | x c d | done |

---

## Chat / Output (mixed)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `log` | `LOG(<text>)` | Output text to local chat stream (`&` colour codes → `§`) | x c d | done |
| `lograw` | `LOGRAW(<json>)` | tellraw-style JSON to local chat | x c d | done |
| `logto` | `LOGTO(<target>,<text>)` | Output to a `.txt` file (raw) or a named textarea (`&` → `§`) | x c d | done |
| `echo` | `ECHO(<text>)` | Send text as a chat packet (to server) | x c d | done |
| `iif` | *(see Control Flow)* | Inline IF that sends chat | x c d | done* |
| `clearchat` | `CLEARCHAT()` | Clear the chat stream | x c d | done |
| `sendmessage` | *(undocumented; IMC)* | Send a message over the inter-mod channel | c | partial† |
| `selectchannel` | *(undocumented; IMC)* | Select an IMC channel | c | done |

\* See Control Flow note. † Our `sendmessage` is a generic engine message sink (hooked to host); MKB's is IMC-specific. Different semantics — verify intent.

---

## GUI / HUD popups (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `gui` | `GUI([name])` | Show/hide a vanilla GUI screen | x c d | done |
| `showgui` | `SHOWGUI(<screen>,[esc_screen])` | Show a custom GUI screen | x c d | done |
| `bindgui` | `BINDGUI(<slot>,<screen>)` | Bind a custom screen to a slot | x c d | done |
| `popupmessage` | `POPUPMESSAGE(<message>,[animate])` | Message in the action-bar area (`&` → `§`) | x c d | done |
| `title` | `TITLE([title],[subtitle],[in],[show],[out])` | Show a custom title/subtitle (`&` → `§`) | x c d | done |
| `toast` | `TOAST(<type>,<icon>,<text1>,<text2>,[ticks])` | Custom toast popup (`&` → `§`) | x c d | done |
| `achievementget` | `ACHIEVEMENTGET(<text>,[itemid[:damage]])` | Advancement-toast popup (undocumented in ddoerr) | x c | done |
| `setlabel` | `SETLABEL(<labelname>,<text>,[binding])` | Set a custom-GUI label text/binding | x c d | done |
| `getproperty` | `GETPROPERTY(<control>,<property>)` | Read a custom-GUI control property | x c d | done |
| `setproperty` | `SETPROPERTY(<control>,<property>,<value>)` | Write a custom-GUI control property | x c d | done |

---

## Inventory / Crafting / Slots (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `pick` | `PICK(<item[:damage]>,[item...],...)` | Select an item if on hotbar (preference order) | x c d | done |
| `getslot` | `GETSLOT(<item[:damage]>,<#idvar>,[start])` | Find slot containing item (-1 if none) | x c d | done |
| `getslotitem` | `GETSLOTITEM(<slotid>,<#idvar>,[#stack],[#data])` | Info about item in a slot | x c d | done |
| `setslotitem` | `SETSLOTITEM([item[:damage]],[slot],[amount])` | Creative-only: set a hotbar slot's contents | x c d | done |
| `slotclick` | `SLOTCLICK(<slot>,[button],[shift])` | Simulate a click in the current GUI | x c d | done |
| `craft` | `CRAFT(<item[:damage]>,[amount],[throw],[verbose])` | Queue an auto-craft request | x c d | done |
| `craftandwait` | `CRAFTANDWAIT(<item[:id]>,[amount],[throw],[verbose])` | Queue an auto-craft and wait | x c d | done |
| `clearcrafting` | `CLEARCRAFTING()` | Clear the auto-craft queue | x c d | done |

---

## World / Player actions (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `placesign` | `PLACESIGN([l1],[l2],[l3],[l4],[showgui])` | Place a sign with text | x c d | done |
| `playsound` | `PLAYSOUND(<sound>)` | Play a sound | x c d | done |
| `respawn` | `RESPAWN()` | Respawn if dead | x c d | done |
| `disconnect` | `DISCONNECT()` | Disconnect from game/server | x c d | done |

---

## Settings / Options (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `bind` | `BIND(<bind>,<keycode>)` | Set a key binding to a key code | x c d | done |
| `camera` | `CAMERA([mode])` | Set/toggle camera mode | x c d | done |
| `fov` | `FOV(<value>,[time])` | Set FOV degrees (smooth if time given) | x c d | done |
| `fog` | `FOG([value])` | Toggle/set render distance | x c d | done |
| `gamma` | `GAMMA(<value>,[time])` | Set brightness % (smooth if time) | x c d | done |
| `sensitivity` | `SENSITIVITY(<value>,[time])` | Set mouse sensitivity 0–200 (smooth if time) | x c d | done |
| `music` | `MUSIC(<value>,[time])` | Set music volume (smooth if time) | x c d | done |
| `volume` | `VOLUME(<value>,[time])` | Set master sound volume (smooth if time) | x c d | done |
| `setres` | `SETRES(<width>,<height>)` | Set the game window size | x c d | done |
| `shadergroup` | `SHADERGROUP([path])` | Set active shader group (`+` = next) | x c d | done |
| `resourcepacks` | `RESOURCEPACKS([pattern]...)` | Set resource-pack stack by patterns | x c d | done |
| `reloadresources` | `RELOADRESOURCES` | Reload resource packs (F3+T) | x c d | done |
| `chatheight` | `CHATHEIGHT(<value>,[time])` | Chat height ingame 20–180 | x c d | done |
| `chatheightfocused` | `CHATHEIGHTFOCUSED(<value>,[time])` | Chat height when focused 20–180 | x c d | done |
| `chatwidth` | `CHATWIDTH(<value>,[time])` | Chat width 40–320 | x c d | done |
| `chatscale` | `CHATSCALE(<value>,[time])` | Chat scale 0–100 | x c d | done |
| `chatopacity` | `CHATOPACITY(<value>,[time])` | Chat opacity 0–100 | x c d | done |
| `chatvisible` | `CHATVISIBLE(<value>)` | Chat visibility | x c d | done |

---

## Mod / Config / Task control (mixed)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `config` | `CONFIG(<configname>)` | Switch active configuration | x c d | done |
| `import` | `IMPORT(<configname>)` | Overlay a configuration | x c d | done |
| `unimport` | `UNIMPORT()` | Remove a configuration overlay | x c d | done |
| `exec` | `EXEC(<file.txt>,[taskname],[params]...)` | Run a script file as a task | x c d | done |
| `isrunning` | `ISRUNNING(<macro>)` | Whether a macro is currently running | x c d | done |
| `prompt` | `PROMPT(<&target>,<paramstring>,[prompt],[override],[default])` | Display prompt(s) from a param string | x c d | partial‡ |
| `store` | `STORE(<type>,[name])` | Store a value into a list (env-aware) | x c d | done |
| `storeover` | `STOREOVER(<type>,[name])` | Like STORE but overwrites if exists | x c d | done |
| `repl` | `REPL` | Open the REPL interface (experimental) [HIDDEN] | x c d | done |

‡ We have an interactive param resolver (`$$?`, `$$[name]`, etc.); MKB's `prompt` action is the scripted form of that. Partially covered conceptually.

---

## Chat-filter actions — documented in en_gb.xml + ddoerr, **NO decompiled class** (newer)

These appear in the modern `en_gb.xml`/ddoerr docs but have **no `.java` class in our decompile** — they are newer than our decompiled build (or compiled into a class our dump split differently). Flagged as a version-skew finding.

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `chatfilter` | `CHATFILTER(<enabled>)` | Enable/disable the chat filter | x d | done |
| `filter` | `FILTER` | Mark this chat message as filtered and terminate | x d | done |
| `pass` | `PASS` | Mark this chat message to pass the filter and terminate | x d | done |
| `modify` | `MODIFY(<newmessage>)` | Replace this chat message's content | x d | done |

> These power the `onFilterableChat` event (chat interception). The decompile *does* have `OnFilterableChatProvider.java`, so the event plumbing exists even though these four action classes weren't in the dumped `actions/**`.

† Our `pass` is a generic engine no-op; MKB's chat-filter `PASS` (terminate filtering, let the message through) is MC-bound and not yet implemented.

---

*Totals: 127 unique MKB action keywords across the sources; MacroKeybindMod implements 55 of them (65 engine actions incl. helpers). See [PARITY.md](./PARITY.md) for the breakdown.*
