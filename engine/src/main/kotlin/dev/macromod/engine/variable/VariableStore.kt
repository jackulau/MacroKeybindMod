package dev.macromod.engine.variable

import dev.macromod.engine.value.Value
import java.util.TreeMap

/** A read-only source of environment variables (player state, world, input…) injected by the host. */
fun interface EnvProvider {
    /** Return the value for [name] (raw, as written — usually uppercase) or null if not provided. */
    fun get(name: String): Value?
}

/**
 * A host-supplied source of named-iterator values for `foreach(<var>, <iterator>)` (e.g. the online
 * players, the hotbar/inventory item ids). Returns the values for [name], or null if this provider
 * does not handle that iterator (so the registry tries the next provider, then array fallback).
 */
fun interface IteratorProvider {
    fun values(name: String): List<Value>?
}

/**
 * Mutable storage for scalar and array variables, keyed by [Variable.storageKey] (sigil + name).
 * Backs both the per-macro local scope and the shared/global scope.
 */
class VariableStore {
    private val scalars = HashMap<String, Value>()
    private val arrays = HashMap<String, TreeMap<Int, Value>>()

    fun get(v: Variable): Value? = when {
        v.isArraySpecifier -> null
        v.isArrayElement -> arrays[v.storageKey()]?.get(v.index)
        else -> scalars[v.storageKey()]
    }

    fun set(v: Variable, value: Value) {
        val coerced = coerce(v.type, value)
        if (v.isArrayElement) arrays.getOrPut(v.storageKey()) { TreeMap() }[v.index!!] = coerced
        else scalars[v.storageKey()] = coerced
    }

    fun unset(v: Variable) {
        if (v.isArraySpecifier) arrays.remove(v.storageKey())
        else if (v.isArrayElement) arrays[v.storageKey()]?.remove(v.index)
        else scalars.remove(v.storageKey())
    }

    fun increment(v: Variable, by: Int) {
        val cur = get(v)?.asInt() ?: 0
        set(v, Value.Num(cur + by))
    }

    /** Names of the currently-set scalar variables (used by the `env` iterator). */
    fun scalarNames(): List<String> = scalars.keys.sorted()

    // --- array operations -------------------------------------------------

    fun arrayValues(v: Variable): List<Value> =
        arrays[v.storageKey()]?.values?.toList() ?: emptyList()

    fun push(v: Variable, value: Value): Boolean {
        val a = arrays.getOrPut(v.storageKey()) { TreeMap() }
        val idx = if (a.isEmpty()) 0 else a.lastKey() + 1
        a[idx] = coerce(v.type, value)
        return true
    }

    fun pop(v: Variable): Value? {
        val a = arrays[v.storageKey()] ?: return null
        if (a.isEmpty()) return null
        val key = a.lastKey()
        return a.remove(key)
    }

    /** Insert at the first free index (hole), else append. */
    fun put(v: Variable, value: Value): Boolean {
        val a = arrays.getOrPut(v.storageKey()) { TreeMap() }
        var idx = 0
        while (a.containsKey(idx)) idx++
        a[idx] = coerce(v.type, value)
        return true
    }

    fun indexOf(v: Variable, value: Value): Int {
        val a = arrays[v.storageKey()] ?: return -1
        for ((k, stored) in a) if (stored.asString() == value.asString()) return k
        return -1
    }

    fun clearArray(v: Variable) { arrays.remove(v.storageKey()) }

    fun clearAll() { scalars.clear(); arrays.clear() }

    private fun coerce(type: VarType, value: Value): Value = when (type) {
        VarType.COUNTER -> Value.Num(value.asInt())
        VarType.STRING -> Value.Str(value.asString())
        VarType.FLAG -> Value.Bool(value.asBoolean())
    }
}

/**
 * Variable resolution + assignment across scopes.
 *
 * Read order: environment providers (uppercase built-ins) → the relevant scope store.
 * Writes route by the `@` sigil: shared → [shared], otherwise → [local].
 */
class VariableRegistry {
    val local = VariableStore()
    val shared = VariableStore()
    private val envProviders = ArrayList<EnvProvider>()
    private val iteratorProviders = ArrayList<IteratorProvider>()
    // Latched snapshot for `%~NAME%` reads: the value is captured on first access this run and
    // reused, so a script sees a stable "value at script start" even as the live var changes.
    private val latched = HashMap<String, Value?>()
    // Action-set built-ins resolved by raw name (e.g. the `trace` action's `%TRACE*%` snapshot).
    // Distinct from user variables: stored as the given typed Value, not coerced via the sigil rules.
    private val transient = HashMap<String, Value>()

    /** Set an action-provided built-in (raw name, typed value) — e.g. `%TRACEID%` from `trace`. */
    fun setTransient(name: String, value: Value) { transient[name] = value }

    fun addEnvProvider(provider: EnvProvider) { envProviders.add(provider) }
    fun addIteratorProvider(provider: IteratorProvider) { iteratorProviders.add(provider) }

    /** Reset latched `%~NAME%` snapshots — called at the start of each script run. */
    fun clearLatched() { latched.clear() }

    private fun storeFor(v: Variable): VariableStore = if (v.shared) shared else local

    fun getVariable(name: String): Value? {
        // `~NAME` = latched: capture once on first access, then return the snapshot for this run.
        if (name.startsWith("~")) {
            val base = name.substring(1)
            return if (latched.containsKey(base)) latched[base]
            else resolveVariable(base).also { latched[base] = it }
        }
        return resolveVariable(name)
    }

    private fun resolveVariable(name: String): Value? {
        // Action-set built-ins (e.g. TRACE*) then live environment built-ins, both by raw name.
        transient[name]?.let { return it }
        for (p in envProviders) p.get(name)?.let { return it }
        val v = Variable.parse(name) ?: return null
        return storeFor(v).get(v)
    }

    fun setVariable(name: String, value: Value) {
        val v = Variable.parse(name) ?: return
        storeFor(v).set(v, value)
    }

    fun unsetVariable(name: String) {
        val v = Variable.parse(name) ?: return
        storeFor(v).unset(v)
    }

    fun increment(name: String, by: Int) {
        val v = Variable.parse(name) ?: return
        storeFor(v).increment(v, by)
    }

    fun arrayValues(name: String): List<Value> {
        val v = Variable.parse(name) ?: return emptyList()
        return storeFor(v).arrayValues(v)
    }

    /**
     * Named-iterator values for `foreach(<var>, <iterator>)`. `env` iterates the names of the
     * currently-set local scalar variables; `running` is the (host-supplied) task list, empty
     * engine-side. Any other name is offered to the registered [IteratorProvider]s (the host wires
     * `players` / `hotbar` / `inventory` there); if none handle it, returns null so `foreach` falls
     * back to array iteration.
     */
    fun iteratorValues(name: String): List<Value>? {
        val key = name.lowercase().removeSuffix("[]")
        when (key) {
            "env" -> return local.scalarNames().map { Value.Str(it) }
            "running" -> return emptyList()
        }
        for (p in iteratorProviders) p.values(key)?.let { return it }
        return null
    }

    fun push(name: String, value: Value) { Variable.parse(name)?.let { storeFor(it).push(it, value) } }
    fun pop(name: String): Value? = Variable.parse(name)?.let { storeFor(it).pop(it) }
    fun put(name: String, value: Value) { Variable.parse(name)?.let { storeFor(it).put(it, value) } }
    fun indexOf(name: String, value: Value): Int = Variable.parse(name)?.let { storeFor(it).indexOf(it, value) } ?: -1
    fun clearArray(name: String) { Variable.parse(name)?.let { storeFor(it).clearArray(it) } }
}
