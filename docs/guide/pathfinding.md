# Pathfinding

MacroMod ships its **own** pathfinding engine — an A\* search over the block grid,
written from scratch. It does **not** depend on [Baritone](https://github.com/cabaletta/baritone).

!!! info "Why our own?"
    Baritone is capable, but a macro client has good reasons to avoid it: anti-cheats
    ship Baritone movement/rotation signatures, it lags new Minecraft versions, and it
    is heavyweight for what a keybind mod needs. Modern Hypixel SkyBlock macro clients
    (e.g. Taunahi) write their own pathfinders for the same reasons. Ours is small,
    version-independent, and — crucially — **unit-tested without Minecraft**.

## The design in one line

A\* over a voxel grid for a **2-block-tall agent**, where the only thing the search
needs from the world is *"is this block solid?"*.

```kotlin
fun interface BlockView {
    fun isSolid(pos: Vec3i): Boolean
}
```

Everything else — what counts as standable ground, which moves are legal, what they
cost — is derived from that. In tests the `BlockView` is a synthetic grid; in-game the
Fabric layer implements it over the live world. The algorithm is identical either way.

## Standable positions

A position `p` (the agent's feet) is **standable** when:

- the block **below** is solid (ground to stand on), and
- the blocks at **`p`** and **`p+1`** are clear (room for feet + head).

That 2-block clearance is what makes the agent a *player* rather than a point.

## The move set

From a standable position the search considers:

| Move | When |
| --- | --- |
| **Cardinal walk** (N/S/E/W) | the neighbour is standable |
| **Diagonal walk** | the diagonal is standable **and** both corners are clear (no clipping) |
| **Step up** (jump +1) | the neighbour is a block you can climb, with headroom to jump |
| **Fall** (−1 … −*maxFall*) | walk off an edge to the first standable block below |
| **Parkour** (1-block gap) | jump a single missing block to land on the far side |

Wider gaps, longer parkour, and sprint/while-falling control are natural extensions of
this set.

## Cost model

Costs are in **ticks** (20/second), in the spirit of Baritone's model, so paths look
like natural movement:

| Constant | Value | Meaning |
| --- | --- | --- |
| `WALK` | 4.63 | one block, walking |
| `DIAGONAL` | `WALK × √2` | one diagonal block |
| `STEP_UP` | +3.0 | extra to jump up one block |
| `FALL_PER` | 1.0 | per block fallen |
| `PARKOUR` | +2.0 | extra to jump a one-block gap |

The absolute numbers only need to be self-consistent for A\* to prefer sensible
routes; matching real movement just makes the chosen paths intuitive.

## The search

Standard A\* with a binary heap open-set, `gScore`/`cameFrom` maps, and a closed set.
The heuristic is **octile distance** scaled by the walk cost (plus a cheap vertical
term):

```
dMax = max(|Δx|, |Δz|)
dMin = min(|Δx|, |Δz|)
h = (dMax − dMin)·WALK + dMin·DIAGONAL + |Δy|·WALK·0.5
```

This is **admissible** (it never overestimates the true cost), so A\* returns an optimal
path for the move set. A node budget (`maxNodes`) bounds the search so a hopeless query
fails fast instead of scanning the world.

## Using it

```kotlin
val pathfinder = Pathfinder(world, maxFall = 3)
val path: List<Vec3i>? = pathfinder.findPath(start = Vec3i(0, 70, 0), goal = Vec3i(40, 64, 12))
// null  → no route within the node budget
// else  → an ordered list of block positions from start to goal
```

## Waypoints vs. real pathfinding

Two strategies, both useful:

- **Waypoint following** — a fixed list of points the agent walks between. Deterministic
  and cheap, but brittle when terrain changes (e.g. a regenerating mineshaft).
- **Real pathfinding** (this engine) — recompute a route over the actual blocks. Robust
  to changing terrain and gives stuck-recovery, at the cost of search time.

A practical client uses both: waypoints for known, static routes; real A\* where the
world moves under you.

## `goto` is now wired (Fabric side)

The pure-JVM core above is done and tested, and the **`goto(x,y,z)` DSL action now actually
walks the player**. The Minecraft-bound binding lives in `fabric/.../FabricNavigator.kt`
(implements the engine's `Navigator`), wired into the engine via `MacroEngine(navigator = …)`
and advanced once per client tick:

- a **`BlockView` over the live world** — a block is solid iff
  `BlockState.isCollisionShapeFullBlock(level, pos)` (a full collidable block, not a
  slab/stair/air); **unloaded chunks read as solid** (`level.hasChunk(...)`) so the search
  never paths into terrain it cannot see. This one method is identically named across MC
  1.16→1.21.x, so the binding needs no per-version branches;
- driving the **`PathExecutor`** each `END_CLIENT_TICK`: it faces the next waypoint and holds
  the movement keys through `FabricInputController` (`forward`, plus `jump` to climb), releasing
  them when the path finishes or `stopnav` fires;
- `pathTo` snaps the player's feet via `LocalPlayer.blockPosition()`, runs the A\* `Pathfinder`,
  and starts navigation only if a route is found.

The binding is gated `>=1.16` (the bridge floor); 1.14.4/1.15.2 keep `Navigator.NoOp`. A demo
**`G`** keybind fires `$${ goto(x, y, z+5) }$$` toward a spot a few blocks ahead of the player.

!!! note "Still pure-JVM where it counts"
    The A\* search and the per-tick movement decisions remain in the `:engine` module and are
    unit-tested without Minecraft. Only the world read (`BlockView`) and the tick driver are
    Fabric-side.

Still ahead: richer goal types (area, entity-follow) and async/segmented planning for long
routes. See the [roadmap](getting-started.md#roadmap) and the
[architecture](architecture.md) for how this binds to the rest of the mod.
