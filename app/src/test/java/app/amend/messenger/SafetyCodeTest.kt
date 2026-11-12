package app.amend.messenger

import app.amend.messenger.core.crypto.SafetyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCodeTest {
    private val alice = "a".repeat(64)
    private val bob = "0123456789abcdef".repeat(4)
    private val carol = "f".repeat(63) + "e"

    @Test
    fun sameCodeOnBothPhones() {
        assertEquals(SafetyCode.of(alice, bob), SafetyCode.of(bob, alice))
    }

    @Test
    fun twelveGroupsOfFiveDigits() {
        val groups = SafetyCode.of(alice, bob).split(" ")
        assertEquals(SafetyCode.GROUPS, groups.size)
        assertTrue(groups.all { it.length == 5 && it.all(Char::isDigit) })
    }

    @Test
    fun differentPeopleGetDifferentCodes() {
        assertNotEquals(SafetyCode.of(alice, bob), SafetyCode.of(alice, carol))
        assertNotEquals(SafetyCode.of(alice, bob), SafetyCode.of(bob, carol))
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(SafetyCode.of(alice, bob), SafetyCode.of(alice.uppercase(), bob.uppercase()))
    }
}
