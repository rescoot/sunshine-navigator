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
class AccountApiTest {
    private val config = AppConfiguration("test-token", "https://example.org", 7, "Scooter")
    private fun repository(server: MockWebServer) = NavigationRepository { configuration ->
        ApiClientProvider(configuration.baseUrl, configuration.token, OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build())
    }

    @Test fun accountLoadsIdentityAndAvatarWithUserAuth() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":42,"name":"Alex","email":"alex@example.org","avatar_url":"https://example.org/avatar.png"}"""))
            val result = repository(server).account(config)
            assertEquals(AccountIdentity(42, "Alex", "alex@example.org", "https://example.org/avatar.png"), result)
            val request = server.takeRequest()
            assertEquals("/api/v1/account", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test fun commandKeyIsSentAndReceiptLookupDoesNotResend() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"success"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"accepted"}"""))
            val repository = repository(server)
            val target = CommandTarget(config, PlaceResult(Coordinates(52.5, 13.4), "Home"), "request-12345678")
            assertEquals(CommandOutcome.ServerAccepted(target), repository.sendToScooter(target))
            val send = server.takeRequest()
            assertEquals("PUT", send.method)
            assertEquals("request-12345678", send.getHeader("Idempotency-Key"))
            assertEquals("accepted", repository.navigationReceipt(target))
            val check = server.takeRequest()
            assertEquals("GET", check.method)
            assertEquals("/api/v1/scooters/7/navigation_receipts/request-12345678", check.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun processingResponseIsNotReportedAsAccepted() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"processing"}"""))
            try {
                repository(server).sendToScooter(CommandTarget(config, null, "request-12345678"))
                fail("Processing is not success")
            } catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun serverPermissionsControlEligibility() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":7,"name":"Read only","online":true,"capabilities":{"navigate":false}}]"""))
            assertFalse(repository(server).scooters(config).single().availableForNavigation)
        }
    }

    @Test fun revocationUsesFormBodyAndDoesNotFollowRedirects() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://unrelated.example/"))
            assertFalse(revokeOAuthTokens(server.url("/").toString().trimEnd('/'), "client", listOf("secret")))
            val request = server.takeRequest()
            assertEquals("/oauth/revoke", request.path)
            assertEquals("POST", request.method)
            assertEquals("client_id=client&token=secret", request.body.readUtf8())
            assertNull(request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }
}
