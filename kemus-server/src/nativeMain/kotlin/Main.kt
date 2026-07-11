package io.github.kemus.server

import io.github.kemus.Persistence
import io.github.kemus.filePersistence
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun envVar(name: String): String? = getenv(name)?.toKString()

actual fun openPersistence(path: String): Persistence = filePersistence(path)

fun main() = runBlocking { runKemusServer() }
