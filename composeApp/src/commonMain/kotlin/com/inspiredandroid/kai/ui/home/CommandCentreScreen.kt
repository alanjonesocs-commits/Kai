package com.inspiredandroid.kai.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.ui.components.HolographicBrain

/**
 * Mobile landing page for NIA. Existing Chat and Settings destinations remain
 * the owners of their respective features; this screen is only the navigation hub.
 */
@Composable
fun CommandCentreScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenProjects: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "A.L.A.N.",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "NIA command centre",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        HolographicBrain(size = 232.dp)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onOpenChat,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open chat")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenProjects,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Projects & workspace")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenSkills,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skills & interactive tools")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Settings")
        }
    }
}
