package com.phonecam.streamer.streaming.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface

/**
 * Renders camera frames straight into a MediaCodec input [Surface] on the
 * GPU, with zero CPU-side buffer copies: the camera writes each frame
 * directly into [cameraInputSurface] (a [SurfaceTexture]-backed Surface,
 * handed to CameraX's `VideoCapture` use case — see
 * [com.phonecam.streamer.streaming.StreamingVideoOutput]) as a
 * `GL_TEXTURE_EXTERNAL_OES` texture, which [renderCameraFrame] then draws
 * (with the mandatory OES transform matrix, plus our own rotation for
 * physical-device-rotation compensation, and the free-tier watermark) into
 * the encoder's input surface.
 *
 * This replaces the previous CPU YUV-plane-upload version: that was never
 * actually the bottleneck it looked like (its own repack/upload was cheap),
 * but it required [androidx.camera.core.ImageAnalysis] as the camera-facing
 * use case, and ImageAnalysis's CPU-accessible buffer format is what
 * capped 4K capture at ~30fps on real hardware — a Camera2/ISP
 * characteristic, not something fixable in this class. Consuming camera
 * frames as an OES texture via VideoCapture's Surface-based path sidesteps
 * that entirely: the same capture class real camera apps use for 4K60.
 *
 * One instance per streaming session, same lifetime as the encoder it feeds
 * — see [com.phonecam.streamer.streaming.H264Encoder].
 */
class EncoderSurfaceRenderer(inputSurface: Surface, private val targetWidth: Int, private val targetHeight: Int) {

    private val eglCore = EglCore()
    private val eglSurface: EGLSurface = eglCore.createWindowSurface(inputSurface)

    private var cameraProgram = 0
    private var cameraPositionHandle = 0
    private var cameraTexCoordHandle = 0
    private var cameraTextureHandle = 0
    private var cameraTexMatrixHandle = 0
    private var cameraRotationMatrixHandle = 0
    private val cameraTextureId: Int

    /** Hand this to [androidx.camera.core.SurfaceRequest.provideSurface] — the camera writes frames directly into it. */
    val cameraInputSurface: Surface
    private val cameraSurfaceTexture: SurfaceTexture

    private var watermarkProgram = 0
    private var watermarkPositionHandle = 0
    private var watermarkTexCoordHandle = 0
    private var watermarkTextureHandle = 0
    private var watermarkTextureId = 0
    private var watermarkUploaded = false

    private val positionBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )

    // Homogeneous (x, y, 0, 1) per vertex, matching aTexCoord's vec4 attribute
    // in the shader — the OES transform matrix from SurfaceTexture is a full
    // 4x4 and expects a 4-component vector to multiply against.
    private val texCoordBuffer = floatBuffer(
        floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        ),
    )
    private val fullTexCoordBuffer = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

    private val texMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)

    init {
        eglCore.makeCurrent(eglSurface)

        cameraProgram = buildProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        cameraPositionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        cameraTexCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        cameraTextureHandle = GLES20.glGetUniformLocation(cameraProgram, "uTexture")
        cameraTexMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")
        cameraRotationMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uRotationMatrix")

        watermarkProgram = buildProgram(VERTEX_SHADER, WATERMARK_FRAGMENT_SHADER)
        watermarkPositionHandle = GLES20.glGetAttribLocation(watermarkProgram, "aPosition")
        watermarkTexCoordHandle = GLES20.glGetAttribLocation(watermarkProgram, "aTexCoord")
        watermarkTextureHandle = GLES20.glGetUniformLocation(watermarkProgram, "uTexture")

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        cameraTextureId = textureIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)
        cameraInputSurface = Surface(cameraSurfaceTexture)

        val watermarkIds = IntArray(1)
        GLES20.glGenTextures(1, watermarkIds, 0)
        watermarkTextureId = watermarkIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Sets the size the camera will actually produce frames at — call once before the first frame arrives. */
    fun setCameraFrameSize(width: Int, height: Int) {
        cameraSurfaceTexture.setDefaultBufferSize(width, height)
    }

    fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener, handler: android.os.Handler) {
        cameraSurfaceTexture.setOnFrameAvailableListener(listener, handler)
    }

    /**
     * Call on *every* frame-available event, unconditionally — this is what
     * actually releases the buffer back to the camera's producer queue.
     * That queue is small and bounded (a handful of buffers); skipping this
     * call for a frame you don't want to encode (rate-limited by fps, or
     * the network connection isn't up yet) leaves that buffer permanently
     * stuck. Confirmed on-device: doing so only when also encoding caused
     * capture fps to collapse from a healthy 30 down to ~0 over the course
     * of a session, as the camera ran out of free buffers to write into and
     * started stalling delivery. [renderCameraFrame] no longer touches the
     * SurfaceTexture itself — call this first, then renderCameraFrame only
     * for the frames you actually want encoded.
     */
    fun updateCameraTexture() {
        cameraSurfaceTexture.updateTexImage()
        cameraSurfaceTexture.getTransformMatrix(texMatrix)
    }

    /**
     * Draws whatever [updateCameraTexture] most recently latched (OES-
     * corrected, rotated for [rotationDegrees], scaled to the encoder's
     * target size via the GL viewport, watermarked if requested) into the
     * encoder's input surface, and submits that as one frame.
     */
    fun renderCameraFrame(rotationDegrees: Int, watermark: Boolean, presentationTimeUs: Long) {
        eglCore.makeCurrent(eglSurface)
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glDisable(GLES20.GL_BLEND)

        drawCamera(rotationDegrees)

        if (watermark) {
            if (!watermarkUploaded) {
                uploadWatermarkTexture()
                watermarkUploaded = true
            }
            drawWatermark()
        }

        eglCore.setPresentationTime(eglSurface, presentationTimeUs * 1000L)
        eglCore.swapBuffers(eglSurface)
    }

    fun release() {
        // renderCameraFrame() always runs on CameraStreamer's dedicated GL
        // thread, but release() runs wherever CameraStreamer.stop() was
        // called from (the UI thread) — a different thread. EGL contexts
        // are current on exactly one thread at a time; without re-asserting
        // it here first, every GL call below silently fails ("call to
        // OpenGL ES API with no current context") and this cleanup never
        // actually runs, leaking the EGL context/surface and GL objects.
        eglCore.makeCurrent(eglSurface)
        cameraInputSurface.release()
        cameraSurfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(watermarkTextureId), 0)
        if (cameraProgram != 0) GLES20.glDeleteProgram(cameraProgram)
        if (watermarkProgram != 0) GLES20.glDeleteProgram(watermarkProgram)
        eglCore.releaseSurface(eglSurface)
        eglCore.release()
    }

    private fun drawCamera(rotationDegrees: Int) {
        // CameraX's convention: rotate the buffer clockwise by this many
        // degrees to reach the desired output orientation. Applied to the
        // vertex positions (not texcoords) so it composes independently of
        // the OES transform matrix, which handles this device's own sensor
        // mounting/buffer convention.
        Matrix.setRotateM(rotationMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)

        GLES20.glUseProgram(cameraProgram)

        positionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(cameraPositionHandle)
        GLES20.glVertexAttribPointer(cameraPositionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(cameraTexCoordHandle)
        GLES20.glVertexAttribPointer(cameraTexCoordHandle, 4, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(cameraTexMatrixHandle, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(cameraRotationMatrixHandle, 1, false, rotationMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(cameraPositionHandle)
        GLES20.glDisableVertexAttribArray(cameraTexCoordHandle)
    }

    private fun uploadWatermarkTexture() {
        val overlay = com.phonecam.streamer.overlay.WatermarkOverlay.renderOverlay(targetWidth, targetHeight)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
        overlay.recycle()
    }

    private fun drawWatermark() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(watermarkProgram)

        positionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(watermarkPositionHandle)
        GLES20.glVertexAttribPointer(watermarkPositionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

        fullTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(watermarkTexCoordHandle)
        GLES20.glVertexAttribPointer(watermarkTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, fullTexCoordBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
        GLES20.glUniform1i(watermarkTextureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(watermarkPositionHandle)
        GLES20.glDisableVertexAttribArray(watermarkTexCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    companion object {
        private const val CAMERA_VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform mat4 uRotationMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uRotationMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // samplerExternalOES does the YUV->RGB conversion in hardware — no
        // manual color math needed, unlike the old CPU-plane-upload path.
        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val WATERMARK_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private fun floatBuffer(values: FloatArray): java.nio.FloatBuffer =
            java.nio.ByteBuffer.allocateDirect(values.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("GL program link failed: $log")
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return program
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("GL shader compile failed: $log")
            }
            return shader
        }
    }
}
