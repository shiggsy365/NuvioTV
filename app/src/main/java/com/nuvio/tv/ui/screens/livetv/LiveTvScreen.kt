package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvProgramme
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private data class FocusedGuideItem(val channel: LiveTvChannel, val programme: LiveTvProgramme?)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    onPlay: (LiveTvChannel) -> Unit,
    onBack: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contentFocusRequester = LocalContentFocusRequester.current
    var group by remember(state.guide.groups) { mutableStateOf(state.guide.groups.firstOrNull()) }
    var focusedItem by remember { mutableStateOf<FocusedGuideItem?>(null) }

    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.error!!, color = NuvioTheme.colors.TextPrimary)
                Button(onClick = viewModel::refresh) { Text("Retry") }
            }
            else -> BoxWithConstraints(
                Modifier.fillMaxSize().padding(start = 42.dp, top = 18.dp, end = 24.dp, bottom = 14.dp)
            ) {
                val now = System.currentTimeMillis()
                val slot = 30 * 60_000L
                val windowStart = (now / slot) * slot
                val windowEnd = windowStart + 4 * slot
                val channelWidth = 132.dp
                val gap = 6.dp
                val timelineWidth = (maxWidth - channelWidth - gap).coerceAtLeast(1.dp)
                val nowOffset = timelineWidth * ((now - windowStart).toFloat() / (windowEnd - windowStart))
                val channels = state.guide.channels.filter { it.group == group }

                LaunchedEffect(group, state.guide.loadedAt) {
                    focusedItem = channels.firstOrNull()?.let { channel ->
                        FocusedGuideItem(
                            channel,
                            state.guide.programmes.firstOrNull {
                                it.channelId == channel.id && it.startMillis <= now && it.endMillis > now
                            }
                        )
                    }
                }

                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgrammeInfoPanel(focusedItem)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.guide.groups) { item ->
                            FilterChip(
                                selected = group == item,
                                onClick = { group = item },
                                colors = FilterChipDefaults.colors(
                                    focusedContentColor = Color.Black,
                                    focusedSelectedContentColor = Color.Black
                                ),
                                modifier = if (item == state.guide.groups.firstOrNull()) {
                                    Modifier.focusRequester(contentFocusRequester)
                                } else Modifier
                            ) { Text(item, maxLines = 1) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().height(24.dp),
                                horizontalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                Spacer(Modifier.width(channelWidth))
                                TimeHeader(windowStart)
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(channels, key = { it.id + it.streamUrl }) { channel ->
                                    val programmes = state.guide.programmes
                                        .filter { it.channelId == channel.id }
                                        .sortedBy { it.startMillis }
                                    ChannelRow(
                                        channel = channel,
                                        programmes = programmes,
                                        windowStart = windowStart,
                                        windowEnd = windowEnd,
                                        channelWidth = channelWidth,
                                        onFocused = { focusedItem = FocusedGuideItem(channel, it) },
                                        onPlay = { onPlay(channel) }
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .offset(x = channelWidth + gap + nowOffset, y = 24.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(NuvioTheme.colors.Primary)
                        )
                    }
                }
                LaunchedEffect(state.guide.loadedAt) {
                    delay(120)
                    runCatching { contentFocusRequester.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeInfoPanel(focused: FocusedGuideItem?) {
    val channel = focused?.channel
    val programme = focused?.programme
    Row(
        Modifier.fillMaxWidth().height(126.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            Modifier.width(224.dp).fillMaxHeight().background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = programme?.iconUrl ?: channel?.logoUrl,
                contentDescription = programme?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            Modifier.fillMaxHeight().weight(1f).padding(vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                programme?.title ?: channel?.name ?: "Live TV",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                programme?.let(::timeRange) ?: "No Programme Information",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            channel?.name?.let {
                Text(it, color = NuvioTheme.colors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            programme?.description?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long) {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(5) {
            Text(
                format.format(Date(windowStart + it * 30 * 60_000L)),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(
    channel: LiveTvChannel,
    programmes: List<LiveTvProgramme>,
    windowStart: Long,
    windowEnd: Long,
    channelWidth: Dp,
    onFocused: (LiveTvProgramme?) -> Unit,
    onPlay: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChannelIdentity(channel, Modifier.width(channelWidth).fillMaxHeight())
        val visible = programmes.filter { it.endMillis > windowStart && it.startMillis < windowEnd }
        if (visible.isEmpty()) {
            ProgrammeCard(
                title = "No Programme Information",
                time = null,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onFocused = { onFocused(null) },
                onClick = onPlay
            )
        } else {
            var cursor = windowStart
            visible.forEach { item ->
                val start = item.startMillis.coerceAtLeast(windowStart)
                val end = item.endMillis.coerceAtMost(windowEnd)
                if (start > cursor) Spacer(Modifier.weight((start - cursor).toFloat()))
                ProgrammeCard(
                    title = item.title,
                    time = timeRange(item),
                    modifier = Modifier.weight((end - start).coerceAtLeast(1).toFloat()).fillMaxHeight(),
                    onFocused = { onFocused(item) },
                    onClick = onPlay
                )
                cursor = end.coerceAtLeast(cursor)
            }
            if (cursor < windowEnd) Spacer(Modifier.weight((windowEnd - cursor).toFloat()))
        }
    }
}

@Composable
private fun ChannelIdentity(channel: LiveTvChannel, modifier: Modifier = Modifier) {
    Box(
        modifier.background(NuvioTheme.colors.SurfaceVariant).padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        val painter = rememberAsyncImagePainter(ImageRequest.Builder(context).data(channel.logoUrl).build())
        val painterState by painter.state.collectAsState()
        if (channel.logoUrl.isNullOrBlank() || painterState is AsyncImagePainter.State.Error) {
            Text(
                channel.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = NuvioTheme.colors.TextPrimary,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Image(
                painter = painter,
                contentDescription = channel.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProgrammeCard(
    title: String,
    time: String?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() }) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            time?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun timeRange(item: LiveTvProgramme): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${format.format(Date(item.startMillis))} – ${format.format(Date(item.endMillis))}"
}
