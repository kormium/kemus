package io.github.kemus

import okio.FileSystem

actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
