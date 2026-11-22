package com.luis.espejotv

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Servicio que captura la pantalla del celular y la publica en el servidor MJPEG.
 */
class MirrorService : Service() {

    companion object {
        const val PORT = 8080
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_QUALITY = "quality"
        const val ACTION_STOP = "com.luis.espejotv.STOP"

        private const val CHANNEL_ID = "espejo"
        private const val NOTIF_ID = 1

        val running = MutableStateFlow(false)
        val clients = MutableStateFlow(0)

        fun start(context: Context, resultCode: Int, data: Intent, quality: Quality) {
            val i = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_QUALITY, quality.name)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }

    /** Lado mayor de la imagen y calidad JPEG. */
    enum class Quality(val label: String, val maxSide: Int, val jpeg: Int) {
        FLUIDA("Fluida", 960, 60),
        NORMAL("Normal", 1280, 70),
        ALTA("Alta (HD)", 1920, 82)
    }

    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: MjpegServer? = null
    private var quality = Quality.NORMAL

    private var curW = 0
    private var curH = 0
    private var bitmap: Bitmap? = null
    private val jpegBuffer = ByteArrayOutputStream(256 * 1024)

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    /** Detecta cuando el celular gira para ajustar el tamaño (horizontal / vertical). */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applySize()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("EspejoCaptura").also { it.start() }
        handler = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // En Android 14+ hay que estar en primer plano ANTES de pedir la proyección
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        quality = runCatching { Quality.valueOf(intent?.getStringExtra(EXTRA_QUALITY)!!) }
            .getOrDefault(Quality.NORMAL)

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        // Obligatorio en Android 14+: registrar el callback antes de crear la pantalla virtual
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        server = MjpegServer(PORT) { n -> clients.value = n }.also { it.start() }

        handler.post { applySize() }
        displayManager.registerDisplayListener(displayListener, handler)
        running.value = true
        return START_NOT_STICKY
    }

    /** Calcula el tamaño de captura según la orientación actual. */
    private fun captureSize(): Triple<Int, Int, Int> {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val scale = min(1f, quality.maxSide / max(w, h).toFloat())
        // El ancho y alto deben ser pares para que todo funcione bien
        val sw = ((w * scale).roundToInt() / 2) * 2
        val sh = ((h * scale).roundToInt() / 2) * 2
        return Triple(sw, sh, metrics.densityDpi)
    }

    private fun applySize() {
        val mp = projection ?: return
        val (w, h, dpi) = captureSize()
        if (w == curW && h == curH) return
        curW = w
        curH = h

        val oldReader = imageReader
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        imageReader = reader

        val vd = virtualDisplay
        if (vd == null) {
            // Solo se puede crear UNA vez por permiso (Android 14+); al girar se usa resize()
            virtualDisplay = mp.createVirtualDisplay(
                "EspejoTV", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = reader.surface
        }
        oldReader?.close()
    }

    private fun onImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmpW = image.width + rowPadding / pixelStride

            var bmp = bitmap
            if (bmp == null || bmp.width != bmpW || bmp.height != image.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bmpW, image.height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            bmp!!.copyPixelsFromBuffer(plane.buffer)

            val finalBmp = if (bmpW != image.width)
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height) else bmp

            jpegBuffer.reset()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, jpegBuffer)
            if (finalBmp !== bmp) finalBmp.recycle()

            server?.publish(jpegBuffer.toByteArray())
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transmisión de pantalla", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ip = NetworkUtils.localIp() ?: "sin WiFi"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Transmitiendo pantalla")
            .setContentText("Abre http://$ip:$PORT en la TV o PC")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.value = false
        clients.value = 0
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        val mp = projection
        projection = null
        runCatching { mp?.stop() }
        runCatching { server?.stop() }
        bitmap?.recycle()
        bitmap = null
        workerThread.quitSafely()
        super.onDestroy()
    }
}
