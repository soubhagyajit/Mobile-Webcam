package com.sjbtechnologies.awa.test

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtsp.RtspCamera2

/**
 * Isolated test for GenericStream (the StreamBase family) PUSHING to an
 * external RTSP server (mediamtx running on the desktop), using a plain
 * TextureView for realtime local preview instead of OpenGlView/SurfaceView.
 *
 * WHY: RtspServerCamera2 (Camera2Base family, what we used before) only
 * supports OpenGlView, which has a well-documented, unresolved SurfaceView-
 * in-Compose rendering bug. StreamBase's classes (GenericStream/RtspStream/
 * etc.) officially support TextureView for preview, which composites through
 * the normal view hierarchy and doesn't have this problem. The tradeoff:
 * StreamBase pushes OUT to a server rather than acting as its own server —
 * hence testing against mediamtx running on the desktop.
 *
 * NOTE: package path for GenericStream (com.pedro.library.generic) is my best
 * inference from the library's structure — if this import doesn't resolve,
 * check Android Studio's autocomplete for the real path and let me know.
 */
object RtspPushTest {

    private var rtspStream: RtspCamera2? = null

    fun start(context: Context, textureView: TextureView, rtspUrl: String) {
        val checker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.d("AWA", "Push: connection started, url=$url")
            }
            override fun onConnectionSuccess() {
                Log.d("AWA", "Push: connected successfully")
            }
            override fun onConnectionFailed(reason: String) {
                Log.e("AWA", "Push: connection failed - $reason")
            }
            override fun onNewBitrate(bitrate: Long) {}
            override fun onDisconnect() {
                Log.d("AWA", "Push: disconnected")
            }
            override fun onAuthError() {
                Log.e("AWA", "Push: auth error")
            }
            override fun onAuthSuccess() {
                Log.d("AWA", "Push: auth success")
            }
        }

        val stream = RtspCamera2(context, checker)
        rtspStream = stream

        val videoOk = stream.prepareVideo(1280, 720, 16_000_000)
        val audioOk = stream.prepareAudio(128*1024, 48_000, false)
        Log.d("AWA", "Push prepareVideo=$videoOk")

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (!stream.isOnPreview) {
                    stream.startPreview()
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                if (stream.isOnPreview) stream.stopPreview()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (videoOk && audioOk) {
            stream.startStream(rtspUrl)
            Log.d("AWA", "Push started to $rtspUrl")
        } else {
            Log.e("AWA", "Push: prepare failed, not starting stream")
        }
    }

    fun stop() {
        rtspStream?.stopStream()
        rtspStream = null
    }
}