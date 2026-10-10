package dev.ligustah.lsnav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FavoriteEditorState(
    val id: Long? = null,
    val draft: FavoriteDraft? = null,
    val saving: Boolean = false,
    val error: String? = null
)

data class NavigationState(
    val configuration: AppConfiguration = AppConfiguration(),
    val configurationLoaded: Boolean = false,
    val scooters: RemoteData<List<ScooterSummary>> = RemoteData(),
    val scooterReconnectRequired: Boolean = false,
    val destination: RemoteData<NavigationTarget> = RemoteData(),
    val savedLocations: RemoteData<List<PlaceResult>> = RemoteData(),
    val personalPlaces: RemoteData<PersonalPlaces> = RemoteData(),
    val favoriteEditor: FavoriteEditorState = FavoriteEditorState(),
    val favoriteMutationSequence: Long = 0,
    val sending: Boolean = false,
    val lastCommandOutcome: CommandOutcome? = null,
    val lastCommandIncomingRequestId: Long? = null,
    val currentIncomingRequestId: Long? = null,
    val incomingResolutionRequired: Boolean = false,
    val resolvedIncomingRequestId: Long? = null,
    val uncertainTarget: CommandTarget? = null,
    val uncertainCheckedDestination: NavigationTarget? = null,
    val uncertainCheckError: String? = null,
    val checkingUncertain: Boolean = false,
    val pendingCommandForOtherAccount: Boolean = false,
    val pendingCommandLoading: Boolean = true,
    val defaultScooterId: Long? = null,
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val resolvedIncomingText: String? = null,
    val searching: Boolean = false,
    val error: String? = null
)

class NavigationViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val gateway: NavigationGateway = NavigationRepository.get(application),
    private val resolver: CoordinateResolver = CoordinateResolver(application),
    private val configurations: kotlinx.coroutines.flow.Flow<AppConfiguration> = AppSettings(application).configuration,
    private val pendingCommands: PendingCommandPersistence = PendingCommandStore(application)
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(NavigationState(
        favoriteMutationSequence = savedState["favoriteMutationSequence"] ?: 0L,
        currentIncomingRequestId = savedState["currentIncomingRequestId"]
    ))
    val state: StateFlow<NavigationState> = mutableState
    private var storedConfiguration: AppConfiguration? = null
    private var scootersJob: Job? = null
    private var destinationJob: Job? = null
    private var locationsJob: Job? = null
    private var personalPlacesJob: Job? = null
    private var favoriteJob: Job? = null
    private var commandJob: Job? = null
    private var searchJob: Job? = null
    private var scooterGeneration = 0
    private var destinationGeneration = 0
    private var locationsGeneration = 0
    private var personalGeneration = 0
    private var searchGeneration = 0
    private var commandGeneration = 0

    init {
        viewModelScope.launch {
            try {
                configurations.collect { config ->
                    if (config != storedConfiguration) {
                        val old = storedConfiguration
                        storedConfiguration = config
                        cancelResources()
                        commandGeneration++
                        commandJob?.cancel()
                        searchGeneration++; searchJob?.cancel()
                        if (old?.accountKey != config.accountKey) favoriteJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            configuration = config, configurationLoaded = true, sending = false, defaultScooterId = config.scooterId,
                            query = if (old?.accountKey == config.accountKey) mutableState.value.query else "",
                            results = if (old?.accountKey == config.accountKey) mutableState.value.results else emptyList(),
                            resolvedIncomingText = if (old?.accountKey == config.accountKey) mutableState.value.resolvedIncomingText else null,
                            resolvedIncomingRequestId = if (old?.accountKey == config.accountKey) mutableState.value.resolvedIncomingRequestId else null,
                            incomingResolutionRequired = if (old?.accountKey == config.accountKey) mutableState.value.incomingResolutionRequired else false,
                            favoriteEditor = if (old?.accountKey == config.accountKey) mutableState.value.favoriteEditor else FavoriteEditorState(),
                            scooters = if (old?.accountKey == config.accountKey) mutableState.value.scooters else RemoteData(),
                            scooterReconnectRequired = if (old?.accountKey == config.accountKey) mutableState.value.scooterReconnectRequired else false,
                            destination = RemoteData(), savedLocations = RemoteData(),
                            personalPlaces = if (old?.accountKey == config.accountKey) mutableState.value.personalPlaces else RemoteData(),
                            lastCommandOutcome = null, lastCommandIncomingRequestId = null,
                            uncertainTarget = null, uncertainCheckedDestination = null,
                            uncertainCheckError = null, checkingUncertain = false,
                            pendingCommandForOtherAccount = false, pendingCommandLoading = true,
                            error = null
                        )
                        restorePendingCommand(config)
                        if (config.isAuthenticated) {
                            fetchScooters()
                            refreshPersonalPlaces()
                            refresh()
                        }
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Unable to load settings. Restart the app and retry.")
            }
        }
        viewModelScope.launch {
            gateway.personalPlacesChanges.collect { changedConfiguration ->
                val current = mutableState.value.configuration
                if (current.isAuthenticated && changedConfiguration.accountKey == current.accountKey) {
                    mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.copy(updatedAt = 0))
                    if (!mutableState.value.sending) refreshPersonalPlaces(forceRefresh = true)
                }
            }
        }
        viewModelScope.launch {
            gateway.changes.collect { change ->
                val current = mutableState.value.configuration
                if (current.isReady && change.configuration.accountKey == current.accountKey &&
                    change.configuration.scooterId == current.scooterId) {
                    mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.copy(updatedAt = 0))
                    if (!mutableState.value.sending) refreshDestination(current)
                }
            }
        }
    }

    private suspend fun restorePendingCommand(config: AppConfiguration) {
        try {
            val pending = pendingCommands.read()
            if (storedConfiguration != config) return
            if (!config.isAuthenticated) {
                mutableState.value = mutableState.value.copy(pendingCommandLoading = false)
                return
            }
            val current = mutableState.value
            mutableState.value = current.copy(
                pendingCommandLoading = false,
                pendingCommandForOtherAccount = pending != null && pending.accountFingerprint != config.accountFingerprint(),
                uncertainTarget = pending?.takeIf { it.accountFingerprint == config.accountFingerprint() }?.let {
                    CommandTarget(config.copy(scooterId = it.scooterId, scooterName = it.scooterName), it.place)
                },
                lastCommandOutcome = pending?.takeIf { it.accountFingerprint == config.accountFingerprint() }?.let {
                    CommandOutcome.DeliveryUncertain(CommandTarget(config.copy(scooterId = it.scooterId, scooterName = it.scooterName), it.place))
                }
            )
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            if (storedConfiguration == config) mutableState.value = mutableState.value.copy(
                pendingCommandLoading = false, pendingCommandForOtherAccount = true,
                error = "A previous scooter request needs recovery, but its local status could not be read. Do not retry it yet."
            )
        }
    }

    private fun cancelResources() {
        scooterGeneration++; scootersJob?.cancel()
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        personalGeneration++; personalPlacesJob?.cancel()
    }

    fun fetchScooters(forceRefresh: Boolean = false) {
        val config = storedConfiguration ?: return
        if (!config.isAuthenticated) return
        val generation = ++scooterGeneration
        scootersJob?.cancel()
        mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.refreshing(), scooterReconnectRequired = false)
        scootersJob = viewModelScope.launch {
            try {
                val value = gateway.scooters(config, forceRefresh).distinctBy { it.id }
                if (generation == scooterGeneration && storedConfiguration?.accountKey == config.accountKey) {
                    val current = mutableState.value
                    val selectedId = current.configuration.scooterId
                    val selectedExists = selectedId == null || value.any { it.id == selectedId }
                    val defaultScooterId = current.defaultScooterId?.takeIf { id -> value.any { it.id == id } }
                    mutableState.value = current.copy(
                        scooters = RemoteData.loaded(value), scooterReconnectRequired = false,
                        defaultScooterId = defaultScooterId,
                        configuration = if (selectedExists) current.configuration else current.configuration.copy(scooterId = null, scooterName = ""),
                        destination = if (selectedExists) current.destination else RemoteData(),
                        savedLocations = if (selectedExists) current.savedLocations else RemoteData()
                    )
                    if (!selectedExists) {
                        destinationGeneration++; destinationJob?.cancel()
                        locationsGeneration++; locationsJob?.cancel()
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == scooterGeneration) mutableState.value = mutableState.value.copy(
                    scooters = mutableState.value.scooters.failed(scooterFetchMessage(error, config)),
                    scooterReconnectRequired = scooterFetchNeedsReconnect(error, config)
                )
            } finally {
                if (generation == scooterGeneration) mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.copy(loading = false))
            }
        }
    }

    fun selectScooter(scooter: ScooterSummary) {
        val state = mutableState.value
        if (state.sending || scooter !in state.scooters.value.orEmpty()) return
        val config = state.configuration.copy(scooterId = scooter.id, scooterName = scooter.name)
        if (config == state.configuration) return
        mutableState.value = state.copy(configuration = config, destination = RemoteData(), savedLocations = RemoteData(), lastCommandOutcome = null, error = null)
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        refresh()
    }

    fun refreshIfStale() {
        val current = mutableState.value
        val config = current.configuration
        if (!config.isAuthenticated || current.sending) return
        if (current.scooters.stale(30_000)) fetchScooters()
        if (current.personalPlaces.stale(60_000)) refreshPersonalPlaces()
        if (config.isReady) {
            if (current.destination.stale(30_000)) refreshDestination(config)
            if (current.savedLocations.stale(60_000)) refreshSavedLocations(config)
        }
    }

    fun refresh() {
        val current = mutableState.value
        val config = current.configuration
        if (!config.isAuthenticated || current.sending) return
        fetchScooters(forceRefresh = true)
        refreshPersonalPlaces(forceRefresh = true)
        if (config.isReady) {
            refreshDestination(config)
            refreshSavedLocations(config)
        }
    }

    private fun refreshDestination(config: AppConfiguration) {
        val generation = ++destinationGeneration
        destinationJob?.cancel()
        mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.refreshing())
        destinationJob = viewModelScope.launch {
            try {
                val value = gateway.destination(config)
                if (generation == destinationGeneration && mutableState.value.configuration == config) mutableState.value = mutableState.value.copy(destination = RemoteData.loaded(value))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == destinationGeneration) mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.failed(userMessage(error, config)))
            } finally {
                if (generation == destinationGeneration) mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.copy(loading = false))
            }
        }
    }

    private fun refreshSavedLocations(config: AppConfiguration) {
        val generation = ++locationsGeneration
        locationsJob?.cancel()
        mutableState.value = mutableState.value.copy(savedLocations = mutableState.value.savedLocations.refreshing())
        locationsJob = viewModelScope.launch {
            try {
                val value = gateway.savedLocations(config)
                if (generation == locationsGeneration && mutableState.value.configuration == config) mutableState.value = mutableState.value.copy(savedLocations = RemoteData.loaded(value))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == locationsGeneration) mutableState.value = mutableState.value.copy(savedLocations = mutableState.value.savedLocations.failed(userMessage(error, config)))
            } finally {
                if (generation == locationsGeneration) mutableState.value = mutableState.value.copy(savedLocations = mutableState.value.savedLocations.copy(loading = false))
            }
        }
    }

    fun refreshPersonalPlaces(forceRefresh: Boolean = false) {
        val config = storedConfiguration ?: return
        if (!config.isAuthenticated) return
        val generation = ++personalGeneration
        personalPlacesJob?.cancel()
        mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.refreshing())
        personalPlacesJob = viewModelScope.launch {
            try {
                val value = gateway.personalPlaces(config, forceRefresh)
                if (generation == personalGeneration && storedConfiguration?.accountKey == config.accountKey) mutableState.value = mutableState.value.copy(personalPlaces = RemoteData.loaded(value))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == personalGeneration) mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.failed(userMessage(error, config)))
            } finally {
                if (generation == personalGeneration) mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.copy(loading = false))
            }
        }
    }

    fun editFavorite(id: Long?, draft: FavoriteDraft?) {
        if (!mutableState.value.favoriteEditor.saving) mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(id, draft))
    }

    fun saveFavorite() {
        val current = mutableState.value
        val config = current.configuration
        val editor = current.favoriteEditor
        val draft = editor.draft ?: return
        if (editor.saving || !config.isAuthenticated || !draft.place.coordinates.isValid()) return
        if (config.favoritePermission == PermissionCapability.UpgradeRequired) {
            mutableState.value = current.copy(favoriteEditor = editor.copy(error = "Reconnect Sunshine and approve saved-place access to manage favorites."))
            return
        }
        favoriteJob?.cancel()
        mutableState.value = current.copy(favoriteEditor = editor.copy(saving = true, error = null))
        favoriteJob = viewModelScope.launch {
            try {
                if (editor.id == null) gateway.createFavorite(config, draft) else gateway.updateFavorite(config, editor.id, draft)
                if (storedConfiguration?.accountKey == config.accountKey) {
                    val sequence = mutableState.value.favoriteMutationSequence + 1
                    savedState["favoriteMutationSequence"] = sequence
                    mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(), favoriteMutationSequence = sequence)
                    refreshPersonalPlaces(forceRefresh = true)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (storedConfiguration?.accountKey == config.accountKey) mutableState.value = mutableState.value.copy(favoriteEditor = editor.copy(saving = false, error = userMessage(error, config)))
            }
        }
    }

    fun setFavorite(savedPlace: SavedPlace, favorite: Boolean) {
        val config = mutableState.value.configuration
        val editor = mutableState.value.favoriteEditor.takeIf { it.id == savedPlace.id }
            ?: FavoriteEditorState(savedPlace.id, FavoriteDraft(savedPlace.place.label, savedPlace.place))
        mutateFavorite(config, editor) { gateway.setFavorite(config, savedPlace.id, savedPlace.place, favorite) }
    }

    fun deleteFavorite(savedPlace: SavedPlace) {
        val config = mutableState.value.configuration
        val editor = mutableState.value.favoriteEditor.takeIf { it.id == savedPlace.id }
            ?: FavoriteEditorState(savedPlace.id, FavoriteDraft(savedPlace.place.label, savedPlace.place))
        mutateFavorite(config, editor) { gateway.deleteFavorite(config, savedPlace.id) }
    }

    private fun mutateFavorite(config: AppConfiguration, editor: FavoriteEditorState, mutation: suspend () -> Unit) {
        if (!config.isAuthenticated || mutableState.value.favoriteEditor.saving) return
        if (config.favoritePermission == PermissionCapability.UpgradeRequired) {
            mutableState.value = mutableState.value.copy(favoriteEditor = editor.copy(error = "Reconnect Sunshine and approve saved-place access to manage favorites."))
            return
        }
        val owner = config.accountKey
        mutableState.value = mutableState.value.copy(favoriteEditor = editor.copy(saving = true, error = null))
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            try {
                mutation()
                if (storedConfiguration?.accountKey == owner) {
                    val sequence = mutableState.value.favoriteMutationSequence + 1
                    savedState["favoriteMutationSequence"] = sequence
                    mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(), favoriteMutationSequence = sequence)
                    refreshPersonalPlaces(forceRefresh = true)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (storedConfiguration?.accountKey == owner) mutableState.value = mutableState.value.copy(favoriteEditor = editor.copy(saving = false, error = userMessage(error, config)))
            } finally {
                if (storedConfiguration?.accountKey == owner && mutableState.value.favoriteEditor.saving) mutableState.value = mutableState.value.copy(favoriteEditor = mutableState.value.favoriteEditor.copy(saving = false))
            }
        }
    }

    fun beginIncomingRequest(requestId: Long, requireResolution: Boolean = false) {
        val current = mutableState.value
        if (current.currentIncomingRequestId == requestId) {
            if (requireResolution && current.resolvedIncomingRequestId != requestId && !current.incomingResolutionRequired) {
                mutableState.value = current.copy(incomingResolutionRequired = true, results = emptyList(), resolvedIncomingText = null)
            }
            return
        }
        searchGeneration++; searchJob?.cancel()
        savedState["currentIncomingRequestId"] = requestId
        mutableState.value = current.copy(
            currentIncomingRequestId = requestId, incomingResolutionRequired = true,
            resolvedIncomingRequestId = null, resolvedIncomingText = null,
            results = emptyList(), query = "", searching = false, error = null,
            lastCommandOutcome = null, lastCommandIncomingRequestId = null
        )
    }

    fun updateQuery(value: String, correctingIncomingRequestId: Long? = null) {
        val current = mutableState.value
        if (current.incomingResolutionRequired && current.currentIncomingRequestId != correctingIncomingRequestId) return
        val currentRequestId = current.currentIncomingRequestId
        val correctedRequestId = correctingIncomingRequestId?.takeIf { it == currentRequestId }
            ?: current.resolvedIncomingRequestId?.takeIf { it == currentRequestId }
        searchGeneration++
        searchJob?.cancel()
        mutableState.value = current.copy(
            query = value, results = emptyList(), resolvedIncomingText = null,
            resolvedIncomingRequestId = correctedRequestId, incomingResolutionRequired = if (correctedRequestId != null) false else current.incomingResolutionRequired,
            searching = false
        )
        if (value.length < 3) return
        searchJob = viewModelScope.launch { kotlinx.coroutines.delay(500); search() }
    }

    fun resolveIncoming(text: String, requestId: Long? = null) {
        if (text.isBlank() || text.length > MapsIntentParser.MAX_INPUT_LENGTH) return
        if (mutableState.value.currentIncomingRequestId != requestId) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        mutableState.value = mutableState.value.copy(query = text, results = emptyList(), resolvedIncomingText = null, resolvedIncomingRequestId = null, searching = true, error = null)
        searchJob = viewModelScope.launch {
            try {
                val results = resolver.resolve(text)
                if (generation == searchGeneration && mutableState.value.currentIncomingRequestId == requestId) mutableState.value = mutableState.value.copy(results = results, resolvedIncomingText = text, resolvedIncomingRequestId = requestId, incomingResolutionRequired = false,
                    error = if (results.isEmpty()) "No destination found. Choose a place with a clear destination." else null)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == searchGeneration && mutableState.value.currentIncomingRequestId == requestId) mutableState.value = mutableState.value.copy(error = userMessage(error), resolvedIncomingRequestId = requestId, incomingResolutionRequired = false)
            } finally {
                if (generation == searchGeneration && mutableState.value.currentIncomingRequestId == requestId) mutableState.value = mutableState.value.copy(searching = false)
            }
        }
    }

    fun search() {
        val current = mutableState.value
        val query = current.query
        if (query.isBlank()) return
        val incomingRequestId = current.resolvedIncomingRequestId?.takeIf { it == current.currentIncomingRequestId }
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(searching = true, error = null)
            try {
                val results = resolver.search(query)
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(results = results, resolvedIncomingRequestId = incomingRequestId,
                    error = if (results.isEmpty()) "No destination found. Enter an address or exact coordinates." else null)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(error = userMessage(error))
            } finally {
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(searching = false)
            }
        }
    }

    fun checkUncertainCommand() {
        val current = mutableState.value
        val target = current.uncertainTarget ?: return
        if (current.checkingUncertain || current.sending || target.configuration.accountFingerprint() != current.configuration.accountFingerprint()) return
        mutableState.value = current.copy(checkingUncertain = true, uncertainCheckError = null)
        commandJob = viewModelScope.launch {
            try {
                val actual = gateway.destination(target.configuration)
                if (mutableState.value.configuration.accountFingerprint() == target.configuration.accountFingerprint() && mutableState.value.uncertainTarget == target) {
                    pendingCommands.clear()
                    val latest = mutableState.value
                    if (latest.configuration.accountFingerprint() == target.configuration.accountFingerprint() && latest.uncertainTarget == target) {
                        mutableState.value = latest.copy(
                            checkingUncertain = false, uncertainCheckedDestination = actual,
                            uncertainCheckError = null, pendingCommandForOtherAccount = false
                        )
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (mutableState.value.uncertainTarget == target) mutableState.value = mutableState.value.copy(
                    checkingUncertain = false, uncertainCheckError = userMessage(error, target.configuration)
                )
            } finally {
                if (mutableState.value.uncertainTarget == target && mutableState.value.checkingUncertain) {
                    mutableState.value = mutableState.value.copy(checkingUncertain = false)
                }
            }
        }
    }

    fun dismissCheckedUncertainCommand() {
        val current = mutableState.value
        if (current.uncertainCheckedDestination != null) mutableState.value = current.copy(
            uncertainTarget = null, uncertainCheckedDestination = null, uncertainCheckError = null, lastCommandOutcome = null
        )
    }

    fun acknowledgePendingCommandCheckedOutsideApp() {
        val current = mutableState.value
        if (current.checkingUncertain || current.pendingCommandLoading || (current.uncertainTarget == null && !current.pendingCommandForOtherAccount)) return
        mutableState.value = current.copy(checkingUncertain = true, uncertainCheckError = null)
        viewModelScope.launch {
            try {
                pendingCommands.clear()
                mutableState.value = mutableState.value.copy(
                    uncertainTarget = null, uncertainCheckedDestination = null, uncertainCheckError = null,
                    pendingCommandForOtherAccount = false, lastCommandOutcome = null, checkingUncertain = false
                )
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(checkingUncertain = false, uncertainCheckError = "Unable to clear the pending request. Check again or try later.")
            }
        }
    }

    fun sendToScooter(target: CommandTarget, expectedIncomingRequestId: Long? = null) {
        val current = mutableState.value
        val config = current.configuration
        if (current.currentIncomingRequestId != expectedIncomingRequestId || current.incomingResolutionRequired) {
            mutableState.value = current.copy(error = "A newer shared place is being resolved. Confirm its destination before sending.")
            return
        }
        val scooter = current.scooters.value.orEmpty().firstOrNull { it.id == target.configuration.scooterId }
        if (current.pendingCommandLoading || current.pendingCommandForOtherAccount || (current.uncertainTarget != null && current.uncertainCheckedDestination == null) || target.configuration != config || !config.isReady || scooter == null || scooter.online != true || current.sending) {
            mutableState.value = current.copy(error = if (scooter?.online == false) "This scooter is offline. Refresh or choose another scooter; your place is still here." else "Scooter or account access changed. Confirm the destination again.")
            return
        }
        if (target.place != null && !target.place.coordinates.isValid()) return
        dispatchCommand(target, expectedIncomingRequestId)
    }

    private fun dispatchCommand(target: CommandTarget, incomingRequestId: Long?) {
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        val requestGeneration = ++commandGeneration
        mutableState.value = mutableState.value.copy(sending = true, error = null, lastCommandOutcome = null, lastCommandIncomingRequestId = null, uncertainTarget = null, uncertainCheckedDestination = null)
        commandJob = viewModelScope.launch {
            var requestStarted = false
            try {
                pendingCommands.write(target)
                requestStarted = true
                val outcome = gateway.sendToScooter(target)
                pendingCommands.clear()
                val latest = mutableState.value
                if (requestGeneration == commandGeneration && latest.configuration.accountFingerprint() == target.configuration.accountFingerprint()) {
                    val requestIsCurrent = latest.currentIncomingRequestId == incomingRequestId
                    mutableState.value = latest.copy(
                        lastCommandOutcome = outcome.takeIf { requestIsCurrent },
                        lastCommandIncomingRequestId = incomingRequestId.takeIf { requestIsCurrent }
                    )
                    refreshDestination(target.configuration)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                val uncertain = requestStarted && (error is java.io.IOException || error is org.openapitools.client.infrastructure.ServerException)
                val latest = mutableState.value
                if (requestGeneration == commandGeneration && latest.configuration.accountFingerprint() == target.configuration.accountFingerprint()) {
                    val requestIsCurrent = latest.currentIncomingRequestId == incomingRequestId
                    mutableState.value = latest.copy(
                        error = if (requestIsCurrent) {
                            if (uncertain) "The server response was interrupted. Check this scooter's destination before retrying." else userMessage(error, target.configuration)
                        } else latest.error,
                        lastCommandOutcome = if (requestIsCurrent && uncertain) CommandOutcome.DeliveryUncertain(target) else null,
                        lastCommandIncomingRequestId = incomingRequestId.takeIf { requestIsCurrent },
                        uncertainTarget = if (requestStarted) target else null,
                        uncertainCheckedDestination = null
                    )
                }
            } finally {
                if (requestGeneration == commandGeneration) mutableState.value = mutableState.value.copy(sending = false)
            }
        }
    }

    fun send(place: PlaceResult?, expectedConfiguration: AppConfiguration = mutableState.value.configuration, expectedIncomingRequestId: Long? = null) {
        val config = mutableState.value.configuration
        if (config != expectedConfiguration || mutableState.value.currentIncomingRequestId != expectedIncomingRequestId || mutableState.value.incomingResolutionRequired) {
            mutableState.value = mutableState.value.copy(error = "Scooter or settings changed. Confirm the destination again.")
            return
        }
        val availableScooter = config.scooterId != null && mutableState.value.scooters.value.orEmpty().any { it.id == config.scooterId }
        if (!config.isReady || !availableScooter || mutableState.value.sending || mutableState.value.pendingCommandLoading || mutableState.value.pendingCommandForOtherAccount || (mutableState.value.uncertainTarget != null && mutableState.value.uncertainCheckedDestination == null)) {
            if (config.isReady && !availableScooter) mutableState.value = mutableState.value.copy(error = "The selected scooter is no longer available. Choose a scooter and confirm again.")
            return
        }
        if (place != null && !place.coordinates.isValid()) return
        dispatchCommand(CommandTarget(config, place), expectedIncomingRequestId)
    }
}
