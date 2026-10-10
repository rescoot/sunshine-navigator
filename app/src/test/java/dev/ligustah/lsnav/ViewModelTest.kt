package dev.ligustah.lsnav

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val config = AppConfiguration("token", "https://example.org", 1, "Test scooter")
    private val place = PlaceResult(Coordinates(52.52, 13.41), "Berlin")
    private val resolver = CoordinateResolver(LocationLookup { listOf(place) })
    private class MemoryPending : PendingCommandPersistence {
        var pending: PendingCommand? = null
        override suspend fun read() = pending
        override suspend fun write(target: CommandTarget) {
            pending = PendingCommand(target.configuration.accountFingerprint(), requireNotNull(target.configuration.scooterId), target.configuration.scooterName, target.place)
        }
        override suspend fun clear() { pending = null }
    }
    private fun model(gateway: NavigationGateway, configurations: kotlinx.coroutines.flow.Flow<AppConfiguration> = MutableStateFlow(config), pending: MemoryPending = MemoryPending()) =
        NavigationViewModel(application, SavedStateHandle(), gateway, resolver, configurations, pending)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class FakeGateway : NavigationGateway {
        var writes = 0
        var clears = 0
        val readIds = mutableListOf<Long?>()
        val locationIds = mutableListOf<Long?>()
        val locations = mutableMapOf<Long, CompletableDeferred<List<PlaceResult>>>()
        var lastWrite: AppConfiguration? = null
        var writeError: Exception? = null
        val firstRead = CompletableDeferred<NavigationTarget>()
        val write = CompletableDeferred<Unit>()
        private var destinationReads = 0
        private var serverDestination = NavigationTarget()

        override suspend fun scooters(configuration: AppConfiguration, forceRefresh: Boolean): List<ScooterSummary> =
            listOf(ScooterSummary(1, "Test scooter"), ScooterSummary(2, "Other scooter"))

        override suspend fun destination(configuration: AppConfiguration): NavigationTarget {
            readIds.add(configuration.scooterId)
            destinationReads++
            return if (destinationReads == 1) firstRead.await() else serverDestination
        }

        override suspend fun savedLocations(configuration: AppConfiguration): List<PlaceResult> {
            locationIds.add(configuration.scooterId)
            return locations[configuration.scooterId]?.await() ?: emptyList()
        }

        override suspend fun setDestination(configuration: AppConfiguration, place: PlaceResult) {
            writes++
            lastWrite = configuration
            write.await()
            writeError?.let { throw it }
            serverDestination = NavigationTarget(place)
        }

        override suspend fun clearDestination(configuration: AppConfiguration) {
            clears++
            write.await()
            writeError?.let { throw it }
            serverDestination = NavigationTarget()
        }
    }

    @Test fun `destination and vehicle places gain labels without issuing commands`() = runTest(dispatcher) {
        val point = PlaceResult(Coordinates(52.5, 13.3), "52.5,13.3")
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget(point))
        gateway.locations[1L] = CompletableDeferred(listOf(point))
        val labels = CoordinateResolver(object : LocationLookup {
            override suspend fun search(query: String) = emptyList<PlaceResult>()
            override suspend fun reverse(coordinates: Coordinates) = "Messedamm 26, Berlin"
        })
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, labels, MutableStateFlow(config), MemoryPending())
        runCurrent()
        val expected = point.copy(label = "Messedamm 26, Berlin")
        assertEquals(expected, model.state.value.destination.value?.place)
        assertEquals(listOf(expected), model.state.value.savedLocations.value)
        assertEquals(0, gateway.writes)
        assertEquals(0, gateway.clears)
    }

    @Test fun `late reverse lookup cannot restore a destination after account switch`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget(PlaceResult(Coordinates(52.5, 13.3), "52.5,13.3")))
        val configurations = MutableStateFlow(config)
        val address = CompletableDeferred<String>()
        val labels = CoordinateResolver(object : LocationLookup {
            override suspend fun search(query: String) = emptyList<PlaceResult>()
            override suspend fun reverse(coordinates: Coordinates) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { address.await() }
        })
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, labels, configurations, MemoryPending())
        runCurrent()
        configurations.value = config.copy(token = "another-account")
        runCurrent()
        address.complete("Messedamm 26, Berlin")
        runCurrent()
        assertNull(model.state.value.destination.value?.place)
        assertEquals(0, gateway.writes)
    }

    @Test fun `navigation serializes writes and ignores cancelled initial reads`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = model(gateway)
        runCurrent()
        assertTrue(model.state.value.destination.loading)
        model.send(place)
        model.send(place)
        model.send(null)
        runCurrent()
        assertEquals(1, gateway.writes)
        assertEquals(0, gateway.clears)
        gateway.firstRead.complete(NavigationTarget(PlaceResult(Coordinates(1.0, 2.0), "Initial read")))
        gateway.write.complete(Unit)
        runCurrent()
        assertEquals(place, model.state.value.destination.value?.place)
        assertFalse(model.state.value.sending)
    }

    @Test fun `share resolves without issuing a command and confirms only once`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = ShareViewModel(application, SavedStateHandle(), gateway, resolver, MutableStateFlow(config))
        model.start("Berlin")
        runCurrent()
        assertEquals(0, gateway.writes)
        assertEquals(listOf(place), model.state.value.places.value)
        model.confirm()
        model.confirm()
        runCurrent()
        assertEquals(1, gateway.writes)
        gateway.write.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.sent)
        model.confirm()
        runCurrent()
        assertEquals(1, gateway.writes)
    }

    @Test fun `share refuses a command after settings change`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val configurations = MutableStateFlow(config)
        val model = ShareViewModel(application, SavedStateHandle(), gateway, resolver, configurations)
        model.start("Berlin")
        runCurrent()
        configurations.value = config.copy(scooterId = 2)
        model.confirm()
        runCurrent()
        assertEquals(0, gateway.writes)
        assertNotNull(model.state.value.error)
    }

    @Test fun `manual correction of an ambiguous share keeps current external results selectable`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val ambiguous = PlaceResult(Coordinates(48.85, 2.35), "Berlin Avenue")
        val corrected = PlaceResult(Coordinates(40.71, -74.0), "New York")
        val correctionResolver = CoordinateResolver(LocationLookup { query ->
            when (query) {
                "Ambiguous destination" -> listOf(place, ambiguous)
                "Corrected destination" -> listOf(corrected)
                else -> emptyList()
            }
        })
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, correctionResolver, MutableStateFlow(config), MemoryPending())
        runCurrent()
        model.beginIncomingRequest(17L, requireResolution = true)
        model.resolveIncoming("Ambiguous destination", 17L)
        runCurrent()
        assertEquals(listOf(place, ambiguous), model.state.value.results)
        assertFalse(model.state.value.incomingResolutionRequired)

        model.updateQuery("Corrected destination", correctingIncomingRequestId = 17L)
        assertEquals(17L, model.state.value.currentIncomingRequestId)
        assertFalse(model.state.value.incomingResolutionRequired)
        assertTrue(model.state.value.results.isEmpty())
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf(corrected), model.state.value.results)
        assertEquals(17L, model.state.value.resolvedIncomingRequestId)
        assertFalse(model.state.value.incomingResolutionRequired)
        assertEquals(0, gateway.writes)
    }

    @Test fun `share chooses a scooter on demand without saving a default`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val configurations = MutableStateFlow(config.copy(scooterId = null, scooterName = ""))
        val model = ShareViewModel(application, SavedStateHandle(), gateway, resolver, configurations)
        model.start("geo:52.52,13.41")
        runCurrent()
        assertEquals(1, model.state.value.places.value?.size)
        assertNull(model.state.value.configuration.scooterId)
        model.confirm()
        runCurrent()
        assertEquals(0, gateway.writes)
        model.selectScooter(model.state.value.scooters.value!!.last())
        model.confirm()
        runCurrent()
        assertEquals(2L, gateway.lastWrite?.scooterId)
        assertNull(configurations.value.scooterId)
        gateway.write.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.sent)
    }

    @Test fun `navigation loads saved locations only for the selected scooter and ignores cancelled reads`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget())
        val oldLocations = CompletableDeferred<List<PlaceResult>>()
        val otherPlace = PlaceResult(Coordinates(48.85, 2.35), "Paris")
        gateway.locations[1] = oldLocations
        gateway.locations[2] = CompletableDeferred(listOf(otherPlace))
        val configurations = MutableStateFlow(config.copy(scooterId = null, scooterName = ""))
        val model = model(gateway, configurations)
        runCurrent()
        assertTrue(gateway.readIds.isEmpty())
        model.selectScooter(model.state.value.scooters.value!!.first())
        runCurrent()
        assertEquals(listOf(1L), gateway.locationIds)
        model.selectScooter(model.state.value.scooters.value!!.last())
        runCurrent()
        assertEquals(listOf(otherPlace), model.state.value.savedLocations.value)
        oldLocations.complete(listOf(place))
        runCurrent()
        assertEquals(listOf(otherPlace), model.state.value.savedLocations.value)
        assertNull(configurations.value.scooterId)
    }

    @Test fun `tap action binds destination and scooter in immutable target`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget())
        val onlineScooter = ScooterSummary(1, "Test scooter", online = true)
        val source = object : NavigationGateway by gateway {
            override suspend fun scooters(configuration: AppConfiguration, forceRefresh: Boolean) = listOf(onlineScooter)
            override suspend fun setDestination(configuration: AppConfiguration, place: PlaceResult) {
                gateway.writes++
                gateway.lastWrite = configuration
                gateway.write.complete(Unit)
            }
        }
        val model = model(source)
        runCurrent()
        val tappedTarget = CommandTarget(config, place)
        model.sendToScooter(tappedTarget)
        gateway.write.complete(Unit)
        runCurrent()
        assertEquals(1, gateway.writes)
        assertEquals(1L, gateway.lastWrite?.scooterId)
        assertEquals(place, tappedTarget.place)
        assertEquals(CommandOutcome.ServerAccepted(tappedTarget), model.state.value.lastCommandOutcome)
        assertFalse(model.state.value.sending)
    }

    @Test fun `browsing another scooter does not change the saved default`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget())
        val configurations = MutableStateFlow(config)
        val model = model(gateway, configurations)
        runCurrent()
        assertEquals(1L, model.state.value.defaultScooterId)
        model.selectScooter(model.state.value.scooters.value!!.last())
        runCurrent()
        assertEquals(2L, model.state.value.configuration.scooterId)
        assertEquals(1L, model.state.value.defaultScooterId)
        assertEquals(1L, configurations.value.scooterId)
    }

    @Test fun `incoming share result is tied to the latest resolved input`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = model(gateway)
        runCurrent()
        model.resolveIncoming("Berlin")
        runCurrent()
        assertEquals("Berlin", model.state.value.resolvedIncomingText)
        model.resolveIncoming("another place")
        assertNull(model.state.value.resolvedIncomingText)
    }

    @Test fun `uncertain command survives a newer share and clears only after destination check`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget())
        val pending = MemoryPending()
        val online = ScooterSummary(1, "Test scooter", online = true)
        val source = object : NavigationGateway by gateway {
            override suspend fun scooters(configuration: AppConfiguration, forceRefresh: Boolean) = listOf(online)
        }
        val model = model(source, pending = pending)
        runCurrent()
        gateway.writeError = java.io.IOException("response lost")
        model.sendToScooter(CommandTarget(config, place))
        runCurrent()
        gateway.write.complete(Unit)
        runCurrent()
        assertEquals(CommandTarget(config, place), model.state.value.uncertainTarget)
        assertNotNull(pending.pending)
        model.resolveIncoming("Berlin")
        runCurrent()
        assertEquals(CommandTarget(config, place), model.state.value.uncertainTarget)
        gateway.writeError = null
        model.checkUncertainCommand()
        runCurrent()
        assertNull(pending.pending)
        assertNotNull(model.state.value.uncertainCheckedDestination)
        assertEquals(CommandTarget(config, place), model.state.value.uncertainTarget)
    }

    @Test fun `pending command is restored only for its matching account`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget())
        val pending = MemoryPending().apply {
            pending = PendingCommand(config.accountFingerprint(), 1L, "Test scooter", place)
        }
        val model = model(gateway, pending = pending)
        runCurrent()
        assertEquals(CommandTarget(config, place), model.state.value.uncertainTarget)
        assertNotNull(model.state.value.lastCommandOutcome)
    }

    @Test fun `pending command from another credential blocks retargeting`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        gateway.firstRead.complete(NavigationTarget())
        val pending = MemoryPending().apply {
            pending = PendingCommand(config.accountFingerprint(), 1L, "Test scooter", place)
        }
        val changed = config.copy(token = "different-token")
        val model = model(gateway, MutableStateFlow(changed), pending)
        runCurrent()
        assertTrue(model.state.value.pendingCommandForOtherAccount)
        assertNull(model.state.value.uncertainTarget)
        model.sendToScooter(CommandTarget(changed, place))
        runCurrent()
        assertEquals(0, gateway.writes)
    }

    @Test fun `navigation rejects a confirmation for a different scooter`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = model(gateway)
        runCurrent()
        model.selectScooter(model.state.value.scooters.value!!.last())
        model.send(place, config)
        runCurrent()
        assertEquals(0, gateway.writes)
        assertNotNull(model.state.value.error)
    }

    @Test fun `completed share survives activity recreation`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = ShareViewModel(application, SavedStateHandle(mapOf("sent" to true)), gateway, resolver, MutableStateFlow(config))
        model.start("Berlin")
        model.confirm()
        runCurrent()
        assertTrue(model.state.value.sent)
        assertEquals(0, gateway.writes)
    }
}
