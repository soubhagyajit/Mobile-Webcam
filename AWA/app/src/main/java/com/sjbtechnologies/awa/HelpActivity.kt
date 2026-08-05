package com.sjbtechnologies.awa

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.sjbtechnologies.awa.ui.theme.AWATheme

class HelpActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()
        setContent {
            AWATheme {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("API Documentation") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    HelpContent(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun HelpContent(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Android Webcam App",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Available endpoints:",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            ApiEndpointCard(
                method = "GET",
                path = "/video",
                description = "MJPEG stream."
            )
        }

        item {
            ApiEndpointCard(
                method = "GET",
                path = "/features",
                description = "Get available features.",
                exampleJson = """
{
    "resolutions": ["640x480","1280x720","1920x1080","3840x2160"],
    "manual_focus": true,
    "exposure_lower": -20,
    "exposure_upper": 20,
    "stream_protocol": "MJPEG"
}
                """.trimIndent()
            )
        }

        item {
            ApiEndpointCard(
                method = "GET",
                path = "/settings",
                description = "Get current settings.",
                exampleJson = """
{
    "focus_mode": 0,
    "focus_distance": 0.0,
    "exposure_index": 0,
    "zoom": 1.0,
    "stream_quality": 80,
    "flip": false,
    "resolution_str": "1280x720",
    "camera": "back"
}
                """.trimIndent()
            )
        }

        item {
            ApiEndpointCard(
                method = "GET",
                path = "/control",
                description = "Get current control states.",
                exampleJson = """
{
    "focus_mode": 0,
    "focus_distance": 0.0,
    "exposure_index": 0,
    "zoom": 1.0,
    "stream_quality": 80,
    "flip": false,
    "resolution_str": "1280x720",
    "camera": "back"
}
                """.trimIndent()
            )
        }

        item {
            ApiEndpointCard(
                method = "POST",
                path = "/settings",
                description = "Set settings endpoint. Post a JSON object with the desired settings to update multiple states at once.",
                exampleJson = """
{
    "focus_mode": 0,
    "focus_distance": 0.0,
    "exposure_index": 0,
    "zoom": 1.0,
    "stream_quality": 80,
    "flip": false,
    "resolution_str": "1280x720",
    "camera": "back"
}
                """.trimIndent()
            )
        }

        item {
            val context = LocalContext.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Note:", fontWeight = FontWeight.Bold)
                    Text(
                        text = "Zoom is not supported yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/soubhagyajit/Android-Webcam-Project".toUri())
                        context.startActivity(intent)
                    }) {
                        Text("Github Project")
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(text = "License:", fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/soubhagyajit/Android-Webcam-Project?tab=GPL-3.0-1-ov-file".toUri())
                        context.startActivity(intent)
                    }) {
                        Text("GNU General Public License v3.0")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(text = "Acknowledgments:", fontWeight = FontWeight.Bold)
                    
                    Text(
                        text = "Special thanks to these amazing projects:",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/tshino/softcam".toUri())
                        context.startActivity(intent)
                    }) {
                        Text("softcam driver by tshino")
                    }

                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/pedroSG94".toUri())
                        context.startActivity(intent)
                    }) {
                        Text("Rootencoder & RTSP-Server by pedroSG94")
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ApiEndpointCard(
    method: String,
    path: String,
    description: String,
    exampleJson: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = when (method) {
                        "GET" -> Color(0xFF4CAF50)
                        "POST" -> Color(0xFF2196F3)
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = method,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (exampleJson != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = exampleJson,
                        color = Color(0xFFF8F8F2),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
