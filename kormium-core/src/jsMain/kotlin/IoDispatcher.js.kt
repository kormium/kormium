package io.github.kormium

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/JS is single-threaded; there is no blocking IO to offload. Dispatchers.IO does
// not exist here, and the JS drivers are already async, so Default is the right context.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
