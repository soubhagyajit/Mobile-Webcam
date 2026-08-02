package com.sjbtechnologies.awa.ui.components

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.sjbtechnologies.awa.viewModel.CameraViewModel

@Composable
fun Preview(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val streamMode by viewModel.streamMode
    Log.d("AWA", "Preview Start")
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (streamMode == CameraViewModel.StreamMode.MJPEG){
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                viewModel.attachPreviewSurface(context, lifecycleOwner, previewView)
                previewView
            },
            modifier = modifier.fillMaxSize()
        )
    }
    else{
        // TextureView, not OpenGlView/SurfaceView — composites through the
        // normal view hierarchy, avoiding the well-documented SurfaceView-in-
        // Compose rendering bug that OpenGlView hit (this is the whole reason
        // for the GenericStream/StreamBase migration).
        AndroidView(
            factory = { ctx ->
                val textureView = TextureView(ctx)
                // NOTE: tap-to-focus on the RTSP preview itself is currently a
                // no-op (see CameraViewModel.tapToFocus(view, event) TODO) —
                // GenericStream/Camera2Source's focus API isn't confirmed yet.
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        // TextureView's surface is available essentially as soon
                        // as this fires — no async "wait for surfaceCreated"
                        // dance needed the way SurfaceView required.
                        viewModel.attachRtspPreviewSurface(textureView)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        viewModel.detachRtspPreviewSurface()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
                textureView
            },
            onRelease = {
                // Composable is leaving composition (local preview auto-hid, or
                // stream mode/server changed) — detach via stopPreview(), which
                // does NOT stop the actual push stream, only local rendering.
                viewModel.detachRtspPreviewSurface()
            },
            modifier = modifier.fillMaxSize()
        )
    }

}