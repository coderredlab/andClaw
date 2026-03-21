package com.coderred.andclaw.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class TerminalLine(
    val text: String,
    val kind: TerminalLineKind,
)

enum class TerminalLineKind {
    COMMAND,
    OUTPUT,
    ERROR,
}

@Composable
fun TerminalDialog(
    lines: List<TerminalLine>,
    commandInput: String,
    isExecuting: Boolean,
    onCommandInputChange: (String) -> Unit,
    onRunCommand: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = "Terminal (proot)",
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F1115),
                ) {
                    if (lines.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Type a command and tap Run",
                                color = Color(0xFFB0BEC5),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(lines) { line ->
                                val color = when (line.kind) {
                                    TerminalLineKind.COMMAND -> Color(0xFF81C784)
                                    TerminalLineKind.ERROR -> Color(0xFFE57373)
                                    TerminalLineKind.OUTPUT -> Color(0xFFECEFF1)
                                }
                                Text(
                                    text = line.text,
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = commandInput,
                    onValueChange = onCommandInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Command") },
                    placeholder = { Text("e.g. ls -la /root") },
                    singleLine = true,
                    enabled = !isExecuting,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isExecuting) {
                        Text("Close")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    TextButton(onClick = onClear, enabled = !isExecuting) {
                        Text("Clear")
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Button(
                        onClick = onRunCommand,
                        enabled = commandInput.isNotBlank() && !isExecuting,
                    ) {
                        Text("Run")
                    }
                }
            }
        }
    }
}
