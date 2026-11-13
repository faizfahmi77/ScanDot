package com.roomscan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionTest {
    @Test fun normalizesLocalAndHttpsAddresses() {
        assertEquals("http://127.0.0.1:8765", validServer("  http://127.0.0.1:8765/  "))
        assertEquals("https://scan.example.test/base", validServer("https://scan.example.test/base/"))
    }
    @Test fun rejectsNonWebAndMissingHostAddresses() {
        listOf("file:///tmp/video", "localhost:8765", "http://", "", "ftp://scan.example.test").forEach {
            assertThrows(Exception::class.java) { validServer(it) }
        }
    }
    @Test fun rejectsCredentialsAndTokenQueryParameters() {
        listOf("http://user:secret@localhost:8765", "https://scan.example.test?token=secret", "https://scan.example.test#secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { validServer(it) }
        }
    }
}
