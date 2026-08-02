package com.sjbtechnologies.awa.server

import com.sjbtechnologies.awa.viewModel.CameraViewModel
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.OutputStream
import kotlinx.serialization.Serializable

object VideoStreamServer {

    @Serializable
    data class FeaturesResponse(
        val resolutions: List<String>,
        val camera: String,
        val manual_focus: Boolean,
        val exposure_lower: Int,
        val exposure_upper: Int,
        val stream_protocol: CameraViewModel.StreamMode,
        val rtsp_port: Int? = null
    )

    @Serializable
    data class SettingsResponse(
        val focus_mode: Int,
        val focus_distance: Float,
        val exposure_index: Int,
        val zoom: Float,
        val stream_quality: Int,
        val flip: Boolean,
        val resolution_str: String
    )

    var featuresProvider: (() -> FeaturesResponse)? = null
    var settingsProvider: (() -> SettingsResponse)? = null

    private var server: EmbeddedServer<*, *>? = null
    @Volatile
    var latestFrame: ByteArray? = null

    var onUserConnected: (() -> Unit)? = null
    var onUserDisconnected: (() -> Unit)? = null

    var onServerStateChanged: ((Boolean) -> Unit)? = null

    val isRunning: Boolean
        get() = server != null

    fun toggleServer(port: Int = 8080) {
        if (isRunning) {
            stop()
        } else {
            start(port)
        }
        onServerStateChanged?.invoke(isRunning)
    }

    fun start(port: Int = 8080) {
        if (server != null) return

        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/video") {
                    onUserConnected?.invoke()
                    try {
                        call.respondOutputStream(
                            contentType = ContentType.parse("multipart/x-mixed-replace; boundary=--frame"),
                            status = HttpStatusCode.OK
                        ) {
                            streamMjpeg(this)
                        }
                    } finally {
                        onUserDisconnected?.invoke()
                    }
                }

                get("/features") {
                    val response = featuresProvider?.invoke() ?: FeaturesResponse(
                        resolutions = listOf("640x480", "1280x720", "1920x1080"),
                        camera = "N/A", manual_focus = false,
                        exposure_lower = 0, exposure_upper = 0,
                        stream_protocol = CameraViewModel.StreamMode.MJPEG,
                        rtsp_port = null
                    )
                    call.respond(response)
                }

                get("/settings") {
                    val response = settingsProvider?.invoke() ?: SettingsResponse(
                        focus_mode = 0, focus_distance = 0f, exposure_index = 1,
                        zoom = 1.0f, stream_quality = 80, flip = false, resolution_str = "1280x720"
                    )
                    call.respond(response)
                }
            }
        }.start(wait = false)
    }

    private suspend fun streamMjpeg(outputStream: OutputStream) {
        val boundary = "\r\n--frame\r\n"

        while (true) {
            val frame = latestFrame
            if (frame != null) {
                try {
                    withContext(Dispatchers.IO) {
                        outputStream.write(boundary.toByteArray())
                        outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                        outputStream.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                        outputStream.write(frame)
                        outputStream.flush()
                    }
                } catch (_: Exception) {
                    break
                }
            }
            delay(33) // ~30 FPS frame pacing
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}