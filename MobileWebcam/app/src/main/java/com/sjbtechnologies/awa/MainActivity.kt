package com.sjbtechnologies.awa

import com.sjbtechnologies.awa.R
import android.Manifest
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var statusText: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var flipButton: ImageButton
    private lateinit var resolutionSpinner: Spinner
    private lateinit var shutterButton: View
    private lateinit var shutterInner: View
    private lateinit var idleOverlay: View
    private lateinit var statusIndicator: View
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsPage: View
    private lateinit var closeSettingsButton: ImageButton
    private lateinit var dimTimeoutSpinner: Spinner
    private lateinit var dimOverlay: View
    
    private lateinit var focusAutoButton: TextView
    private lateinit var focusTapButton: TextView
    private lateinit var focusManualButton: TextView
    private lateinit var focusSeekBar: SeekBar
    
    private var focusMode = 0 // 0: Auto, 1: Tap, 2: Manual
    
    private var dimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dimRunnable = Runnable { showDimOverlay() }
    private var dimTimeoutMs: Long = 0 // 0 means Never
    
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var httpServer: VideoServer? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var isFrontCamera = false
    @Volatile
    private var isServerRunning = false
    @Volatile
    private var currentFrame: ByteArray? = null
    
    private val PORT = 8080

    companion object {
        private const val TAG = "MobileWebcam"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main_simple)

        initViews()
        requestPermissions()
    }

    private fun initViews() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        ipAddressText = findViewById(R.id.ipAddressText)
        shutterButton = findViewById(R.id.shutterButton)
        shutterInner = findViewById(R.id.shutterInner)
        statusIndicator = findViewById(R.id.statusIndicator)
        settingsButton = findViewById(R.id.settingsButton)
        flipButton = findViewById(R.id.flipButton)
        settingsPage = findViewById(R.id.settingsPage)
        closeSettingsButton = findViewById(R.id.closeSettingsButton)
        dimTimeoutSpinner = findViewById(R.id.dimTimeoutSpinner)
        dimOverlay = findViewById(R.id.dimOverlay)
        
        focusAutoButton = findViewById(R.id.focusAutoButton)
        focusTapButton = findViewById(R.id.focusTapButton)
        focusManualButton = findViewById(R.id.focusManualButton)
        focusSeekBar = findViewById(R.id.focusSeekBar)

        focusAutoButton.setOnClickListener { setFocusMode(0) }
        focusTapButton.setOnClickListener { setFocusMode(1) }
        focusManualButton.setOnClickListener { setFocusMode(2) }
        
        focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && focusMode == 2) {
                    updateManualFocus(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Hidden references to keep logic intact
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        idleOverlay = findViewById(R.id.idleOverlay)

        val resolutions = arrayOf("640x480", "1280x720", "1920x1080")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        resolutionSpinner.adapter = resAdapter
        resolutionSpinner.setSelection(prefs.getInt("resolution_pos", 1))

        val dimOptions = arrayOf("Never", "30 seconds", "1 minute", "2 minutes", "5 minutes")
        val dimAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dimOptions)
        dimTimeoutSpinner.adapter = dimAdapter
        dimTimeoutSpinner.setSelection(prefs.getInt("dim_pos", 0))
        dimTimeoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                dimTimeoutMs = when (position) {
                    1 -> 30000L
                    2 -> 60000L
                    3 -> 120000L
                    4 -> 300000L
                    else -> 0L
                }
                prefs.edit().putInt("dim_pos", position).apply()
                resetDimTimer()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("resolution_pos", position).apply()
                // Only re-bind if camera is currently active
                if (camera != null) {
                    bindCameraWithAnalyzer()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        shutterButton.setOnClickListener {
            if (isServerRunning) stopServer() else startServer()
        }

        settingsButton.setOnClickListener { 
            settingsPage.visibility = View.VISIBLE 
        }

        closeSettingsButton.setOnClickListener { 
            settingsPage.visibility = View.GONE
        }

        flipButton.setOnClickListener { flipCamera() }

        dimOverlay.setOnClickListener {
            hideDimOverlay()
        }

        updateUiState(false)
        displayNetworkInfo()
        resetDimTimer()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        resetDimTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetDimTimer() {
        dimHandler.removeCallbacks(dimRunnable)
        if (dimTimeoutMs > 0 && !isDimmed()) {
            dimHandler.postDelayed(dimRunnable, dimTimeoutMs)
        }
    }

    private fun isDimmed(): Boolean = dimOverlay.visibility == View.VISIBLE

    private fun showDimOverlay() {
        if (isServerRunning) { // Only dim if server is running to avoid confusion? 
            // Actually user might want it dimmed even when just previewing.
            dimOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideDimOverlay() {
        dimOverlay.visibility = View.GONE
        resetDimTimer()
    }

    private fun setFocusMode(mode: Int) {
        focusMode = mode
        val activeColor = ContextCompat.getColor(this, android.R.color.white)
        val inactiveColor = ContextCompat.getColor(this, android.R.color.darker_gray)
        val inactiveAlpha = 0.5f
        val activeAlpha = 1.0f
        
        focusAutoButton.setTextColor(if (mode == 0) activeColor else inactiveColor)
        focusAutoButton.alpha = if (mode == 0) activeAlpha else inactiveAlpha

        focusTapButton.setTextColor(if (mode == 1) activeColor else inactiveColor)
        focusTapButton.alpha = if (mode == 1) activeAlpha else inactiveAlpha

        focusManualButton.setTextColor(if (mode == 2) activeColor else inactiveColor)
        focusManualButton.alpha = if (mode == 2) activeAlpha else inactiveAlpha
        
        focusSeekBar.visibility = if (mode == 2) View.VISIBLE else View.GONE
        
        applyCurrentFocusMode()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyCurrentFocusMode() {
        val currentCamera = camera ?: return
        val cameraControl = currentCamera.cameraControl
        val camera2CameraControl = Camera2CameraControl.from(cameraControl)
        
        try {
            val builder = CaptureRequestOptions.Builder()
            when (focusMode) {
                0 -> { // Auto (Continuous)
                    Log.d(TAG, "Setting Focus Mode: Auto (Continuous AF)")
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    camera2CameraControl.captureRequestOptions = builder.build()
                    cameraControl.cancelFocusAndMetering()
                }
                1 -> { // Tap (Single AF)
                    Log.d(TAG, "Setting Focus Mode: Tap (Single AF)")
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    camera2CameraControl.captureRequestOptions = builder.build()
                }
                2 -> { // Manual
                    val progress = focusSeekBar.progress
                    val focusDistance = progress / 10f
                    Log.d(TAG, "Setting Focus Mode: Manual ($focusDistance)")
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                    camera2CameraControl.captureRequestOptions = builder.build()
                    cameraControl.cancelFocusAndMetering()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply focus mode", e)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFocusToBuilders(previewBuilder: Preview.Builder, analysisBuilder: ImageAnalysis.Builder) {
        val previewExtender = Camera2Interop.Extender(previewBuilder)
        val analysisExtender = Camera2Interop.Extender(analysisBuilder)
        
        when (focusMode) {
            0 -> {
                previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                analysisExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            1 -> {
                previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                analysisExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            2 -> {
                val focusDistance = focusSeekBar.progress / 10f
                previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                previewExtender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                analysisExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                analysisExtender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
        }
    }

    private fun updateManualFocus(progress: Int) {
        if (focusMode == 2) {
            applyCurrentFocusMode()
        }
    }

    private fun updateUiState(running: Boolean) {
        isServerRunning = running
        shutterInner.isSelected = running
        
        // Settings and Flip are always available now, but will trigger re-bind if camera is active
        settingsButton.isEnabled = true
        flipButton.isEnabled = true
        
        if (running) {
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_light)
            statusText.text = "LIVE"
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            statusIndicator.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            statusText.text = "READY"
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        
        applyImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        if (isLandscape) {
            windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun displayNetworkInfo() {
        val ips = getLocalIpAddress()
        val info = buildString {
            if (ips.isNotEmpty()) {
                append("WiFi: http://${ips[0]}:$PORT")
            } else {
                append("USB: http://localhost:$PORT (adb forward)")
            }
        }
        ipAddressText.text = info
    }

    private fun getLocalIpAddress(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.contains("wlan") || iface.name.contains("eth")) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr.address.size == 4) {
                            ips.add(addr.hostAddress ?: "")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return ips
    }

    private fun requestPermissions() {
        Dexter.withContext(this)
            .withPermissions(Manifest.permission.CAMERA)
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {
                        updateStatus("READY")
                        displayNetworkInfo()
                        startServer() // Auto-start the server once permissions are ready
                    } else {
                        Toast.makeText(this@MainActivity, "Camera permission required!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                override fun onPermissionRationaleShouldBeShown(
                    permissions: MutableList<PermissionRequest>,
                    token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            }).check()
    }

    private fun startCameraPreviewOnly() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            val resolutionStr = resolutionSpinner.selectedItem.toString()
            val (width, height) = resolutionStr.split("x").map { it.toInt() }
            val aspectRatio = if (width.toDouble() / height.toDouble() > 1.5) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

            val resSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .setResolutionStrategy(ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resSelector)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startServer() {
        try {
            httpServer = VideoServer(PORT)
            httpServer?.start()
            
            // Start Foreground Service
            val serviceIntent = Intent(this, WebcamService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            updateUiState(true)
            updateStatus("LIVE")
            
            Log.d(TAG, "HTTP Server started on port $PORT (Camera IDLE)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting server", e)
            updateStatus("ERROR")
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        httpServer?.stop()
        httpServer = null
        
        // Stop Foreground Service
        stopService(Intent(this, WebcamService::class.java))

        // Unbind camera entirely when stopping server
        cameraProvider?.unbindAll()
        camera = null
        currentFrame = null
        
        updateUiState(false)
        updateStatus("STOPPED")
    }

    private fun bindCameraWithAnalyzer() {
        val cameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val resolutionStr = resolutionSpinner.selectedItem.toString()
        val (width, height) = resolutionStr.split("x").map { it.toInt() }
        val aspectRatio = if (width.toDouble() / height.toDouble() > 1.5) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

        val resSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(resSelector)
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        // Ensure the initial session starts with the correct focus mode
        applyFocusToBuilders(previewBuilder, analysisBuilder)

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        previewView.setOnTouchListener { v, event ->
            if (focusMode == 1 && event.action == android.view.MotionEvent.ACTION_UP) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                Log.d(TAG, "Triggering tap-to-focus")
                camera?.cameraControl?.startFocusAndMetering(action)
                v.performClick()
                true
            } else false
        }

        val imageAnalyzer = analysisBuilder.build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if ((httpServer?.connectionCount ?: 0) > 0) {
                        processImage(imageProxy)
                    }
                    imageProxy.close()
                }
            }

        try {
            if (cameraProvider == null) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                cameraProvider = cameraProviderFuture.get()
            }
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            // Re-apply focus mode settings to the new session
            previewView.post {
                applyCurrentFocusMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Primary binding failed, attempting fallback", e)
            try {
                // Fallback: 720p 16:9 for both if 1080p failed
                val fallbackSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                    .build()
                
                val fallbackPreview = Preview.Builder().setResolutionSelector(fallbackSelector).build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val fallbackAnalyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(fallbackSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build().also {
                        it.setAnalyzer(cameraExecutor) { proxy -> processImage(proxy); proxy.close() }
                    }
                
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, cameraSelector, fallbackPreview, fallbackAnalyzer)
                applyCurrentFocusMode()
                
                runOnUiThread {
                    Toast.makeText(this, "High-res failed. Using 720p fallback.", Toast.LENGTH_SHORT).show()
                }
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Critical fallback failed", fallbackEx)
                // Ultimate fallback: Just Preview at default resolution
                try {
                    cameraProvider?.unbindAll()
                    camera = cameraProvider?.bindToLifecycle(this, cameraSelector, Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) })
                } catch (finalEx: Exception) {
                    runOnUiThread { Toast.makeText(this, "Camera error: ${finalEx.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height
        
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        // NV21 size is width * height * 1.5
        val nv21 = ByteArray(ySize + (ySize / 2))
        
        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave V and U planes (NV21 is YYYY VUVU)
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        var pos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                // V is at (row * rowStride) + (col * pixelStride)
                nv21[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                nv21[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        currentFrame = out.toByteArray()
    }

    private fun flipCamera() {
        val popup = PopupMenu(this, flipButton)
        popup.menu.add(0, 0, 0, "Back Camera")
        popup.menu.add(0, 1, 1, "Front Camera")
        
        popup.setOnMenuItemClickListener { item ->
            val targetFront = item.itemId == 1
            if (isFrontCamera != targetFront) {
                isFrontCamera = targetFront
                // Re-bind if camera is currently active
                if (camera != null) {
                    bindCameraWithAnalyzer()
                }
                Toast.makeText(this, "Switched to ${item.title}", Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    inner class VideoServer(port: Int) : NanoHTTPD(port) {
        @Volatile
        var connectionCount = 0

        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/video" -> {
                    // Lazy start camera hardware on first connection
                    synchronized(this) {
                        if (connectionCount == 0) {
                            runOnUiThread {
                                bindCameraWithAnalyzer()
                            }
                        }
                        connectionCount++
                    }

                    val mjpegInputStream = object : java.io.InputStream() {
                        private var buffer: java.io.ByteArrayInputStream? = null
                        private var isClosed = false
                        
                        override fun read(): Int {
                            if (isClosed) return -1
                            
                            while (buffer == null || buffer?.available() == 0) {
                                if (isClosed) return -1
                                val frame = currentFrame
                                if (frame == null) {
                                    try { Thread.sleep(50) } catch (e: Exception) { return -1 }
                                    continue
                                }
                                
                                try {
                                    val header = "--jpgboundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                                    val outputStream = ByteArrayOutputStream()
                                    outputStream.write(header.toByteArray())
                                    outputStream.write(frame)
                                    outputStream.write("\r\n".toByteArray())
                                    buffer = java.io.ByteArrayInputStream(outputStream.toByteArray())
                                } catch (e: Exception) {
                                    return -1
                                }
                            }
                            return buffer?.read() ?: -1
                        }

                        override fun close() {
                            if (!isClosed) {
                                isClosed = true
                                synchronized(this@VideoServer) {
                                    connectionCount--
                                    if (connectionCount <= 0) {
                                        connectionCount = 0
                                        runOnUiThread {
                                            cameraProvider?.unbindAll()
                                            camera = null
                                            currentFrame = null
                                        }
                                    }
                                }
                            }
                            super.close()
                        }
                    }
                    
                    val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--jpgboundary", mjpegInputStream)
                    response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    response.addHeader("Pragma", "no-cache")
                    response.addHeader("Expires", "0")
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    return response
                }
                "/" -> {
                    val streamUrl = "/video"
                    newFixedLengthResponse("""
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>AWA Webcam</title>
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <style>
                                body { background: #0b0e14; color: #e2e8f0; text-align: center; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 20px; }
                                .container { margin: 20px auto; max-width: 960px; border: 1px solid #2d3748; border-radius: 12px; overflow: hidden; background: #000; box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.5); }
                                img { width: 100%; height: auto; display: block; min-height: 200px; }
                                .status { display: inline-block; padding: 4px 12px; border-radius: 20px; font-size: 12px; font-weight: bold; margin-bottom: 10px; }
                                .status-live { background: #c53030; color: white; animation: pulse 2s infinite; }
                                @keyframes pulse { 0% { opacity: 1; } 50% { opacity: 0.6; } 100% { opacity: 1; } }
                                code { background: #1a202c; padding: 2px 6px; border-radius: 4px; color: #6366f1; }
                            </style>
                        </head>
                        <body>
                            <h1 style="color: #6366F1; margin-bottom: 5px;">AWA</h1>
                            <div class="status status-live" id="connectionStatus">CONNECTING...</div>
                            
                            <div class="container">
                                <img id="stream" src="$streamUrl" alt="Camera Stream">
                            </div>
                            
                            <p style="color: #a0aec0; margin-top: 20px;">Stream URL: <code>http://${session.headers["host"]}$streamUrl</code></p>

                            <script>
                                const img = document.getElementById('stream');
                                const status = document.getElementById('connectionStatus');
                                const baseUrl = "$streamUrl";
                                
                                function handleDisconnect() {
                                    status.textContent = "RECONNECTING...";
                                    status.style.background = "#4a5568";
                                    setTimeout(() => {
                                        img.src = baseUrl + "?t=" + new Date().getTime();
                                    }, 2000);
                                }

                                img.onerror = handleDisconnect;
                                img.onload = () => {
                                    status.textContent = "LIVE STREAM";
                                    status.style.background = "#c53030";
                                };

                                // Initial trigger to ensure we start trying immediately
                                if (!img.complete || img.naturalWidth === 0) {
                                    handleDisconnect();
                                }
                            </script>
                        </body>
                        </html>
                    """.trimIndent())
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        cameraExecutor.shutdown()
    }
}