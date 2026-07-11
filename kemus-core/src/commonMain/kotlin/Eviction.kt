package io.github.kemus

/**
 * Key-eviction policy applied when [Kemus] reaches its `maxmemory` limit. Mirrors Redis's
 * `maxmemory-policy`.
 *
 * `VOLATILE_*` policies only consider keys that carry a TTL; `ALLKEYS_*` consider every key. When
 * no key is eligible (e.g. a `volatile-*` policy with no expiring keys, or [NOEVICTION]) the store
 * rejects memory-growing writes with an `OOM` error instead of evicting.
 *
 * Eviction is **approximated**, exactly as in Redis: rather than maintaining a global ordering, the
 * engine samples a handful of keys (`maxmemory-samples`) and evicts the best candidate from the
 * sample — the least-recently-used (`*_LRU`), least-frequently-used (`*_LFU`), nearest-expiry
 * (`VOLATILE_TTL`) or a random one (`*_RANDOM`).
 */
enum class EvictionPolicy(val configName: String) {
    NOEVICTION("noeviction"),
    ALLKEYS_LRU("allkeys-lru"),
    VOLATILE_LRU("volatile-lru"),
    ALLKEYS_RANDOM("allkeys-random"),
    VOLATILE_RANDOM("volatile-random"),
    VOLATILE_TTL("volatile-ttl"),
    ALLKEYS_LFU("allkeys-lfu"),
    VOLATILE_LFU("volatile-lfu");

    /** Whether the policy only evicts keys that have a TTL. */
    val isVolatile: Boolean get() = configName.startsWith("volatile")

    companion object {
        /** Parse a Redis `maxmemory-policy` config value (e.g. `allkeys-lru`); `null` if unknown. */
        fun fromConfig(name: String): EvictionPolicy? =
            entries.firstOrNull { it.configName == name.trim().lowercase() }
    }
}

/**
 * Parse a memory size for `maxmemory`: a plain byte count, or a value with a `kb`/`mb`/`gb` suffix
 * (1024-based, as Redis's `*b` forms). Returns `null` if the text is not a valid size. `0` means
 * "no limit".
 */
internal fun parseMemory(text: String): Long? {
    val s = text.trim().lowercase()
    val units = listOf("kb" to 1024L, "mb" to 1024L * 1024, "gb" to 1024L * 1024 * 1024, "b" to 1L)
    for ((suffix, mult) in units) {
        if (s.endsWith(suffix)) {
            val n = s.dropLast(suffix.length).trim().toLongOrNull() ?: return null
            return n * mult
        }
    }
    return s.toLongOrNull()
}
