package io.github.kormium

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Wasm (JS interop) is single-threaded like Kotlin/JS; the JS drivers are async.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
