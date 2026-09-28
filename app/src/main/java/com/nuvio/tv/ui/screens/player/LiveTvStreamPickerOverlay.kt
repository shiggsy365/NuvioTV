package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.LiveTvStreamOption
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.livetv.LiveTvStreamPickerState
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvStreamPickerOverlay(
    state: LiveTvStreamPickerState,
    focusRequester: FocusRequester,
    onClose: () -> Unit,
    onSelect: (LiveTvStreamOption) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(state.visible, state.loading, state.options.size, state.error) {
        if (state.visible && (state.loading || state.error != null || state.options.isEmpty())) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .focusGroup()
                .width(520.dp)
                .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
                .background(NuvioTheme.colors.BackgroundElevated)
                .padding(NuvioTheme.spacing.xl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Channel streams",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                DialogButton(
                    text = "Close",
                    onClick = onClose,
                    isPrimary = false,
                    modifier = Modifier.focusRequester(focusRequester)
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                state.error != null -> Text(
                    text = state.error,
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                state.options.isEmpty() -> Text(
                    text = "No alternate streams returned by Dispatcharr.",
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                else -> {
                    val firstItemFocusRequester = remember(state.options) { FocusRequester() }
                    LaunchedEffect(state.visible, state.options) {
                        if (state.visible) {
                            runCatching { firstItemFocusRequester.requestFocus() }
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        itemsIndexed(state.options, key = { _, item -> item.id }) { index, item ->
                            LiveTvStreamOptionCard(
                                option = item,
                                onSelect = { onSelect(item) },
                                modifier = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTvStreamOptionCard(
    option: LiveTvStreamOption,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val subtitle = listOfNotNull(
        option.source,
        option.resolution,
        option.bitrate,
        option.status
    ).joinToString(" • ")
    DialogButton(
        text = option.name,
        onClick = onSelect,
        enabled = option.url?.isNotBlank() == true,
        isPrimary = false,
        modifier = modifier.fillMaxWidth()
    )
    if (subtitle.isNotBlank()) {
        Text(
            text = subtitle,
            color = NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = NuvioTheme.spacing.md, end = NuvioTheme.spacing.md)
        )
    }
}
