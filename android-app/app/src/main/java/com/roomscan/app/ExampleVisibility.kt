package com.roomscan.app

/** Local diagnostic examples from earlier development builds; files are retained. */
internal object ExampleVisibility {
    private val hiddenIds = setOf(
        "3b6d578b-d83a-4e23-bba5-4a6918f5f54b",
        "4b17993c-20d7-4542-adc2-3eb113d1adba",
        "6be28be0-fcc7-4100-8251-56256fcb4e5c",
        "6e8e32cb-c8cb-4fbb-812e-8d67f408c260",
        "ccecdb9a-6d25-4bf3-9b76-05735cd71743",
    )
    fun isVisible(id: String): Boolean = id !in hiddenIds
}
