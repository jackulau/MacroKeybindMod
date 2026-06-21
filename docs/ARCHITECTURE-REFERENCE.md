# Macro / Keybind Mod — Architecture Reference (for a from-scratch Fabric reimplementation)

This document reverse-engineers the **non-scripting** parts of the original *Macro / Keybind Mod* (a LiteLoader client mod, package root `net.eq2online.macros`) and maps each subsystem to its modern **Fabric / Fabric API** equivalent. It is the design reference for rebuilding the mod from scratch on Fabric.

Source of truth: `net/eq2online/macros/` (and `net/eq2online/util/`, `net/eq2online/xml/`). The decompiler left vanilla Minecraft classes obfuscated (e.g. `bib` = `Minecraft`, `bkn` = `GuiChat`, `bid` = `GameSettings`, `nf` = `ResourceLocation`); obfuscated names are noted inline where they matter.

> Scope note: the `scripting/` package (the macro language, parser, actions, variables, iterators, REPL engine) is intentionally **out of scope** here except where the non-scripting subsystems plug into it (event variable providers, module loading, permissions). The script engine is its own design document.

---

## 0. Big-picture model

A user binds **macros** to **triggers** (keyboard keys, mouse buttons, mouse wheel, in-game custom-GUI buttons, or game events). Each macro holds a small script (the "macro language") plus parameters and flags. At runtime the mod intercepts raw input *below* Minecraft's keybinding layer, decides whether to fire a macro and/or let the normal key function through ("**override**"), and runs the macro's script asynchronously across ticks. A parallel **event system** fires event-bound macros (onChat, onJoinGame, health change, etc.). All binds live in a per-server-switchable **config/layout** persisted to a plain-text `.macros.txt` file. A large **custom GUI toolkit** (its own widgets, dialogs, list boxes, a code editor, an in-world button bar, overlays, and a drag-and-drop custom-GUI builder) sits on top of vanilla screens. **Permissions** (client-side, server-gated via LiteLoader's ClientPermissions) can disable individual script actions/events. A **module loader** allows runtime add-ons (the bundled `chatfilter` module is the worked example).

Key singletons (created during init, see §1):
`MacroModCore` → owns `Macros` (the registry/executor), `InputHandler`, `MacroEventManager` (inside `Macros`), `SettingsHandler`, `ServerSwitchHandler`, `AutoDiscoveryHandler`, `OverlayHandler`, `ThumbnailHandler`, `ScreenTransformHandler`, `LocalisationHandler`, `ChatHandler`, `UserSkinHandler`.

---

## 1. Mod lifecycle & entry

### 1.1 LiteLoader entry point — `LiteModMacros`
`net/eq2online/macros/LiteModMacros.java`

`LiteModMacros` is the LiteLoader "LiteMod" class. It implements a long list of LiteLoader callback interfaces (verbatim, lines 34–47):

| LiteLoader interface | Callback method | What it does |
|---|---|---|
| `Tickable` | `onTick(bib mc, float partial, boolean inGame, boolean clock)` | Per-client-tick pump → `core.onTick(...)`. `clock` is true once per 20 ticks. |
| `InitCompleteListener` | `onInitCompleted(bib mc, LiteLoader loader)` | Post-init: `core.onInitCompleted()`, registers the IMC variable provider into all script contexts. |
| `ChatListener` | `onChat(hh chat, String message)` | Inbound chat → `chatHandler.onChat(message)`. |
| `OutboundChatFilter` | `onSendChatMessage(String message)` → `boolean` | Outbound chat *filter* (return false = block) → `chatHandler.onSendChatMessage(message)`. |
| `RenderListener` | `onRender()` | → `core.onRender()` → `ScreenTransformHandler.onRender()` (injects macro entries into the Controls screen). |
| `PostRenderListener` | `onPostRender(float partial)` | → `core.onPostRender()` → `InputHandler.performDeepInjection()` (low-level synthetic input). |
| `GameLoopListener` | `onRunGameLoop(bib mc)` | → `core.onTimerUpdate()` → `InputHandler` per-frame buffer refresh. |
| `JoinGameListener` | `onJoinGame(hb netHandler, jh joinPacket, bse serverData, RealmsServer realms)` | → `core.onServerConnect(netHandler)` (sets up per-server state, triggers auto-switch). |
| `Permissible` | `registerPermissions(PermissionsManagerClient pm)` | → `MacroModPermissions.init(this, pm)` + `core.onPermissionsChanged()`. |
| `Configurable` | `getConfigPanelClass()` → `GuiMacroConfigPanel.class` | LiteLoader in-launcher config panel. |
| `PacketHandler` | `getHandledPackets()` / `handlePacket(...)` | Handles S2C collect-item packet (`ks.class`) → `core.onItemPickup()` (drives `onPickupItem` event). |
| `Messenger` | `getMessageChannels()` / `receiveMessage(Message)` | Inter-mod comms (IMC) channels `macros:version`, `macros:variable`. |
| `ViewportListener` | `onViewportResized(...)`, `onFullScreenToggled(...)` | Empty stubs. |

There is no annotation-based registration; LiteLoader discovers the class by name and the interfaces it implements.

### 1.2 Init sequence (ordered)
`net/eq2online/macros/core/MacroModCore.java`

**Phase 1 — `LiteModMacros.init(File configPath)`** (early): `this.core = new MacroModCore(Minecraft)`, cache `chatHandler`.

**Phase 2 — `MacroModCore(...)` constructor** creates singletons in this order:
1. `BetaExpiryCheckHandler` (background thread, pings an expiry URL — drop for the rebuild).
2. `LocalisationHandler` → registers itself as Minecraft's i18n provider (`I18n.setProvider(...)` and into the language manager).
3. `ChatHandler` (empty listener registry).
4. `Macros macros = new Macros(mc, this); macros.prepare();` — **builds the macro registry, the script contexts, loads `.macros.txt`, and runs the module loader** (see §5, §7).
5. `InputHandler`.
6. `AutoDiscoveryHandler`.
7. `ServerSwitchHandler`.
8. `ThumbnailHandler`.
9. `ScreenTransformHandler`.
10. Cache display resolution.

**Phase 3 — `LiteModMacros.onInitCompleted(...)` → `MacroModCore.onInitCompleted()`** (deferred, after all mods loaded):
1. Create the legacy font renderer.
2. `inputHandler.init(LiteLoader.getInput())` — register with LiteLoader's input API.
3. `thumbnailHandler.init()`.
4. `macros.init(inputHandler)` — inject the input handler into the registry.
5. `UserSkinHandler`.
6. `OverlayHandler` (the in-world overlay renderer).
7. `macros.postInit()`.
Then `LiteModMacros` builds `VariableProviderIMC` and registers it into every `ScriptContext`.

### 1.3 Per-tick loop
`MacroModCore.onTick(partial, inGame, clock)` branches:
- `onTickInGame(...)`: update skins, update resolution, **render overlays**, `inputHandler.processBuffers(true,false)` (consume input), `macros.onTick(clock)` (advance all running macros + event queue), and once-per-second (`clock`) → `update()`.
- `update()` (1 Hz): every 20 ticks re-scans input devices; ticks localisation; `switchHandler.handleAutoSwitch()`; `inputHandler.onTick()` (fires key-edge macros); `overlayHandler.onTick()`; `autoDiscoveryHandler.onTick()`.
- `onTickInGUI(...)`: thumbnail bookkeeping; lazy input init on first screen; detect disconnect/main-menu screens; process input buffers when no world is loaded.

### 1.4 Rendering hooks
- `onRender()` → `ScreenTransformHandler` rewrites the vanilla Controls (key-binding) screen to insert macro key entries.
- `onPostRender()` → `InputHandler.performDeepInjection()` (synthetic cursor/keypress injection for playback).
- In-game overlay draw happens inside `onTickInGame` via `OverlayHandler.drawOverlays(mouseX, mouseY, ...)`.

### 1.5 Chat interception
`net/eq2online/macros/core/handler/ChatHandler.java` keeps a list of `IChatEventListener`.
- **Inbound**: `onChat(message)` strips colour codes and broadcasts `onChatMessage(raw, clean)` to every listener.
- **Outbound**: `onSendChatMessage(message)` ANDs every listener's vote; any `false` blocks the message (used by the chat-filter module and `onSendChatMessage` event).

### 1.6 Mixins (vanilla touch-points)
`net/eq2online/macros/core/mixin/`
- `MixinGuiChat` (targets `GuiChat`): embeds the macro button bar + mini-toolbar + context menu directly into the chat screen; intercepts `initGui`, `drawScreen`, `keyTyped` (Alt+key design shortcuts), `mouseClicked`, `updateScreen`.
- `MixinGameSettings` (targets `GameSettings`): adds display names for virtual keys `-37`=Scroll Up, `-36`=Scroll Down (so the Controls screen can render mouse-wheel binds).
- `MixinEntityLivingBase` (targets `EntityLivingBase.onItemPickup`): redirects the collect-item packet send to stamp the real pickup **quantity** into the packet so `onPickupItem` can report the amount.
- The `core/mixin/` directory also holds many `@Mixin` accessor interfaces (`IKeyBinding`, `IKeyEntry`, `IGuiControls`, `IGuiKeyBindingList`, `ILocale`, `II18n`, `IRenderGlobal`, `IThreadDownloadImageData`, recipe accessors…) used for reflection-free field access.

### 1.7 Resources
`net/eq2online/macros/res/ResourceLocations.java` enumerates the texture/font `ResourceLocation`s under the `macros` namespace (GUI atlases `macros_gui_main.png`, `lib_gui_parts.png`, custom fonts, colour-picker textures, icon atlases for players/towns/homes/friends/shaders).

### Modern Fabric mapping — lifecycle
Replace the LiteMod class with a `ClientModInitializer` (`onInitializeClient`) registered in `fabric.mod.json` (`entrypoints.client`). Map each LiteLoader hook:

| LiteLoader hook | Fabric / Fabric API equivalent |
|---|---|
| `Tickable.onTick` | `ClientTickEvents.END_CLIENT_TICK` (and/or `START_CLIENT_TICK`). The `clock`/1 Hz cadence becomes a manual `tickCounter % 20` check. |
| `RenderListener` / overlay draw | `HudRenderCallback` (in-world HUD overlays) and `WorldRenderEvents` if 3D. |
| `PostRenderListener` (deep input inject) | A custom mixin into the input/mouse handler, or `ClientTickEvents` — there is no exact API; see §3. |
| `GameLoopListener.onRunGameLoop` | No per-frame Fabric event; use a mixin on `MinecraftClient.run`/`tick` or fold into tick events. |
| `ChatListener.onChat` (inbound) | `ClientReceiveMessageEvents.CHAT` / `.GAME` (and `ALLOW_*` variants to cancel). |
| `OutboundChatFilter.onSendChatMessage` | `ClientSendMessageEvents.ALLOW_CHAT` (return false to block) + `ClientSendMessageEvents.CHAT` (observe/modify). |
| `JoinGameListener.onJoinGame` | `ClientPlayConnectionEvents.JOIN` (and `DISCONNECT`). |
| `Configurable.getConfigPanelClass` | ModMenu API (`ModMenuApi.getModConfigScreenFactory`) if a launcher config screen is wanted. |
| `PacketHandler` (collect-item) | Mixin into the S2C pickup packet handler, or `ClientPlayNetworking` for modded channels; vanilla pickup detection needs a mixin/`ClientTickEvents` inventory diff. |
| `Messenger` (IMC) | Fabric has no built-in IMC; use a custom networking channel or a shared API jar. |
| `Permissible.registerPermissions` | No client-permission system in Fabric; see §6 (likely drop or reimplement). |
| Mixins (GuiChat, GameSettings, EntityLivingBase) | Keep as Fabric mixins (`ScreenEvents` can replace much of `MixinGuiChat`; see §8). |
| `I18n` provider | Use vanilla `Text.translatable` + a `assets/<modid>/lang/*.json`; no custom provider needed. |

---

## 2. Macro model

### 2.1 What a "bound macro" is
A bound macro is the pairing of a **trigger** (`MacroTriggerType` + an integer `id`) with a **`MacroTemplate`** (the saved definition) that is instantiated into a runtime **`Macro`** when fired.

### 2.2 `MacroTemplate` — the persisted definition
`net/eq2online/macros/core/MacroTemplate.java` (954 lines)

A template is the editable, serialised unit. Notable fields:
- `id` (int) and `macroType` (`MacroTriggerType`) — the bind identity.
- `playbackType` (`MacroPlaybackType`: `ONESHOT` / `KEYSTATE` / `CONDITIONAL`).
- Script bodies: `keyDownMacro`, `keyHeldMacro`, `keyUpMacro`, and `macroCondition` (boolean expr for CONDITIONAL).
- `repeatRate` (ms between repeats while held in KEYSTATE).
- Modifier requirements: `requireControl`, `requireAlt`, `requireShift`.
- **`alwaysOverride`** (the "run alongside the normal key function" flag — see §3.3).
- `global` (active in every config), `inhibitParamLoad`, `debounceTicks`.
- Parameter storage: a normal `parameter`, a `namedParameters` TreeMap (`%name%`), special typed params (`item`, `friend`, `onlineUser`, `town`, `warp`, `home`, `place`, `file`) each with a "first-occurrence-only" flag, and a 10-slot `presetText[]` array.
- Script-language state maps: `flags` (`%flag%` booleans), `counters` (`%#counter%` ints), `strings` (`%&string%`), and control `properties` (for CONTROL-type designable controls).

Key methods: `createInstance(checkModifiers, context)` (validates modifiers, applies debounce, returns a `Macro`), `saveTemplate(PrintWriter)` / `loadFrom(line,key,value)` (the `Macro[id].*` property format — see §5.3), and typed accessors for params/flags/counters/strings.

### 2.3 `Macro` — the runtime instance
`net/eq2online/macros/core/Macro.java` (524 lines)

Created per trigger fire. Holds the compiled script (`MacroActionProcessor` for down/held/up), execution context, instance variables, and runtime flags: `built`, `killed`, `dirty`, `stop`, `keyWasDown`, `synchronous`, `lastTriggerTime`. Lifecycle: `compile()` → `build()` (lazy param substitution) → `play(triggerActive, clock)` returns true while still running. `getDisplayName()` resolves via `triggerType.getName(...)`. `kill()` tears down actions.

### 2.4 `MacroTriggerType` — the trigger kinds
`net/eq2online/macros/core/MacroTriggerType.java`

Enum with four kinds, each owning an **id range** (so a single integer `id` self-identifies its trigger kind via `fromId(int)`), plus `MAX_TEMPLATES = 10000`:

| Trigger type | id range | Supports params | Supports playback modes | Meaning |
|---|---|---|---|---|
| `KEY` | 0–254 (keyboard + mouse codes) | yes | yes | A keyboard key or mouse button/wheel. Name resolves to LWJGL key name, or special: 248=MWHEELUP, 249=MWHEELDOWN, 250=LMOUSE, 251=RMOUSE, 252=MIDDLEMOUSE, 253/254=MOUSE3/4, 240–245=MOUSE5–10. |
| `CONTROL` | 256–999 (ext 3000–9999) | yes | yes | A button/control inside a user-built custom GUI layout. Name from `LayoutManager`. |
| `EVENT` | 1000–1999 | yes | no (no held/conditional) | A game event (onChat, onJoinGame, …) from `MacroEventManager`. |
| `NONE` | 2000–2999 | no | no | A free-standing named macro ("MACRO1"…) not tied to a physical trigger. |

### 2.5 `Macros` — registry & executor
`net/eq2online/macros/core/Macros.java` (850 lines), extends `MacroStorage` (§5).

- Storage: inherited from `MacroStorage` — a `baseTemplates: MacroTemplate[10000]` array plus a `configs: Map<String, MacroTemplate[10000]>` of named per-server configs. Binds are looked up by integer `id` directly into the array (the `id` *is* the key code / control id / event id), with optional **overlay** (the active per-server config shadows the base array).
- Lookup + fire: `playMacro(int key, checkModifiers, ScriptContext, IVariableProvider)` → `getMacroTemplate(key, useOverlay)` → `template.createInstance(...)` → if `play()` returns true, add to `executingMacros`.
- Per-tick: `onTick(clock)` iterates `executingMacros`, calling `macro.play(InputHandler.isTriggerActive(id), clock)`; uses `pendingAdditions`/`pendingRemovals` to mutate the list safely during iteration.
- Query helpers used by the input layer: `isMacroBound(key, overlay)`, `isMacroGlobal`, `getMacroType`, `isKeyAlwaysOverridden(key, overlay, check)`.

### 2.6 `MacroTemplate` vs `Macro` vs parameters
- `MacroParams` (`core/MacroParams.java`) scans a script for `$$` placeholders (regex on unescaped `\x24\x24`) and walks a list of providers (NORMAL, ITEM, FRIEND, USER, TOWN, WARP, HOME, PLACE, PRESET, FILE) to substitute values (prompting via GUI when needed) before execution.
- The `core/params/` package contains a `MacroParam<T>` base plus one subclass per param type (`MacroParamItem`, `MacroParamFriend`, `MacroParamPlace`, `MacroParamPreset`, …) and a parallel `MacroParamProvider*` set — these drive the parameter-picker GUIs and auto-discovery.
- `MacroPlaybackType` (`core/MacroPlaybackType.java`): `ONESHOT` (run down-script once), `KEYSTATE` (down on press, held on repeat, up on release), `CONDITIONAL` (eval condition → down or up branch).

### Modern Fabric mapping — macro model
The model is plain data + behaviour and is engine-agnostic; port it almost verbatim as POJOs/records. Replace LWJGL-2 key codes (the 0–254 scheme + negative mouse codes) with **GLFW** key/mouse codes used by Fabric (`GLFW.GLFW_KEY_*`, `GLFW.GLFW_MOUSE_BUTTON_*`, scroll handled separately). Define your own stable id-space for KEY/CONTROL/EVENT/NONE (you no longer need to pack everything into one LWJGL keycode int, but keeping a single-int id keeps serialization simple). Keep `MacroTemplate`/`Macro` split. The `$$`/`%var%` substitution stays inside the (separate) script engine.

---

## 3. Trigger & input handling

`net/eq2online/macros/input/`

### 3.1 Interception strategy
`InputHandler.java` (~760 lines) is the core. It does **not** rely on Minecraft keybindings or LiteLoader callbacks for the hot path — it reaches *under* the game by reflecting LWJGL-2's `Keyboard` and `Mouse` internal buffers (`readBuffer`, `keyDownBuffer`, mouse `buttons`) in `update()`, then each tick `processBuffers()` drains those buffers, decides per event whether to consume or pass it through, and writes the surviving events back so vanilla still sees them.

### 3.2 Edge detection & state
- `pressedKeys: boolean[256]` tracks "already handled this press" to detect the down-edge.
- A queue of `InputEvent` is built from the LWJGL event buffer; on a true→ state where `pressedKeys[key]` is false it calls `handleKey(...)` then sets `pressedKeys[key]=true`; on key-up it clears it.
- `handleKey(key, overrideDown, modifierDown)` ultimately calls `macros.autoPlayMacro(key, ...)`, and the playback type (ONESHOT/KEYSTATE/CONDITIONAL) + `repeatRate` decide repeat/hold/up behaviour. `isKeyDown(code)` reads `Keyboard.isKeyDown` for codes 0–254 and `Mouse.isButtonDown(code+100)` for negative mouse codes. A **fallback mode** uses `Keyboard.getEventKey()` polling when direct buffer access fails.

### 3.3 The override mechanism (run-alongside vs replace)
This is the defining feature. For each raw key/mouse event:
1. Compute `override = macros.isKeyAlwaysOverridden(id, true, false)`.
2. XOR with the **MACRO OVERRIDE key** state (a reserved key the user can hold to flip override on/off live).
3. If `override && canOverride && isScreenOverridable(currentScreen) && macros.isMacroBound(id)` → **consume** the event (`consumeEvent=true`) and mark the key in a shadow `keyDownOverrideBuffer`; vanilla never sees it → the macro runs *instead of* the normal key function.
4. Otherwise the event is written back to LWJGL → both the macro **and** the vanilla keybinding fire (run-alongside).
5. `updateKeyStateBuffer()` zeroes the real `keyDownBuffer` for overridden keys to stop vanilla repeat.

`IProhibitOverride` (marker interface) on a `GuiScreen` makes `isScreenOverridable()` return false, so sensitive screens (the bind editor, sign editor, command block, etc.) are protected from macro interference. Reserved keys (default `"59,61,68,87"` = F1/F3/etc.) always require the override key — configured via the `input.keys.reserved` setting (§5.2) and edited in `GuiEditReservedKeys`.

### 3.4 Mouse & wheel
Mouse buttons mirror the keyboard path (consume vs pass-through). The mouse wheel arrives as a single delta; `readMouseEvents()` converts a positive delta into a synthetic MWHEELUP press and negative into MWHEELDOWN, with per-tick damping counters to avoid multi-fire.

### 3.5 Injection & pluggable modules
- `InputHandlerInjector.java` is an ASM transformer — but note it only patches the permission/obfuscation stubs (`MacroModPermissions.b()`, `ObfTbl$1.bb()`) for tamper-checking; the actual input pump is driven from the tick/game-loop callbacks, not from this injector.
- `IInputHandlerModule` is an abstraction for extra input sources; `InputHandlerModuleJInput.java` implements it for **JInput** game controllers/joysticks — it reads controller buttons/axes from a `.jinput.txt` mapping file, maps buttons to virtual key ids, writes button states into `keyDownBuffer`, and feeds analog axes into `Mouse.dx/dy` via reflection (joystick-as-mouse).
- `CharMap.java` maps characters ↔ key codes (a serialised `{char,code}` table) for the key-pump / synthetic typing feature.

### Modern Fabric mapping — input
LWJGL-2 buffer reflection is gone; LWJGL-3 / GLFW is callback-based and Minecraft owns the callbacks.
- **Normal binds**: register `KeyBinding`s via `KeyBindingHelper.registerKeyBinding(...)` and poll `wasPressed()`/`isPressed()` in `ClientTickEvents`.
- **Override / consume-the-key**: there is no public API to "swallow" a vanilla key. Recreate it with a **mixin** on `Keyboard.onKey` / `Mouse.onMouse` (or `MinecraftClient.handleKeyboardInput`) that, when a macro is bound-and-overriding, cancels the callback (`ci.cancel()`) before vanilla keybinding state updates — this is the direct analogue of `consumeEvent`/`keyDownOverrideBuffer`. The run-alongside case = simply don't cancel.
- **Mouse wheel**: mixin on `Mouse.onMouseScroll` (GLFW scroll callback) → synthesize MWHEELUP/DOWN triggers.
- **`IProhibitOverride`**: replace with a check against the current `Screen` type (or a marker interface on your own screens) inside the mixin.
- **JInput / controllers**: GLFW has a native gamepad/joystick API (`GLFW.glfwGetGamepadState`); reimplement the JInput module against that, or use a controller library. Polling fits naturally into `ClientTickEvents`.

#### Implemented: the input bridge (`FabricInputController`)
The engine's `key`/`keydown`/`keyup`/`press`/`look`/`turn` actions call a platform `dev.macromod.engine.action.InputController` (pure-JVM interface; the engine ships only `NoOp`). `fabric/.../FabricInputController.kt` is the live implementation — the input counterpart to `FabricOutputSink` — wired in via `MacroEngine(input = …)` in `MacroModClient`.
- **Logical key map**: logical names → the *game's own* `KeyMapping`s on `Minecraft.getInstance().options` (`attack`→`keyAttack`, `forward`→`keyUp`, `sneak`→`keyShift`, `swapHands`→`keySwapOffhand`, hotbar `"1".."9"`→`keyHotbarSlots[0..8]`, …), resolved lazily once `options` exists and cached. Tapping a logical key is therefore identical to the player pressing their *bound* key — rebinds are respected and vanilla `isDown()` consumers see it. Unknown names are ignored.
- **Press/hold/release**: `KeyMapping.setDown(boolean)` (stable Mojmap on every targeted era ≥1.16). `tap()`/`press()` set the key down and queue it; `hold()` is sticky; `release()` clears. The bridge calls `controller.endClientTick()` at the **start** of each `END_CLIENT_TICK`, releasing the *previous* tick's taps — so a tap stays down across one full client tick (long enough for the player tick to poll `isDown()`) then lets go, like a real keystroke.
- **Rotation**: `look(yaw,pitch)` (pitch clamped to ±90) / `turn(dYaw,dPitch)` set the local player's rotation, plus `yRotO`/`xRotO` (always public) so the next render frame doesn't interpolate from the old angle and snap. This is the one version-divergent spot: **≥1.17** uses `Entity.setYRot/​setXRot` (the `yRot`/`xRot` fields went private); **1.16.x** assigns the public `yRot`/`xRot` fields directly — split with two independent Stonecutter `//? if` blocks (a multi-line `else` body desyncs its comment markers on flip).
- **Version gate**: the whole controller is gated `>=1.16` (same floor as the tick/keybind loop and `FabricOutputSink`). On 1.14.4/1.15.2 there is no tick loop to release taps, so the engine keeps its `InputController.NoOp` default there (no-op, still builds). A demo: the `H` hotkey macro runs `key("jump"); look(0,0)`, so pressing `H` in-world exercises the bridge end to end.

#### Implemented: the navigation binding (`FabricNavigator`)
The engine's `goto(x,y,z)` / `stopnav` actions call a platform `dev.macromod.engine.action.Navigator` (pure-JVM interface; the engine ships only `NoOp`). `fabric/.../FabricNavigator.kt` is the live implementation — the navigation counterpart to `FabricInputController`/`FabricOutputSink` — wired in via `MacroEngine(navigator = …)` in `MacroModClient` and advanced by a `navigator.tick()` call inside the same `END_CLIENT_TICK` loop. It is the binding that makes `goto` actually walk the player; the A\* search and per-tick movement decisions themselves live in the pure-JVM `:engine` (`Pathfinder`, `PathExecutor`) and stay unit-tested without Minecraft.
- **World `BlockView`**: a `dev.macromod.pathfinding.BlockView` over `Minecraft.getInstance().level`. A block is **solid** iff `BlockState.isCollisionShapeFullBlock(level, pos)` — a *full collidable block* (dirt/stone), not a slab/stair/torch/air — which is exactly what the pathfinder needs (full ground under the feet, a clear feet+head column). Crucially this is a single **non-deprecated, identically named instance method on `BlockStateBase` across the whole 1.16→1.21.x range** under Mojmap, so the navigator needs **no inner version gates** — it sidesteps the `Material.isSolid()/blocksMotion()` → `BlockState` migration (and `Material`'s removal) at ~1.20. **Unloaded chunks read as solid** (via `level.hasChunk(chunkX, chunkZ)`, inherited from `LevelAccessor`) so the search never paths into terrain it cannot see.
- **`pathTo(x,y,z)`**: snaps the player's feet to a `Vec3i` via `LocalPlayer.blockPosition()` (floors the entity position), runs `Pathfinder(worldView).findPath(start, goal)`, and — if a route is found — stores it with a fresh `PathExecutor` (`isNavigating = true`) and returns `true`; no path → `false`, nothing changes.
- **`tick()`**: reads the live feet position, asks the `PathExecutor` for this tick's `MovementCommand`, calls `input.look(yaw, 0)` and `input.hold(key)` for each requested key, **releasing** any held movement key the command no longer wants (e.g. drops `jump` once a climb finishes). When the executor is done (null step) it stops.
- **`stop()`** / **`stopnav`**: clears the route and releases every movement key it held.
- **Version gate**: the whole class is gated `>=1.16` (same floor as the input/keybind/tick loop). On 1.14.4/1.15.2 the engine keeps its `Navigator.NoOp` default (no-op, still builds). A demo: the `G` hotkey builds `$${ goto(x, y, z+5) }$$` from the live feet position at press time (always a loaded, nearby target), exercising the navigator end to end.

---

## 4. Event system

`net/eq2online/macros/event/`

### 4.1 `MacroEvent`
`MacroEvent.java`: an immutable event definition — `name` (e.g. "onChat"), owning `provider`, `permissible`/`permissionGroup`, `icon`, and a lazily-resolved `IMacroEventVariableProvider` constructor. On a name like `onChat` it reflectively loads `event.providers.OnChatProvider` to expose script variables; if none exists the event still fires with no variables. `getVariableProvider(String[] args)` instantiates the provider and `initInstance(args)` fills it so scripts can read named variables (e.g. `CHAT`, `CHATPLAYER`).

### 4.2 `MacroEventManager`
`MacroEventManager.java` (~400 lines): the dispatcher.
- Providers register via `registerEventProvider(provider)`; each provider's `registerEvents(manager)` runs with `activeProvider` guarded so only the active provider can register. Each event gets a unique id and is stored in `eventsByName` / `eventsByID`.
- Dispatch path: `sendEvent(name, priority, args...)` → if synchronous (priority 100 or `Integer.MAX_VALUE`) `dispatchEvent(...)` immediately, else enqueue a `MacroEventQueueEntry` in a `PriorityQueue` (priority 0–100, FIFO within a priority via a sequence number). `dispatchEvent` resolves the event, builds its variable provider from the args, calls `onDispatch()`, then `macros.playMacro(eventId, false, ScriptContext.MAIN, variableProvider, synchronous)` to run all macros bound to that event id (EVENT trigger range 1000–1999).
- Per-tick (`onTick`): ticks all registered `dispatchers` (they sample game state and enqueue events), then dequeues **one** event per tick (frame-lag protection) — except synchronous events which bypass the queue.

### 4.3 Built-in events
`event/BuiltinEvent.java` + `event/MacroEventDispatcherBuiltin.java` + `event/providers/*`. The built-in dispatcher uses **value watchers** (`MacroEventValueWatcher`) and a **list watcher** (`MacroEventListWatcher`) to detect changes each tick.

| Event | Trigger | Variables exposed |
|---|---|---|
| `onJoinGame` | Join a world/server | — |
| `onChat` | Inbound chat | `CHAT` (raw), `CHATCLEAN` (no colours), `CHATPLAYER` (guessed sender), `CHATMESSAGE` (body) |
| `onSendChatMessage` | About to send chat (synchronous, **blockable**) | `CHAT`; script can accept/reject |
| `onFilterableChat` | Inbound chat when `showFilterableChat` enabled | `CHAT` |
| `onPickupItem` | Item picked up | `PICKUPID`, `PICKUPITEM`, `PICKUPAMOUNT`, `PICKUPDATA` |
| `onPlayerJoined` | Another player appears in the tab list | `JOINEDPLAYER` (list watcher) |
| `onInventorySlotChange` | Hotbar selection changes | `OLDINVSLOT` |
| `onHealthChange` / `onFoodChange` / `onOxygenChange` | Player vitals change (watchers) | — |
| `onArmourChange` / `onArmourDurabilityChange` | Armour rating / per-slot durability changes | — |
| `onItemDurabilityChange` | Held item durability drops | — |
| `onXPChange` / `onLevelChange` | Experience / level change | — |
| `onModeChange` | Game mode change | — |
| `onWorldChange` | Dimension/world change | — |
| `onWeatherChange` | Weather change | — |
| `onShowGui` | A GUI screen opens/closes | — |
| `onAutoCraftingComplete` | Auto-craft task finishes | `REASON` |
| `onConfigChange` | Active config changed | — |

### 4.4 How providers hook the game
The built-in dispatcher is fed by the mod's other hooks (chat handler, the collect-item packet handler, the tick loop) and by polling player state each tick through the watchers (e.g. `healthWatcher.checkValueAndDispatch(currentHealth)`; the list watcher diffs the tab-player list). `onSendChatMessage` runs synchronously at max priority and uses a per-message UUID so the script's accept/reject vote can be read back before the message is allowed.

### Modern Fabric mapping — events
Keep the manager/provider/queue architecture; replace each provider's *source*:
- `onChat`/`onFilterableChat` → `ClientReceiveMessageEvents`.
- `onSendChatMessage` → `ClientSendMessageEvents.ALLOW_CHAT` (synchronous block fits perfectly).
- `onJoinGame`/`onWorldChange` → `ClientPlayConnectionEvents.JOIN` + a dimension check in tick.
- `onPickupItem` → mixin on the pickup packet (as today) or inventory diff.
- `onPlayerJoined` → diff `ClientPlayNetworkHandler.getPlayerList()` each tick (or `EntityTrackingEvents`).
- Vital/stat/slot/durability/xp/level/mode/weather watchers → poll `MinecraftClient.player` in `ClientTickEvents` (the existing watcher classes port directly).
- `onShowGui` → `ScreenEvents.AFTER_INIT` / screen open-close callbacks.
- The priority queue + one-event-per-tick drain is plain Java; port verbatim.

---

## 5. Per-server config & storage

### 5.1 `SettingsHandler` + observer pattern
`net/eq2online/macros/core/handler/SettingsHandler.java`: central hub holding the global `Settings` object and two observer lists — `ISettingsObserver` (notified on load/save/clear via `onLoadSettings`/`onSaveSettings`/`onClearSettings`) and `IConfigObserver` (notified on config changed/added/removed). `registerObserver(...)` routes by interface. Many subsystems register here (`Settings`, `AutoDiscoveryHandler`, the chat-filter module, `Game.Settings`).

### 5.2 `Settings` / `SettingsBase`
`core/settings/SettingsBase.java` provides a reflection + annotation framework: fields tagged `@Setting("key")` (optionally `@Comment`, `@Range`) are auto-loaded/saved through an `ISettingsStore`; supports String/int(+range)/boolean/enum. `core/settings/Settings.java` declares the actual keys. Notable ones:

| Key | Type | Default | Purpose |
|---|---|---|---|
| `config.autoswitch` | bool | true | Auto-switch config to match server address. |
| `config.initial` | String | "" | Config selected at startup. |
| `override.enabled` | bool | true | Enable the MACRO OVERRIDE feature. |
| `input.keys.reserved` | String | "59,61,68,87" | Keys that always require the override key. |
| `macrosdirectory` | String | "/liteconfig/common/macros/" | Base directory for config files. |
| `layout.bindings.loadatstartup` | bool | true | Load custom-GUI layouts at startup. |
| `compiler.flags` | String | "" | Feature toggles i/t/w/h/u/f (items/towns/warps/homes/users/friends). |
| `configs.enable.{friends,homes,towns,warps,places,presets}` | bool | false | Make those param lists per-config. |
| `debug.enabled` | bool | false | HUD debug overlay. |
| `script.stripdefaultnamespace` | bool | true | Strip `minecraft:` from item/block names (declared in `util/Game.Settings`). |

### 5.3 `MacroStorage` — persistence (the key part)
`net/eq2online/macros/core/settings/MacroStorage.java` (~700 lines)

- **Files & location**: macros + settings live in **`.macros.txt`** (plain text), with macro params/variables in **`.vars.xml`**. The directory is `<.minecraft>/liteconfig/common/macros/` by default (the `macrosdirectory` setting); legacy fallbacks `<.minecraft>/mods/macros/.macros.txt` and `<.minecraft>/macros.txt` are checked on load.
- **In-memory**: `baseTemplates: MacroTemplate[10000]` (global binds, written before any config directive) + `configs: Map<String, MacroTemplate[10000]>` (per-server/per-name configs). Empty-string key == base.
- **`.macros.txt` line grammar**:
  - Global settings: `key=value` (only parsed while no config is active).
  - Macro property lines: `Macro[<id>].<Property>=<value>` parsed by `MacroTemplate.loadFrom(...)`. Properties include `Macro`, `OnKeyHeld`, `OnKeyUp`, `Condition`, `Mode` (oneshot/keystate/conditional), `RepeatRate`, `Control`, `Alt`, `Shift`, `Global`, `Override`, `Inhibit`, `Param`, `Item`/`Friend`/`Town`/`Warp`/`Home`/`Place`/`File`, `PresetText[n]`, `NamedParam[name]`, `CompilerFlags`.
  - Config section header: `DIRECTIVE BEGINCONFIG() <configName>` — all following macro lines belong to that config.
  - A legacy compact format (`<prefix><id>:<content>`) is still parsed for old files.
- **Save** writes a header + version, then global settings (with comments), then non-null base templates (`saveTemplate`), then each config under its `DIRECTIVE BEGINCONFIG()` header (skipping `global` templates, which only live in base).
- **`.vars.xml`** stores per-config, per-template variable/param state as `<variables><config name="@default"><template id="0">…`; `@default` maps to the empty-string config.

### 5.4 `ServerSwitchHandler` — per-server auto-switch
`net/eq2online/macros/core/handler/ServerSwitchHandler.java`
- `handleAutoSwitch()` (called ~1 Hz after join, once per session via a `haveAutoSwitched` flag) determines the current server:
  - Single-player → switch to the single-player config name (emits "SP").
  - Multiplayer → read the `InetSocketAddress` from the net handler (`getHostName()` + `getPort()`); if it differs from `lastServerName`, call `onConnectToServer(...)`.
- `onConnectToServer` (when `config.autoswitch` is on) looks up a matching config name in priority order: **`host:port`** first, then **`host`** alone; if `macros.hasConfig(name)`, it activates that config and shows a `ConfigOverlay` notification. So a layout is "bound" to a server simply by naming the config after the server address.
- On disconnect the flag resets so the next join re-switches.

### 5.5 XML backend & util
- `xml/PropertiesXMLUtils.java` serialises Java `Properties` + typed arrays to/from an XML `<properties>`/`<entry>`/`<array>` document (used for structured settings/array bundles via `IArrayStorageBundle`); `xml/Xml.java` is a DOM+XPath helper (used for `.vars.xml`); `xml/Xmlns.java` is an XPath namespace context. (Core macro binds themselves are the plain-text `.macros.txt`, **not** XML.)
- `util/Util.java`: `sanitiseFileName(name, ext)`, `parsePositiveInt(...)`. `util/Game.java`: `getPlayerMP()`, `getKeybinding(code)`, `getIngameGui()`, plus the `Game.Settings` observer for `script.stripdefaultnamespace`. `util/Colour.java`/`GlColour.java` are colour helpers for the GUI.

### Modern Fabric mapping — config & per-server
- Config dir: `FabricLoader.getInstance().getConfigDir().resolve("macros")` (or a `mods/macros`-style path if you prefer parity). Keep the human-editable `.macros.txt` format for backwards-compat import, or migrate to JSON/TOML for new installs.
- Server identity: from `ClientPlayConnectionEvents.JOIN`, read `MinecraftClient.getCurrentServerEntry()` / the connection's `SocketAddress`; single-player via `MinecraftClient.isInSingleplayer()`. Same `host:port` → `host` lookup order.
- Observer pattern ports verbatim. The `@Setting`/`@Comment`/`@Range` reflection framework can stay, or be replaced by a config lib (e.g. a simple Gson-backed config or Cloth Config if a GUI is desired).
- XML helpers can be dropped in favour of Gson/`NbtCompound`; only keep an XML reader if you must import legacy `.vars.xml`.

---

## 6. Permissions

`net/eq2online/macros/permissions/MacroModPermissions.java` + `gui/screens/GuiPermissions.java`

- Built on LiteLoader's **ClientPermissions** (`Permissible` + `PermissionsManagerClient`). Permission nodes are dot-hierarchical and registered at startup (`initPermissions()` → registers `*`, then walks every registered script action and event):
  - `*` (all), `script.*`, `script.<group>.*`, `script.<group>.<action>` (per script action), `events.*`, `events.<group>.*`, `events.<name>` (per event), and `spam.nolimit`.
- Checks: `MacroModPermissions.hasPermission(node)`; each `IScriptAction.checkExecutePermission()` and each event gate on their node before running. Denied actions are collected by `ScriptAction.getDeniedActionList(context)`.
- **Server gating**: `refreshPermissions(mc)` queries the server, which decides which nodes the client holds — i.e. a server can disable specific macro actions/events for connected clients. A `tamperCheck()` (the only thing `InputHandlerInjector` actually patches in) is invoked around register/check calls to deter local bypass.
- `GuiPermissions` surfaces: a title, a "last updated from server" banner, a red grid of currently **denied** actions, a manual Refresh button (with cooldown), and a "generate permissions warnings" checkbox.

### Modern Fabric mapping — permissions
Fabric has **no** client-permission/ClientPermissions equivalent and no server→client permission negotiation channel out of the box. Options: (a) drop server-gating entirely (simplest, and the threat model — "server restricts a client mod" — is weak on Fabric); (b) reimplement as a custom plugin-message channel that a cooperating server-side mod answers; or (c) keep only a *local* allow/deny list editable in a Fabric screen (no server authority). The node taxonomy (`script.<group>.<action>`, `events.<name>`) can be retained for a local toggle UI regardless.

---

## 7. Module system (runtime add-ons)

- **Loader**: `ModuleLoader` (in `scripting/`, invoked from `Macros.prepareScripting()` during init). It scans `<macros-dir>/modules/` for `module_*.zip` / `module_*.jar` (and a `-Dmacros.modules=` system property), adds them to the LaunchClassLoader, and instantiates classes whose names match a type prefix. All modules carry an `@APIVersion(26)` annotation that is enforced, and implement `IMacrosAPIModule` (single method `onInit()`).
- **Four extension types**, by class-name prefix: `ScriptAction*` → `IScriptAction`, `VariableProvider*` → `IVariableProvider`, `ScriptedIterator*` → `IScriptedIterator`, `EventProvider*` → `IMacroEventProvider`.
- **Registration surface** (`ScriptCore` in `scripting/parser/`): `registerScriptAction(IScriptAction)`, `registerVariableProvider(IVariableProvider)`, `registerIterator(name, class)`, `registerEventProvider(IMacroEventProvider)`. Actions/variables register into a `ScriptContext` (`MAIN` or `CHATFILTER`); events register into `MacroEventManager`.
- **Worked example — `chatfilter`** (`modules/chatfilter/`): `ChatFilterManager` (singleton; implements `ISettingsObserver` + LiteLoader `ChatFilter`) registers script actions `pass`, `filter`, `modify` (in the `CHATFILTER` context) and `chatfilter` (in `MAIN` to enable/disable), persists templates to `.chatfilter.txt`, and ships its own GUI `GuiEditChatFilter`. It intercepts inbound chat, runs it through a chat-filter macro template, and can block/modify/pass each message.

### Modern Fabric mapping — modules
The simplest Fabric-native approach is to **expose an API entrypoint** in `fabric.mod.json` so other mods register script actions/variables/events/iterators at init (Fabric's `entrypoints` + an `@ApiStatus` interface jar) instead of class-name-prefix scanning of loose zips. If runtime drop-in zips are a hard requirement, you can still scan a `modules/` dir and load via a child classloader, but the `@APIVersion` contract + `IMacrosAPIModule.onInit()` design ports directly. Keep the `ScriptContext` (MAIN/CHATFILTER) and `ScriptCore` registration API as-is.

---

## 8. GUI inventory (what must be rebuilt as Fabric screens)

The mod ships a **complete custom GUI toolkit** (146 files under `gui/`) layered over vanilla screens — its own widget hierarchy, dialog system, dropdowns, list boxes, a syntax-highlighting code editor, an in-world button bar, overlays, and a drag-and-drop custom-GUI builder. None of it is interoperable with vanilla/Fabric screen code; a Fabric port must replace the whole toolkit.

### 8.1 Main screens — `gui/screens/`
| Class | Purpose |
|---|---|
| `GuiMacroBind` | Top-level keybind editor: keyboard/mouse/event/button panels + minimizable toolbar. |
| `GuiMacroEdit` | Full macro script editor (down/held/up/condition tabs, syntax highlighting). |
| `GuiMacroEditSimple` | Cut-down macro editor (modifier checkboxes only). |
| `GuiMacroConfig` | Mod-wide config screen (auto-switch, per-config options). |
| `GuiMacroConfigPanel` | LiteLoader in-launcher `ConfigPanel` implementation. |
| `GuiCustomGui` | Runs/renders a user-built custom GUI (playback + context menu). |
| `GuiDesigner` / `GuiDesignerBase` | The custom-GUI **builder** (drag/drop/copy/delete controls on a grid). |
| `GuiMacroParam` | Param picker (items/places/friends/…); import + refresh + auto-discover. |
| `GuiMacroPlayback` | Running-macro status + override controls. |
| `GuiEditText` / `GuiEditTextBase` / `GuiEditTextString` / `GuiEditTextFile` | Inline string editor and full file-backed script editor (highlighted). |
| `GuiCommandReference` | Filterable command/function documentation browser. |
| `GuiEditReservedKeys` | Editor for reserved/override-only keys. |
| `GuiEditThumbnails` | Macro icon/thumbnail picker grid. |
| `GuiEditListEntry` | Edit a list entry (text/name/coords/icon). |
| `GuiPermissions` | Denied-actions viewer (§6). |
| `GuiAutoDiscoverStatus` | Progress dialog for the auto-discovery flow. |
| `GuiChatFilterable` | Chat screen variant that emits filterable-chat triggers. |
| `GuiScreenWithHeader` | Base screen with header banner, paging, dropdown menus. |

### 8.2 Custom GUI framework — `gui/` (top) + `gui/controls/`
- Framework core: `GuiScreenEx` (base screen w/ custom control system, drag, dialog-parent, mini-toolbar), `GuiControl` / `GuiControlEx` (custom buttons replacing vanilla), `GuiRenderer` / `GuiRendererMacros` (texture/tooltip/icon/clipping draw layer), `ChatRenderer`, `IconResourcePack`.
- Widgets (`gui/controls/`): `GuiListBox<T>` (scroll, drag/drop, edit-in-place, icons), `GuiListBoxIconic`, `GuiListBoxFilebound`, `GuiListItemSocket`, `GuiTextFieldEx`, `GuiTextEditor` (multi-line syntax-highlighted code editor w/ undo + doc popups), `GuiLabel`, `GuiDropDownMenu`, `GuiDropDownList`, `GuiCheckBox`, `GuiButtonTab`, `GuiColourPicker`, `GuiColourButton`, `GuiColourCodeSelector`, `GuiScrollBar`, `GuiMiniToolbarButton`. `gui/controls/specialised/` holds typed list boxes (configs, files, friends, homes, items, places, presets, resources, resource-packs, shaders, towns, warps) and a highlighting text field.

### 8.3 Custom-GUI builder — `gui/designable/` (+ `editor/`, `editor/browse/`)
`DesignableGuiControl` (base) + typed controls (`Button`, `Label`, `Icon`, `Slider`, `ProgressBar`, `PlaybackStatus`, `TextArea`, `Ranged`, `Aligned`, `Layout`), `DesignableGuiControls` (registry/factory), `DesignableGuiLayout` (grid container), `LayoutManager` (loads/saves `.gui.xml` layouts + bindings). `editor/` holds the property-editor dialogs (per control type), grid-size dialog, layout patch/undo, and `browse/` import dialogs.

### 8.4 Dialogs — `gui/` + `gui/dialogs/`
`GuiDialogBox` (movable modal base) + `GuiDialogBoxBrowse` (file browser). `gui/dialogs/`: `Confirm`, `YesNoCancel`, `ConfirmWithCheckbox`, `AddConfig`, `RenameItem`, `SaveItem`, `CreateItem`, `ErrorList`, `FirstRunPrompt`, `BetaExpired`.

### 8.5 In-world macro button bar — `gui/layout/`
`LayoutButton` / `LayoutButtonEvent` (a clickable macro button w/ icon + colour states), `LayoutWidget` (base), `LayoutPanel<T>` + `LayoutPanelStandard` / `LayoutPanelKeys` / `LayoutPanelEvents` / `LayoutPanelButtons` (the keyboard-grid / event / button panels), `PanelManager` (lifecycle + settings sync). This is the bar embedded into the chat screen by `MixinGuiChat`.

### 8.6 Overlays — `gui/overlay/`
`Overlay` (base) + `OverlayHandler` (manager); concrete: `DebugOverlay`, `ThumbnailOverlay`, `ConfigOverlay` (active-config popup), `CustomGuiOverlay`, `CraftingOverlay`, `OverrideOverlay` (override-state indicator), `SlotIdOverlay`. `IOverlay` interface.

### 8.7 REPL — `gui/repl/`
`GuiMacroRepl` (interactive script console w/ history, tab-completion, highlighting), `ReplConsoleHistory`, `ReplConsoleTabCompleter`.

### 8.8 List entries — `gui/list/`
`ListEntry<T>` (base) + `ConfigListEntry`, `EditableListEntry`, `EditInPlaceListEntry`, `FriendListEntry`, `OnlineUserListEntry`, `PlaceListEntry`, `ResourcePackListEntry`, `GuiLayoutListEntry`.

### 8.9 Helpers & infra
`gui/ext/` (options-screen integration entries: `BindScreenOptionEntry`, `BindingButtonEntry`, `LinkableKeyEntry`, `OverrideKeyEntry`), `gui/helpers/` (`ItemList`, `ListProvider`, `SlotHelper`), `gui/hook/CustomScreenManager` (registry/factory for plugin screens), `gui/skins/UserSkinHandler` (player-skin loading), `gui/thumbnail/` (`ThumbnailHandler`, `Thumbnailer`, `MacroThumbnailResourcePack` — render macro icons into a dynamic resource pack).

### Modern Fabric mapping — GUI
This is the largest rebuild. Map onto Fabric/vanilla 1.20+ screen APIs:
- Screens → `net.minecraft.client.gui.screen.Screen`; open via `MinecraftClient.setScreen`. Register key-binding-list injection and screen lifecycle via `ScreenEvents` (`AFTER_INIT`, render/key/mouse events) and `ScreenMouseEvents`/`ScreenKeyboardEvents` — these replace much of `MixinGuiChat` and `ScreenTransformHandler`.
- Widgets → vanilla `ButtonWidget`, `TextFieldWidget`, `CheckboxWidget`, `SliderWidget`, `EditBoxWidget`/`MultilineTextWidget`, and ` alwaysList`/`EntryListWidget` for the list boxes. The bespoke `GuiTextEditor` (syntax-highlighted multiline code editor) and `GuiColourPicker`/dropdowns/the designable builder have **no vanilla equivalent** and must be built as custom `Widget`/`Element` implementations (or via a UI lib like owo-ui / Cloth). Budget the code editor, the drag-drop GUI builder, and the in-world `LayoutPanel` bar as the three biggest custom pieces.
- In-world button bar & overlays → `HudRenderCallback` (draw) + `ScreenEvents` (when embedding into the chat screen). Use `DrawContext` for all rendering (the old `GuiRenderer` blit/tooltip helpers map to `DrawContext.drawTexture`/`drawTooltip`).
- Thumbnails-as-resource-pack → render to a `NativeImageBackedTexture` / dynamic texture registered with `TextureManager`, instead of a synthetic resource pack.
- Drop the LiteLoader `ConfigPanel` (`GuiMacroConfigPanel`) in favour of a ModMenu config screen, or fold its options into the in-game `GuiMacroConfig` equivalent.

---

## 9. Cross-cutting Fabric porting checklist

1. **Entry**: `ClientModInitializer` + `fabric.mod.json` entrypoints; rebuild the init order from §1.2 (registry → input → events → settings → overlays).
2. **Input hot path**: mixins on `Keyboard.onKey` / `Mouse.onMouse` / `Mouse.onMouseScroll` to implement consume-vs-passthrough **override**; `KeyBindingHelper` for the reserved/override/REPL keys; GLFW codes throughout (retire LWJGL-2 buffer reflection and the `-36/-37` scheme — pick your own wheel codes).
3. **Events**: keep the manager + queue; re-source each provider from Fabric API callbacks (§4 table).
4. **Chat**: `ClientReceiveMessageEvents` (in) + `ClientSendMessageEvents` (out/block).
5. **Per-server**: `ClientPlayConnectionEvents.JOIN/DISCONNECT` + `getCurrentServerEntry()`; same `host:port`→`host` config-name lookup; keep/Import `.macros.txt`.
6. **Permissions**: likely drop server-gating; optionally keep a local node-based allow/deny screen.
7. **Modules**: prefer a Fabric API entrypoint over zip scanning; keep `ScriptContext`/`ScriptCore` registration.
8. **GUI**: rebuild the whole toolkit on vanilla `Screen`/widgets + `DrawContext`, with custom widgets for the code editor, colour picker, dropdowns, list boxes, the designable-GUI builder, the in-world layout bar, and overlays.
9. **Scripting** (separate doc): the `scripting/` engine, actions, variables, iterators, parser, and REPL are unchanged in concept and plug in through the same `ScriptContext`/event-variable-provider/permission seams described here.

---

## 9b. Fabric bridge — current implementation status

The client entry point (`fabric/src/main/kotlin/dev/macromod/fabric/MacroModClient.kt`) is now a working **bridge** between Fabric input/output and the pure-JVM `:engine` (`MacroEngine`), not just a load-time smoke test. A single `MacroEngine` is created in `onInitializeClient` and driven as follows.

- **Output** (`FabricOutputSink.kt`, implements `dev.macromod.engine.action.OutputSink`):
  - `chat(msg)` — a `/`-prefixed message is sent as a **command**, otherwise as a **chat message**, via the version-correct client API.
  - `log(msg)` — appended to the client chat HUD (`Minecraft.gui.getChat().addMessage(Component)`); falls back to the mod logger when there is no player/connection.
- **Keybind dispatch** — a demo `KeyMapping` (`key.macromod.demo`, default **H**) is registered via `KeyBindingHelper.registerKeyBinding`. On `ClientTickEvents.END_CLIENT_TICK`, queued presses are drained with `consumeClick()` and forwarded to `engine.fireKey(code, sink)`. A demo binding (`H` → `$${ log("MacroKeybindMod: hotkey!") }$$`) is seeded into the registry so a press is visible.
- **Event dispatch** — each tick fires `engine.fireEvent("onTick", sink)` **only when** the registry has `onTick` bindings (no per-tick spam otherwise). On modern versions, `ClientReceiveMessageEvents` (`CHAT`/`GAME`) fires `"onChat"` (also gated to non-empty bindings); a demo `onChat` binding is seeded.
- **Env provider** — a player `EnvProvider` exposes `HEALTH / XPOS / YPOS / ZPOS / YAW / PITCH` (read from `Minecraft.getInstance().player`) on the engine's `variables`.
- **Navigation** (`FabricNavigator.kt`, implements `dev.macromod.engine.action.Navigator`) — `goto(x,y,z)` runs the `:engine` A\* `Pathfinder` over a live-world `BlockView` (`BlockState.isCollisionShapeFullBlock`, unloaded chunks treated as solid) and drives the resulting `PathExecutor` through `FabricInputController` each `END_CLIENT_TICK` (`navigator.tick()`); `stopnav` cancels and releases the movement keys. Wired via `MacroEngine(navigator = …)`. A demo `KeyMapping` (`key.macromod.goto`, default **G**) fires `goto` toward a spot ~5 blocks ahead of the player.

**Version coverage & Stonecutter feature gates** (official Mojang mappings; vcs/source-of-truth branch = 1.21.1). The most divergent client APIs are gated so all 23 targets (1.14.4–1.21.11) still compile:

| Feature | Live on | Gated off below cutoff → fallback |
|---|---|---|
| Keybind + tick wiring (`KeyBindingHelper`, `ClientTickEvents`, `FabricOutputSink`) | **≥1.16** | 1.14.4 / 1.15.2: no tick/keybind loop; sink degrades to logging (engine still runs at load) |
| Chat send | all (≥1.16) | **≥1.19.3**: `connection.sendChat(msg)` / `sendCommand(msg.substring(1))` (slash stripped). **<1.19.3**: `player.chat(msg)` (one method, slash kept) |
| `Text`/`Component` construction | all (≥1.16) | **≥1.19**: `Component.literal(msg)`. **<1.19**: `new TextComponent(msg)` |
| `onChat` from chat-receive (`fabric-message-api-v1`) | **≥1.19.3** | <1.19.3: not wired (no first-party client receive event; would need a Mixin) |
| Player `EnvProvider` (yaw/pitch getters) | **≥1.17** | <1.17: not wired (`getYRot()/getXRot()` getters arrived in 1.17; older eras use `yRot`/`xRot` fields) |
| Navigation binding (`FabricNavigator`, `goto`/`stopnav`) | **≥1.16** | 1.14.4 / 1.15.2: no tick loop; engine keeps `Navigator.NoOp`. No *inner* gates — `BlockState.isCollisionShapeFullBlock(level,pos)`, `level.hasChunk`, `LocalPlayer.blockPosition()` are identically named across the whole ≥1.16 range, sidestepping the `Material`→`BlockState` solidity migration at ~1.20 |
| `KeyMapping` category argument | all (≥1.16) | **≥1.21.9**: `KeyMapping.Category.MISC` (the arg became a `KeyMapping.Category` record). **<1.21.9**: `String` translation key |

The `chat-HUD` chain (`gui.getChat().addMessage`) is uniform across the whole 1.16–1.21 range under Mojmap. The two Fabric API modules the bridge adds (`fabric-key-binding-api-v1`, `fabric-message-api-v1`) are declared conditionally in `fabric/build.gradle.kts` via `stonecutter.eval(current, "…")`, mirroring the source gates.

---

### Appendix — key source paths
- Entry / core: `macros/LiteModMacros.java`, `macros/core/MacroModCore.java`, `macros/AutoDiscoveryHandler.java`, `macros/res/ResourceLocations.java`
- Macro model: `macros/core/{Macro,Macros,MacroTemplate,MacroTriggerType,MacroParams,MacroPlaybackType}.java`, `macros/core/params/*`
- Input: `macros/input/{InputHandler,InputHandlerInjector,InputHandlerModuleJInput,IInputHandlerModule,IProhibitOverride,CharMap}.java`
- Events: `macros/event/*` (+ `event/providers/*`)
- Config/storage: `macros/core/handler/{SettingsHandler,ServerSwitchHandler,ChatHandler,ScreenTransformHandler,LocalisationHandler}.java`, `macros/core/settings/{Settings,SettingsBase,MacroStorage}.java`, `xml/*`, `util/*`
- Permissions: `macros/permissions/MacroModPermissions.java`, `macros/gui/screens/GuiPermissions.java`
- Modules: `macros/modules/chatfilter/*` (+ scripting `ModuleLoader`/`ScriptCore`/`ScriptContext`)
- Mixins: `macros/core/mixin/*`
- GUI: `macros/gui/**` (146 files)
