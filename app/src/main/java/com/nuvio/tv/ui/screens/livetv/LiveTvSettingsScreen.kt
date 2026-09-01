package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import com.nuvio.tv.domain.model.LiveTvSettings
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvSettingsScreen(onBack: () -> Unit, viewModel: LiveTvSettingsViewModel = hiltViewModel()) {
    val stored by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var draft by remember(stored) { mutableStateOf(stored) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Live TV", style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary)
        Text("Configure this profile's extended M3U playlist and XMLTV programme guide.", color = NuvioTheme.colors.TextSecondary)
        FocusButton("Show Live TV in the menu: ${if (draft.enabled) "On" else "Off"}") {
            draft = draft.copy(enabled = !draft.enabled)
        }
        LiveTvTextField("M3U playlist URL", draft.playlistUrl) { draft = draft.copy(playlistUrl = it) }
        LiveTvTextField("XMLTV EPG URL", draft.epgUrl) { draft = draft.copy(epgUrl = it) }
        LiveTvTextField("User-Agent", draft.userAgent) { draft = draft.copy(userAgent = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FocusButton("Save") { viewModel.save(draft, onBack) }
            FocusButton("Back", onBack)
        }
        if (message != null) Text(message!!, color = NuvioTheme.colors.Primary)
    }
}

@Composable
private fun LiveTvTextField(label: String, value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = NuvioTheme.colors.TextSecondary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = NuvioTheme.colors.TextPrimary),
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .onFocusChanged { focused = it.isFocused }
                .background(NuvioTheme.colors.SurfaceVariant, RoundedCornerShape(8.dp))
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(16.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FocusButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.SurfaceVariant,
            focusedContainerColor = NuvioTheme.colors.Primary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = Color.White
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) { Text(label) }
}
