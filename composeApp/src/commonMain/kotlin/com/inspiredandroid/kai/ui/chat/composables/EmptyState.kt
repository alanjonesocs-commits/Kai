package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inspiredandroid.kai.ui.components.HolographicBrain
import com.inspiredandroid.kai.ui.components.animatedGradientBorder
import com.inspiredandroid.kai.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.kai_build_open
import kai.composeapp.generated.resources.privacy_agree_prefix
import kai.composeapp.generated.resources.privacy_policy
import kai.composeapp.generated.resources.start_interactive_ui
import kai.composeapp.generated.resources.welcome_message
import org.jetbrains.compose.resources.stringResource

private val TerminalGreenOnDark = Color(0xFF16C60C)
private val TerminalGreenOnLight = Color(0xFF13A10E)
private val NiaCyan = Color(0xFF62F5E5)
private val NiaViolet = Color(0xFFB69CFF)
private val NiaGreen = Color(0xFFB7FF6A)

@Composable
internal fun EmptyState(
    modifier: Modifier,
    isUsingSharedKey: Boolean,
    onStartInteractiveMode: (() -> Unit)? = null,
    onOpenKaiBuild: (() -> Unit)? = null,
    onOpenSystems: () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "N I A  /  NATURAL INTELLIGENCE ASSISTANT",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = NiaCyan,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "PERSONAL INTELLIGENCE SYSTEM",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = NiaViolet,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        HolographicBrain(size = 196.dp)
        Text(
            text = "NEURAL CORE  /  STANDBY",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.7.sp),
            color = NiaCyan,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(Res.string.welcome_message),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip("CHAT", NiaCyan)
            StatusChip("VOICE", NiaViolet)
            StatusChip("MEMORY", NiaGreen)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenSystems,
            modifier = Modifier.handCursor(),
            border = BorderStroke(1.dp, NiaCyan.copy(alpha = 0.65f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NiaCyan),
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("OPEN SYSTEMS")
        }
        if (onStartInteractiveMode != null) {
            Spacer(Modifier.height(6.dp))
            AnimatedBorderButton(
                text = stringResource(Res.string.start_interactive_ui),
                onClick = onStartInteractiveMode,
            )
        }
        if (onOpenKaiBuild != null) {
            val terminalGreen = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                TerminalGreenOnDark
            } else {
                TerminalGreenOnLight
            }
            OutlinedButton(
                onClick = onOpenKaiBuild,
                modifier = Modifier.handCursor(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = terminalGreen),
                border = BorderStroke(1.dp, terminalGreen.copy(alpha = 0.6f)),
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.kai_build_open))
            }
        }
        if (isUsingSharedKey) {
            val linkColor = MaterialTheme.colorScheme.primary
            val prefixText = stringResource(Res.string.privacy_agree_prefix)
            val policyText = stringResource(Res.string.privacy_policy)
            val annotatedString = remember(prefixText, policyText, linkColor) {
                buildAnnotatedString {
                    append(prefixText)
                    withLink(LinkAnnotation.Url(url = "https://schubert-simon.de/privacy/kai.txt")) {
                        withStyle(style = SpanStyle(color = linkColor)) {
                            append(policyText)
                        }
                    }
                }
            }
            Text(
                annotatedString,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.08f),
        contentColor = accent,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        )
    }
}

@Composable
private fun AnimatedBorderButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .handCursor()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .animatedGradientBorder(
                cornerRadius = 50.dp,
                borderWidth = 3.dp,
                backgroundColor = MaterialTheme.colorScheme.background,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
