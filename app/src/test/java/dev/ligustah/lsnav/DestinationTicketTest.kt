package dev.ligustah.lsnav

import org.junit.Assert.*
import org.junit.Test

class DestinationTicketTest {
    @Test fun displayLabelSplitPreservesCommandLabel() {
        val place = PlaceResult(Coordinates(52.0, 13.0), "CityCube Berlin, Messedamm 26, Berlin")
        assertEquals("CityCube Berlin", place.displayTitle())
        assertEquals("Messedamm 26, Berlin", place.displayDetail())
        assertEquals("CityCube Berlin, Messedamm 26, Berlin", place.label)
        assertEquals("52.00000, 13.00000", place.copy(label = "Home").displayDetail())
    }

    @Test fun tilesCenterExactCoordinateAndWrapLongitude() {
        listOf(Coordinates(52.5145, 13.3501), Coordinates(0.0, 180.0), Coordinates(90.0, -180.0)).forEach { coordinate ->
            val tiles = mapTiles(coordinate)
            assertEquals(6, tiles.size)
            assertTrue(tiles.all { it.x in 0..65535 && it.y in 0..65535 })
            assertTrue(tiles.any { it.offsetX <= 0 && it.offsetX + 256 >= 0 && it.offsetY <= 0 && it.offsetY + 256 >= 0 })
        }
    }
}
