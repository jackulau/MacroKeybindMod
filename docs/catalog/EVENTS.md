# MKB Event Catalog (Authoritative)

Every event the Macro/Keybind Mod can bind a macro to.

**Sources cross-referenced:**
- **ddoerr** = https://mkb.ddoerr.com/docs/events (index of 21 events) + per-event detail pages (`/docs/events/<name>`) for exposed variables.
- **providers** = decompiled `event/providers/*.java` + `event/MacroEventProviderBuiltin.java` / `BuiltinEvent.java` (ground truth for event names + the variables each sets).

**How events work (from decompiled `event/`):** A macro file is bound to an event name; when the engine raises that event it runs the macro with event-scoped variables injected into local scope. "Change" events use a generic `MacroEventValueWatcher` that exposes `%OLD<VAR>%` / `%NEW<VAR>%` plus the live variable. Each event is permission-gated (e.g. `mod.macros.events.player.onhealthchange`).

**OUR STATUS:** the Fabric host now fires **11 of the 21** events plus 5 extensions (16 total), tick-polled from live client state (no mixins required): change-watchers for health / food / oxygen / level / xp, inventory-slot, weather, and world (dimension), plus `onChat` / `onSendChatMessage` / `onJoinGame`, and the bonus `onTick` / `onLeaveGame` / `onDeath` / `onDamage` / `onHeldItemChange`. The remaining events need a specific callback or a config/task model. Status is per row below.

Decompiled event names (23 literals): the 21 public events below, plus internal `onEventId` (dispatch plumbing) and `onItemPickup`/`onJoinGame` (internal aliases of `onPickupItem`/`onPlayerJoined`).

---

## Player / stats events

| Event | Trigger | Exposed variables | Our Status |
|---|---|---|---|
| `onHealthChange` | Health level changes (damage, food, potions) | `%HEALTH%`, `%OLDHEALTH%`, `%NEWHEALTH%` | done |
| `onFoodChange` | Food bar level changes | `%HUNGER%`, `%OLDHUNGER%`, `%NEWHUNGER%` (OLD/NEW via value-watcher) | done |
| `onOxygenChange` | Oxygen level changes | `%OXYGEN%`, `%OLDOXYGEN%`, `%NEWOXYGEN%` | done |
| `onLevelChange` | XP level changes | `%LEVEL%`, `%OLDLEVEL%`, `%NEWLEVEL%` | done |
| `onXPChange` | XP gained/lost | `%XP%`, `%OLDXP%`, `%NEWXP%` | done |
| `onModeChange` | Game mode changes (e.g. creative↔survival) | `%MODE%`/`%GAMEMODE%`, `%OLD...%`/`%NEW...%` | missing |
| `onArmourChange` | Armour level changes (damage or new piece) | armour vars + OLD/NEW | missing |
| `onArmourDurabilityChange` | Any worn armour's durability changes | armour durability vars | missing |
| `onItemDurabilityChange` | Wielded item's durability changes | item durability vars | missing |
| `onPickupItem` | Player picks up an item | `%PICKUPITEM%`, `%PICKUPID%`, `%PICKUPDATA%`, `%PICKUPAMOUNT%` | missing |
| `onInventorySlotChange` | Selected hotbar slot changes | `%INVSLOT%`, `%OLDINVSLOT%` | done |

## Chat events

| Event | Trigger | Exposed variables | Our Status |
|---|---|---|---|
| `onChat` | A chat message arrives from the server | `%CHAT%` (with codes), `%CHATCLEAN%` (no codes), `%CHATPLAYER%`, `%CHATMESSAGE%` | done |
| `onSendChatMessage` | A chat message is sent by the client (added v0.10.4) | `%CHAT%` | done |
| `onFilterableChat` | A chat message is sent/received and can be filtered | `%CHAT%` — handler uses `FILTER` / `PASS` / `MODIFY` actions to intercept | missing |

> `onFilterableChat` + the `chatfilter`/`filter`/`pass`/`modify` actions form the chat-interception subsystem. The decompile has `OnFilterableChatProvider.java` even though those 4 action classes weren't in the dumped `actions/**` (version skew — see ACTIONS.md).

## World / session events

| Event | Trigger | Exposed variables | Our Status |
|---|---|---|---|
| `onWorldChange` | Transition between worlds/dimensions | world vars (`%DIMENSION%`, etc.) | done |
| `onWeatherChange` | Weather level changes | `%RAIN%`, `%OLDRAIN%`/`%NEWRAIN%` | done |
| `onJoinGame` | Player joins a game (init background macros / server cmds) | — (session bootstrap) | done |
| `onPlayerJoined` | Another player joins the server (multiplayer) | `%JOINEDPLAYER%` | missing |
| `onShowGui` | The current Minecraft GUI changes | `%GUI%` (new screen) | missing |
| `onConfigChange` | Active configuration changes | `%CONFIG%` | missing |
| `onAutoCraftingComplete` | An auto-crafting process completes | `%REASON%` | missing |

---

## Notes & findings

- **21 public events**, all MC-bound. Names match exactly between ddoerr and the decompiled `BuiltinEvent`/provider classes.
- **OLD/NEW pattern:** all "*Change" events use `MacroEventValueWatcher`, so they consistently expose `%OLD<X>%` and `%NEW<X>%` alongside the current `%<X>%`. ddoerr only spells these out explicitly on `onHealthChange`; the pattern generalises to food/oxygen/level/xp/mode/armour/weather.
- **Internal-only:** `onEventId` (generic dispatch id) and the alias names `onItemPickup` / `onJoinGame` appear in source but are not user-facing distinct events.
- **Permissions:** each event has a permission node `mod.macros.events.<group>.<eventname>` (groups: `player`, `world`, `stats`, etc.) — relevant if we replicate the permission model.
- **No detail page per event was needed beyond a sample** — provider source gave the authoritative exposed-variable sets for the chat/pickup/join/slot/crafting events; change-event vars follow the watcher pattern.

**OUR STATUS rollup:** 11 of 21 events live, plus 5 engine-extension events (`onTick`, `onLeaveGame`, `onDeath`, `onDamage`, `onHeldItemChange`) for 16 total, all tick-polled from client state in the Fabric bridge (no mixins). The 10 remaining need a dedicated callback (pickup, GUI-change, player-join, filterable-chat) or a config/task model (config-change, auto-crafting-complete); armour / durability / mode are further change-watchers to add. Compile-verified across all 23 versions; live firing needs a running client.
