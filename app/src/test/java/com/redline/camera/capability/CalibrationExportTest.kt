package com.redline.camera.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationExportTest {
    private val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun cal(cm1: List<Double>? = identity) = FactoryCalibration(
        cameraId = "0",
        referenceIlluminant1 = 17,
        referenceIlluminant2 = 21,
        colorTransform1 = cm1,
        colorTransform2 = identity,
        forwardMatrix1 = identity,
        forwardMatrix2 = null,
        calibrationTransform1 = identity,
        calibrationTransform2 = identity,
        lensDistortion = listOf(0.1f, -0.2f, 0f, 0f, 0f),
        lensIntrinsicCalibration = null,
        whiteLevel = 1023,
        blackLevelPattern = listOf(64, 64, 64, 64)
    )

    @Test
    fun jsonUsesCamera2KeyNamesAndNulls() {
        val json = cal().toJson()
        assertTrue(json.contains("\"SENSOR_REFERENCE_ILLUMINANT1\": 17"))
        assertTrue(json.contains("\"SENSOR_FORWARD_MATRIX2\": null"))
        assertTrue(json.contains("\"SENSOR_COLOR_TRANSFORM1\": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]"))
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun missingColorCalibrationIsReported() {
        assertTrue(cal().hasColorCalibration)
        assertFalse(cal(cm1 = null).hasColorCalibration)
    }

    @Test
    fun jsonValueEscapesAndRejectsNonFinite() {
        assertEquals("\"a\\\"b\"", FactoryCalibration.jsonValue("a\"b"))
        assertEquals("null", FactoryCalibration.jsonValue(Double.NaN))
        assertEquals("[1, null]", FactoryCalibration.jsonValue(listOf(1, null)))
    }

    @Test
    fun pickPrefersCandidateWithColorCalibration() {
        val logical = cal(cm1 = null)
        val physical = cal().copy(sourceCameraId = "2")
        assertEquals("2", FactoryCalibration.pick(listOf(logical, physical))?.sourceCameraId)
        assertEquals("0", FactoryCalibration.pick(listOf(logical))?.cameraId)
        assertTrue(physical.toJson().contains("\"source_camera_id\": \"2\""))
    }
}
