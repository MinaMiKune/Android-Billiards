package com.minamikune.billiards.resources

/**
 * Small keyed resource registry with explicit ownership.
 *
 * A value is loaded at most once per key and is closed exactly once when the
 * registry is closed or the key is released. This is used for Activity-scoped
 * resources (audio/fonts/config) and can also wrap GL-side caches. Loaders are
 * invoked while the registry lock is held, so a failed loader leaves no entry behind.
 */
class ResourceManager : AutoCloseable {
    private data class Entry(
        val value: Any,
        val closeable: AutoCloseable?,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var closed = false

    @Synchronized
    fun <T : Any> get(key: String, loader: () -> T): T {
        check(!closed) { "ResourceManager is closed" }
        entries[key]?.let { entry ->
            @Suppress("UNCHECKED_CAST")
            return entry.value as T
        }
        val value = loader()
        entries[key] = Entry(value, value as? AutoCloseable)
        return value
    }

    @Synchronized
    fun isLoaded(key: String): Boolean = entries.containsKey(key)

    @Synchronized
    fun loadedKeys(): List<String> = entries.keys.toList()

    @Synchronized
    fun size(): Int = entries.size

    /** Releases one entry. Returns true when the key existed. */
    fun release(key: String): Boolean {
        val entry = synchronized(this) { entries.remove(key) } ?: return false
        runCatching { entry.closeable?.close() }
        return true
    }

    override fun close() {
        val snapshot = synchronized(this) {
            if (closed) return
            closed = true
            entries.values.toList().also { entries.clear() }
        }
        // Reverse creation order mirrors dependency order and keeps shutdown
        // deterministic. A vendor close failure must not prevent the rest closing.
        for (entry in snapshot.asReversed()) {
            runCatching { entry.closeable?.close() }
        }
    }

    val isClosed: Boolean
        @Synchronized get() = closed
}
