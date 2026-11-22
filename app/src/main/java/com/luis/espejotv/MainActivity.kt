package com.luis.espejotv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Fondo = Color(0xFF0B1120)
private val Tarjeta = Color(0xFF151E33)
private val Acento = Color(0xFF38BDF8)
private val Verde = Color(0xFF22C55E)
private val Rojo = Color(0xFFEF4444)
private val TextoSuave = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Acento, background = Fondo)) {
                PantallaPrincipal()
            }
        }
    }
}

@Composable
fun PantallaPrincipal() {
    val context = LocalContext.current
    val running by MirrorService.running.collectAsState()
    val clients by MirrorService.clients.collectAsState()
    var ip by remember { mutableStateOf(NetworkUtils.localIp()) }
    var calidad by remember { mutableStateOf(MirrorService.Quality.NORMAL) }

    // Actualiza la IP por si cambia la red WiFi
    LaunchedEffect(Unit) {
        while (true) {
            ip = NetworkUtils.localIp()
            delay(3000)
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MirrorService.start(context, result.resultCode, result.data!!, calidad)
        }
    }

    fun pedirCaptura() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { pedirCaptura() }

    fun iniciar() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else pedirCaptura()
    }

    val url = ip?.let { "http://$it:${MirrorService.PORT}" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Fondo, Color(0xFF0F1B33))))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Espejo TV", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Tu celular en la pantalla grande",
            color = TextoSuave, fontSize = 15.sp
        )
        Spacer(Modifier.height(28.dp))

        // Indicador de estado
        Box(
            modifier = Modifier
                .size(150.dp)
                .background(
                    (if (running) Verde else Acento).copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (running) Icons.Filled.CastConnected else Icons.Filled.Cast,
                contentDescription = null,
                tint = if (running) Verde else Acento,
                modifier = Modifier.size(72.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                !running -> "Listo para transmitir"
                clients == 0 -> "Transmitiendo · esperando pantalla"
                clients == 1 -> "Transmitiendo a 1 pantalla"
                else -> "Transmitiendo a $clients pantallas"
            },
            color = if (running) Verde else Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 17.sp
        )

        Spacer(Modifier.height(24.dp))

        // Dirección para abrir en la TV / PC
        TarjetaDireccion(url)

        Spacer(Modifier.height(20.dp))

        if (!running) {
            Text("Calidad", color = TextoSuave, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MirrorService.Quality.entries.forEach { q ->
                    FilterChip(
                        selected = calidad == q,
                        onClick = { calidad = q },
                        label = { Text(q.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Button(
            onClick = { if (running) MirrorService.stop(context) else iniciar() },
            enabled = running || url != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Rojo else Acento,
                contentColor = if (running) Color.White else Fondo
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Icon(if (running) Icons.Filled.Stop else Icons.Filled.Cast, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                if (running) "Detener transmisión" else "Iniciar transmisión",
                fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(24.dp))
        Instrucciones()
    }
}

@Composable
private fun TarjetaDireccion(url: String?) {
    val clipboard = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Tarjeta),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Abre esta dirección en la TV o PC", color = TextoSuave, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (url != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        url, color = Acento, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar", tint = TextoSuave)
                    }
                }
            } else {
                Text(
                    "Conéctate a una red WiFi (o activa tu hotspot) para obtener la dirección",
                    color = Rojo, textAlign = TextAlign.Center, fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun Instrucciones() {
    val pasos = listOf(
        "Conecta el celular y la TV o PC a la misma red WiFi.",
        "Toca \"Iniciar transmisión\" y acepta el permiso para compartir la pantalla completa.",
        "En la TV o PC abre el navegador y escribe la dirección de arriba.",
        "Presiona OK en el control (o haz clic) para verla a pantalla completa."
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = Tarjeta),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Cómo usarla", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            pasos.forEachIndexed { i, paso ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Box(
                        Modifier.size(24.dp).background(Acento.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", color = Acento, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(paso, color = Color(0xFFCBD5E1), fontSize = 14.sp)
                }
            }
        }
    }
}
