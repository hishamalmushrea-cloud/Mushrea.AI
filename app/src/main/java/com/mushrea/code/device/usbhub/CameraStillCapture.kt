package com.mushrea.code.device.usbhub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.mushrea.code.device.usb.AdbException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One JPEG still through Camera2, including cameras Android flags as
 * [CameraCharacteristics.LENS_FACING_EXTERNAL] (USB webcams on devices that expose them).
 *
 * Scope that is claimed: a still from a Camera2-visible camera, saved under Download/Mushrea-camera.
 * Scope that is not claimed: a private UVC stack for webcams the platform does not publish.
 */
class CameraStillCapture(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun capture(params: JSONObject): File {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw AdbException("camera permission is not granted")
        }
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: throw AdbException("camera manager is unavailable on this device")
        val cameraId = selectCamera(manager, params)
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val jpegSize =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: throw AdbException("camera $cameraId does not expose JPEG stills")

        val thread = HandlerThread("mushrea-camera").also { it.start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1)
        val jpeg = AtomicReference<ByteArray?>(null)
        val jpegLatch = CountDownLatch(1)
        reader.setOnImageAvailableListener(
            { incoming ->
                incoming.acquireNextImage().use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    jpeg.set(bytes)
                }
                jpegLatch.countDown()
            },
            handler,
        )

        val device = openCamera(manager, cameraId, handler)
        val texture = SurfaceTexture(0)
        val preview = Surface(texture)
        try {
            texture.setDefaultBufferSize(640, 480)
            val session = createSession(device, listOf(preview, reader.surface), handler)
            try {
                val previewRequest =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                    }
                runCatching { session.setRepeatingRequest(previewRequest.build(), null, handler) }
                val still =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                    }
                val captured = CountDownLatch(1)
                session.capture(
                    still.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            captureSession: CameraCaptureSession,
                            request: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult,
                        ) {
                            captured.countDown()
                        }

                        override fun onCaptureFailed(
                            captureSession: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure,
                        ) {
                            captured.countDown()
                        }
                    },
                    handler,
                )
                if (!captured.await(8, TimeUnit.SECONDS)) {
                    throw AdbException("the camera did not complete a still capture in 8 s")
                }
                if (!jpegLatch.await(5, TimeUnit.SECONDS)) {
                    throw AdbException("the camera produced no JPEG frame")
                }
            } finally {
                runCatching { session.close() }
            }
        } finally {
            runCatching { device.close() }
            runCatching { reader.close() }
            runCatching { preview.release() }
            runCatching { texture.release() }
            thread.quitSafely()
        }

        val bytes = jpeg.get() ?: throw AdbException("the camera produced no JPEG frame")
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mushrea-camera")
        val dir = if (publicDir.isDirectory || publicDir.mkdirs()) publicDir else File(context.filesDir, "camera")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw AdbException("cannot create ${dir.absolutePath} to save the still")
        }
        val destination = File(dir, "still-${System.currentTimeMillis()}.jpg")
        destination.writeBytes(bytes)
        if (destination.length() == 0L) throw AdbException("the JPEG was written but is empty")
        return destination
    }

    private fun selectCamera(
        manager: CameraManager,
        params: JSONObject,
    ): String {
        val ids = manager.cameraIdList
        if (ids.isEmpty()) throw AdbException("this device exposes no cameras")
        val requested = params.optString("camera_id").ifBlank { null }
        if (requested != null) {
            return ids.firstOrNull { it == requested }
                ?: throw AdbException("camera_id $requested is not in camera_list")
        }
        val facingName = params.optString("facing").lowercase()
        val wanted =
            when (facingName) {
                "front" -> CameraCharacteristics.LENS_FACING_FRONT
                "back" -> CameraCharacteristics.LENS_FACING_BACK
                "external", "usb" -> CameraCharacteristics.LENS_FACING_EXTERNAL
                else -> null
            }
        if (wanted != null) {
            return ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: throw AdbException("no $facingName camera is visible to Android")
        }
        val external =
            ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_EXTERNAL
            }
        if (external != null) return external
        val back =
            ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        return back ?: ids.first()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler,
    ): CameraDevice {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<CameraDevice?>()
        val error = AtomicReference<String?>()
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    holder.set(camera)
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    error.set("camera $cameraId disconnected")
                    runCatching { camera.close() }
                    latch.countDown()
                }

                override fun onError(
                    camera: CameraDevice,
                    errorCode: Int,
                ) {
                    error.set("camera $cameraId failed to open (error $errorCode)")
                    runCatching { camera.close() }
                    latch.countDown()
                }
            },
            handler,
        )
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw AdbException("opening camera $cameraId timed out")
        }
        error.get()?.let { throw AdbException(it) }
        return holder.get() ?: throw AdbException("opening camera $cameraId produced no device")
    }

    @Suppress("DEPRECATION")
    private fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<CameraCaptureSession?>()
        val error = AtomicReference<String?>()
        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    holder.set(session)
                    latch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    error.set("the camera refused a capture session")
                    latch.countDown()
                }
            },
            handler,
        )
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw AdbException("creating a camera session timed out")
        }
        error.get()?.let { throw AdbException(it) }
        return holder.get() ?: throw AdbException("creating a camera session produced no session")
    }
}
