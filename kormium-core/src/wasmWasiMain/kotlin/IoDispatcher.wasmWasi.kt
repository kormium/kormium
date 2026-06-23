package io.github.kormium

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Wasm WASI is single-threaded. Dispatchers.Default is the only general-purpose
// dispatcher available; there is no IO dispatcher and no batteries-included driver here yet.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
