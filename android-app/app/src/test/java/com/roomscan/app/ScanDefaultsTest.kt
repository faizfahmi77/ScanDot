package com.roomscan.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScanDefaultsTest {
    @Test fun newRecordingsAndImportsDefaultToDetailedReconstruction() {
        assertTrue(Scan(project = "Office", location = "First floor", label = "Walkthrough").dense)
        assertTrue(Scan(project = "Imported scans", location = "Selected video", label = "Imported walkthrough").dense)
    }

    @Test fun explicitSparseChoiceAndSubmittedMetadataSurviveSaving() {
        val original = Scan(project = "Office", location = "First floor", label = "Old scan",
            dense = false, jobId = "submitted-job", uploadAttempted = true, status = "submitted")
        assertEquals(original, Scan.parse(original.json()))
        assertFalse(Scan.parse(original.json()).dense)
        assertFalse(original.copy(label = "Updated label").dense)
    }

    @Test fun detailedChoiceSurvivesSaving() {
        val original = Scan(project = "Office", location = "First floor", label = "New scan", dense = true)
        assertEquals(original, Scan.parse(original.json()))
    }

    @Test fun legacyRecordsWithoutDenseFieldKeepTheSparseChoice() {
        val legacy = JSONObject().put("id", "old-scan").put("label", "Legacy scan").put("job_id", "old-job")
        val restored = Scan.parse(legacy)
        assertFalse(restored.dense)
        assertEquals("old-job", restored.jobId)
    }
}
