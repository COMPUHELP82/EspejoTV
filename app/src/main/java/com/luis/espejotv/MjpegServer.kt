package com.luis.espejotv

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo que transmite la pantalla como MJPEG.
 *  /          -> página que muestra la imagen a pantalla completa
 *  /stream    -> video MJPEG (multipart/x-mixed-replace)
 *  /frame.jpg -> última imagen (respaldo para navegadores de TV que no soportan MJPEG)
 */
class MjpegServer(
    private val port: Int,
    private val onClientsChanged: (Int) -> Unit
) {
    private val lock = Object()
    private var frame: ByteArray? = null
    private var frameId = 0L

    @Volatile private var closed = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val clients = AtomicInteger(0)

    fun start() {
        thread(name = "EspejoServer", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (!closed) {
                    val socket = try { ss.accept() } catch (e: Exception) { break }
                    pool.execute { handle(socket) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            frame = jpeg
            frameId++
            lock.notifyAll()
        }
    }

    fun stop() {
        closed = true
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.soTimeout = 15000
                val path = readRequestPath(s) ?: return
                s.soTimeout = 0
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                when {
                    path.startsWith("/stream") -> stream(out)
                    path.startsWith("/frame.jpg") -> singleFrame(out)
                    else -> page(out)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Lee la línea "GET /ruta HTTP/1.1" y descarta los encabezados. */
    private fun readRequestPath(s: Socket): String? {
        val input = s.getInputStream()
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break           // fin de encabezados
            if (sb.length > 16 * 1024) return null
        }
        val firstLine = sb.lineSequence().firstOrNull() ?: return null
        val parts = firstLine.split(" ")
        return if (parts.size >= 2) parts[1] else "/"
    }

    private fun stream(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=espejo\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        onClientsChanged(clients.incrementAndGet())
        try {
            var lastSent = -1L
            while (!closed) {
                var data: ByteArray?
                synchronized(lock) {
                    if (frameId == lastSent && !closed) lock.wait(1000)
                    data = frame
                    lastSent = frameId
                }
                val jpeg = data ?: continue
                out.write(
                    ("--espejo\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                        .toByteArray()
                )
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            onClientsChanged(clients.decrementAndGet())
        }
    }

    private fun singleFrame(out: OutputStream) {
        var jpeg: ByteArray?
        synchronized(lock) {
            if (frame == null) lock.wait(2000)
            jpeg = frame
        }
        val data = jpeg
        if (data == null) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n" +
                    "Cache-Control: no-cache, no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(data)
        }
        out.flush()
    }

    private fun page(out: OutputStream) {
        val body = VIEWER_HTML.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .toByteArray()
        )
        out.write(body)
        out.flush()
    }

    companion object {
        /** Página que se abre en la TV / PC. Imagen centrada ocupando toda la pantalla. */
        private val VIEWER_HTML = """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Espejo TV</title>
<style>
  html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; cursor:none; }
  #pantalla { position:fixed; inset:0; width:100vw; height:100vh; object-fit:contain; background:#000; }
  #aviso { position:fixed; left:50%; bottom:6vh; transform:translateX(-50%);
           background:rgba(15,23,42,.85); color:#fff; font:600 2.2vh/1.4 system-ui,sans-serif;
           padding:1.4vh 3vh; border-radius:2vh; transition:opacity .8s; pointer-events:none; }
  #aviso.oculto { opacity:0; }
</style>
</head>
<body>
<img id="pantalla" alt="">
<div id="aviso">Toca la pantalla o presiona OK para ver en pantalla completa</div>
<script>
  var img = document.getElementById('pantalla');
  var aviso = document.getElementById('aviso');
  var modoRespaldo = false;

  function iniciarStream() { img.src = '/stream?t=' + Date.now(); }

  // Si el navegador no soporta MJPEG (algunas TV), se piden imágenes una tras otra
  function respaldo() {
    modoRespaldo = true;
    var siguiente = new Image();
    siguiente.onload = function () { img.src = siguiente.src; setTimeout(pedir, 30); };
    siguiente.onerror = function () { setTimeout(pedir, 800); };
    function pedir() { siguiente.src = '/frame.jpg?t=' + Date.now(); }
    pedir();
  }

  var yaSeVio = false, reintentos = 0;
  img.onload = function () { yaSeVio = true; reintentos = 0; };
  img.onerror = function () {
    if (modoRespaldo) return;
    // Si ya estaba funcionando, se cortó la señal: reconectar
    if (yaSeVio && reintentos < 5) { reintentos++; setTimeout(iniciarStream, 1500); }
    else respaldo();
  };
  iniciarStream();

  function pantallaCompleta() {
    var el = document.documentElement;
    var fn = el.requestFullscreen || el.webkitRequestFullscreen || el.mozRequestFullScreen || el.msRequestFullscreen;
    if (fn && !document.fullscreenElement && !document.webkitFullscreenElement) {
      try { fn.call(el); } catch (e) {}
    }
    aviso.className = 'oculto';
  }
  document.addEventListener('click', pantallaCompleta);
  document.addEventListener('touchend', pantallaCompleta);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.keyCode === 13 || e.key === 'f' || e.key === ' ') pantallaCompleta();
  });
  setTimeout(function () { aviso.className = 'oculto'; }, 6000);
</script>
</body>
</html>
""".trimIndent()
    }
}
