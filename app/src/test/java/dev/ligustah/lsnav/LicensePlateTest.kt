package dev.ligustah.lsnav

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LicensePlateTest {
    private val today = LocalDate.of(2026, 10, 10)
    private fun insurance(date: String?, text: String = "176 jlg") = ScooterSummary(1, "Scooter", licensePlate = text, licensePlateType = "versicherungskennzeichen", licensePlateSetAt = date).plateLayout(today)!!

    @Test fun insuranceUsesTwoLinesAndTheRecordedSeasonNotTheCurrentYear() {
        val plate = insurance("2025-03-01T00:00:00+01:00")
        assertEquals("176", plate.top)
        assertEquals("JLG", plate.bottom)
        assertEquals(Color(0xFF1A6E1A), plate.ink)
        assertTrue(plate.expired)
        assertEquals(Color(0xFF1A3A8A), insurance("2024-03-01T00:00:00+01:00").ink)
        assertEquals(Color(0xFF1A1A1A), insurance(null).ink)
        assertFalse(insurance(null).expired)
    }

    @Test fun seasonRolloverUsesBerlinTime() {
        val plate = insurance("2026-02-28T23:30:00Z")
        assertEquals(Color(0xFF1A1A1A), plate.ink)
        assertFalse(plate.expired)
        assertEquals(2025, plateSeason(LocalDate.of(2026, 2, 28)))
        assertEquals(2026, plateSeason(LocalDate.of(2026, 3, 1)))
    }

    @Test fun registeredPlateSeparatesDistrictRecognitionAndSuffix() {
        val plate = ScooterSummary(1, "Scooter", licensePlate = "B EM 4453 E", licensePlateType = "zulassungskennzeichen").plateLayout(today)!!
        assertEquals(PlateKind.Registration, plate.kind)
        assertEquals("B", plate.top)
        assertEquals("EM 4453", plate.bottom)
        assertEquals("E", plate.suffix)
        assertFalse(plate.expired)
    }

    @Test fun unknownOrMalformedFormatsAreNotReinterpreted() {
        assertNull(ScooterSummary(1, "Scooter", licensePlate = " ").plateLayout(today))
        val unknown = ScooterSummary(1, "Scooter", licensePlate = "NL 1-ABC").plateLayout(today)!!
        assertEquals(PlateKind.Other, unknown.kind)
        assertEquals("NL 1-ABC", unknown.text)
        assertEquals(PlateKind.Other, insurance(null, "CUSTOM 1234").kind)
    }
}
