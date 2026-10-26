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
    fun alertsFromOpenMeteo() {
        val forecast = """{"current":{"temperature_2m":36.5,"apparent_temperature":44.2,"relative_humidity_2m":70,"weather_code":2,"wind_speed_10m":12,"wind_gusts_10m":30},
            "daily":{"apparent_temperature_max":[45.0,43.0],"precipitation_sum":[2.0,75.0],"wind_gusts_10m_max":[40,95],"uv_index_max":[11.5,10]}}"""
        val air = """{"current":{"us_aqi":168,"pm2_5":88.0}}"""
        val flood = """{"daily":{"river_discharge":[120,150,260,400,380,300,250,200]}}"""
        val w = EcoAlerts.parse(23.8, 90.4, forecast, air, flood, 0, "Dhaka")
        assertEquals(36.5, w.tempC!!, 0.01)
        assertEquals(168, w.usAqi)
        val ids = EcoAlerts.alerts(w).map { it.id }
        assertTrue(ids.toString(), "heat2" in ids)   // feels 45
        assertTrue("rain2" in ids)                     // 75 mm tomorrow
        assertTrue("wind2" in ids)                     // 95 km/h
        assertTrue("air2" in ids)                      // AQI 168
        assertTrue("river" in ids)                     // 120 → 400
        assertTrue("uv" in ids)
        assertTrue(EcoAlerts.safeMessage(EcoAlerts.alerts(w), "Dhaka").contains("Dhaka"))
        val calm = EcoAlerts.alerts(WeatherSnapshot(60.0, 25.0, todayMaxFeelsC = 18.0, usAqi = 20, rainTodayMm = 1.0))
        assertTrue(calm.isEmpty())
        assertNotNull(EcoAlerts.describe(95))
    }
}
