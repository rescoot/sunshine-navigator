package dev.ligustah.lsnav

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsIntentParserTest {

    @Test
    fun `viewport coordinates are not a destination`() {
        val uri = Uri.parse("https://www.google.com/maps/place/Berlin/@52.520008,13.404954,17z/data=...")
        assertNull(MapsIntentParser.extractCoordinates(uri))
    }

    @Test
    fun `extractCoordinates extracts from search query parameter`() {
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=52.520,13.404")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(52.520, coords?.latitude)
        assertEquals(13.404, coords?.longitude)
    }

    @Test
    fun `extractCoordinates extracts from q parameter`() {
        val uri = Uri.parse("http://maps.google.com/maps?q=52.5,13.4")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(52.5, coords?.latitude)
        assertEquals(13.4, coords?.longitude)
    }

    @Test
    fun `extractCoordinates prefers q destination over map viewport`() {
        val uri = Uri.parse("https://www.google.com/maps/@52.52,13.41,17z?q=48.137,11.576")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(48.137, coords?.latitude)
        assertEquals(11.576, coords?.longitude)
    }

    @Test
    fun `extractCoordinates rejects out of range query coordinates`() {
        val uri = Uri.parse("https://www.google.com/maps/search/?query=91,181")
        assertNull(MapsIntentParser.extractCoordinates(uri))
    }

    @Test
    fun `extractCoordinates rejects out of range q coordinates`() {
        val uri = Uri.parse("https://www.google.com/maps?q=91,181")
        assertNull(MapsIntentParser.extractCoordinates(uri))
    }

    @Test
    fun `extractCoordinates returns null for invalid coordinates in query`() {
        val uri = Uri.parse("http://maps.google.com/maps?q=berlin,germany")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertNull(coords)
    }

    @Test
    fun `extractCoordinates returns null for shortlinks without unrolling`() {
        // Unrolling is done by CoordinateResolver, the parser just returns null
        val uri = Uri.parse("https://maps.app.goo.gl/shortlink123")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertNull(coords)
    }

    @Test
    fun `extractUrlOrText extracts url from string`() {
        val text = "Check out this place \n https://maps.app.goo.gl/12345"
        val output = MapsIntentParser.extractUrlOrText(text)
        assertEquals("https://maps.app.goo.gl/12345", output)
    }

    @Test
    fun `extractUrlOrText extracts url with surrounding whitespace`() {
        val text = "  https://google.com/maps/place/Berlin  "
        val output = MapsIntentParser.extractUrlOrText(text)
        assertEquals("https://google.com/maps/place/Berlin", output)
    }

    @Test
    fun `extractUrlOrText returns plain text if no url`() {
        val text = "Alexanderplatz, Berlin"
        val output = MapsIntentParser.extractUrlOrText(text)
        assertEquals("Alexanderplatz, Berlin", output)
    }

    @Test
    fun `extractCoordinates extracts from basic data parameter route`() {
        // Last route coordinate is Lat 53.5768647, Lon -2.4282192
        val uri = Uri.parse("https://www.google.co.uk/maps/dir/53.3544359,-2.1083514/.../@53.5188983,-2.3993109,12z/data=!4m16!4m15!1m1!4e1!1m5!1m1!1s0x487bb1c18d758c47:0xefab6c7fe0032f62!2m2!1d-2.2445756!2d53.4792335!1m5!1m1!1s0x487b0629dc3b1c93:0xcaa40cfafe557822!2m2!1d-2.4282192!2d53.5768647!3e0?hl=en")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(53.5768647, coords?.latitude)
        assertEquals(-2.4282192, coords?.longitude)
    }

    @Test
    fun `extractCoordinates extracts from multi-waypoint data parameter route`() {
        // Last coordinate in block is Lat 40.7127837, Lon -74.0059413
        val uri = Uri.parse("https://www.google.co.uk/maps/dir/Seattle,+WA.../@39.04.../data=!3m1!4b1!4m49!...2m2!1d-122.3320708!2d47.6062095!...2m2!1d-74.0059413!2d40.7127837!3e1!4e1")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(40.7127837, coords?.latitude)
        assertEquals(-74.0059413, coords?.longitude)
    }

    @Test
    fun `extractCoordinates extracts from single pin data parameter`() {
        // 3d/4d map coordinate pair (Lat 59.6099005, Lon 16.5448091)
        val uri = Uri.parse("https://www.google.co.uk/maps/place/V%C3%A4ster.../data=!4m5!3m4!1s0x465e4281455ba9a7...!8m2!3d59.6099005!4d16.5448091")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(59.6099005, coords?.latitude)
        assertEquals(16.5448091, coords?.longitude)
    }

    @Test
    fun `explicit place coordinates outrank viewport`() {
        val uri = Uri.parse("https://www.google.com/maps/place/48.137,11.576/@52.52,13.41,17z")
        assertEquals(48.137, MapsIntentParser.extractCoordinates(uri)?.latitude)
    }

    @Test
    fun `geo URI supports coordinates and labelled query`() {
        assertEquals(Coordinates(52.52, 13.41), MapsIntentParser.extractCoordinates(Uri.parse("geo:52.52,13.41")))
        assertEquals(Coordinates(48.137, 11.576), MapsIntentParser.extractCoordinates(Uri.parse("geo:0,0?q=48.137,11.576(Munich)")))
        assertNull(MapsIntentParser.extractCoordinates(Uri.parse("geo:0,0?q=Alexanderplatz")))
        assertEquals("Alexanderplatz", MapsIntentParser.extractPlaceName(Uri.parse("geo:0,0?q=Alexanderplatz")))
    }

    @Test
    fun `shared place preview is readable and keeps explicit coordinates`() {
        val preview = parseSharedPlacePreview("geo:52.500218,13.270753?q=CityCube%20Berlin%2C%20Messedamm%2026")
        assertEquals("CityCube Berlin, Messedamm 26", preview.label)
        assertEquals(Coordinates(52.500218, 13.270753), preview.coordinates)
    }

    @Test
    fun `geo coordinates keep explicit destination beside a decoded address query`() {
        val uri = Uri.parse("geo:52.500218,13.270753?q=CityCube%20Berlin%2C%20Messedamm%2026%2C%2014055%20Berlin-Bezirk%20Charlottenburg-Wilmersdorf%2C%20Germany")
        assertEquals(Coordinates(52.500218, 13.270753), MapsIntentParser.extractCoordinates(uri))
        assertEquals("CityCube Berlin, Messedamm 26, 14055 Berlin-Bezirk Charlottenburg-Wilmersdorf, Germany", MapsIntentParser.extractPlaceName(uri))
    }

    @Test
    fun `geo altitude and encoded address query are supported`() {
        assertEquals(Coordinates(52.52, 13.41), MapsIntentParser.extractCoordinates(Uri.parse("geo:52.52,13.41,100")))
        assertEquals("C++", MapsIntentParser.extractPlaceName(Uri.parse("geo:0,0?q=C%2B%2B")))
    }

    @Test
    fun `geo query coordinates preserve a label and placeholders are not destinations`() {
        val labelledCoordinates = Uri.parse("geo:0,0?q=48.137,11.576(Munich)")
        assertEquals(Coordinates(48.137, 11.576), MapsIntentParser.extractCoordinates(labelledCoordinates))
        assertEquals("Munich", MapsIntentParser.extractPlaceName(labelledCoordinates))
        assertNull(MapsIntentParser.extractCoordinates(Uri.parse("geo:0,0?q=Alexanderplatz")))
        assertNull(MapsIntentParser.extractCoordinates(Uri.parse("geo:91,181")))
        assertNull(MapsIntentParser.extractCoordinates(Uri.parse("geo:0,0?q=91,181")))
        assertNull(MapsIntentParser.extractPlaceName(Uri.parse("geo:0,0?q=91,181")))
    }

    @Test
    fun `route path uses destination rather than origin`() {
        assertEquals(Coordinates(48.137, 11.576), MapsIntentParser.extractCoordinates(Uri.parse("https://www.google.com/maps/dir/52.52,13.41/48.137,11.576/@50,12,8z")))
        assertEquals("Munich Germany", MapsIntentParser.extractPlaceName(Uri.parse("https://www.google.com/maps/dir/Berlin/Munich+Germany/@50,12,8z")))
    }

    @Test
    fun `place names are decoded once`() {
        assertEquals("100% Coffee", MapsIntentParser.extractPlaceName(Uri.parse("https://www.google.com/maps/place/100%25+Coffee")))
        assertEquals("C++", MapsIntentParser.extractPlaceName(Uri.parse("https://www.google.com/maps?q=C%2B%2B")))
    }

    @Test
    fun `Google direction destination query is supported`() {
        assertEquals(Coordinates(48.137, 11.576), MapsIntentParser.extractCoordinates(Uri.parse("https://www.google.com/maps/dir/?api=1&destination=48.137,11.576")))
    }

    @Test
    fun `parser bounds untrusted input and rejects nonfinite coordinates`() {
        assertNull(MapsIntentParser.extractCoordinates(Uri.parse("https://www.google.com/maps/data=" + "!1m999999".repeat(3000))))
        assertNull(MapsIntentParser.parsePair("NaN,13"))
        assertNull(MapsIntentParser.parsePair("Infinity,13"))
    }

    @Test
    fun `extractCoordinates prefers data parameter over path segments`() {
        // Path contains 51.5074,0.1278 (Origin)
        // Data contains 52.3640414,13.508157299999999 (Destination)
        val uri = Uri.parse("https://www.google.com/maps/dir/51.5074,0.1278/Terminal+1/data=!4m12!4m11!1m1!4e1!1m5!1m4!1s0x47a846d6bff24f93:0x76502352a2b8b5cd!8m2!3d52.3640414!4d13.508157299999999!2m1!11b1!3e0")
        val coords = MapsIntentParser.extractCoordinates(uri)
        assertEquals(52.3640414, coords?.latitude)
        assertEquals(13.508157299999999, coords?.longitude)
    }
}
