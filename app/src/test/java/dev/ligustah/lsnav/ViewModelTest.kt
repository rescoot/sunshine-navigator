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

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class FakeGateway : NavigationGateway {
        var writes = 0
        var clears = 0
        val readIds = mutableListOf<Long?>()
        val locationIds = mutableListOf<Long?>()
        val locations = mutableMapOf<Long, CompletableDeferred<List<PlaceResult>>>()
        var lastWrite: AppConfiguration? = null
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
            serverDestination = NavigationTarget(place)
        }

        override suspend fun clearDestination(configuration: AppConfiguration) {
            clears++
            write.await()
            serverDestination = NavigationTarget()
        }
    }

    @Test fun `navigation serializes writes and ignores cancelled initial reads`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, resolver, MutableStateFlow(config))
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
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, resolver, configurations)
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
        val model = NavigationViewModel(application, SavedStateHandle(), source, resolver, MutableStateFlow(config))
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

    @Test fun `incoming share result is tied to the latest resolved input`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, resolver, MutableStateFlow(config))
        runCurrent()
        model.resolveIncoming("Berlin")
        runCurrent()
        assertEquals("Berlin", model.state.value.resolvedIncomingText)
        model.resolveIncoming("another place")
        assertNull(model.state.value.resolvedIncomingText)
    }

    @Test fun `navigation rejects a confirmation for a different scooter`() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val model = NavigationViewModel(application, SavedStateHandle(), gateway, resolver, MutableStateFlow(config))
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
