package dev.ligustah.lsnav

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.openapitools.client.infrastructure.ClientException
import java.util.concurrent.TimeUnit

class RepositoryTest {
    private val config = AppConfiguration("test-token", "https://example.org", 1, "Test scooter")
    private fun provider(server: MockWebServer) = ApiClientProvider(config.baseUrl, config.token,
        OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build())

    @Test fun `scooter list uses bearer auth and accepts firmware states`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":1,"name":"Test","state":"parked"}]"""))
            val gateway = NavigationRepository { provider(server) }
            assertEquals("parked", gateway.scooters(config).single().state)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/scooters", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }
    @Test fun `saved locations are read for the selected scooter with user auth`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"locations":[{"slot":0,"latitude":52.52,"longitude":13.41,"label":"Home"},{"slot":1,"latitude":200,"longitude":13,"label":"Invalid"}]}"""))
            val places = NavigationRepository { provider(server) }.savedLocations(config.copy(scooterId = 42))
            assertEquals(listOf(PlaceResult(Coordinates(52.52, 13.41), "Home")), places)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/scooters/42/locations", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }
    @Test fun `scooters map physical metadata without guessing from nickname`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":7,"name":"Bluebird","vin":"VIN7","color":"red","color_id":"red-1","color_hex":"#cc0000","model":{"model_name":"unu scooter 2.0","engine_power_rating_w":4000,"speed_class_kmh":45,"construction_year":2024,"color":"red","full_name":"unu scooter 2.0, 4000W, 2024, red"},"license_plate":"AB-123","online":true,"last_seen_at":"2025-01-02T03:04:05Z","images":{"front":"https://sunshine.example/front.png","left":null,"right":"https://sunshine.example/right.png"}},{"id":8,"name":"No model metadata","model":null}]"""))
            val scooters = NavigationRepository { provider(server) }.scooters(config)
            val scooter = scooters.first()
            assertEquals("red", scooter.color)
            assertEquals("unu scooter 2.0, 4000W, 2024, red", scooter.model?.displayName)
            assertEquals(4000, scooter.model?.enginePowerRatingW)
            assertEquals(45, scooter.model?.speedClassKmh)
            assertEquals(2024, scooter.model?.constructionYear)
            assertEquals("red", scooter.model?.color)
            assertNull(scooters.last().model)
            assertEquals("AB-123", scooter.licensePlate)
            assertEquals("https://sunshine.example/front.png", scooter.images.front)
            assertEquals("2025-01-02T03:04:05Z", scooter.lastSeenAt)
        }
    }
    @Test fun `favorite mutations use user saved destination routes and invalidate cache`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"favorites":[],"recent":[]}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":12,"label":"Home","latitude":52.5,"longitude":13.4,"is_favorite":true,"search_key":"home"}"""))
            val gateway = NavigationRepository { provider(server) }
            gateway.personalPlaces(config)
            val place = gateway.createFavorite(config, FavoriteDraft("Home", PlaceResult(Coordinates(52.5, 13.4), "Home"), "home"))
            assertTrue(place.isFavorite)
            assertEquals("home", place.searchKey)
            val listRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            val createRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/saved_destinations", listRequest.path)
            assertEquals("POST", createRequest.method)
            assertTrue(createRequest.body.readUtf8().contains("\"search_key\":\"home\""))
            server.enqueue(MockResponse().setBody("""{"favorites":[{"id":12,"label":"Home","latitude":52.5,"longitude":13.4,"is_favorite":true}],"recent":[]}"""))
            assertEquals(1, gateway.personalPlaces(config).favorites.size)
            assertEquals("/api/v1/saved_destinations", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun `favorite update and delete use source owner routes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":12,"label":"Home","latitude":52.5,"longitude":13.4,"is_favorite":false}"""))
            server.enqueue(MockResponse().setBody("""{"status":"success"}"""))
            val gateway = NavigationRepository { provider(server) }
            gateway.setFavorite(config, 12, PlaceResult(Coordinates(52.5, 13.4), "Home"), false)
            gateway.deleteFavorite(config, 12)
            val update = server.takeRequest(5, TimeUnit.SECONDS)!!
            val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PATCH", update.method)
            assertEquals("/api/v1/saved_destinations/12", update.path)
            assertTrue(update.body.readUtf8().contains("\"is_favorite\":false"))
            assertEquals("DELETE", delete.method)
            assertEquals("/api/v1/saved_destinations/12", delete.path)
        }
    }

    @Test fun `scooter list failures classify access and network errors without exposing response details`() {
        val oauth = config.copy(token = "", oauthSessionId = "session", grantedScopes = setOf("read"))
        val denied = ClientException("response payload token=hidden", 403)
        val deniedMessage = scooterFetchMessage(denied, oauth)
        assertTrue(deniedMessage.contains("HTTP 403"))
        assertFalse(deniedMessage.contains("response payload"))
        assertTrue(scooterFetchNeedsReconnect(denied, oauth))

        val networkMessage = scooterFetchMessage(java.io.IOException("https://example.org?token=hidden&lat=52.5"), config)
        assertTrue(networkMessage.contains("Couldn't reach Sunshine"))
        assertFalse(networkMessage.contains("hidden"))
        assertFalse(networkMessage.contains("52.5"))
        assertFalse(scooterFetchNeedsReconnect(java.io.IOException("offline"), config))
    }

    @Test fun `authorization failures expose a useful message`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"forbidden","message":"Location permission required"}}"""))
            try {
                NavigationRepository { provider(server) }.destination(config)
                fail("Forbidden response accepted")
            } catch (error: ClientException) {
                assertEquals(403, error.statusCode)
                assertTrue(userMessage(error).contains("permission"))
            }
        }
    }
    @Test fun `cancelled providers never dispatch a new command`() {
        MockWebServer().use { server ->
            val provider = provider(server)
            provider.cancel()
            assertThrows(java.io.IOException::class.java) { provider.getNavigationApi().clearDestination(1) }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `cancelling a repository request cancels its HTTP call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider = provider(server)
            val request = launch(Dispatchers.Default) { NavigationRepository { provider }.destination(config) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            request.cancelAndJoin()
            withTimeout(5_000) {
                while (provider.client.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(request.isCancelled)
        }
    }
}
