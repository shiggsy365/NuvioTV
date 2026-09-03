package com.nuvio.tv.ui.screens.podcasts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.data.podcast.PodcastRepository
import com.nuvio.tv.domain.model.Podcast
import com.nuvio.tv.domain.model.PodcastEpisode
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class PodcastBrowseState(
    val loading: Boolean = true,
    val podcasts: List<Podcast> = emptyList(),
    val subscribedIds: Set<Long> = emptySet(),
    val query: String = "",
    val error: String? = null
)

@HiltViewModel
class PodcastBrowseViewModel @Inject constructor(private val repository: PodcastRepository) : ViewModel() {
    private val _state = MutableStateFlow(PodcastBrowseState())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.subscribedFeedIds.collect { ids -> _state.update { it.copy(subscribedIds = ids) } }
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        repository.trending().fold(
            onSuccess = { podcasts -> _state.update { it.copy(loading = false, podcasts = podcasts) } },
            onFailure = { error -> _state.update { it.copy(loading = false, error = error.message ?: "Unable to load podcasts") } }
        )
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            if (query.isBlank()) { refresh(); return@launch }
            _state.update { it.copy(loading = true, error = null) }
            repository.search(query).fold(
                onSuccess = { podcasts -> _state.update { it.copy(loading = false, podcasts = podcasts) } },
                onFailure = { error -> _state.update { it.copy(loading = false, error = error.message ?: "Search failed") } }
            )
        }
    }
}

data class PodcastDetailState(
    val loading: Boolean = true,
    val podcast: Podcast? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    val progress: Map<String, WatchProgress> = emptyMap(),
    val subscribed: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    progressRepository: WatchProgressRepository
) : ViewModel() {
    private val feedId = checkNotNull(savedStateHandle.get<String>("feedId")).toLong()
    private val _state = MutableStateFlow(PodcastDetailState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.subscribedFeedIds.collect { ids -> _state.update { it.copy(subscribed = feedId in ids) } }
        }
        viewModelScope.launch {
            progressRepository.allProgress.collect { items ->
                _state.update { state ->
                    state.copy(progress = items.filter {
                        it.contentType.equals("podcast", true) && it.contentId == "podcast:$feedId"
                    }.associateBy { it.videoId })
                }
            }
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        val podcast = repository.podcast(feedId).getOrElse {
            _state.update { state -> state.copy(loading = false, error = it.message ?: "Podcast not found") }
            return@launch
        }
        repository.episodes(feedId).fold(
            onSuccess = { episodes -> _state.update { it.copy(loading = false, podcast = podcast, episodes = episodes) } },
            onFailure = { error ->
                val detail = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                _state.update {
                    it.copy(
                        loading = false,
                        podcast = podcast,
                        error = detail?.let { message -> "Unable to load episodes: $message" }
                            ?: "Unable to load episodes"
                    )
                }
            }
        )
    }

    fun toggleSubscription() = viewModelScope.launch {
        repository.setSubscribed(feedId, !_state.value.subscribed)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PodcastsScreen(
    onPodcast: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: PodcastBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = LocalContentFocusRequester.current
    var query by remember { mutableStateOf(TextFieldValue(state.query)) }
    var initialTrendingFocusRequested by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(start = 42.dp, top = 24.dp, end = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Podcasts", style = MaterialTheme.typography.headlineLarge, color = NuvioTheme.colors.TextPrimary)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.search(it.text) },
                modifier = Modifier.width(420.dp),
                singleLine = true,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NuvioTheme.colors.Primary,
                    focusedBorderColor = NuvioTheme.colors.FocusRing,
                    unfocusedBorderColor = NuvioTheme.colors.Border,
                    focusedLabelColor = NuvioTheme.colors.Primary,
                    unfocusedLabelColor = NuvioTheme.colors.TextSecondary
                ),
                label = { androidx.compose.material3.Text("Search podcasts") }
            )
        }
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.error != null -> Column {
                Text(state.error!!, color = NuvioTheme.colors.TextPrimary)
                Button(onClick = viewModel::refresh) { Text("Retry") }
            }
            else -> {
                Text(
                    if (state.query.isBlank()) "Trending" else "Search results",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                LaunchedEffect(state.loading, state.podcasts) {
                    if (!initialTrendingFocusRequested && state.query.isBlank() && !state.loading && state.podcasts.isNotEmpty()) {
                        delay(120)
                        runCatching { focusRequester.requestFocus() }
                        initialTrendingFocusRequested = true
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    gridItems(state.podcasts, key = Podcast::id) { podcast ->
                        PodcastCard(
                            podcast = podcast,
                            onClick = { onPodcast(podcast.id) },
                            modifier = if (podcast == state.podcasts.firstOrNull()) Modifier.focusRequester(focusRequester) else Modifier
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PodcastCard(podcast: Podcast, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.width(148.dp).height(198.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing))
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp))
    ) {
        Column {
            AsyncImage(
                model = podcast.imageUrl,
                contentDescription = podcast.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(podcast.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    onPlay: (Podcast, PodcastEpisode, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.podcast == null -> Text(state.error ?: "Podcast not found", Modifier.align(Alignment.Center), color = NuvioTheme.colors.TextPrimary)
            else -> {
                val podcast = state.podcast!!
                Row(Modifier.fillMaxSize().padding(start = 42.dp, top = 24.dp, end = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(model = podcast.imageUrl, contentDescription = podcast.title, modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                        Text(podcast.title, style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
                        Text(podcast.author, color = NuvioTheme.colors.TextSecondary)
                        Button(onClick = viewModel::toggleSubscription) { Text(if (state.subscribed) "Unsubscribe" else "Subscribe") }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text("Episodes", style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary) }
                        state.error?.let { error ->
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(error, color = NuvioTheme.colors.TextPrimary)
                                    Button(onClick = viewModel::refresh) { Text("Retry") }
                                }
                            }
                        }
                        items(state.episodes, key = PodcastEpisode::id) { episode ->
                            val progress = state.progress["podcast-episode:${episode.id}"]
                            EpisodeCard(episode, progress) {
                                onPlay(podcast, episode, (episode.id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(episode: PodcastEpisode, progress: WatchProgress?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(112.dp),
        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, focusedContainerColor = NuvioTheme.colors.FocusBackground),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border(BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing))),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp))
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(model = episode.imageUrl, contentDescription = null, modifier = Modifier.size(92.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(episodeDate(episode.publishedAt), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                Text(episode.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                progress?.takeIf { it.duration > 0 }?.let {
                    LinearProgressIndicator(
                        progress = { (it.position.toFloat() / it.duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = NuvioTheme.colors.Primary,
                        trackColor = Color.DarkGray
                    )
                }
            }
        }
    }
}

private fun episodeDate(epochMs: Long): String =
    if (epochMs <= 0) "" else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))
