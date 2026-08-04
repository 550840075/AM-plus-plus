package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatPlayerBoundaryPolicyTest {
    @Test
    fun `detects navigation overlap below the eager aspect ratio gate`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 982,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            navigationInset = 16,
            wasNavigationSpaceReserved = false,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(16, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `keeps detected reservation after the overlap has moved above tabs`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 856,
            sheetBottom = 954,
            tabsTop = 954,
            tabsHeight = 126,
            navigationInset = 16,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(16, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `leaves ordinary non-overlapping tablet geometry unchanged`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 800,
            sheetTop = 650,
            sheetBottom = 744,
            tabsTop = 744,
            tabsHeight = 56,
            navigationInset = 16,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet remains native before a reservation is observed`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            navigationInset = 16,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet stays visible when it does not cover the navigation area`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 900,
            tabsTop = 954,
            tabsHeight = 126,
            navigationInset = 16,
            wasNavigationSpaceReserved = false,
        )

        assertFalse(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `expanded sheet clears margin and hides tabs after reservation`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 0,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            navigationInset = 16,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertFalse(decision.tabsVisible)
    }

    @Test
    fun `collapsed wide sheet reserves only the navigation inset`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1440,
            sheetTop = 1066,
            sheetBottom = 1296,
            tabsTop = 1296,
            tabsHeight = 144,
            navigationInset = 32,
            wasNavigationSpaceReserved = true,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(32, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `fresh collapsed overlap uses the dynamic inset`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1440,
            sheetTop = 1178,
            sheetBottom = 1408,
            tabsTop = 1296,
            tabsHeight = 144,
            navigationInset = 32,
            wasNavigationSpaceReserved = false,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(32, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }

    @Test
    fun `zero navigation inset keeps the detected geometry unchanged`() {
        val decision = FlatPlayerBoundaryPolicy.decide(
            rootHeight = 1080,
            sheetTop = 982,
            sheetBottom = 1080,
            tabsTop = 954,
            tabsHeight = 126,
            navigationInset = 0,
            wasNavigationSpaceReserved = false,
        )

        assertTrue(decision.reserveNavigationSpace)
        assertEquals(0, decision.bottomMargin)
        assertTrue(decision.tabsVisible)
    }
}
