package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inspiredandroid.kai.ui.components.HeartBrainDuo
import com.inspiredandroid.kai.ui.components.NiaVisualState
import com.inspiredandroid.kai.ui.chat.ChatActions
import com.inspiredandroid.kai.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.ic_volume_off
import kai.composeapp.generated.resources.ic_volume_up
import kai.composeapp.generated.resources.toggle_speech_output_content_description
import nl.marc_apps.tts.TextToSpeechInstance
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun TopBar(
    textToSpeech: TextToSpeechInstance? = null,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
    isChatHistoryEmpty: Boolean,
    hasSavedConversations: Boolean,
    onNavigateToSettings: () -> Unit,
    isSandboxAvailable: Boolean,
    isSandboxOpen: Boolean,
    isShellExecuting: Boolean,
    onToggleSandbox: () -> Unit,
    onShowHistory: () -> Unit,
    brainState: NiaVisualState = NiaVisualState.IDLE,
    navigationTabBar: (@Composable () -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HolographicBrain(
                modifier = Modifier.size(42.dp),
                size = 42.dp,
                state = brainState,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NIA",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = brainState.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (brainState) {
                        NiaVisualState.LISTENING -> MaterialTheme.colorScheme.secondary
                        NiaVisualState.THINKING -> MaterialTheme.colorScheme.primary
                        NiaVisualState.SPEAKING -> MaterialTheme.colorScheme.tertiary
                        NiaVisualState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    letterSpacing = 1.2.sp,
                )
            }

            if (textToSpeech != null) {
                SpeechToggleButton(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions)
            }

            Box {
                IconButton(
                    modifier = Modifier.handCursor(),
                    onClick = { menuExpanded = true },
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open NIA menu",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!isChatHistoryEmpty) {
                        DropdownMenuItem(
                            text = { Text("New chat") },
                            onClick = {
                                menuExpanded = false
                                if (isSpeechOutputEnabled && isSpeaking) {
                                    actions.setIsSpeaking(false, "")
                                    textToSpeech?.stop()
                                }
                                actions.startNewChat()
                            },
                        )
                    }
                    if (hasSavedConversations) {
                        DropdownMenuItem(
                            text = { Text("Chat history") },
                            onClick = {
                                menuExpanded = false
                                onShowHistory()
                            },
                        )
                    }
                    if (isSandboxAvailable) {
                        DropdownMenuItem(
                            text = { Text(if (isSandboxOpen) "Close Linux sandbox" else "Linux sandbox") },
                            onClick = {
                                menuExpanded = false
                                onToggleSandbox()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Settings, connections and memory") },
                        onClick = {
                            menuExpanded = false
                            onNavigateToSettings()
                        },
                    )
                }
            }
        }

        if (navigationTabBar != null) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                navigationTabBar()
            }
        }
    }
}

private fun NiaVisualState.label(): String = when (this) {
    NiaVisualState.IDLE -> "READY"
    NiaVisualState.LISTENING -> "LISTENING"
    NiaVisualState.THINKING -> "THINKING"
    NiaVisualState.SPEAKING -> "SPEAKING"
}

@Composable
private fun SpeechToggleButton(
    textToSpeech: TextToSpeechInstance,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
) {
    IconButton(
        modifier = Modifier.handCursor(),
        onClick = {
            if (isSpeechOutputEnabled && isSpeaking) {
                actions.setIsSpeaking(false, "")
                textToSpeech.stop()
            }
            actions.toggleSpeechOutput()
        },
    ) {
        Icon(
            imageVector = if (isSpeechOutputEnabled) {
                vectorResource(Res.drawable.ic_volume_up)
            } else {
                vectorResource(Res.drawable.ic_volume_off)
            },
            contentDescription = stringResource(Res.string.toggle_speech_output_content_description),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}
