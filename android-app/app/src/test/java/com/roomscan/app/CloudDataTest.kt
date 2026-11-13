package com.roomscan.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudDataTest {
    @Test fun keepsPointsBeyondTheOldTwentyThousandLimit() {
        val points = JSONArray()
        repeat(25_000) { points.put(JSONArray(listOf(it, 0, 0, 120, 130, 140))) }
        val cloud = parseCloud(JSONObject().put("points", points).put("total_points", 90_000))
        assertEquals(25_000, cloud.pointCount)
        assertEquals(90_000, cloud.totalPoints)
    }

    @Test fun clientCapSamplesTheWholeResponse() {
        val points = JSONArray()
        repeat(CloudGeometry.MAX_POINTS + 100) { index ->
            points.put(JSONArray(listOf(index, 0, 0, if (index >= CloudGeometry.MAX_POINTS) 255 else 0, 0, 0)))
        }
        val cloud = parseCloud(JSONObject().put("points", points))
        assertEquals(CloudGeometry.MAX_POINTS, cloud.pointCount)
        assertTrue((0 until cloud.pointCount).any { cloud.vertices[it * 6 + 3] == 1f })
    }

    @Test fun malformedPointsAreSkippedAndColorsAreBounded() {
        val cloud = parseCloud(JSONObject("""{"points":[null,[1,2],["invalid",0,0,0,0,0],[1,2,3,-10,300,100]]}"""))
        assertEquals(1, cloud.pointCount)
        assertEquals(0f, cloud.vertices[3], 0f)
        assertEquals(1f, cloud.vertices[4], 0f)
        assertEquals(100 / 255f, cloud.vertices[5], 0f)
    }

    @Test fun acceptsOptionalCameraFrameAndOlderResponsesWithoutIt() {
        val points = """"points":[[-1,0,-2,255,0,0],[1,0,2,0,255,0]]"""
        val original = parseCloud(JSONObject("{$points}"))
        val rotated = parseCloud(JSONObject("{$points,\"view_rotation\":[[0,0,1],[0,1,0],[-1,0,0]]}"))
        assertTrue(original.vertices[2] < original.vertices[8])
        assertTrue(rotated.vertices[2] > rotated.vertices[8])
        val malformed = parseCloud(JSONObject("{$points,\"view_rotation\":[[9,9,9],[9,9,9],[9,9,9]]}"))
        assertArrayEquals(original.vertices, malformed.vertices, 0f)
    }

    @Test(expected = IllegalArgumentException::class) fun emptyCloudProducesAUsefulLoadError() {
        parseCloud(JSONObject("""{"points":[]}"""))
    }
}
