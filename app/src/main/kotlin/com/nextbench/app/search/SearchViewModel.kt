package com.nextbench.app.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextbench.data.firebase.SearchRepository
import com.nextbench.data.model.Club
import com.nextbench.data.model.Post
import com.nextbench.data.model.Product
import com.nextbench.data.model.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchTab { Posts, Books, People, Clubs }

@Immutable
data class SearchUiState(
    val query: String = "",
    val people: List<UserData> = emptyList(),
    val posts: List<Post> = emptyList(),
    val listings: List<Product> = emptyList(),
    val clubs: List<Club> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val selectedTab: SearchTab = SearchTab.Posts,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val recentSearchRepository: RecentSearchRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var viewer: UserData? = null
    private var searchJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            recentSearchRepository.searches.collect { searches ->
                _state.update { it.copy(recentSearches = searches) }
            }
        }
    }

    fun syncViewer(user: UserData?) {
        if (viewer?.uid == user?.uid && viewer?.school == user?.school && viewer?.city == user?.city) return
        viewer = user
        if (state.value.query.isBlank()) search()
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value.take(MaxQueryLength), error = null) }
        search()
    }

    fun selectTab(tab: SearchTab) = _state.update { it.copy(selectedTab = tab) }

    fun clearQuery() {
        _state.update { it.copy(query = "") }
        search()
    }

    fun clearRecentSearches() {
        viewModelScope.launch { recentSearchRepository.clear() }
    }

    fun submitQuery() = search(immediate = true, saveRecent = true)

    fun useRecentSearch(query: String) {
        _state.update { it.copy(query = query.take(MaxQueryLength), error = null) }
        search(immediate = true)
    }

    fun recordCurrentQuery() {
        val query = state.value.query
        if (query.isNotBlank()) viewModelScope.launch { recentSearchRepository.add(query) }
    }

    fun retry() = search(immediate = true)

    private fun search(immediate: Boolean = false, saveRecent: Boolean = false) {
        searchJob?.cancel()
        val requestGeneration = ++generation
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DebounceMillis)
            _state.update { it.copy(isLoading = true, error = null) }
            repository.search(
                query = state.value.query,
                school = viewer?.school.orEmpty(),
                city = viewer?.city.orEmpty(),
            ).fold(
                onSuccess = { results ->
                    if (requestGeneration != generation) return@fold
                    _state.update { it.copy(people = results.people, posts = results.posts, listings = results.listings, clubs = results.clubs, isLoading = false, hasSearched = true, error = null) }
                    if (saveRecent && state.value.query.isNotBlank()) recentSearchRepository.add(state.value.query)
                },
                onFailure = { error ->
                    if (requestGeneration != generation) return@fold
                    _state.update { it.copy(isLoading = false, hasSearched = true, error = error.searchMessage()) }
                },
            )
        }
    }

    companion object {
        const val MaxQueryLength = 120
        private const val DebounceMillis = 280L
    }
}

internal fun Throwable.searchMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("not configured", ignoreCase = true) -> "Firebase is not configured for this build. Add google-services.json to search."
        raw.contains("network", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true) -> "No internet connection. Check your network and try again."
        raw.isNotBlank() -> raw
        else -> "Search is unavailable right now. Please try again."
    }
}
