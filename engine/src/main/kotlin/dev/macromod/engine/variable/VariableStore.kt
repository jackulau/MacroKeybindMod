package dev.macromod.engine.variable

import dev.macromod.engine.value.Value
import java.util.TreeMap

/** A read-only source of environment variables (player state, world, input…) injected by the host. */
fun interface EnvProvider {
    /** Return the value for [name] (raw, as written — usually uppercase) or null if not provided. */
    fun get(name: String): Value?
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

    fun addEnvProvider(provider: EnvProvider) { envProviders.add(provider) }

    private fun storeFor(v: Variable): VariableStore = if (v.shared) shared else local

    fun getVariable(name: String): Value? {
        // Environment built-ins are matched on the raw (typically uppercase) name first.
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

    fun push(name: String, value: Value) { Variable.parse(name)?.let { storeFor(it).push(it, value) } }
    fun pop(name: String): Value? = Variable.parse(name)?.let { storeFor(it).pop(it) }
    fun put(name: String, value: Value) { Variable.parse(name)?.let { storeFor(it).put(it, value) } }
    fun indexOf(name: String, value: Value): Int = Variable.parse(name)?.let { storeFor(it).indexOf(it, value) } ?: -1
    fun clearArray(name: String) { Variable.parse(name)?.let { storeFor(it).clearArray(it) } }
}
