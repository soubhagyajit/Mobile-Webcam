package com.sjbtechnologies.awa.viewModel

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.generic.GenericStream
import com.sjbtechnologies.awa.server.VideoStreamServer
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraViewModel : ViewModel() {

    enum class StreamMode(val label: String) {
        MJPEG("MJPEG"),
        H264_RTSP("H.264 (RTSP)")
    }
    enum class StreamResolution(val label: String, val size: Size, val aspectRatio: Int) {
        P480("480p", Size(640, 480), AspectRatio.RATIO_4_3),
        P720("720p", Size(1280, 720), AspectRatio.RATIO_16_9),
        P1080("1080p", Size(1920, 1080), AspectRatio.RATIO_16_9),
        P4K("4K", Size(3840, 2160), AspectRatio.RATIO_16_9)
    }
    enum class FocusMode { AUTO, MANUAL }

    data class CameraSettings(
        val resolution: StreamResolution = StreamResolution.P720,
        val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        val focusMode: FocusMode = FocusMode.AUTO,
        val focusDistance: Float = 0f, // 0.0 = infinity, 1.0 = closest
        val exposureIndex: Int = 0,
        val exposureRange: IntRange = 0..0,
        val jpegQuality: Int = 80
    )

    @Volatile
    var currentFrame: ByteArray? = null
        private set

    private var mjpegCamera: androidx.camera.core.Camera? = null
    private var genericStream: GenericStream? = null
    private var rtspView: TextureView? = null
    private var mjpegView: PreviewView? = null


    private var appContext: Context? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private val _isPreviewActive = mutableStateOf(false)
    val isPreviewActive: State<Boolean> = _isPreviewActive
    private val _isServerRunning = mutableStateOf(true)
    val isServerRunning: State<Boolean> = _isServerRunning

    private val _showLocalPreview = mutableStateOf(false)
    val showLocalPreview: State<Boolean> = _showLocalPreview
    private var hidePreviewRunnable: Runnable? = null
    private val previewPeekDurationMs = 20000L

    private val _streamMode = mutableStateOf(StreamMode.MJPEG)
    val streamMode: State<StreamMode> = _streamMode

    private val _settings = mutableStateOf(CameraSettings())
    val settings: State<CameraSettings> = _settings

    private val viewerCount = AtomicInteger(0)
    private val jpegViewerCount = AtomicInteger(0) // tracks /video viewers specifically, so JPEG work can be skipped when nobody's watching it
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private val rtspBitrate = 16_000_000 // 16 Mbps default, hardcoded for now

    // GenericStream PUSHES to an external RTSP server rather than acting as its
    // own server (unlike the old RtspServerCamera2) — this is the address of
    // that server (mediamtx running on the desktop).
    // TODO: replace this
    // manual text entry with automatic discovery once the desktop side is built.
    private val _rtspPushUrl = mutableStateOf("rtsp://192.168.31.30:8554/awa")


    val rtspPushUrl: State<String> = _rtspPushUrl
    fun setRtspPushUrl(url: String) {
        _rtspPushUrl.value = url
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext

        VideoStreamServer.onUserConnected = {
            jpegViewerCount.incrementAndGet()
            if (viewerCount.incrementAndGet() == 1 && _isServerRunning.value && _streamMode.value == StreamMode.MJPEG) {
                mainHandler.post {
                    _isPreviewActive.value = true
                    bindCamera()
                }
            }
        }
        VideoStreamServer.onUserDisconnected = {
            jpegViewerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (viewerCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
                mainHandler.post {
                    _isPreviewActive.value = false
                    unbindCamera()
                }
            }
        }

        VideoStreamServer.featuresProvider = {
            val s = _settings.value
            VideoStreamServer.FeaturesResponse(
                resolutions = StreamResolution.entries.map { "${it.size.width}x${it.size.height}" },
                camera = if (s.lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front",
                manual_focus = true,
                exposure_lower = s.exposureRange.first,
                exposure_upper = s.exposureRange.last,
                stream_protocol = _streamMode.value,
                rtsp_port = null // no longer server-mode; phone pushes to a desktop URL now (see rtspPushUrl)
            )
        }

        VideoStreamServer.settingsProvider = {
            val s = _settings.value
            VideoStreamServer.SettingsResponse(
                focus_mode = if (s.focusMode == FocusMode.AUTO) 0 else 1,
                focus_distance = s.focusDistance,
                exposure_index = s.exposureIndex,
                zoom = 1.0f, // TODO add zoom later
                stream_quality = s.jpegQuality,
                flip = s.lensFacing == CameraSelector.LENS_FACING_FRONT,
                resolution_str = "${s.resolution.size.width}x${s.resolution.size.height}"
            )
        }

        // Ktor runs unconditionally, always — it's the control plane (/features,
        // /settings) plus the /video route, and must be reachable regardless of
        // which stream mode is selected or whether streaming is currently on.
        VideoStreamServer.start(8080)
    }

    // --- Unified start/stop, used by the shutter button regardless of mode ---

    fun toggleServer() {
        if (_isServerRunning.value) {
            stopActiveStream()
            _isServerRunning.value = false
        } else {
            startActiveStream()
            _isServerRunning.value = true
        }
    }

    private fun startActiveStream() {
        when (_streamMode.value) {
            StreamMode.MJPEG -> {/* Nothing to do here */}
            StreamMode.H264_RTSP -> startRtspCamera()
        }
    }

    private fun stopActiveStream() {
        when (_streamMode.value) {
            StreamMode.MJPEG -> {
                unbindCamera()
                _isPreviewActive.value = false
            }
            StreamMode.H264_RTSP -> stopRtspCamera()
        }
        hideLocalPreview()
    }

    fun setStreamMode(mode: StreamMode) {
        if (_streamMode.value == mode) return
        val wasRunning = _isServerRunning.value
        if (wasRunning) stopActiveStream()
        _streamMode.value = mode
        if (wasRunning) startActiveStream()
    }

    fun notifyScreenTapped() {
        Log.d("AWA", "Screen Tapped")
        if (!_isServerRunning.value) return
        _showLocalPreview.value = true
        if (_streamMode.value == StreamMode.MJPEG) {
            startPreviewHideTimer()
        }
    }

    private fun startPreviewHideTimer() {
        hidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { hideLocalPreview() }
        hidePreviewRunnable = runnable
        mainHandler.postDelayed(runnable, previewPeekDurationMs)
    }

    private fun hideLocalPreview() {
        hidePreviewRunnable?.let { mainHandler.removeCallbacks(it) }
        hidePreviewRunnable = null
        _showLocalPreview.value = false
    }


    // --- RTSP Streaming with AVC/H.264 video and audio ---
    private fun buildConnectChecker() = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.d("AWA", "RTSP: connection started, url=$url")
        }
        override fun onConnectionSuccess() {
            Log.d("AWA", "RTSP: client connected")
            mainHandler.post { _isPreviewActive.value = true }
        }
        override fun onConnectionFailed(reason: String) {
            Log.e("AWA", "RTSP: connection failed - $reason")
        }
        override fun onNewBitrate(bitrate: Long) {}
        override fun onDisconnect() {
            Log.d("AWA", "RTSP: client disconnected")
            mainHandler.post { _isPreviewActive.value = false }
        }
        override fun onAuthError() {
            Log.e("AWA", "RTSP: auth error")
        }
        override fun onAuthSuccess() {
            Log.d("AWA", "RTSP: auth success")
        }
    }

    fun attachPreviewSurface(context: Context, owner: LifecycleOwner, preview: PreviewView) {
        appContext = context.applicationContext
        lifecycleOwner = owner
        mjpegView = preview
        if (_streamMode.value == StreamMode.MJPEG) bindCamera()
    }
    fun attachRtspPreviewSurface(view: TextureView) {
        rtspView = view
        try {
            if (genericStream?.isOnPreview == false) {
                genericStream?.startPreview(view)
            }
            startPreviewHideTimer()
        } catch (e: Exception) {
            Log.e("AWA", "RTSP startPreview failed", e)
        }
    }
    fun detachRtspPreviewSurface() {
        rtspView = null
        try {
            if (genericStream?.isOnPreview == true) {
                genericStream?.stopPreview()
            }
        } catch (e: Exception) {
            Log.e("AWA", "RTSP stopPreview failed", e)
        }
    }

    private fun startRtspCamera() {
        val ctx = appContext ?: return
        val s = _settings.value

        val stream = GenericStream(ctx, buildConnectChecker())
        genericStream = stream

        val videoOk = stream.prepareVideo(s.resolution.size.width, s.resolution.size.height, rtspBitrate)
        val audioOk = stream.prepareAudio(48_000,false,64*1024)
        Log.d("AWA", "RTSP prepareVideo=$videoOk prepareAudio=$audioOk")

        if (videoOk && audioOk) {
            stream.startStream(_rtspPushUrl.value)
            Log.d("AWA", "RTSP push started to ${_rtspPushUrl.value}")
        } else {
            Log.e("AWA", "RTSP: prepare failed, not starting stream")
            genericStream = null
        }
    }
    private fun stopRtspCamera() {
        genericStream?.stopStream()
        genericStream = null
        rtspView = null
        _isPreviewActive.value = false
    }
    private fun restartRtspCamera(reason: String) {
        if (genericStream == null) return
        Log.d("AWA", "Restarting RTSP camera — reason: $reason")

        val viewToReattach = rtspView
        stopRtspCamera()
        startRtspCamera()

        if (viewToReattach != null && _showLocalPreview.value) {
            attachRtspPreviewSurface(viewToReattach)
        }
    }





    // --- MJPEG (CameraX) pipeline ---
    private fun bindCamera() {
        val ctx = appContext ?: return
        val owner = lifecycleOwner ?: return
        val preview = mjpegView ?: return
        val s = _settings.value

        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }

            val executor = cameraExecutor ?: Executors.newSingleThreadExecutor().also { cameraExecutor = it }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(s.resolution.aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                        )
                        .setResolutionStrategy(
                            ResolutionStrategy(s.resolution.size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        try {
                            val width = imageProxy.width
                            val height = imageProxy.height
                            val nv21 = imageProxyToNv21(imageProxy)

                            if (jpegViewerCount.get() > 0) {
                                try {
                                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                                    val out = ByteArrayOutputStream()
                                    yuvImage.compressToJpeg(Rect(0, 0, width, height), _settings.value.jpegQuality, out)
                                    val jpegBytes = out.toByteArray()
                                    currentFrame = jpegBytes
                                    VideoStreamServer.latestFrame = jpegBytes
                                } catch (e: Exception) {
                                    Log.e("AWA", "JPEG compression failed", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AWA", "Analyzer callback failed", e)
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(s.lensFacing)
                .build()

            provider.unbindAll()
            mjpegCamera = provider.bindToLifecycle(owner, cameraSelector, previewUseCase, imageAnalysis)
            val exposureState = mjpegCamera!!.cameraInfo.exposureState
            _settings.value = _settings.value.copy(
                exposureRange = exposureState.exposureCompensationRange.lower..exposureState.exposureCompensationRange.upper,
                exposureIndex = exposureState.exposureCompensationIndex
            )
        }, ContextCompat.getMainExecutor(ctx))
    }
    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        cameraProvider = null
        currentFrame = null
        mjpegCamera = null
        mjpegView = null
        lifecycleOwner = null
        VideoStreamServer.latestFrame = null
    }
    private fun imageProxyToNv21(image: androidx.camera.core.ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        var outputOffset = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            outputOffset = ySize
        } else {
            var yOffset = 0
            for (i in 0 until height) {
                yBuffer.position(yOffset)
                yBuffer.get(nv21, outputOffset, width)
                outputOffset += width
                yOffset += yRowStride
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * uvRowStride + col * uvPixelStride
                val uIndex = row * uvRowStride + col * uvPixelStride
                nv21[outputOffset++] = vBuffer.get(vIndex)
                nv21[outputOffset++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }

    // --- Focus ---
    fun toggleFocusMode() {
        val newMode = if (_settings.value.focusMode == FocusMode.AUTO) FocusMode.MANUAL else FocusMode.AUTO
        _settings.value = _settings.value.copy(focusMode = newMode)
        if (newMode == FocusMode.MANUAL) {
            applyManualFocus(_settings.value.focusDistance)
        } else {
            cancelFocusAndMetering()
        }
    }
    fun setFocusDistance(distance: Float) {
        _settings.value = _settings.value.copy(focusDistance = distance)
        if (_settings.value.focusMode == FocusMode.MANUAL) {
            applyManualFocus(distance)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualFocus(dist: Float) {
        val distance = 1f - dist
        if (_streamMode.value == StreamMode.MJPEG){
            val currentCamera = mjpegCamera ?: return

            val camera2Info = Camera2CameraInfo.from(currentCamera.cameraInfo)
            val minFocusDistance = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            ) ?: 0f

            if (minFocusDistance == 0f) return // fixed-focus cameras don't support manual distance

            val focusDiopters = distance * minFocusDistance

            val camera2Control = Camera2CameraControl.from(currentCamera.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
                .build()

            camera2Control.setCaptureRequestOptions(options)
        }
        else{
            val test = genericStream?.videoSource is Camera2Source
            Log.d("AWA", "Manual focus for RTSP not yet verified against GenericStream/Camera2Source API $test")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun cancelFocusAndMetering() {
        if (_streamMode.value == StreamMode.MJPEG) {
            val currentCamera = mjpegCamera ?: return
            val camera2Control = Camera2CameraControl.from(currentCamera.cameraControl)
            camera2Control.clearCaptureRequestOptions()
            currentCamera.cameraControl.cancelFocusAndMetering()
        }
        else{
            // TODO VERIFY: same caveat as applyManualFocus — Camera2Source's
            // equivalent to enableAutoFocus() isn't confirmed yet.
            Log.d("AWA", "Auto focus re-enable for RTSP not yet verified against GenericStream/Camera2Source API")
        }
    }

    fun tapToFocus(x: Float, y: Float, width: Float, height: Float) {
        val cameraControl = mjpegCamera?.cameraControl ?: return
        _settings.value = _settings.value.copy(focusMode = FocusMode.AUTO)

        val factory = SurfaceOrientedMeteringPointFactory(width, height)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        cameraControl.startFocusAndMetering(action)
    }
    fun tapToFocus(view: View, event: MotionEvent) {
        _settings.value = _settings.value.copy(focusMode = FocusMode.AUTO)


    }
    fun flipCamera() {
        val newFacing = if (_settings.value.lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        _settings.value = _settings.value.copy(lensFacing = newFacing)

        when (_streamMode.value) {
            StreamMode.MJPEG -> bindCamera()
            StreamMode.H264_RTSP -> {
                try {
                    (genericStream?.videoSource as? Camera2Source)?.switchCamera()
                } catch (e: Exception) {
                    Log.e("AWA", "RTSP switchCamera failed", e)
                }
            }
        }
    }

    fun setResolution(res: StreamResolution) {
        _settings.value = _settings.value.copy(resolution = res)
        when (_streamMode.value) {
            StreamMode.MJPEG -> {
                if (cameraProvider != null) bindCamera()
            }
            StreamMode.H264_RTSP -> restartRtspCamera("resolution changed to ${res.label}")
        }
    }

    fun setQuality(quality: Int) {
        _settings.value = _settings.value.copy(jpegQuality = quality)
    }

    fun setExposure(index: Int) {
        mjpegCamera?.cameraControl?.setExposureCompensationIndex(index)
        _settings.value = _settings.value.copy(exposureIndex = index)
    }

    override fun onCleared() {
        hideLocalPreview()
        unbindCamera()
        stopRtspCamera()
    }
}