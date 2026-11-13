package com.roomscan.app

import org.junit.Assert.*
import org.junit.Test

class DepthAvailabilityTest {
    @Test fun timesOutAtFifteenSecondsOfTrackingWithoutDepth() {
        val availability = DepthAvailability()
        assertFalse(availability.update(100, true))
        assertFalse(availability.update(15_099, true))
        assertTrue(availability.update(15_100, true))
    }

    @Test fun freshDepthResetsWaitAndRepeatedFramesDoNot() {
        val availability = DepthAvailability()
        availability.update(0, true)
        assertFalse(availability.update(14_000, true))
        assertFalse(availability.update(14_500, true, freshDepth = true))
        assertFalse(availability.update(29_499, true))
        assertTrue(availability.update(29_500, true))
    }

    @Test fun trackingGapsAndExplicitPauseDoNotCountAsCaptureTime() {
        val availability = DepthAvailability()
        availability.update(0, true)
        availability.update(5_000, true)
        assertFalse(availability.update(5_001, false))
        assertFalse(availability.update(100_000, false))
        assertFalse(availability.update(200_000, true))
        assertTrue(availability.update(210_000, true))
        availability.reset()
        assertFalse(availability.update(500_000, true))
        assertFalse(availability.update(500_001, true))
    }
}
