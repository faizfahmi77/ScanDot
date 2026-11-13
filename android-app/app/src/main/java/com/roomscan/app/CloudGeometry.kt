package com.roomscan.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Interleaved XYZ/RGB floats. Coordinates are centered and scaled, never measured units. */
internal class CloudGeometry(val vertices: FloatArray, val pointCount: Int, val totalPoints: Int) {
    companion object {
        const val STRIDE = 6
        const val MAX_POINTS = 200_000

        fun normalized(vertices: FloatArray, totalPoints: Int, viewRotation: FloatArray? = null): CloudGeometry {
            require(vertices.isNotEmpty() && vertices.size % STRIDE == 0) { "This preview has no usable 3D points." }
            require(vertices.all { it.isFinite() }) { "The preview contains invalid coordinates." }
            val count = vertices.size / STRIDE
            if (viewRotation != null && validViewRotation(viewRotation)) {
                for (i in 0 until count) {
                    val j = i * STRIDE
                    val x = vertices[j].toDouble(); val y = vertices[j + 1].toDouble(); val z = vertices[j + 2].toDouble()
                    for (row in 0..2) vertices[j + row] =
                        (viewRotation[row * 3] * x + viewRotation[row * 3 + 1] * y + viewRotation[row * 3 + 2] * z)
                            .coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
                }
            }
            val axes = Array(3) { axis -> FloatArray(count) { vertices[it * STRIDE + axis] }.apply { sort() } }
            val center = FloatArray(3) { axis -> axes[axis][count / 2] }
            // Distant reconstruction outliers must not make the room disappear at fit.
            val low = count / 100; val high = count - 1 - low
            val radius = (0..2).maxOf { axis ->
                max(abs(axes[axis][low].toDouble() - center[axis]), abs(axes[axis][high].toDouble() - center[axis]))
            }.coerceAtLeast(0.0001)
            for (i in 0 until count) for (axis in 0..2) {
                vertices[i * STRIDE + axis] = ((vertices[i * STRIDE + axis].toDouble() - center[axis]) / radius)
                    .coerceIn(-1_000_000.0, 1_000_000.0).toFloat()
            }
            return CloudGeometry(vertices, count, max(count, totalPoints))
        }
    }
}

/** Ignore malformed optional orientation metadata rather than distorting the reconstruction. */
internal fun validViewRotation(matrix: FloatArray): Boolean {
    if (matrix.size != 9 || matrix.any { !it.isFinite() }) return false
    for (row in 0..2) for (other in 0..2) {
        val dot = (0..2).sumOf { (matrix[row * 3 + it] * matrix[other * 3 + it]).toDouble() }
        if (abs(dot - if (row == other) 1.0 else 0.0) > 0.02) return false
    }
    val determinant = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
        matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
        matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
    return abs(determinant - 1f) < 0.02f
}

internal enum class CloudPreset(val yaw: Float, val pitch: Float) {
    FRONT(0f, 0f), SIDE((Math.PI / 2).toFloat(), 0f), TOP(0f, (Math.PI / 2).toFloat()),
}

internal data class CloudRotation(val yaw: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f) {
    /** Row-major rotation, shared by fit calculations and the GPU projection. */
    fun matrix(): FloatArray {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        return floatArrayOf(
            cr * cy - sr * sp * sy, -sr * cp, cr * sy + sr * sp * cy,
            sr * cy + cr * sp * sy, cr * cp, sr * sy - cr * sp * cy,
            -cp * sy, sp, cp * cy,
        )
    }
}

internal data class CloudFrame(val centerX: Float, val centerY: Float, val halfHeight: Float)

/** Fit the central 98% in screen space; all points remain available when orbiting/panning. */
internal fun fitCloud(geometry: CloudGeometry, rotation: CloudRotation, aspect: Float): CloudFrame {
    require(aspect > 0f && aspect.isFinite())
    val matrix = rotation.matrix()
    val xs = FloatArray(geometry.pointCount)
    val ys = FloatArray(geometry.pointCount)
    for (i in xs.indices) {
        val j = i * CloudGeometry.STRIDE
        val x = geometry.vertices[j]; val y = geometry.vertices[j + 1]; val z = geometry.vertices[j + 2]
        xs[i] = matrix[0] * x + matrix[1] * y + matrix[2] * z
        ys[i] = matrix[3] * x + matrix[4] * y + matrix[5] * z
    }
    xs.sort(); ys.sort()
    val low = xs.size / 100; val high = xs.size - 1 - low
    val halfWidth = (xs[high] - xs[low]) / 2f
    val halfHeight = (ys[high] - ys[low]) / 2f
    return CloudFrame((xs[low] + xs[high]) / 2f, (ys[low] + ys[high]) / 2f,
        max(halfHeight, halfWidth / aspect).coerceAtLeast(0.01f) / 0.9f)
}

/** Column-major orthographic projection for OpenGL; nearer (smaller) camera Z wins. */
internal fun cloudProjection(rotation: CloudRotation, frame: CloudFrame, aspect: Float,
    zoom: Float, panX: Float, panY: Float, output: FloatArray) {
    val matrix = rotation.matrix()
    val scaleY = zoom / frame.halfHeight
    val scaleX = scaleY / aspect
    output.fill(0f)
    for (column in 0..2) {
        output[column * 4] = matrix[column] * scaleX
        output[column * 4 + 1] = -matrix[3 + column] * scaleY
        output[column * 4 + 2] = matrix[6 + column] / 32f
    }
    output[12] = -frame.centerX * scaleX + panX
    output[13] = frame.centerY * scaleY + panY
    output[15] = 1f
}
