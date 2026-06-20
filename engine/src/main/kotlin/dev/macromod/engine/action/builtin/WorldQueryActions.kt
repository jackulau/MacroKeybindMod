package dev.macromod.engine.action.builtin

import dev.macromod.engine.action.Args
import dev.macromod.engine.action.ExecutionContext
import dev.macromod.engine.action.ReturnValue
import dev.macromod.engine.action.ScriptAction
import dev.macromod.engine.value.Value

/**
 * Read-only world / inventory queries, routed to the platform [dev.macromod.engine.action.WorldQuery].
 *
 * Note on IDs: MKB's `getid`/`itemid`/`tileid` returned *legacy numeric* block/item IDs, which the
 * 1.13 "flattening" removed. Post-1.13 the stable identifier is the **registry id** (e.g.
 * `minecraft:stone`), so these actions return that string. `itemid`/`itemname`/`tileid`/`tilename`
 * therefore round-trip the registry id (the modern equivalent of the legacy lookup tables).
 */

/** `getslot(item[, #out])` — inventory slot holding [item] (registry id), or -1. */
object GetSlotAction : ScriptAction("getslot") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val slot = ctx.client.query.findSlot(ctx.expand(args[0]).trim())
        args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Num(slot)) }
        return ReturnValue.of(slot)
    }
}

/** `getslotitem(slot[, &idvar])` — registry id of the item in [slot] (or "" if empty/unknown). */
object GetSlotItemAction : ScriptAction("getslotitem") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val id = ctx.client.query.itemInSlot(ctx.evaluate(args[0]).asInt())
        // MKB writes the id into the optional <#idvar> out-var (ScriptActionGetSlotItem.java:36-37,
        // ACTIONS.md GETSLOTITEM(<slotid>,<#idvar>,...)), mirroring our getid 4th-param (goal-043). The
        // [#stack]/[#data] out-vars need host ItemStack count/damage, so they stay bridge-gated.
        args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Str(id)) }
        return ReturnValue.of(id)
    }
}

/**
 * Resolve a getid coordinate. An MKB `~`/`~N` prefix is player-relative — base = the XPOS/YPOS/ZPOS
 * env position (the same source [GetIdRelAction] uses), offset = the part after `~` as our expression
 * (or 0 for a bare `~`). Without the prefix it stays the existing absolute expression, so the
 * pre-existing non-`~` behaviour is byte-for-byte preserved. Mirrors ScriptActionGetId.getPosition.
 */
private fun coord(ctx: ExecutionContext, arg: String, posVar: String): Int {
    val expanded = ctx.expand(arg).trim()
    if (!expanded.startsWith("~")) return ctx.evaluate(arg).asInt()
    val offset = expanded.substring(1).trim()
    return (ctx.resolve(posVar)?.asInt() ?: 0) + if (offset.isEmpty()) 0 else ctx.evaluate(offset).asInt()
}

/** `getid(x, y, z[, &out])` — block registry id at world coords; each coord may be MKB `~`-relative. */
object GetIdAction : ScriptAction("getid") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val id = ctx.client.query.blockAt(coord(ctx, args[0], "XPOS"), coord(ctx, args[1], "YPOS"), coord(ctx, args[2], "ZPOS"))
        args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Str(id)) }
        return ReturnValue.of(id)
    }
}

/** `getidrel(dx, dy, dz[, &out])` — block registry id relative to the player's position. */
object GetIdRelAction : ScriptAction("getidrel") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val x = (ctx.resolve("XPOS")?.asInt() ?: 0) + ctx.evaluate(args[0]).asInt()
        val y = (ctx.resolve("YPOS")?.asInt() ?: 0) + ctx.evaluate(args[1]).asInt()
        val z = (ctx.resolve("ZPOS")?.asInt() ?: 0) + ctx.evaluate(args[2]).asInt()
        val id = ctx.client.query.blockAt(x, y, z)
        args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { ctx.registry.setVariable(it.trim(), Value.Str(id)) }
        return ReturnValue.of(id)
    }
}

/**
 * `trace([distance])` — ray-trace the block/entity the player is looking at (default reach 4).
 * Returns its registry id AND populates the local `%TRACE*%` vars (TRACETYPE / TRACEID / TRACENAME /
 * TRACEX / TRACEY / TRACEZ / TRACESIDE) from the platform's detailed result.
 */
object TraceAction : ScriptAction("trace") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue {
        val distance = if (args.isEmpty()) 4 else ctx.evaluate(args[0]).asInt()
        for ((name, value) in ctx.client.query.traceVars(distance)) {
            ctx.registry.setTransient(name, Value.Str(value))
        }
        return ReturnValue.of(ctx.client.query.trace(distance))
    }
}

/** `pick(item[, item...])` — select the first matching item on the hotbar; returns whether one was found. */
object PickAction : ScriptAction("pick") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(ctx.client.query.pick((0 until args.size).map { ctx.expand(args[it]).trim() }))
}

/** `getiteminfo` — registry id of the held item (slot -1 by convention). */
object GetItemInfoAction : ScriptAction("getiteminfo") {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue =
        ReturnValue.of(ctx.client.query.itemInSlot(-1))
}

/** Legacy id-lookup actions: post-flattening the registry id IS the identifier, so these echo it. */
private class IdentityIdAction(name: String) : ScriptAction(name) {
    override fun execute(ctx: ExecutionContext, args: Args): ReturnValue = ReturnValue.of(ctx.expand(args[0]).trim())
}

val ITEMID_ACTION: ScriptAction = IdentityIdAction("itemid")
val ITEMNAME_ACTION: ScriptAction = IdentityIdAction("itemname")
val TILEID_ACTION: ScriptAction = IdentityIdAction("tileid")
val TILENAME_ACTION: ScriptAction = IdentityIdAction("tilename")

/** World/inventory read actions, for bulk registration. */
val WORLD_QUERY_ACTIONS: List<ScriptAction> = listOf(
    GetSlotAction, GetSlotItemAction, GetIdAction, GetIdRelAction, TraceAction, PickAction, GetItemInfoAction,
    ITEMID_ACTION, ITEMNAME_ACTION, TILEID_ACTION, TILENAME_ACTION,
)
