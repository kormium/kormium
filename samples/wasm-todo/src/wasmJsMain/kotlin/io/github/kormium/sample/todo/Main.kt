@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.kormium.sample.todo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

fun main() {
    ComposeViewport(document.body!!) { App() }
}
