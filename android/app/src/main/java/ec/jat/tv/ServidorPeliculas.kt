package ec.jat.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Para mandar a la TV las películas guardadas en el celular: la TV no puede leer la memoria
 * del celular, así que el celular le "presta" el archivo por el WiFi de la casa.
 * Solo envía el archivo tal cual (no lo procesa ni lo muestra), por eso gasta poca batería.
 * Se apaga solo si pasan 5 minutos sin que la TV pida nada.
 */
class ServidorPeliculas : Service() {

    companion object {
        private const val PUERTO = 8765
        private const val CANAL = "transmision"
        private val archivos = ConcurrentHashMap<String, Uri>()

        /** Prepara el archivo y devuelve la dirección que la TV debe abrir (o null si no hay WiFi). */
        fun direccion(ctx: Context, uri: Uri): String? {
            val ip = ipWifi(ctx) ?: return null
            val id = Integer.toHexString(uri.toString().hashCode() and 0x7fffffff)
            archivos[id] = uri
            val i = Intent(ctx, ServidorPeliculas::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            return "http://$ip:$PUERTO/v/$id"
        }

        fun detener(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ServidorPeliculas::class.java)) } catch (e: Exception) {}
        }

        private fun ipWifi(ctx: Context): String? {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return props.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }

    private var socket: ServerSocket? = null
    @Volatile private var ultimoPedido = System.currentTimeMillis()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        avisoEnPrimerPlano()
        ultimoPedido = System.currentTimeMillis()
        if (socket == null) arrancar()
        return START_NOT_STICKY
    }

    private fun avisoEnPrimerPlano() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null)
            nm.createNotificationChannel(NotificationChannel(CANAL, "Enviar películas a la TV", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 1, Intent(this, PeliculasActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("📡 Enviando película a la TV")
            .setContentText("Puedes apagar la pantalla del celular")
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(77, n)
    }

    private fun arrancar() {
        try {
            val s = ServerSocket(PUERTO)
            s.reuseAddress = true
            socket = s
        } catch (e: Exception) { stopSelf(); return }
        try {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvjat:tv").apply { acquire() }
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvjat:tv").apply { acquire(4 * 60 * 60 * 1000L) }
        } catch (e: Exception) {}
        thread(isDaemon = true) {
            val s = socket ?: return@thread
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                ultimoPedido = System.currentTimeMillis()
                thread(isDaemon = true) { atender(c) }
            }
        }
        // apagarse solo si la TV deja de pedir
        thread(isDaemon = true) {
            while (socket?.isClosed == false) {
                Thread.sleep(30_000)
                if (System.currentTimeMillis() - ultimoPedido > 5 * 60_000) { stopSelf(); break }
            }
        }
    }

    private fun atender(c: Socket) {
        try {
            c.soTimeout = 60_000
            val entrada = BufferedReader(InputStreamReader(c.getInputStream()))
            val primera = entrada.readLine() ?: return
            var rango: String? = null
            while (true) {
                val l = entrada.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("Range:", true)) rango = l.substringAfter(':').trim()
            }
            val partes = primera.split(" ")
            val metodo = partes.getOrNull(0) ?: ""
            val ruta = partes.getOrNull(1) ?: ""
            val salida = c.getOutputStream()
            val uri = archivos[ruta.removePrefix("/v/").substringBefore('?')]
            if (uri == null) {
                salida.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val pedidoRango: String? = rango
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
            pfd.use {
                val total = it.statSize
                val tipo = contentResolver.getType(uri) ?: "video/mp4"
                var desde = 0L
                var hasta = total - 1
                if (pedidoRango != null && pedidoRango.startsWith("bytes=")) {
                    val r = pedidoRango.removePrefix("bytes=").substringBefore(',')
                    val a = r.substringBefore('-').trim()
                    val b = r.substringAfter('-').trim()
                    if (a.isNotEmpty()) { desde = a.toLong(); if (b.isNotEmpty()) hasta = minOf(b.toLong(), total - 1) }
                    else if (b.isNotEmpty()) desde = maxOf(0L, total - b.toLong())
                }
                val largo = hasta - desde + 1
                val cab = StringBuilder()
                cab.append(if (pedidoRango != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                cab.append("Content-Type: $tipo\r\n")
                cab.append("Accept-Ranges: bytes\r\n")
                cab.append("Access-Control-Allow-Origin: *\r\n")
                cab.append("Content-Length: $largo\r\n")
                if (pedidoRango != null) cab.append("Content-Range: bytes $desde-$hasta/$total\r\n")
                cab.append("Connection: close\r\n\r\n")
                salida.write(cab.toString().toByteArray())
                if (metodo == "HEAD") return
                FileInputStream(it.fileDescriptor).use { fis ->
                    val canal = fis.channel
                    canal.position(desde)
                    val buf = ByteArray(256 * 1024)
                    var falta = largo
                    while (falta > 0) {
                        val n = fis.read(buf, 0, minOf(buf.size.toLong(), falta).toInt())
                        if (n <= 0) break
                        salida.write(buf, 0, n)
                        falta -= n
                        ultimoPedido = System.currentTimeMillis()
                    }
                }
                salida.flush()
            }
        } catch (e: Exception) {
            // la TV cortó la conexión (normal al adelantar o pausar)
        } finally {
            try { c.close() } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        try { wifiLock?.release() } catch (e: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
