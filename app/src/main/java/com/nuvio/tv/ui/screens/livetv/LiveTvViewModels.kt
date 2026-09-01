package com.nuvio.tv.ui.screens.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.livetv.LiveTvRepository
import com.nuvio.tv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.domain.model.LiveTvGuide
import com.nuvio.tv.domain.model.LiveTvSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTvUiState(val loading: Boolean = true, val guide: LiveTvGuide = LiveTvGuide(), val error: String? = null)

@HiltViewModel
class LiveTvViewModel @Inject constructor(private val repository: LiveTvRepository) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()
    init { refresh(false) }
    fun refresh(force: Boolean = true) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        repository.load(force).fold(
            onSuccess = { _state.value = LiveTvUiState(false, it) },
            onFailure = { _state.value = _state.value.copy(loading = false, error = it.message ?: "Unable to load Live TV") }
        )
    }
}

@HiltViewModel
class LiveTvSettingsViewModel @Inject constructor(private val store: LiveTvSettingsDataStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvSettings())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun save(value: LiveTvSettings, onSaved: () -> Unit = {}) = viewModelScope.launch {
        val valid = listOf(value.playlistUrl, value.epgUrl).all { url ->
            url.isBlank() || runCatching { val parsed = java.net.URI(url); parsed.scheme in setOf("http", "https") && parsed.host != null }.getOrDefault(false)
        }
        if (!valid) { _message.value = "Enter valid HTTP or HTTPS URLs"; return@launch }
        store.save(value.copy(playlistUpdatedAt = 0, epgUpdatedAt = 0))
        _message.value = "Live TV settings saved"
        onSaved()
    }
}

@HiltViewModel
class LiveTvPlaybackViewModel @Inject constructor(private val repository: LiveTvRepository) : ViewModel() {
    fun adjacent(channelId: String, delta: Int) = repository.guide.value.channels.let { channels ->
        val current = channels.indexOfFirst { it.id == channelId }
        if (current < 0 || channels.isEmpty()) null
        else channels[(current + delta).mod(channels.size)]
    }
}
