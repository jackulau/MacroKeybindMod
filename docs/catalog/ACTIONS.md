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
Our engine currently implements **33 actions** (see PARITY.md). Note our engine adds two keywords that are **not** MKB actions: `calc` and `length` (MKB does math inline / via `set`, and string length via `%...%` length semantics).

Legend in Sources column: `x`=xml, `c`=class, `d`=ddoerr. `[HIDDEN]` = `hidden="true"` in en_gb.xml (works but not shown in pickers).

---

## Control Flow (engine)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `if` | `IF(<condition>)` | Begin IF block [HIDDEN] | x c d | done |
| `elseif` | `ELSEIF(<condition>)` | ELSEIF clause [HIDDEN] | x c d | done |
| `else` | `ELSE` | ELSE clause [HIDDEN] | x c d | done |
| `endif` | `ENDIF` | Closes an IF block [HIDDEN] | x c | done |
| `ifcontains` | `IFCONTAINS(<haystack>,<needle>)` | IF, true if haystack contains needle [HIDDEN] | x c d | missing |
| `ifbeginswith` | `IFBEGINSWITH(<haystack>,<needle>)` | IF, true if haystack starts with needle [HIDDEN] | x c d | missing |
| `ifendswith` | `IFENDSWITH(<haystack>,<needle>)` | IF, true if haystack ends with needle [HIDDEN] | x c d | missing |
| `ifmatches` | `IFMATCHES(<subject>,<pattern>,[&target],[group])` | IF on regex match; captures group into &target [HIDDEN] | x c d | missing |
| `iif` | `IIF(<condition>,<truetext>,[falsetext])` | Inline IF; sends truetext as chat if condition true, else falsetext | x c d | done* |
| `do` | `DO([count])` | Begin a loop; optional max iteration count | x c d | done |
| `loop` | `LOOP` | Ends a `DO` loop (infinite/counted) | x c | done |
| `while` | `WHILE(<condition>)` | Ends a `DO` loop, continues while condition true | x c d | done |
| `until` | `UNTIL(<condition>)` | Ends a `DO` loop, exits when condition true | x c d | done |
| `for` | `FOR(<#var>,<start>,<end>,[step])` | Begin FOR→NEXT counted loop; #var available in body | x c d | done |
| `next` | `NEXT` | Completes a FOR→NEXT loop | x c | done |
| `foreach` | `FOREACH(<iterator>)` | Loop over an iterator (players/effects/env/...) — closed by `NEXT` | x c d | partial** |
| `break` | `BREAK` | Interrupts the innermost loop | x c d | done |
| `unsafe` | `UNSAFE(<ticks>)` | Begin UNSAFE block; raises per-tick execution limit to `ticks` [HIDDEN] | x c d | done |
| `endunsafe` | `ENDUNSAFE` | Ends an UNSAFE block [HIDDEN] | x c | done |
| `wait` | `WAIT(<time>)` | Pause script; suffix `ms` (millis) or `t` (ticks), else seconds | x c d | missing*** |
| `stop` | `STOP([id])` | Stop the current macro, or macros matching ID | x c d | missing |

\* Our `iif` is implemented as an expression/assignment helper; MKB's `iif` additionally *sends chat*. Verify our semantics match (chat side-effect).
\** We implement `foreach`/`next` loop mechanics but have **no iterator providers** (players/effects/env/etc. are MC-bound). Engine-only `env` and array iteration are feasible now.
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
| `toggle` | `TOGGLE([flag])` | Toggle a boolean flag's value | x c d | missing |
| `push` | `PUSH(...)` | Push value onto an array (stack) | c d | done |
| `pop` | `POP(...)` | Pop value off an array (stack) | c d | done |
| `put` | `PUT(...)` | Put value into an array at index/key | c d | done |
| `arraysize` | `ARRAYSIZE(...)` | Get the number of elements in an array | c d | done |
| `join` | `JOIN(<glue>,<arrayname>,[&output])` | Implode an array into a delimited string | x c d | missing |
| `split` | `SPLIT(<delimiter>,<source>,[output])` | Explode a string into an array | x c d | missing |
| `indexof` | `INDEXOF(...)` | Index of a substring/element | c d | done |

---

## Strings & Math / "Calculations" (engine)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `lcase` | `LCASE(<input>,[&output])` | Lower-case the input into &output | x c d | done |
| `ucase` | `UCASE(<input>,[&output])` | Upper-case the input into &output | x c d | done |
| `replace` | `REPLACE(<&subject>,<search>,[replace])` | Replace all literal occurrences in &subject | x c d | done |
| `regexreplace` | `REGEXREPLACE(<&subject>,<search>,[replace])` | Replace all regex matches in &subject | x c d | missing |
| `match` | `MATCH(<subject>,<pattern>,[&target],[group],[default])` | Regex match; store result/group in &target | x c d | missing |
| `strip` | `STRIP(<&target>,<text>)` | Strip formatting (§) codes; store in &target | x c d | missing |
| `encode` | `ENCODE(<input>,[&output])` | base64 encode | x c d | missing |
| `decode` | `DECODE(<input>,[&output])` | base64 decode | x c d | missing |
| `random` | `RANDOM(<#target>,[max],[min])` | Random number in [min,max] into target | x c d | missing |
| `sqrt` | `SQRT(<value>,[#outvar])` | Square root into #outvar | x c d | missing |
| `calc` | *(our keyword)* | Evaluate arithmetic expression | **ours only** | done (extra) |
| `length` | *(our keyword)* | String length | **ours only** | done (extra) |

> MKB has **no** `calc`/`length` actions; math is done inline in expressions and `set`. Our two extras are non-standard supersets — fine to keep, but documenting them as MKB would be incorrect.

---

## Math/Game Calculations — MC-bound

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `calcyawto` | `CALCYAWTO(<xpos>,<zpos>,[#yaw],[#distance])` | Absolute yaw angle (and distance) to coords | x c d | missing |
| `getid` | `GETID(<x>,<y>,<z>,<#idvar>,[#datavar])` | Block id (+data) at world coords | x c d | missing |
| `getidrel` | `GETIDREL(<xo>,<yo>,<zo>,<#idvar>,[#datavar])` | Block id (+data) relative to player | x c d | missing |
| `getiteminfo` | *(undocumented)* | Get info about an item | c | missing |
| `itemid` | `ITEMID(<item>)` | Legacy numeric ID for an item | x c d | missing |
| `itemname` | `ITEMNAME(<id>)` | Item descriptor for a legacy numeric ID | x c d | missing |
| `tileid` | `TILEID(<item>)` | Legacy numeric ID for a tile/block (undocumented in ddoerr) | x c | missing |
| `tilename` | `TILENAME(<id>)` | Descriptor for a legacy numeric tile ID (undocumented in ddoerr) | x c | missing |
| `trace` | `TRACE(<distance>,[entities])` | Ray-trace; sets `TRACE*` vars in local scope (distance 3–256) | x c d | missing |
| `time` | `TIME(<[&target]>,[format])` | Store current date/time into &target with optional format | x c d | missing |

---

## Input / Keys (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `key` | `KEY(<bind>)` | Activate a key binding for 1 tick | x c d | missing |
| `keydown` | `KEYDOWN(<bind>)` | Hold a (pressable) binding down | x c d | missing |
| `keyup` | `KEYUP(<bind>)` | Release a (pressable) binding | x c d | missing |
| `togglekey` | `TOGGLEKEY(<bind>)` | Toggle a binding's pressed state | x c d | missing |
| `press` | `PRESS(<lwjgl_name>)` | Inject a raw key event for 1 tick | x c d | missing |
| `type` | `TYPE(<text>)` | Inject a key sequence, 1 key/tick | x c d | missing |
| `sprint` | `SPRINT()` | Start sprinting (if enough food) | x c d | missing |
| `unsprint` | `UNSPRINT()` | Stop sprinting | x c d | missing |
| `inventoryup` | `INVENTORYUP([amount])` | Scroll hotbar up | x c d | missing |
| `inventorydown` | `INVENTORYDOWN([amount])` | Scroll hotbar down | x c d | missing |
| `slot` | `SLOT(<slot>)` | Select hotbar slot | x c d | missing |
| `look` | `LOOK(<yaw>,[pitch],[time])` | Snap player facing (prefix +/- for relative) | x c d | missing |
| `looks` | `LOOKS(<yaw>,[pitch],[time])` | Smoothly turn player facing | x c d | missing |

---

## Chat / Output (mixed)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `log` | `LOG(<text>)` | Output text to local chat stream | x c d | done |
| `lograw` | `LOGRAW(<json>)` | tellraw-style JSON to local chat | x c d | missing |
| `logto` | `LOGTO(<target>,<text>)` | Output to a file or named textarea | x c d | missing |
| `echo` | `ECHO(<text>)` | Send text as a chat packet (to server) | x c d | done |
| `iif` | *(see Control Flow)* | Inline IF that sends chat | x c d | done* |
| `clearchat` | `CLEARCHAT()` | Clear the chat stream | x c d | missing |
| `sendmessage` | *(undocumented; IMC)* | Send a message over the inter-mod channel | c | partial† |
| `selectchannel` | *(undocumented; IMC)* | Select an IMC channel | c | missing |

\* See Control Flow note. † Our `sendmessage` is a generic engine message sink (hooked to host); MKB's is IMC-specific. Different semantics — verify intent.

---

## GUI / HUD popups (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `gui` | `GUI([name])` | Show/hide a vanilla GUI screen | x c d | missing |
| `showgui` | `SHOWGUI(<screen>,[esc_screen])` | Show a custom GUI screen | x c d | missing |
| `bindgui` | `BINDGUI(<slot>,<screen>)` | Bind a custom screen to a slot | x c d | missing |
| `popupmessage` | `POPUPMESSAGE(<message>,[animate])` | Message in the action-bar area | x c d | missing |
| `title` | `TITLE([title],[subtitle],[in],[show],[out])` | Show a custom title/subtitle | x c d | missing |
| `toast` | `TOAST(<type>,<icon>,<text1>,<text2>,[ticks])` | Custom toast popup | x c d | missing |
| `achievementget` | `ACHIEVEMENTGET(<text>,[itemid[:damage]])` | Advancement-toast popup (undocumented in ddoerr) | x c | missing |
| `setlabel` | `SETLABEL(<labelname>,<text>,[binding])` | Set a custom-GUI label text/binding | x c d | missing |
| `getproperty` | `GETPROPERTY(<control>,<property>)` | Read a custom-GUI control property | x c d | missing |
| `setproperty` | `SETPROPERTY(<control>,<property>,<value>)` | Write a custom-GUI control property | x c d | missing |

---

## Inventory / Crafting / Slots (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `pick` | `PICK(<item[:damage]>,[item...],...)` | Select an item if on hotbar (preference order) | x c d | missing |
| `getslot` | `GETSLOT(<item[:damage]>,<#idvar>,[start])` | Find slot containing item (-1 if none) | x c d | missing |
| `getslotitem` | `GETSLOTITEM(<slotid>,<#idvar>,[#stack],[#data])` | Info about item in a slot | x c d | missing |
| `setslotitem` | `SETSLOTITEM([item[:damage]],[slot],[amount])` | Creative-only: set a hotbar slot's contents | x c d | missing |
| `slotclick` | `SLOTCLICK(<slot>,[button],[shift])` | Simulate a click in the current GUI | x c d | missing |
| `craft` | `CRAFT(<item[:damage]>,[amount],[throw],[verbose])` | Queue an auto-craft request | x c d | missing |
| `craftandwait` | `CRAFTANDWAIT(<item[:id]>,[amount],[throw],[verbose])` | Queue an auto-craft and wait | x c d | missing |
| `clearcrafting` | `CLEARCRAFTING()` | Clear the auto-craft queue | x c d | missing |

---

## World / Player actions (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `placesign` | `PLACESIGN([l1],[l2],[l3],[l4],[showgui])` | Place a sign with text | x c d | missing |
| `playsound` | `PLAYSOUND(<sound>)` | Play a sound | x c d | missing |
| `respawn` | `RESPAWN()` | Respawn if dead | x c d | missing |
| `disconnect` | `DISCONNECT()` | Disconnect from game/server | x c d | missing |

---

## Settings / Options (MC)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `bind` | `BIND(<bind>,<keycode>)` | Set a key binding to a key code | x c d | missing |
| `camera` | `CAMERA([mode])` | Set/toggle camera mode | x c d | missing |
| `fov` | `FOV(<value>,[time])` | Set FOV degrees (smooth if time given) | x c d | missing |
| `fog` | `FOG([value])` | Toggle/set render distance | x c d | missing |
| `gamma` | `GAMMA(<value>,[time])` | Set brightness % (smooth if time) | x c d | missing |
| `sensitivity` | `SENSITIVITY(<value>,[time])` | Set mouse sensitivity 0–200 (smooth if time) | x c d | missing |
| `music` | `MUSIC(<value>,[time])` | Set music volume (smooth if time) | x c d | missing |
| `volume` | `VOLUME(<value>,[time])` | Set master sound volume (smooth if time) | x c d | missing |
| `setres` | `SETRES(<width>,<height>)` | Set the game window size | x c d | missing |
| `shadergroup` | `SHADERGROUP([path])` | Set active shader group (`+` = next) | x c d | missing |
| `resourcepacks` | `RESOURCEPACKS([pattern]...)` | Set resource-pack stack by patterns | x c d | missing |
| `reloadresources` | `RELOADRESOURCES` | Reload resource packs (F3+T) | x c d | missing |
| `chatheight` | `CHATHEIGHT(<value>,[time])` | Chat height ingame 20–180 | x c d | missing |
| `chatheightfocused` | `CHATHEIGHTFOCUSED(<value>,[time])` | Chat height when focused 20–180 | x c d | missing |
| `chatwidth` | `CHATWIDTH(<value>,[time])` | Chat width 40–320 | x c d | missing |
| `chatscale` | `CHATSCALE(<value>,[time])` | Chat scale 0–100 | x c d | missing |
| `chatopacity` | `CHATOPACITY(<value>,[time])` | Chat opacity 0–100 | x c d | missing |
| `chatvisible` | `CHATVISIBLE(<value>)` | Chat visibility | x c d | missing |

---

## Mod / Config / Task control (mixed)

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `config` | `CONFIG(<configname>)` | Switch active configuration | x c d | missing |
| `import` | `IMPORT(<configname>)` | Overlay a configuration | x c d | missing |
| `unimport` | `UNIMPORT()` | Remove a configuration overlay | x c d | missing |
| `exec` | `EXEC(<file.txt>,[taskname],[params]...)` | Run a script file as a task | x c d | missing |
| `isrunning` | `ISRUNNING(<macro>)` | Whether a macro is currently running | x c d | missing |
| `prompt` | `PROMPT(<&target>,<paramstring>,[prompt],[override],[default])` | Display prompt(s) from a param string | x c d | partial‡ |
| `store` | `STORE(<type>,[name])` | Store a value into a list (env-aware) | x c d | missing |
| `storeover` | `STOREOVER(<type>,[name])` | Like STORE but overwrites if exists | x c d | missing |
| `repl` | `REPL` | Open the REPL interface (experimental) [HIDDEN] | x c d | missing |

‡ We have an interactive param resolver (`$$?`, `$$[name]`, etc.); MKB's `prompt` action is the scripted form of that. Partially covered conceptually.

---

## Chat-filter actions — documented in en_gb.xml + ddoerr, **NO decompiled class** (newer)

These appear in the modern `en_gb.xml`/ddoerr docs but have **no `.java` class in our decompile** — they are newer than our decompiled build (or compiled into a class our dump split differently). Flagged as a version-skew finding.

| Keyword | Signature | Description | Sources | Our Status |
|---|---|---|---|---|
| `chatfilter` | `CHATFILTER(<enabled>)` | Enable/disable the chat filter | x d | missing |
| `filter` | `FILTER` | Mark this chat message as filtered and terminate | x d | missing |
| `pass` | `PASS` | Mark this chat message to pass the filter and terminate | x d | missing |
| `modify` | `MODIFY(<newmessage>)` | Replace this chat message's content | x d | missing |

> These power the `onFilterableChat` event (chat interception). The decompile *does* have `OnFilterableChatProvider.java`, so the event plumbing exists even though these four action classes weren't in the dumped `actions/**`.

---

## Cross-source discrepancies (findings)

**In `en_gb.xml`/ddoerr but missing a decompiled `actions/**` class** (newer / version skew):
`chatfilter`, `filter`, `modify`, `pass`.

**Decompiled classes with NO en_gb.xml doc entry** (undocumented but real):
`getiteminfo`, `selectchannel`, `sendmessage` — *(remain undocumented even on ddoerr)*; plus `arraysize`, `indexof`, `pop`, `push`, `put` were undocumented in this XML but **have since been documented on ddoerr** (added to the modern docs).

**Decompiled / xml but NOT in ddoerr's top-level action index** (ddoerr documents them only as block-closers or omits):
`assign`, `endif`, `endunsafe`, `loop`, `next` (block-closers, documented under their openers), `tileid`, `tilename` (ddoerr only lists `itemid`/`itemname`), `achievementget`, `selectchannel`, `sendmessage`, `getiteminfo`.

**Totals:** xml=119 named, classes=123, ddoerr≈117 → **127 unique action keywords** in the union. Our engine implements **31** of them (33 keywords incl. 2 non-MKB extras `calc`/`length`).
