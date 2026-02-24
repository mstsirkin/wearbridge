package io.vibe.wearbridge.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vibe.wearbridge.watch.core.MessagePasswordStore
import io.vibe.wearbridge.watch.core.WatchBridgeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val passwordStore = MessagePasswordStore(this)

        setContent {
            MaterialTheme {
                val logs by WatchBridgeState.logs.collectAsStateWithLifecycle()
                var password by remember { mutableStateOf(passwordStore.getPassword().orEmpty()) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "WearBridge Watch",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        item {
                            Text(
                                text = "Companion listener is active. Recent events:",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        item {
                            PasswordSection(
                                password = password,
                                onPasswordChanged = { password = it },
                                onSave = {
                                    passwordStore.setPassword(password)
                                    WatchBridgeState.log(
                                        if (password.isBlank()) {
                                            "Message password cleared"
                                        } else {
                                            "Message password updated"
                                        }
                                    )
                                },
                                onClear = {
                                    password = ""
                                    passwordStore.setPassword(null)
                                    WatchBridgeState.log("Message password cleared")
                                }
                            )
                        }
                        items(logs.takeLast(80).asReversed()) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordSection(
    password: String,
    onPasswordChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Message password (optional)",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("Password") }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            TextButton(onClick = onClear, enabled = password.isNotEmpty()) {
                Text("Clear")
            }
        }
    }
}
