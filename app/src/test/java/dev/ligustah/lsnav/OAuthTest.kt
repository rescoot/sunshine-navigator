package dev.ligustah.lsnav

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OAuthTest {
    @Test fun `public OAuth uses authorization code with S256 and consumer scopes`() {
        val request = OAuthManager.authorizationRequest("https://sunshine.rescoot.org/api/v1", "public-client")
        val uri = request.toUri()
        assertEquals("https://sunshine.rescoot.org/oauth/authorize", uri.buildUpon().clearQuery().build().toString())
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals("read write scooter_control", uri.getQueryParameter("scope"))
        assertEquals(OAuthManager.REDIRECT_URI, uri.getQueryParameter("redirect_uri"))
        assertNotNull(request.codeVerifier)
        assertFalse(uri.getQueryParameter("code_challenge").isNullOrBlank())
        assertNull(uri.getQueryParameter("client_secret"))
    }
    @Test fun `granted scope parsing is conservative and enables explicit upgrade`() {
        assertEquals(setOf("read", "scooter_control"), OAuthManager.grantedScopes("read scooter_control"))
        val oldGrant = AppConfiguration(oauthSessionId = "old", grantedScopes = setOf("read", "scooter_control"))
        assertTrue(oldGrant.needsPermissionUpgrade)
        assertEquals(setOf("write"), oldGrant.missingOAuthScopes)
        assertTrue(AppConfiguration(oauthSessionId = "new", grantedScopes = AppConfiguration.REQUIRED_OAUTH_SCOPES).canManageFavorites)
    }

    @Test fun `OAuth state and verifier are unique per attempt`() {
        val first = OAuthManager.authorizationRequest("https://example.org", "client")
        val second = OAuthManager.authorizationRequest("https://example.org", "client")
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
    }
    @Test fun `manual token and OAuth configurations both support scooter selection`() {
        assertTrue(AppConfiguration(token = "manual", scooterId = 1).isReady)
        assertTrue(AppConfiguration(oauthSessionId = "session", scooterId = 1).isReady)
        assertFalse(AppConfiguration(scooterId = 1).isReady)
        assertFalse(AppConfiguration(oauthSessionId = "session").isReady)
    }
    @Test fun `OAuth refuses insecure endpoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            OAuthManager.authorizationRequest("http://example.org", "client")
        }
    }
}
