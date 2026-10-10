package dev.ligustah.lsnav

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateResolverTest {
    @Test fun `plain address reaches the lookup unchanged`() = runBlocking {
        var actual: String? = null
        val result = PlaceResult(Coordinates(52.52, 13.41), "Berlin")
        val resolver = CoordinateResolver(LocationLookup { actual = it; listOf(result) })
        assertEquals(listOf(result), resolver.resolve("Alexanderplatz, Berlin"))
        assertEquals("Alexanderplatz, Berlin", actual)
    }
    @Test fun `exact coordinates and geo links do not invoke geocoding`() = runBlocking {
        val resolver = CoordinateResolver(LocationLookup { error("Unexpected geocoding") })
        assertEquals(Coordinates(52.52, 13.41), resolver.resolve("52.52,13.41").single().coordinates)
        assertEquals(Coordinates(52.52, 13.41), resolver.resolve("geo:52.52,13.41").single().coordinates)
    }
    @Test fun `explicit geo coordinates and decoded place label skip lookup`() = runBlocking {
        val resolver = CoordinateResolver(LocationLookup { error("Unexpected geocoding") })
        val place = resolver.resolve("geo:52.500218,13.270753?q=CityCube%20Berlin%2C%20Messedamm%2026%2C%2014055%20Berlin-Bezirk%20Charlottenburg-Wilmersdorf%2C%20Germany").single()
        assertEquals(Coordinates(52.500218, 13.270753), place.coordinates)
        assertEquals("CityCube Berlin, Messedamm 26, 14055 Berlin-Bezirk Charlottenburg-Wilmersdorf, Germany", place.label)
    }
    @Test fun `geo placeholder resolves only a textual query and never the sentinel`() = runBlocking {
        var lookupQuery: String? = null
        val result = PlaceResult(Coordinates(52.52, 13.41), "Alexanderplatz, Berlin")
        val resolver = CoordinateResolver(LocationLookup { lookupQuery = it; listOf(result) })
        assertEquals(listOf(result), resolver.resolve("geo:0,0?q=Alexanderplatz%2C%20Berlin"))
        assertEquals("Alexanderplatz, Berlin", lookupQuery)
        assertEquals(emptyList<PlaceResult>(), CoordinateResolver(LocationLookup { error("Invalid coordinates must not be geocoded") })
            .resolve("geo:0,0?q=91,181"))
    }
    @Test fun `query-only shared address reaches lookup unchanged`() = runBlocking {
        var actual: String? = null
        val result = PlaceResult(Coordinates(52.52, 13.41), "CityCube Berlin")
        assertEquals(listOf(result), CoordinateResolver(LocationLookup { actual = it; listOf(result) })
            .resolve("CityCube Berlin, Messedamm 26"))
        assertEquals("CityCube Berlin, Messedamm 26", actual)
    }
    @Test fun `short link follows a trusted redirect and closes response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://www.google.com/maps/place/48.137,11.576"))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/short")).build())
            }.build()
            val resolver = CoordinateResolver(LocationLookup { error("Unexpected geocoding") }, client)
            assertEquals(Coordinates(48.137, 11.576), resolver.resolve("https://maps.app.goo.gl/test").single().coordinates)
            assertEquals("GET", server.takeRequest().method)
        }
    }
    @Test fun `short links cannot redirect to arbitrary sites`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.org/private"))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/short")).build())
            }.build()
            try {
                CoordinateResolver(LocationLookup { emptyList() }, client).resolve("https://maps.app.goo.gl/test")
                fail("Unsupported redirect accepted")
            } catch (_: java.io.IOException) { }
        }
    }
    @Test fun `shortlink substrings do not authorize arbitrary network requests`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
        assertTrue(CoordinateResolver(LocationLookup { emptyList() }, client)
            .resolve("https://example.org/maps.app.goo.gl/test").isEmpty())
    }
}
