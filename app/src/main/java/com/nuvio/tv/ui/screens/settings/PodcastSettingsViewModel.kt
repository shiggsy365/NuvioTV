package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.PodcastLibraryDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastSettingsViewModel @Inject constructor(
    private val store: PodcastLibraryDataStore
) : ViewModel() {
    val menuEnabled = store.menuEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true
    )

    fun setMenuEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setMenuEnabled(enabled) }
    }
}
