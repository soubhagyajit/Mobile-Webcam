package com.sjbtechnologies.awa.ui.components

import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.pedro.library.view.OpenGlView
import com.sjbtechnologies.awa.viewModel.CameraViewModel

@Composable
fun Preview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val streamMode by viewModel.streamMode
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (streamMode == CameraViewModel.StreamMode.MJPEG) {

        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    viewModel.attachPreviewSurface(
                        context,
                        lifecycleOwner,
                        this
                    )
                }
            }
        )

    } else {

        // Create ONLY ONE OpenGlView for this composition
        val openGlView = remember {
            OpenGlView(context)
        }

        AndroidView(
            factory = {
                openGlView.apply {
                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            viewModel.tapToFocus(view, event)
                            viewModel.notifyScreenTapped()
                        }
                        true
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.d("AWA", "Attach preview called")
                            viewModel.attachRtspPreviewSurface(this@apply)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.detachRtspPreviewSurface()
                        }
                    })
                }

            }
        )

        DisposableEffect(Unit) {
            onDispose {
                viewModel.detachRtspPreviewSurface()
            }
        }
    }
}