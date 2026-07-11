package io.github.kemus.server

import io.github.kemus.Persistence
import io.github.kemus.filePersistence
import kotlinx.coroutines.runBlocking

actual fun envVar(name: String): String? = System.getenv(name)

actual fun openPersistence(path: String): Persistence = filePersistence(path)

fun main() = runBlocking { runKemusServer() }
