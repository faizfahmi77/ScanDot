package com.roomscan.app

import org.junit.Assert.*
import org.junit.Test

class ExampleVisibilityTest {
    @Test fun hidesKnownExamplesAndKeepsOtherIdsVisible() {
        assertFalse(ExampleVisibility.isVisible("3b6d578b-d83a-4e23-bba5-4a6918f5f54b"))
        assertFalse(ExampleVisibility.isVisible("6e8e32cb-c8cb-4fbb-812e-8d67f408c260"))
        assertTrue(ExampleVisibility.isVisible("00000000-0000-4000-8000-000000000000"))
        assertTrue(ExampleVisibility.isVisible(""))
    }
}
