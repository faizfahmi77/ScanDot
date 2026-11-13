package com.roomscan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class PresentationTest {
    @Test fun reportsSelectedAlignmentWithoutClaimingCoverage() {
        val summary = JSONObject().put("point_count", 201179).put("registered_images", 50)
            .put("input_frames", 50).put("candidate_model_count", 3)
        val text = resultSummaryLabel(summary)
        assertTrue(text.contains("50 / 50 input images aligned"))
        assertTrue(text.contains("3 candidate alignment models (may overlap)"))
        assertTrue(text.contains("Room coverage is unverified"))
        assertFalse(text.contains("components"))
        summary.put("registered_images", 11)
        assertTrue(resultSummaryLabel(summary).contains("11 / 50"))
        summary.remove("input_frames")
        assertTrue(resultSummaryLabel(summary).contains("input count unavailable"))
        assertTrue(resultSummaryLabel(JSONObject()).contains("aligned image count unavailable"))
        summary.put("input_frames", 5)
        assertTrue(resultSummaryLabel(summary).contains("aligned image count unavailable"))
    }

    @Test fun formatsTotalElapsedTimeAndHidesUnavailableValues() {
        val job = JSONObject().put("status", "completed").put("total_elapsed_seconds", 748.276)
        assertEquals("Total job time: 12m 28s (includes queueing and retries)", totalElapsedLabel(job))
        job.put("total_elapsed_seconds", 3661)
        assertEquals("Total job time: 1h 1m 1s (includes queueing and retries)", totalElapsedLabel(job))
        for (value in listOf(-1, "invalid", JSONObject.NULL)) {
            job.put("total_elapsed_seconds", value)
            assertNull(totalElapsedLabel(job))
        }
        job.put("total_elapsed_seconds", 10).put("status", "reconstructing")
        assertNull(totalElapsedLabel(job))
        assertNull(totalElapsedLabel(JSONObject().put("status", "completed")))
    }
    @Test fun presentsKnownReconstructionStagesWithoutProcessorNames() {
        mapOf(
            "exhaustive matcher" to "Connecting camera views",
            "sequential matcher" to "Connecting camera views",
            "feature_extractor" to "Finding image details",
            "mapper" to "Building 3D structure",
            "model analyzer" to "Preparing preview",
            "patch_match_stereo" to "Adding surface detail",
            "stereo fusion" to "Adding surface detail",
        ).forEach { (stage, expected) -> assertEquals(expected, processingStageLabel(stage)) }
    }
    @Test fun handlesStageSetupAndCaseVariants() {
        assertEquals("Connecting camera views", processingStageLabel(" Exhaustive_Matcher_Help "))
        assertEquals("Finding image details", processingStageLabel("FEATURE EXTRACTOR HELP"))
        assertEquals("Preparing preview", processingStageLabel("colmap version help"))
    }
    @Test fun preservesUnknownAndTerminalMessages() {
        listOf("Completed", "Failed", "Server restarted", "A future processing stage", "").forEach {
            assertEquals(it, processingStageLabel(it))
        }
    }
}
