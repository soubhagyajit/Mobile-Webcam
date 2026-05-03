package com.sjbtechnologies.awa

import com.sjbtechnologies.awa.R
import android.Manifest
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
    private lateinit var settingsOverlay: View
    private lateinit var settingsPanel: View
    private lateinit var closeSettingsButton: ImageButton
    private lateinit var dimTimeoutSpinner: Spinner
    private lateinit var saveExposureSwitch: Switch
    private lateinit var qualitySeekBar: SeekBar
    private lateinit var qualityLabel: TextView
    private lateinit var dimOverlay: View
    private lateinit var focusRing: ImageView
    private lateinit var gridButton: TextView
    private lateinit var gridLines: View
    
    private lateinit var expButton: TextView
    private lateinit var focusToggleButton: TextView
    private lateinit var focusContainer: View
    private lateinit var focusSeekBar: SeekBar
    private lateinit var exposureContainer: View
    private lateinit var exposureSeekBar: SeekBar
    private lateinit var gestureDetector: android.view.GestureDetector
    private lateinit var scaleGestureDetector: android.view.ScaleGestureDetector
    
    private var focusMode = 0 // 0: Auto, 1: Tap, 2: Manual
    private var streamQuality = 70
    
    private var dimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dimRunnable = Runnable { showDimOverlay() }
    private var dimTimeoutMs: Long = 0 // 0 means Never
    
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var httpServer: VideoServer? = null
    private var preview: Preview? = null
    
    private var isFrontCamera = false
    @Volatile
    private var isServerRunning = false
    @Volatile
    private var currentFrame: ByteArray? = null
    
    // Pre-allocated buffers for zero-copy (or reduced copy) image processing
    private var nv21Buffer: ByteArray? = null
    private var yBytes: ByteArray? = null
    private var uBytes: ByteArray? = null
    private var vBytes: ByteArray? = null
    private val jpegOutputStream = ByteArrayOutputStream()
    
    private val PORT = 8080

    companion object {
        private const val TAG = "MobileWebcam"
        var activeServer: NanoHTTPD? = null
        var activeExecutor: ExecutorService? = null
        var instance: MainActivity? = null

        fun stopServerGlobally(context: android.content.Context) {
            val activity = instance
            if (activity != null) {
                activity.runOnUiThread {
                    activity.stopServer()
                }
            } else {
                activeServer?.stop()
                activeServer = null
                activeExecutor?.shutdown()
                context.stopService(Intent(context, WebcamService::class.java))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        
        activeExecutor?.shutdown()
        cameraExecutor = Executors.newSingleThreadExecutor()
        activeExecutor = cameraExecutor
        
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
        settingsOverlay = findViewById(R.id.settingsOverlay)
        settingsPanel = findViewById(R.id.settingsPanel)
        closeSettingsButton = findViewById(R.id.closeSettingsButton)
        dimTimeoutSpinner = findViewById(R.id.dimTimeoutSpinner)
        saveExposureSwitch = findViewById(R.id.saveExposureSwitch)
        qualitySeekBar = findViewById(R.id.qualitySeekBar)
        qualityLabel = findViewById(R.id.qualityLabel)
        dimOverlay = findViewById(R.id.dimOverlay)
        focusRing = findViewById(R.id.focusRing)
        gridButton = findViewById(R.id.gridButton)
        gridLines = findViewById(R.id.gridLines)
        
        expButton = findViewById(R.id.expButton)
        focusToggleButton = findViewById(R.id.focusToggleButton)
        focusContainer = findViewById(R.id.focusContainer)
        focusSeekBar = findViewById(R.id.focusSeekBar)
        exposureContainer = findViewById(R.id.exposureContainer)
        exposureSeekBar = findViewById(R.id.exposureSeekBar)
        focusSeekBar.max = 1000
        
        expButton.setOnClickListener {
            exposureContainer.visibility = if (exposureContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        scaleGestureDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })
        
        gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                handleFocusTap(e.x, e.y, lock = false)
                return true
            }
            override fun onLongPress(e: android.view.MotionEvent) {
                handleFocusTap(e.x, e.y, lock = true)
                Toast.makeText(this@MainActivity, "AE/AF Locked", Toast.LENGTH_SHORT).show()
            }
        })

        focusToggleButton.setOnClickListener {
            when (focusMode) {
                0 -> setFocusMode(2) // A -> M
                2 -> setFocusMode(0) // M -> A
                1 -> setFocusMode(0) // Tap -> A
            }
        }
        
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

        updateResolutionSpinner(isFrontCamera)

        val dimOptions = arrayOf("Never", "30 seconds", "1 minute", "2 minutes", "5 minutes")
        val dimAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dimOptions)
        dimTimeoutSpinner.adapter = dimAdapter
        
        streamQuality = prefs.getInt("stream_quality", 70)
        qualitySeekBar.progress = streamQuality - 10
        qualityLabel.text = "Stream Quality ($streamQuality%)"

        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val q = progress + 10
                qualityLabel.text = "Stream Quality ($q%)"
                if (fromUser) {
                    streamQuality = q
                    prefs.edit().putInt("stream_quality", q).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val savedDimPos = prefs.getInt("dim_pos", 0)
        dimTimeoutMs = when (savedDimPos) {
            1 -> 30000L
            2 -> 60000L
            3 -> 120000L
            4 -> 300000L
            else -> 0L
        }
        dimTimeoutSpinner.setSelection(savedDimPos)
        
        saveExposureSwitch.isChecked = prefs.getBoolean("save_exposure", true)
        saveExposureSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("save_exposure", isChecked).apply()
        }
        
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
                setResolution(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        shutterButton.setOnClickListener {
            if (isServerRunning) stopServer() else startServer()
        }

        gridButton.setOnClickListener {
            gridLines.visibility = if (gridLines.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            gridButton.setTextColor(if (gridLines.visibility == View.VISIBLE) 
                ContextCompat.getColor(this, android.R.color.holo_blue_light) 
            else 
                ContextCompat.getColor(this, android.R.color.white))
        }

        settingsButton.setOnClickListener { 
            openSettings()
        }

        closeSettingsButton.setOnClickListener { 
            closeSettings()
        }
        
        settingsOverlay.setOnClickListener {
            closeSettings()
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
        dimOverlay.visibility = View.VISIBLE
        // Detach surface to pause rendering and save power/heat
        preview?.setSurfaceProvider(null)
        // Set screen brightness to minimum to save power
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
    }

    private fun hideDimOverlay() {
        dimOverlay.visibility = View.GONE
        // Reattach surface to resume preview
        preview?.setSurfaceProvider(previewView.surfaceProvider)
        // Restore default screen brightness
        val params = window.attributes
        params.screenBrightness = -1.0f
        window.attributes = params
        resetDimTimer()
    }

    private fun openSettings() {
        settingsOverlay.visibility = View.VISIBLE
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val displayWidth = resources.displayMetrics.widthPixels
        
        // Portrait = 100% width, Landscape = 50% width
        val panelWidth = if (isLandscape) displayWidth / 2 else displayWidth
        settingsPanel.layoutParams.width = panelWidth
        settingsPanel.requestLayout()
        
        // Animate slide in from the right edge
        settingsPanel.translationX = panelWidth.toFloat()
        settingsPanel.animate()
            .translationX(0f)
            .setDuration(250)
            .start()
    }

    private fun closeSettings() {
        val panelWidth = settingsPanel.width.toFloat()
        settingsPanel.animate()
            .translationX(panelWidth)
            .setDuration(200)
            .withEndAction { settingsOverlay.visibility = View.GONE }
            .start()
    }

    private fun setFocusMode(mode: Int) {
        if (focusMode != mode) {
            // Clear any active focus locks when switching modes
            camera?.cameraControl?.cancelFocusAndMetering()
        }
        
        focusMode = mode
        
        focusToggleButton.text = when (mode) {
            0 -> "FOC: A"
            1 -> "FOC: TAP"
            2 -> "FOC: M"
            else -> "FOC: A"
        }
        
        focusContainer.visibility = if (mode == 2) View.VISIBLE else View.GONE
        
        applyCurrentFocusMode()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyCurrentFocusMode() {
        val currentCamera = camera ?: return
        val camera2CameraControl = Camera2CameraControl.from(currentCamera.cameraControl)
        
        try {
            if (focusMode == 2) {
                // Manual mode: explicitly override AF_MODE and set focus distance via Camera2Interop
                val builder = CaptureRequestOptions.Builder()
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val focusDistance = focusSeekBar.progress / 100f
                Log.d(TAG, "Applying Real-time Focus: Manual ($focusDistance)")
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                camera2CameraControl.captureRequestOptions = builder.build()
            } else {
                // Auto/Tap mode: Clear interop overrides to let CameraX handle the state machine natively
                camera2CameraControl.clearCaptureRequestOptions()
                
                if (focusMode == 0) {
                    Log.d(TAG, "Applying Real-time Focus: Auto (Continuous)")
                    // Cancel any active tap-focus metering to restore continuous auto-focus
                    currentCamera.cameraControl.cancelFocusAndMetering()
                } else {
                    Log.d(TAG, "Applying Real-time Focus: Tap/Manual-AF")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply focus mode", e)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFocusToBuilders(previewBuilder: Preview.Builder, analysisBuilder: ImageAnalysis.Builder) {
        // We no longer set focus-specific options here to avoid conflicts with Camera2CameraControl
        // during real-time updates. Focus is handled in applyCurrentFocusMode().
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
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
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        Dexter.withContext(this)
            .withPermissions(permissions)
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    // We primarily care about the CAMERA permission to proceed.
                    // If POST_NOTIFICATIONS is denied, the user just won't see the foreground icon on Android 13+,
                    // but the server can still run (though it might get killed more easily by the OS).
                    var cameraGranted = false
                    report.grantedPermissionResponses.forEach {
                        if (it.permissionName == Manifest.permission.CAMERA) {
                            cameraGranted = true
                        }
                    }

                    if (cameraGranted) {
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
            activeServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active server", e)
        }
        activeServer = null

        try {
            httpServer = VideoServer(PORT)
            activeServer = httpServer
            httpServer?.start()
            
            // Start Foreground Service
            val serviceIntent = Intent(this, WebcamService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            updateKeepScreenOn(true)
            updateUiState(true)
            updateStatus("LIVE")
            
            Log.d(TAG, "HTTP Server started on port $PORT (Camera IDLE)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting server", e)
            updateStatus("ERROR")
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        activeServer = null
        
        // Stop Foreground Service
        stopService(Intent(this, WebcamService::class.java))

        // Unbind camera entirely when stopping server
        cameraProvider?.unbindAll()
        camera = null
        currentFrame = null
        
        updateKeepScreenOn(false)
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

        preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
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
            
            // Use WebcamService instance as LifecycleOwner if available so stream survives backgrounding
            val lifecycleOwner = WebcamService.instance ?: this
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            
            // Setup exposure slider
            camera?.cameraInfo?.exposureState?.let { exposureState ->
                val range = exposureState.exposureCompensationRange
                if (range.lower != range.upper) {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    val shouldSave = prefs.getBoolean("save_exposure", true)
                    val savedExp = if (shouldSave) prefs.getInt("last_exposure", 0) else 0
                    val clampedExp = savedExp.coerceIn(range.lower, range.upper)
                    
                    camera?.cameraControl?.setExposureCompensationIndex(clampedExp)
                    
                    runOnUiThread {
                        expButton.visibility = View.VISIBLE
                        // Hide by default, user can toggle it via expButton
                        exposureContainer.visibility = View.GONE
                        exposureSeekBar.max = range.upper - range.lower
                        exposureSeekBar.progress = clampedExp - range.lower
                        
                        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                if (fromUser) {
                                    val index = progress + range.lower
                                    camera?.cameraControl?.setExposureCompensationIndex(index)
                                    if (prefs.getBoolean("save_exposure", true)) {
                                        prefs.edit().putInt("last_exposure", index).apply()
                                    }
                                }
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                        })
                    }
                } else {
                    runOnUiThread { 
                        expButton.visibility = View.GONE
                        exposureContainer.visibility = View.GONE 
                    }
                }
            }

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
                val lifecycleOwner = WebcamService.instance ?: this
                camera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, fallbackPreview, fallbackAnalyzer)
                applyCurrentFocusMode()
                
                runOnUiThread {
                    Toast.makeText(this, "High-res failed. Using 720p fallback.", Toast.LENGTH_SHORT).show()
                }
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Critical fallback failed", fallbackEx)
                // Ultimate fallback: Just Preview at default resolution
                try {
                    cameraProvider?.unbindAll()
                    val lifecycleOwner = WebcamService.instance ?: this
                    camera = cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) })
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

        val ySizeRaw = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Tightly packed NV21 size is width * height * 1.5
        val nv21Size = width * height + (width * height / 2)
        
        if (nv21Buffer == null || nv21Buffer!!.size != nv21Size) {
            nv21Buffer = ByteArray(nv21Size)
        }
        val nv21 = nv21Buffer!!
        
        // Extract Y plane into pre-allocated buffer
        if (yBytes == null || yBytes!!.size != ySizeRaw) {
            yBytes = ByteArray(ySizeRaw)
        }
        yBuffer.get(yBytes!!)
        val yArr = yBytes!!
        
        val yRowStride = yPlane.rowStride
        
        // Copy Y plane, stripping padding if present
        if (yRowStride == width) {
            System.arraycopy(yArr, 0, nv21, 0, width * height)
        } else {
            var yPos = 0
            for (row in 0 until height) {
                System.arraycopy(yArr, row * yRowStride, nv21, yPos, width)
                yPos += width
            }
        }
        
        // Detect if device is rotated to reverse landscape (180 degree flip)
        val rotation = windowManager.defaultDisplay.rotation
        val shouldFlip180 = (rotation == android.view.Surface.ROTATION_270)
        
        if (shouldFlip180) {
            // Flip Y plane in-place
            val yEnd = width * height
            for (i in 0 until yEnd / 2) {
                val temp = nv21[i]
                nv21[i] = nv21[yEnd - 1 - i]
                nv21[yEnd - 1 - i] = temp
            }
        }

        // Bulk extract U and V into pre-allocated byte arrays to avoid slow DirectByteBuffer per-pixel access
        if (uBytes == null || uBytes!!.size != uSize) uBytes = ByteArray(uSize)
        if (vBytes == null || vBytes!!.size != vSize) vBytes = ByteArray(vSize)
        
        uBuffer.get(uBytes!!)
        vBuffer.get(vBytes!!)
        
        val uArr = uBytes!!
        val vArr = vBytes!!

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        if (shouldFlip180) {
            // Write UV data backwards to complete the 180 flip
            var pos = nv21Size - 2
            for (row in 0 until height / 2) {
                var vPos = row * vRowStride
                var uPos = row * uRowStride
                for (col in 0 until width / 2) {
                    nv21[pos] = vArr[vPos]
                    nv21[pos + 1] = uArr[uPos]
                    pos -= 2
                    vPos += vPixelStride
                    uPos += uPixelStride
                }
            }
        } else {
            // Standard UV write
            var pos = width * height // Start of U/V interleaved data
            for (row in 0 until height / 2) {
                var vPos = row * vRowStride
                var uPos = row * uRowStride
                for (col in 0 until width / 2) {
                    nv21[pos++] = vArr[vPos]
                    nv21[pos++] = uArr[uPos]
                    vPos += vPixelStride
                    uPos += uPixelStride
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        jpegOutputStream.reset() // Reuse the stream instead of allocating a new one
        yuvImage.compressToJpeg(Rect(0, 0, width, height), streamQuality, jpegOutputStream)
        currentFrame = jpegOutputStream.toByteArray()
    }

    private fun updateResolutionSpinner(front: Boolean) {
        val manager = getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (front) facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//            println(map)
            val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()
            val videoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java) ?: emptyArray()
            
            // Filter to only include sizes that are officially supported for continuous video recording
            val validSizes = yuvSizes.filter { yuvSize -> 
                videoSizes.any { it.width == yuvSize.width && it.height == yuvSize.height }
            }
            val resolutions = if (validSizes.isNotEmpty()) validSizes else yuvSizes.toList()
            
            // Common useful resolutions or all if needed. Let's filter to keep it sane or just show top 10
            val resList = resolutions.map { "${it.width}x${it.height}" }.distinct()
                .sortedByDescending { 
                    val parts = it.split("x")
                    parts[0].toInt() * parts[1].toInt()
                }
                .take(15)

            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val savedRes = prefs.getString("resolution_str_${if(front) "f" else "b"}", "1280x720")

            val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resList)
            resolutionSpinner.adapter = resAdapter
            
            val index = resList.indexOf(savedRes)
            if (index >= 0) {
                resolutionSpinner.setSelection(index)
            } else {
                // Default to something sensible if not found
                val fallbackIndex = resList.indexOfFirst { it.startsWith("1280x") }
                resolutionSpinner.setSelection(if (fallbackIndex >= 0) fallbackIndex else 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating resolutions", e)
        }
    }

    private fun setResolution(position: Int) {
        val resStr = resolutionSpinner.adapter.getItem(position).toString()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit().putString("resolution_str_${if(isFrontCamera) "f" else "b"}", resStr).apply()
        
        runOnUiThread {
            if (resolutionSpinner.selectedItemPosition != position) {
                resolutionSpinner.setSelection(position)
            }
            // Only re-bind if camera is currently active
            if (camera != null) {
                bindCameraWithAnalyzer()
            }
        }
    }

    private fun setResolutionByString(resStr: String) {
        val adapter = resolutionSpinner.adapter
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == resStr) {
                runOnUiThread { setResolution(i) }
                return
            }
        }
    }

    private fun flipCamera() {
        val popup = PopupMenu(this, flipButton)
        popup.menu.add(0, 0, 0, "Back Camera")
        popup.menu.add(0, 1, 1, "Front Camera")
        
        popup.setOnMenuItemClickListener { item ->
            setCamera(item.itemId == 1)
            true
        }
        popup.show()
    }

    private fun setCamera(front: Boolean) {
        if (isFrontCamera != front) {
            isFrontCamera = front
            runOnUiThread {
                updateResolutionSpinner(front)
                // Re-bind if camera is currently active
                if (camera != null) {
                    bindCameraWithAnalyzer()
                }
                Toast.makeText(this, "Switched to ${if (front) "Front" else "Back"} Camera", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateKeepScreenOn(keep: Boolean) {
        runOnUiThread {
            if (keep) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun triggerCenterFocus() {
        val width = previewView.width.toFloat()
        val height = previewView.height.toFloat()
        if (width > 0 && height > 0) {
            val factory = previewView.meteringPointFactory
            val centerPoint = factory.createPoint(width / 2f, height / 2f)
            val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            camera?.cameraControl?.startFocusAndMetering(action)
            showFocusRing(width / 2f, height / 2f)
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        if (settingsOverlay.visibility == View.VISIBLE || isDimmed()) return

        focusRing.translationX = x - focusRing.width / 2
        focusRing.translationY = y - focusRing.height / 2
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.2f
        focusRing.scaleY = 1.2f

        focusRing.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(0f)
            .setDuration(500)
            .withEndAction { focusRing.visibility = View.INVISIBLE }
            .start()
    }

    private fun handleFocusTap(x: Float, y: Float, lock: Boolean) {
        showFocusRing(x, y)
        if (focusMode == 0) {
            setFocusMode(1) // Switch to Tap mode on touch
        }
        if (focusMode == 1) {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            if (lock) {
                builder.disableAutoCancel()
                Log.d(TAG, "Triggering tap-to-focus (LOCKED)")
            } else {
                builder.setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                Log.d(TAG, "Triggering tap-to-focus (AUTO-CANCEL)")
            }
            camera?.cameraControl?.startFocusAndMetering(builder.build())
        }
    }

    inner class VideoServer(port: Int) : NanoHTTPD(port) {
        @Volatile
        var connectionCount = 0

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.uri == "/video" -> {
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
                        
                        private fun loadNextFrame(): Boolean {
                            val frame = currentFrame
                            if (frame == null) {
                                try { Thread.sleep(10) } catch (e: Exception) { return false }
                                return true
                            }
                            try {
                                val header = "--jpgboundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                                val outputStream = ByteArrayOutputStream(header.length + frame.size + 4)
                                outputStream.write(header.toByteArray())
                                outputStream.write(frame)
                                outputStream.write("\r\n".toByteArray())
                                buffer = java.io.ByteArrayInputStream(outputStream.toByteArray())
                                return true
                            } catch (e: Exception) {
                                return false
                            }
                        }

                        override fun read(): Int {
                            if (isClosed) return -1
                            while (buffer == null || buffer?.available() == 0) {
                                if (isClosed) return -1
                                if (!loadNextFrame()) return -1
                            }
                            return buffer?.read() ?: -1
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (isClosed) return -1
                            while (buffer == null || buffer?.available() == 0) {
                                if (isClosed) return -1
                                if (!loadNextFrame()) return -1
                            }
                            return buffer?.read(b, off, len) ?: -1
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
                session.uri == "/help" -> {
                    val helpText = """
                        AWA Remote Control - API Guide
                        
                        Endpoints:
                        1. /video
                           - MJPEG video stream.
                           - Example: <img src="http://192.168.1.x:8080/video">
                        
                        2. /features
                           - Returns JSON with camera capabilities (resolutions, focus support, exposure range, zoom limit).
                           - Example: fetch('/features').then(r => r.json())
                        
                        3. /settings
                           - Returns JSON with current active settings.
                           - Example: fetch('/settings').then(r => r.json())
                        
                        4. /control
                           - Modifies camera settings via query parameters.
                           - Supported params: focus_mode (0=Auto, 1=Tap, 2=Manual), focus_distance (0-1000), exposure_index (int), zoom (float), stream_quality (10-100), flip (true/false), resolution_str (e.g. "1920x1080").
                           - Example: fetch('/control?zoom=2.0&focus_mode=2&focus_distance=500')
                           
                        5. /
                           - Web Dashboard UI.
                    """.trimIndent()
                    val response = newFixedLengthResponse(Response.Status.OK, "text/plain", helpText)
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    return response
                }
                session.uri == "/settings" -> {
                    val resStr = resolutionSpinner.selectedItem?.toString() ?: "Unknown"
                    val expIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                    val zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
                    val settings = """
                        {
                            "focus_mode": $focusMode,
                            "focus_distance": ${focusSeekBar.progress},
                            "exposure_index": $expIndex,
                            "zoom": $zoomRatio,
                            "stream_quality": $streamQuality,
                            "flip": $isFrontCamera,
                            "resolution_str": "$resStr"
                        }
                    """.trimIndent()
                    val response = newFixedLengthResponse(Response.Status.OK, "application/json", settings)
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    return response
                }
                session.uri == "/features" -> {
                    val features = getCameraFeatures(isFrontCamera)
                    val response = newFixedLengthResponse(Response.Status.OK, "application/json", features)
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    return response
                }
                session.uri == "/control" -> {
                    val params = session.parameters
                    
                    params["focus_mode"]?.firstOrNull()?.toIntOrNull()?.let { mode ->
                        runOnUiThread { 
                            setFocusMode(mode) 
                            if (mode == 1) triggerCenterFocus()
                        }
                    }
                    
                    params["focus_distance"]?.firstOrNull()?.toIntOrNull()?.let { dist ->
                        runOnUiThread { 
                            focusSeekBar.progress = dist
                            updateManualFocus(dist)
                        }
                    }

                    params["exposure_index"]?.firstOrNull()?.toIntOrNull()?.let { exp ->
                        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                        if (prefs.getBoolean("save_exposure", true)) {
                            prefs.edit().putInt("last_exposure", exp).apply()
                        }
                        camera?.cameraControl?.setExposureCompensationIndex(exp)
                        runOnUiThread {
                            camera?.cameraInfo?.exposureState?.let { state ->
                                val lower = state.exposureCompensationRange.lower
                                exposureSeekBar.progress = exp - lower
                            }
                        }
                    }

                    params["zoom"]?.firstOrNull()?.toFloatOrNull()?.let { zoom ->
                        camera?.cameraControl?.setZoomRatio(zoom)
                    }

                    params["stream_quality"]?.firstOrNull()?.toIntOrNull()?.let { q ->
                        val validQ = q.coerceIn(10, 100)
                        streamQuality = validQ
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("stream_quality", validQ).apply()
                        runOnUiThread {
                            qualitySeekBar.progress = validQ - 10
                            qualityLabel.text = "Stream Quality ($validQ%)"
                        }
                    }

                    params["flip"]?.firstOrNull()?.toBoolean()?.let { front ->
                        runOnUiThread { setCamera(front) }
                    }

                    params["resolution_str"]?.firstOrNull()?.let { res ->
                        runOnUiThread { setResolutionByString(res) }
                    }

                    val jsonResponse = """{"status":"ok"}"""
                    val response = newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse)
                    response.addHeader("Access-Control-Allow-Origin", "*")
                    return response
                }
                session.uri == "/" -> {
                    val streamUrl = "/video"
                    newFixedLengthResponse("""
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <title>AWA Remote Control</title>
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <style>
                                body { background: #0b0e14; color: #e2e8f0; text-align: center; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 20px; }
                                .container { margin: 20px auto; max-width: 960px; border: 1px solid #2d3748; border-radius: 12px; overflow: hidden; background: #000; box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.5); position: relative; }
                                img { width: 100%; height: auto; display: block; min-height: 200px; }
                                .controls { background: #1a202c; padding: 20px; border-radius: 12px; max-width: 960px; margin: 20px auto; display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; text-align: left; }
                                .control-group { border: 1px solid #2d3748; padding: 15px; border-radius: 8px; }
                                .control-group h3 { margin-top: 0; color: #6366f1; font-size: 14px; text-transform: uppercase; }
                                .control-group p { font-size: 12px; color: #718096; margin-top: 5px; }
                                button { background: #2d3748; color: white; border: none; padding: 8px 16px; border-radius: 6px; cursor: pointer; margin: 4px; transition: background 0.2s; }
                                button:hover { background: #4a5568; }
                                button.active { background: #6366f1; }
                                button.danger { background: #c53030; }
                                button.danger:hover { background: #9b2c2c; }
                                button:disabled { opacity: 0.3; cursor: not-allowed; }
                                input[type=range] { width: 100%; margin-top: 10px; }
                                input[type=range]:disabled { opacity: 0.3; }
                                .status { display: inline-block; padding: 4px 12px; border-radius: 20px; font-size: 12px; font-weight: bold; margin-bottom: 10px; }
                                .status-live { background: #c53030; color: white; }
                            </style>
                        </head>
                        <body>
                            <h1 style="color: #6366F1; margin-bottom: 5px;">AWA REMOTE</h1>
                            <div class="status status-live" id="connectionStatus">CONNECTING...</div>
                            
                            <div class="container" id="videoContainer">
                                <img id="stream" alt="Camera Stream">
                            </div>
                            
                            <button id="togglePreviewBtn" class="danger" style="margin-bottom: 20px;" onclick="togglePreview()">Start Preview</button>

                            <div class="controls">
                                <div class="control-group">
                                    <h3>Focus Mode</h3>
                                    <button onclick="ctrl('focus_mode=0', this)" class="focus-btn" id="f0">Auto</button>
                                    <button onclick="ctrl('focus_mode=2', this)" class="focus-btn" id="f2">Manual</button>
                                    <p id="focusSupport">Checking support...</p>
                                </div>
                                <div class="control-group">
                                    <h3>Manual Focus</h3>
                                    <input type="range" id="focusDist" min="0" max="1000" value="0" oninput="ctrl('focus_mode=2&focus_distance=' + this.value)">
                                </div>
                                <div class="control-group">
                                    <h3>Exposure</h3>
                                    <input type="range" id="exposureDist" min="0" max="0" value="0" oninput="ctrl('exposure_index=' + this.value)">
                                </div>
                                <div class="control-group">
                                    <h3>Zoom: <span id="zVal">1.0</span>x</h3>
                                    <input type="range" id="zoomDist" min="1.0" max="1.0" step="0.1" value="1.0" oninput="document.getElementById('zVal').innerText=this.value; ctrl('zoom=' + this.value)">
                                </div>
                                <div class="control-group">
                                    <h3>Stream Quality: <span id="qVal">70</span>%</h3>
                                    <input type="range" id="qualityDist" min="10" max="100" value="70" oninput="document.getElementById('qVal').innerText=this.value; ctrl('stream_quality=' + this.value)">
                                </div>
                                <div class="control-group">
                                    <h3>Camera</h3>
                                    <button onclick="ctrl('flip=false', this)" class="cam-btn" id="c_false">Back</button>
                                    <button onclick="ctrl('flip=true', this)" class="cam-btn" id="c_true">Front</button>
                                </div>
                                <div class="control-group">
                                    <h3>Resolution</h3>
                                    <div id="resolutionList">
                                        <!-- Dynamic resolution buttons -->
                                    </div>
                                </div>
                            </div>

                            <script>
                                let currentResolutions = [];

                                function ctrl(query, btn) {
                                    fetch('/control?' + query).then(r => r.json()).then(data => {
                                        if (btn) {
                                            const cls = btn.classList[0];
                                            document.querySelectorAll('.' + cls).forEach(b => b.classList.remove('active'));
                                            btn.classList.add('active');
                                        }
                                        updateStatus();
                                    });
                                }

                                function updateFeatures() {
                                    fetch('/features').then(r => r.json()).then(data => {
                                        const resList = document.getElementById('resolutionList');
                                        const focusSupport = document.getElementById('focusSupport');
                                        const focusDist = document.getElementById('focusDist');
                                        const f2Btn = document.getElementById('f2');

                                        // Update manual focus support UI
                                        if (data.manual_focus) {
                                            focusSupport.textContent = "Manual focus supported";
                                            focusSupport.style.color = "#48bb78";
                                            focusDist.disabled = false;
                                            f2Btn.disabled = false;
                                        } else {
                                            focusSupport.textContent = "Fixed focus camera";
                                            focusSupport.style.color = "#f56565";
                                            focusDist.disabled = true;
                                            f2Btn.disabled = true;
                                        }

                                        // Update exposure
                                        const expInput = document.getElementById('exposureDist');
                                        if (data.exposure_lower !== data.exposure_upper) {
                                            expInput.min = data.exposure_lower;
                                            expInput.max = data.exposure_upper;
                                            expInput.disabled = false;
                                        } else {
                                            expInput.disabled = true;
                                        }

                                        // Update zoom
                                        const zInput = document.getElementById('zoomDist');
                                        zInput.max = data.zoom_max;
                                        if (data.zoom_max <= 1.0) zInput.disabled = true;
                                        else zInput.disabled = false;

                                        // Update resolution buttons if list changed
                                        if (JSON.stringify(data.resolutions) !== JSON.stringify(currentResolutions)) {
                                            currentResolutions = data.resolutions;
                                            resList.innerHTML = '';
                                            data.resolutions.forEach((res, index) => {
                                                const btn = document.createElement('button');
                                                btn.textContent = res;
                                                btn.className = 'res-btn';
                                                btn.id = 'res_' + res;
                                                btn.onclick = () => ctrl('resolution_str=' + res, btn);
                                                resList.appendChild(btn);
                                            });
                                        }
                                    });
                                }

                                function updateStatus() {
                                    fetch('/settings').then(r => r.json()).then(data => {
                                        document.querySelectorAll('.focus-btn').forEach(b => b.classList.remove('active'));
                                        if(document.getElementById('f'+data.focus_mode)) document.getElementById('f'+data.focus_mode).classList.add('active');
                                        
                                        document.getElementById('focusDist').value = data.focus_distance;
                                        document.getElementById('exposureDist').value = data.exposure_index;
                                        
                                        const zDist = document.getElementById('zoomDist');
                                        if (document.activeElement !== zDist) {
                                            zDist.value = data.zoom;
                                            document.getElementById('zVal').innerText = parseFloat(data.zoom).toFixed(1);
                                        }
                                        
                                        const qDist = document.getElementById('qualityDist');
                                        if (document.activeElement !== qDist) {
                                            qDist.value = data.stream_quality;
                                            document.getElementById('qVal').innerText = data.stream_quality;
                                        }
                                        
                                        document.querySelectorAll('.cam-btn').forEach(b => b.classList.remove('active'));
                                        if(document.getElementById('c_'+data.flip)) document.getElementById('c_'+data.flip).classList.add('active');
                                        
                                        document.querySelectorAll('.res-btn').forEach(b => {
                                            b.classList.toggle('active', b.textContent === data.resolution_str);
                                        });
                                    });
                                }

                                // Initial load
                                updateFeatures();
                                updateStatus();

                                // Periodic sync
                                setInterval(() => {
                                    updateFeatures();
                                    updateStatus();
                                }, 5000);

                                const img = document.getElementById('stream');
                                const status = document.getElementById('connectionStatus');
                                const toggleBtn = document.getElementById('togglePreviewBtn');
                                const videoContainer = document.getElementById('videoContainer');
                                
                                let isPreviewing = false;
                                
                                // Initially hide the video container and set status
                                videoContainer.style.display = 'none';
                                status.textContent = "PREVIEW PAUSED";
                                status.style.background = "#4a5568";

                                function togglePreview() {
                                    isPreviewing = !isPreviewing;
                                    
                                    if (isPreviewing) {
                                        videoContainer.style.display = 'block';
                                        img.src = "$streamUrl?t=" + Date.now();
                                        toggleBtn.textContent = "Stop Preview";
                                        toggleBtn.classList.add('danger');
                                        toggleBtn.classList.remove('active');
                                        status.textContent = "CONNECTING...";
                                        status.style.background = "#c53030";
                                    } else {
                                        videoContainer.style.display = 'none';
                                        img.src = ""; // Clear source to immediately stop stream
                                        toggleBtn.textContent = "Start Preview";
                                        toggleBtn.classList.remove('danger');
                                        toggleBtn.classList.add('active');
                                        status.textContent = "PREVIEW PAUSED";
                                        status.style.background = "#4a5568";
                                    }
                                }
                                
                                img.onerror = () => {
                                    if (isPreviewing) {
                                        status.textContent = "DISCONNECTED";
                                        status.style.background = "#4a5568";
                                        setTimeout(() => { 
                                            if(isPreviewing) img.src = "$streamUrl?t=" + Date.now(); 
                                        }, 2000);
                                    }
                                };
                                img.onload = () => {
                                    if (isPreviewing) {
                                        status.textContent = "LIVE STREAM";
                                        status.style.background = "#c53030";
                                    }
                                };
                            </script>
                        </body>
                        </html>
                    """.trimIndent())
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    private fun getCameraFeatures(front: Boolean): String {
        val manager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (front) facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return "{}"

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: emptyArray()
            val videoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java) ?: emptyArray()
            
            // Filter to only include sizes that are officially supported for continuous video recording
            val validSizes = yuvSizes.filter { yuvSize -> 
                videoSizes.any { it.width == yuvSize.width && it.height == yuvSize.height }
            }
            val resolutions = if (validSizes.isNotEmpty()) validSizes else yuvSizes.toList()

            val resList = resolutions.map { "${it.width}x${it.height}" }.distinct()
                .sortedByDescending { 
                    val parts = it.split("x")
                    if (parts.size == 2) parts[0].toInt() * parts[1].toInt() else 0
                }
            
            val minFocusDist = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val manualFocusSupported = (minFocusDist != null && minFocusDist > 0)

            val aeRange = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val expLower = aeRange?.lower ?: 0
            val expUpper = aeRange?.upper ?: 0
            val expStep = chars.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

            val maxZoom = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f

            val resJson = resList.joinToString(",") { "\"$it\"" }
            
            return """
                {
                    "resolutions": [$resJson],
                    "manual_focus": $manualFocusSupported,
                    "exposure_lower": $expLower,
                    "exposure_upper": $expUpper,
                    "exposure_step": $expStep,
                    "zoom_max": $maxZoom,
                    "camera": "${if (front) "front" else "back"}"
                }
            """.trimIndent()
        } catch (e: Exception) {
            return """{"error": "${e.message}"}"""
        }
    }

    override fun onStart() {
        super.onStart()
        if (isServerRunning && !isDimmed()) {
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    override fun onStop() {
        super.onStop()
        // Extremely important for background streams:
        // Detach the hardware surface before the OS destroys the Activity Window.
        // If we don't do this, CameraX crashes or stalls when it loses the display!
        preview?.setSurfaceProvider(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        // Do NOT stop the server or shutdown the executor if the service is running
        // This allows the background stream to survive the Activity being destroyed.
        if (!isServerRunning) {
            stopServer()
            cameraExecutor.shutdown()
        }
    }
}