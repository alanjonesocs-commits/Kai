package com.inspiredandroid.kai.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inspiredandroid.kai.ui.components.HolographicBrain
import com.inspiredandroid.kai.voice.NiaRuntimeState
import com.inspiredandroid.kai.voice.NiaVoiceOutputStatus
import com.inspiredandroid.kai.voice.NiaVoicePhase
import org.koin.compose.koinInject

@Composable
fun NiaHomeScreen(
    onOpenConversation: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSystems: () -> Unit,
) {
    val runtimeState = koinInject<NiaRuntimeState>()
    val runtime by runtimeState.state.collectAsStateWithLifecycle()
    val phaseLabel = when (runtime.voicePhase) {
        NiaVoicePhase.Idle -> "STANDBY"
        NiaVoicePhase.Listening -> "LISTENING"
        NiaVoicePhase.Thinking -> "THINKING"
        NiaVoicePhase.Speaking -> "SPEAKING"
    }
    val voiceLabel = when (runtime.voiceOutputStatus) {
        NiaVoiceOutputStatus.PiperReady -> "PIPER READY"
        NiaVoiceOutputStatus.PiperInstalled -> "VOICE INSTALLED"
        NiaVoiceOutputStatus.Preparing -> "VOICE PREPARING"
        NiaVoiceOutputStatus.SystemTtsFallback -> "SYSTEM VOICE"
        NiaVoiceOutputStatus.NotInstalled -> "VOICE NOT INSTALLED"
        NiaVoiceOutputStatus.Unavailable -> "VOICE UNAVAILABLE"
        NiaVoiceOutputStatus.Error -> "VOICE ERROR"
        NiaVoiceOutputStatus.Unknown -> "VOICE STATUS UNKNOWN"
    }
    val cyan = Color(0xFF63E9FF)
    val background = Brush.verticalGradient(
        listOf(Color(0xFF07121E), Color(0xFF0B1020), Color(0xFF090D17)),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "N I A",
                color = cyan,
                fontSize = 27.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp,
            )
            Text(
                text = "NATURAL INTELLIGENCE ASSISTANT",
                color = Color(0xFF91A9BD),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "PERSONAL INTELLIGENCE SYSTEM",
                color = Color(0xFF68859B),
                fontSize = 9.sp,
                letterSpacing = 1.7.sp,
            )
            Spacer(Modifier.height(4.dp))
            HolographicBrain(size = 220.dp)
            Text(
                text = phaseLabel,
                color = when (runtime.voicePhase) {
                    NiaVoicePhase.Listening -> Color(0xFFB7FF6A)
                    NiaVoicePhase.Thinking -> Color(0xFFB69CFF)
                    NiaVoicePhase.Speaking -> Color(0xFF62F5E5)
                    NiaVoicePhase.Idle -> cyan
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusTile(
                    title = "VOICE OUTPUT",
                    value = voiceLabel,
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    title = "MEMORY",
                    value = runtime.memoryEntryCount?.let { "$it RECORDS" } ?: "OPEN TO SYNC",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusTile(
                    title = "PROVIDERS",
                    value = runtime.configuredProviderCount?.let { "$it CONFIGURED" } ?: "NOT CHECKED",
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    title = "LOCAL MODELS",
                    value = runtime.localModelCount?.let { "$it AVAILABLE" } ?: "NOT CHECKED",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onOpenConversation,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF123B50),
                    contentColor = Color(0xFFBDF7FF),
                ),
            ) {
                Text("OPEN CONVERSATION", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onOpenMemory,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF18283B),
                        contentColor = Color(0xFFB9D8F2),
                    ),
                ) {
                    Text("MEMORY & PRIVACY", fontSize = 10.sp, letterSpacing = 0.7.sp)
                }
                Button(
                    onClick = onOpenSystems,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF18283B),
                        contentColor = Color(0xFFB9D8F2),
                    ),
                ) {
                    Text("SYSTEMS & VOICE", fontSize = 10.sp, letterSpacing = 0.7.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "LIVE STATUS • CONNECTED TO NIA RUNTIME",
                color = Color(0xFF54758B),
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatusTile(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xCC111F2E),
        contentColor = Color(0xFFE1F3FF),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                color = Color(0xFF6F9BB4),
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
            Text(
                text = value,
                color = Color(0xFFBDEEFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
        }
    }
}
