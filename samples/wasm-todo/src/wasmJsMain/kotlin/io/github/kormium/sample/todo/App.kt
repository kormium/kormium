package io.github.kormium.sample.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The whole todo UI. State lives in Compose; every mutation calls [TodoRepository] (the Kormium
 * DSL over wa-sqlite) and then refreshes the list. This is ordinary Compose Multiplatform — the
 * only thing unusual is that the database is a real SQLite running in the browser.
 */
@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf<TodoRepository?>(null) }
    var todos by remember { mutableStateOf<List<Todo>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Opening embedded SQLite…") }

    LaunchedEffect(Unit) {
        val r = TodoRepository.open()
        repo = r
        todos = r.all()
        status = ""
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().widthIn(max = 560.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Kormium · Todo", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "wa-sqlite (SQLite in WASM) + Kormium DSL, persisted to IndexedDB.",
                    style = MaterialTheme.typography.bodySmall,
                )

                val ready = repo != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("New todo") },
                        singleLine = true,
                        enabled = ready,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = ready && input.isNotBlank(),
                        onClick = {
                            val title = input.trim()
                            input = ""
                            scope.launch {
                                val r = repo ?: return@launch
                                r.add(title)
                                todos = r.all()
                            }
                        },
                    ) { Text("Add") }
                }

                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(todos, key = { it.id.toString() }) { todo ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = todo.done,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        val r = repo ?: return@launch
                                        r.setDone(todo, checked)
                                        todos = r.all()
                                    }
                                },
                            )
                            Text(
                                todo.title,
                                modifier = Modifier.weight(1f),
                                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val r = repo ?: return@launch
                                    r.remove(todo)
                                    todos = r.all()
                                }
                            }) { Text("Delete") }
                        }
                    }
                }

                if (ready && todos.isEmpty()) {
                    Text("No todos yet — add one above.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
