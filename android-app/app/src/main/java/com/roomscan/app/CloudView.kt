package com.roomscan.app

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI

/** One GPU draw call with depth testing; rear surfaces cannot paint over nearer points. */
@SuppressLint("ViewConstructor") // Created by Compose AndroidView with prepared geometry, never inflated from XML.
internal class CloudView(context: Context, geometry: CloudGeometry) : GLSurfaceView(context) {
    private val cloudRenderer = CloudRenderer(geometry, resources.displayMetrics.density)
    private var lastX = 0f
    private var lastY = 0f
    private var lastPointers = 0
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoomBy(detector.scaleFactor); return true }
    })

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(cloudRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "3D point cloud. Drag to rotate, pinch to zoom, drag with two fingers to pan. View, fit and point-size controls are below."
        isClickable = true; isFocusable = true
    }

    private fun change(action: CloudRenderer.() -> Unit) { queueEvent { cloudRenderer.action() }; requestRender() }
    fun reset() = change { preset(CloudPreset.FRONT) }
    fun fit() = change { fitView() }
    fun preset(preset: CloudPreset) = change { preset(preset) }
    fun turnUpright() = change { rotation = rotation.copy(roll = rotation.roll + (PI / 2).toFloat()); fitView() }
    fun zoomBy(factor: Float) = change { zoom = (zoom * factor).coerceIn(0.1f, 20f) }
    fun pointSize(size: Float) = change { pointSize = size.coerceIn(1f, 5f) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        val pointers = event.pointerCount
        val x = (0 until pointers).sumOf { event.getX(it).toDouble() }.toFloat() / pointers
        val y = (0 until pointers).sumOf { event.getY(it).toDouble() }.toFloat() / pointers
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> if (pointers == lastPointers) {
                val dx = x - lastX; val dy = y - lastY
                if (pointers >= 2) change { panX += dx * 2f / width.coerceAtLeast(1); panY -= dy * 2f / height.coerceAtLeast(1) }
                else if (!scaler.isInProgress) change {
                    rotation = rotation.copy(yaw = rotation.yaw + dx * 0.008f, pitch = rotation.pitch + dy * 0.008f)
                }
            }
            MotionEvent.ACTION_UP -> { performClick(); parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        lastX = x; lastY = y
        // Do not use a two-finger centroid as the next single-finger rotation origin.
        lastPointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) 0 else pointers
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private class CloudRenderer(private val geometry: CloudGeometry, private val density: Float) : Renderer {
        var rotation = CloudRotation()
        var zoom = 1f
        var panX = 0f
        var panY = 0f
        var pointSize = 2f
        private var aspect = 1f
        private var frame = CloudFrame(0f, 0f, 1f)
        private var program = 0
        private var buffer = 0
        private var position = 0
        private var color = 0
        private var transform = 0
        private var size = 0
        private var maxPointSize = 1f
        private val projection = FloatArray(16)

        fun fitView() { frame = fitCloud(geometry, rotation, aspect); zoom = 1f; panX = 0f; panY = 0f }
        fun preset(preset: CloudPreset) { rotation = CloudRotation(preset.yaw, preset.pitch); fitView() }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.025f, 0.04f, 0.06f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            val vertex = shader(GLES20.GL_VERTEX_SHADER, """
                uniform mat4 uTransform;
                uniform float uSize;
                attribute vec3 aPosition;
                attribute vec3 aColor;
                varying vec3 vColor;
                void main() {
                    gl_Position = uTransform * vec4(aPosition, 1.0);
                    gl_PointSize = uSize;
                    vColor = aColor;
                }
            """.trimIndent())
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                varying vec3 vColor;
                void main() {
                    vec2 offset = gl_PointCoord - vec2(0.5);
                    if (dot(offset, offset) > 0.25) discard;
                    gl_FragColor = vec4(vColor, 1.0);
                }
            """.trimIndent())
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { "3D renderer could not link: ${GLES20.glGetProgramInfoLog(program)}" }
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            position = GLES20.glGetAttribLocation(program, "aPosition")
            color = GLES20.glGetAttribLocation(program, "aColor")
            transform = GLES20.glGetUniformLocation(program, "uTransform")
            size = GLES20.glGetUniformLocation(program, "uSize")
            val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); buffer = ids[0]
            val vertices = ByteBuffer.allocateDirect(geometry.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(geometry.vertices).position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, geometry.vertices.size * 4, vertices, GLES20.GL_STATIC_DRAW)
            val sizes = FloatArray(2); GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, sizes, 0)
            maxPointSize = sizes[1]
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat().coerceAtLeast(1f) / height.coerceAtLeast(1)
            fitView()
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            cloudProjection(rotation, frame, aspect, zoom, panX, panY, projection)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(transform, 1, false, projection, 0)
            GLES20.glUniform1f(size, (pointSize * density).coerceAtMost(maxPointSize))
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(color)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0)
            GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 24, 12)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, geometry.pointCount)
        }

        private fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { "3D renderer could not compile: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
    }
}
