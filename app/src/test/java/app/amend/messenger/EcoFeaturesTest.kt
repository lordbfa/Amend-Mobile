package app.amend.messenger

import app.amend.messenger.core.nostr.Event
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.data.EcoAlerts
import app.amend.messenger.data.EcoCategory
import app.amend.messenger.data.EcoReports
import app.amend.messenger.data.Geohash
import app.amend.messenger.data.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoFeaturesTest {
    @Test
    fun geohashMatchesKnownValues() {
        // reference value from the geohash spec examples
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
        val (lat, lon) = Geohash.decode("u4pruydqqvj")!!
        assertEquals(57.64911, lat, 1e-4)
        assertEquals(10.40744, lon, 1e-4)
        val around = Geohash.around(23.81, 90.41, 4) // Dhaka
        assertEquals(9, around.size)
        assertTrue(Geohash.encode(23.81, 90.41, 4) in around)
    }

    @Test
    fun reportRoundTripAndVerification() {
        val alice = KeyPair.generate()
        val (content, tags) = EcoReports.build("abc123", EcoCategory.TRASH, "Rubbish by the pond", 23.8103, 90.4125, "https://x.example/p.jpg", "ff")
        val ev = Event.sign(alice.secretKey, EcoReports.KIND, content, tags)
        val r = EcoReports.parse(ev)!!
        assertEquals(EcoCategory.TRASH, r.category)
        assertEquals(23.8103, r.lat, 1e-6)
        assertEquals("https://x.example/p.jpg", r.imageUrl)
        assertTrue(tags.any { it[0] == "g" && it[1].length == 4 })
        var x = EcoReports.applyReaction(r, alice.publicKeyHex, EcoReports.VERIFY) // own reaction ignored
        assertTrue(x.verifiedBy.isEmpty())
        x = EcoReports.applyReaction(x, "b", EcoReports.CLEANED)
        assertFalse(x.cleaned)
        x = EcoReports.applyReaction(x, "c", EcoReports.CLEANED)
        assertTrue("two others confirm the clean-up", x.cleaned)
        val tree = EcoReports.parse(Event.sign(alice.secretKey, EcoReports.KIND, EcoReports.build("t1", EcoCategory.PLANTED, "Mango", 1.0, 2.0, null, null).first,
            EcoReports.build("t1", EcoCategory.PLANTED, "Mango", 1.0, 2.0, null, null).second))!!
        assertFalse(tree.treeConfirmed)
        val confirmed = EcoReports.applyReaction(EcoReports.applyReaction(tree, "b", EcoReports.VERIFY), "c", EcoReports.VERIFY)
        assertTrue(confirmed.treeConfirmed)
        assertEquals("30419:${alice.publicKeyHex}:t1", tree.address)
    }

    @Test
    fun alertsFromMetNorway() {
        // now = 2026-06-01T06:00Z; hourly steps today, a 6-hour step tomorrow
        val now = java.time.Instant.parse("2026-06-01T06:00:00Z").toEpochMilli()
        fun hour(t: String, temp: Double, rh: Double, wind: Double, gust: Double, uv: Double, rain1: Double, symbol: String) = """
            {"time":"$t","data":{"instant":{"details":{"air_temperature":$temp,"relative_humidity":$rh,"wind_speed":$wind,"wind_speed_of_gust":$gust,"ultraviolet_index_clear_sky":$uv}},
             "next_1_hours":{"summary":{"symbol_code":"$symbol"},"details":{"precipitation_amount":$rain1}}}}"""
        val series = listOf(
            hour("2026-06-01T06:00:00Z", 36.5, 70.0, 3.3, 8.0, 5.0, 0.5, "partlycloudy_day"),
            hour("2026-06-01T09:00:00Z", 39.0, 70.0, 3.0, 10.0, 11.5, 1.0, "partlycloudy_day"),
            hour("2026-06-01T12:00:00Z", 40.0, 65.0, 3.0, 11.0, 10.0, 0.5, "fair_day"),
        ) + """{"time":"2026-06-02T07:00:00Z","data":{"instant":{"details":{"air_temperature":30,"relative_humidity":90,"wind_speed":10,"wind_speed_of_gust":27}},
             "next_6_hours":{"summary":{"symbol_code":"heavyrain"},"details":{"precipitation_amount":75.0}}}}"""
        val json = """{"type":"Feature","properties":{"timeseries":[${series.joinToString(",")}]}}"""
        val w = EcoAlerts.parseMet(23.8, 90.4, json, now, "Dhaka", "BD")
        assertEquals(36.5, w.tempC!!, 0.01)
        assertEquals("BD", w.country)
        assertEquals(2, w.weatherCode)
        assertEquals(2.0, w.rainTodayMm!!, 0.01)
        assertEquals(75.0, w.rainTomorrowMm!!, 0.01)
        assertEquals(97.2, w.maxGustKmh!!, 0.1)       // 27 m/s
        val ids = EcoAlerts.alerts(w).map { it.id }
        assertTrue(ids.toString(), ids.any { it.startsWith("heat") })   // hot and humid
        assertTrue("rain2" in ids)                     // 75 mm tomorrow
        assertTrue("wind2" in ids)                     // 97 km/h
        assertTrue("uv" in ids)
        assertTrue(EcoAlerts.safeMessage(EcoAlerts.alerts(w), "Dhaka").contains("Dhaka"))
        assertEquals(65, EcoAlerts.symbolToCode("heavyrainshowers_night"))
        assertEquals(0, EcoAlerts.symbolToCode("clearsky_day"))
        assertEquals(app.amend.messenger.data.EcoRegions.nordics, app.amend.messenger.data.EcoRegions.forCountry("fi"))
        assertEquals(app.amend.messenger.data.EcoRegions.southAsia, app.amend.messenger.data.EcoRegions.forCountry("BD"))
        val calm = EcoAlerts.alerts(WeatherSnapshot(60.0, 25.0, todayMaxFeelsC = 18.0, usAqi = 20, rainTodayMm = 1.0))
        assertTrue(calm.isEmpty())
        assertNotNull(EcoAlerts.describe(95))
    }
}
