package dev.ligustah.lsnav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DestinationTicketTest {
    @Test fun displayLabelSplitPreservesCommandLabel() {
        val place = PlaceResult(Coordinates(52.0, 13.0), "CityCube Berlin, Messedamm 26, Berlin")
        assertEquals("CityCube Berlin", place.displayTitle())
        assertEquals("Messedamm 26, Berlin", place.displayDetail())
        assertEquals("CityCube Berlin, Messedamm 26, Berlin", place.label)
        assertEquals("52.00000, 13.00000", place.copy(label = "Home").displayDetail())
    }

    @Test fun tileResolutionTracksDisplayDensityWithoutUpscaling() {
        for (density in listOf(1.0, 1.5, 2.0, 2.625, 3.0, 4.0)) {
            val tiles = mapTiles(Coordinates(52.5145, 13.3501), 371.0, 140.0, density)
            assertEquals(16 + ceil(log2(density)).toInt(), tiles.first().zoom)
            assertTrue(tiles.all { it.right - it.left <= 256 && it.bottom - it.top <= 256 })
            assertTrue(tiles.all { it.right > 0 && it.left < 371 * density && it.bottom > 0 && it.top < 140 * density })
            assertTrue(tiles.any { it.left <= 0 })
            assertTrue(tiles.any { it.right >= (371 * density).roundToInt() })
            assertTrue(tiles.any { it.top <= 0 })
            assertTrue(tiles.any { it.bottom >= (140 * density).roundToInt() })
            tiles.groupBy { it.y }.values.forEach { row -> row.sortedBy { it.left }.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left) } }
            tiles.groupBy { it.x }.values.forEach { column -> column.sortedBy { it.top }.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top) } }
        }
    }

    @Test fun projectedDestinationRemainsAtTheExactViewportCentre() {
        val point = Coordinates(52.5145, 13.3501)
        for (density in listOf(1.0, 2.625, 4.0)) {
            val tiles = mapTiles(point, 371.0, 140.0, density)
            val zoom = tiles.first().zoom
            val n = (1 shl zoom).toDouble()
            val x = (point.longitude + 180) / 360 * n
            val latitude = point.latitude * PI / 180
            val y = (1 - ln(tan(latitude) + 1 / cos(latitude)) / PI) / 2 * n
            val tile = tiles.single { it.x == floor(x).toInt() && it.y == floor(y).toInt() }
            val pixels = 256 * density / (1 shl (zoom - 16))
            assertEquals(371 * density / 2, tile.left + (x - tile.x) * pixels, .51)
            assertEquals(140 * density / 2, tile.top + (y - tile.y) * pixels, .51)
        }
    }

    @Test fun tilesWrapLongitudeAndStayInsideMercatorBounds() {
        listOf(Coordinates(0.0, 180.0), Coordinates(90.0, -180.0), Coordinates(-90.0, 0.0)).forEach { coordinate ->
            val tiles = mapTiles(coordinate, 600.0, 140.0, 3.0)
            assertTrue(tiles.isNotEmpty())
            assertTrue(tiles.all { it.x in 0 until (1 shl it.zoom) && it.y in 0 until (1 shl it.zoom) })
        }
        val wrapped = mapTiles(Coordinates(0.0, 180.0), 371.0, 140.0, 3.0)
        assertTrue(wrapped.any { it.x == 0 })
        assertTrue(wrapped.any { it.x == (1 shl it.zoom) - 1 })
        assertTrue(mapTiles(Coordinates(0.0, 0.0), 0.0, 140.0, 3.0).isEmpty())
    }
}
