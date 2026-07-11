package io.github.kemus.benchmarks

import java.net.ServerSocket

/**
 * Shared workload definitions so the three layers (engine, kemus-server over HTTP, Redis) all
 * exercise the *same* keys and value sizes — the only thing that differs between benchmarks is the
 * path the command travels, which is exactly what we want to isolate.
 */
object Workload {
    const val GET_KEY = "bench:get"
    const val SET_KEY = "bench:set"
    const val INCR_KEY = "bench:incr"

    /** Value sizes (bytes) swept by `@Param`. Small reveals fixed per-op overhead; large reveals
     *  per-byte serialization/copy cost — where HTTP+JSON envelope and RESP framing diverge. */
    val VALUE_SIZES = arrayOf("16", "256", "4096")

    fun value(size: Int): String = "v".repeat(size)
}

/** Pick a free localhost port for the in-process server. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * URI of an external, native Redis to benchmark against, from `-Dkemus.redis.uri` or the
 * `KEMUS_REDIS_URI` env var (env is preferred — it is inherited by JMH's forked JVM, a `-D` is not
 * unless passed via `-jvmArgsAppend`). Returns `null` to fall back to a Testcontainers container —
 * whose Docker network proxy adds latency and *understates* Redis, so it is only a convenience, not
 * a fair baseline. Example: `KEMUS_REDIS_URI=redis://localhost:6379`.
 */
internal fun externalRedisUri(): String? =
    System.getProperty("kemus.redis.uri") ?: System.getenv("KEMUS_REDIS_URI")
