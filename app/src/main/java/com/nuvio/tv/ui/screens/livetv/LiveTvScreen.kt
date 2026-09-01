package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvProgramme
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.components.NuvioDialog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(onPlay: (LiveTvChannel) -> Unit, onBack: () -> Unit, viewModel: LiveTvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contentFocusRequester = LocalContentFocusRequester.current
    var group by remember(state.guide.groups) { mutableStateOf(state.guide.groups.firstOrNull()) }
    var details by remember { mutableStateOf<Pair<LiveTvChannel, LiveTvProgramme?>?>(null) }
    BackHandler { if (details != null) details = null else onBack() }
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!, color = NuvioTheme.colors.TextPrimary); Button(onClick = { viewModel.refresh() }) { Text("Retry") }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().padding(start = 42.dp, top = 28.dp, end = 28.dp)) {
                val now = System.currentTimeMillis()
                val slot = 30 * 60_000L
                val windowStart = (now / slot) * slot - slot
                val windowEnd = windowStart + 4 * slot
                val channelWidth = 104.dp
                val gap = 10.dp
                val timelineWidth = (maxWidth - channelWidth - gap).coerceAtLeast(1.dp)
                val nowOffset = timelineWidth * ((now - windowStart).toFloat() / (windowEnd - windowStart).toFloat())
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Live TV", style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary)
                    LazyRow(
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.guide.groups) { item ->
                            FilterChip(
                                selected = group == item,
                                onClick = { group = item },
                                modifier = if (item == state.guide.groups.firstOrNull()) Modifier.focusRequester(contentFocusRequester) else Modifier
                            ) { Text(item) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Spacer(Modifier.width(channelWidth)); TimeHeader(windowStart)
                    }
                    val channels = state.guide.channels.filter { it.group == group }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(channels, key = { it.id + it.streamUrl }) { channel ->
                            val programmes = state.guide.programmes.filter { it.channelId == channel.id }.sortedBy { it.startMillis }
                            ChannelRow(channel, programmes, windowStart, windowEnd) { details = channel to it }
                        }
                    }
                }
                Box(
                    Modifier
                        .offset(x = channelWidth + gap + nowOffset, y = 112.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(NuvioTheme.colors.Primary)
                )
                LaunchedEffect(state.guide.loadedAt) {
                    delay(120)
                    runCatching { contentFocusRequester.requestFocus() }
                }
            }
        }
        details?.let { (channel, programme) -> ProgrammeDetails(channel, programme, { onPlay(channel) }) { details = null } }
    }
}

@Composable private fun TimeHeader(windowStart: Long) {
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        repeat(5) { Text(format.format(Date(windowStart + it * 30 * 60_000L)), color = NuvioTheme.colors.TextSecondary) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable private fun ChannelRow(channel: LiveTvChannel, programmes: List<LiveTvProgramme>, windowStart: Long, windowEnd: Long, onSelect: (LiveTvProgramme?) -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(104.dp).fillMaxHeight().background(NuvioTheme.colors.SurfaceVariant).padding(6.dp), contentAlignment = Alignment.Center) {
            val context = LocalContext.current
            val painter = rememberAsyncImagePainter(
                ImageRequest.Builder(context).data(channel.logoUrl).build()
            )
            val painterState by painter.state.collectAsState()
            if (channel.logoUrl.isNullOrBlank() || painterState is AsyncImagePainter.State.Error) {
                Text(
                    channel.name,
                    maxLines = 3,
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
        val visible = programmes.filter { it.endMillis > windowStart && it.startMillis < windowEnd }
        if (visible.isEmpty()) Card(onClick = { onSelect(null) }, modifier = Modifier.weight(1f).fillMaxHeight()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { Text("No Programme Information", Modifier.padding(12.dp)) } }
        else {
            var cursor = windowStart
            visible.forEach { item ->
                val start = item.startMillis.coerceAtLeast(windowStart)
                val end = item.endMillis.coerceAtMost(windowEnd)
                if (start > cursor) Spacer(Modifier.weight((start - cursor).toFloat()))
                Card(onClick = { onSelect(item) }, modifier = Modifier.weight((end - start).coerceAtLeast(1).toFloat()).fillMaxHeight()) { Column(Modifier.padding(10.dp)) { Text(item.title, maxLines=1, overflow=TextOverflow.Ellipsis); Text(timeRange(item), style=MaterialTheme.typography.bodySmall) } }
                cursor = end.coerceAtLeast(cursor)
            }
            if (cursor < windowEnd) Spacer(Modifier.weight((windowEnd - cursor).toFloat()))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable private fun ProgrammeDetails(channel: LiveTvChannel, programme: LiveTvProgramme?, onPlay: () -> Unit, onClose: () -> Unit) {
    val playFocusRequester = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onClose,
        title = channel.name,
        subtitle = programme?.let(::timeRange),
        width = 720.dp,
        backgroundContent = {
            AsyncImage(
                model = programme?.iconUrl ?: channel.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(NuvioTheme.colors.BackgroundElevated.copy(alpha=.88f)))
        }
    ) {
        Text(programme?.title ?: "No Programme Information", style=MaterialTheme.typography.headlineMedium, color=NuvioTheme.colors.TextPrimary)
        programme?.description?.takeIf(String::isNotBlank)?.let { Text(it, color=NuvioTheme.colors.TextPrimary) }
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Button(onClick=onPlay, modifier=Modifier.focusRequester(playFocusRequester)) { Text("Play channel") }
            Button(onClick=onClose) { Text("Return to guide") }
        }
    }
    LaunchedEffect(programme, channel.id) {
        delay(80)
        runCatching { playFocusRequester.requestFocus() }
    }
}

private fun timeRange(item: LiveTvProgramme): String { val f=SimpleDateFormat("HH:mm", Locale.getDefault()); return "${f.format(Date(item.startMillis))} – ${f.format(Date(item.endMillis))}" }
