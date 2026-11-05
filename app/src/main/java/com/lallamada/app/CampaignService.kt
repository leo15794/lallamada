package com.lallamada.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.telecom.TelecomManager
import java.util.Calendar

/** Recorre la lista de clientes: llama, espera que atiendan, dice el mensaje, corta y anota. */
class CampaignService : Service() {

    companion object {
        const val ACTION_STOP = "com.lallamada.app.STOP"
        const val EXTRA_TEST = "test_number"
        private const val CHANNEL = "campania"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Live.stopRequested = true
            Live.worker?.interrupt()
            if (!Live.running) stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (Live.running) return START_NOT_STICKY

        val test = intent?.getStringExtra(EXTRA_TEST)
        Live.running = true
        Live.stopRequested = false
        acquireWakeLock()

        val t = Thread {
            try {
                if (test != null) runTest(test) else runCampaign()
            } catch (e: InterruptedException) {
                Live.status = "Detenido"
            } catch (e: Exception) {
                Live.status = "Error: ${e.message}"
            } finally {
                if (Live.inCall) {
                    try { hangUp() } catch (_: Exception) {}
                    Live.inCall = false
                }
                Live.running = false
                releaseWakeLock()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        Live.worker = t
        t.start()
        return START_NOT_STICKY
    }

    // ---------- Notificación obligatoria del servicio ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Campaña de llamadas", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("La Llamada")
            .setContentText("Campaña de llamadas en curso")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        // Mantiene la pantalla prendida: con la pantalla apagada no se puede leer la pantalla de llamada.
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "lallamada:campania"
        ).apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    // ---------- Utilidades ----------

    private val telecom by lazy { getSystemService(TelecomManager::class.java) }

    private fun inCall(): Boolean = try { telecom.isInCall } catch (e: SecurityException) { false }

    /** Espera con chequeo de "Detener". Lanza InterruptedException si se pidió detener. */
    private fun pause(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (Live.stopRequested) throw InterruptedException()
            Thread.sleep(minOf(250L, (end - System.currentTimeMillis()).coerceAtLeast(1L)))
        }
    }

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            pause(400)
        }
        return cond()
    }

    private fun diag(label: String) {
        if (!Store.diagnostic(this)) return
        Store.appendDiag(this, "== $label ==\n" + (Live.service?.dump() ?: "(accesibilidad inactiva)"))
    }

    private fun placeCall(phone: String) {
        val uri = Uri.fromParts("tel", phone, null)
        try {
            telecom.placeCall(uri, Bundle())
        } catch (e: SecurityException) {
            throw IllegalStateException("Falta el permiso de llamadas")
        }
        if (!waitUntil(4000) { inCall() }) {
            // Plan B: pedirle al marcador que llame.
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    private fun hangUp() {
        var ok = false
        try {
            @Suppress("DEPRECATION")
            ok = telecom.endCall()
        } catch (_: Exception) {}
        if (!ok) Live.service?.hangUpByUi(Store.kwHangup(this))
    }

    // ---------- Una llamada ----------

    private fun doCall(c: Contact): Pair<String, String> {
        val who = if (c.name.isNotEmpty()) "${c.name} (${c.phone})" else c.phone
        Live.status = "Llamando a $who"
        placeCall(c.phone)
        if (!waitUntil(6000) { inCall() }) {
            return "error" to "La llamada no se inició"
        }
        Live.inCall = true
        pause(3000)
        diag("marcando ${c.phone}")

        val ringMs = Store.ringTimeout(this) * 1000L
        waitUntil(ringMs) { !inCall() || Live.service?.isCallConnected() == true }

        if (!inCall()) {
            Live.inCall = false
            return "no atendió" to "rechazó o se cortó antes de atender"
        }
        if (Live.service?.isCallConnected() != true) {
            hangUp()
            waitUntil(8000) { !inCall() }
            Live.inCall = false
            return "no atendió" to "sin respuesta en ${Store.ringTimeout(this)} s"
        }

        Live.status = "Atendió: $who"
        diag("atendió ${c.phone}")
        pause(1500)

        val detail: String
        val talkMs: Long
        val configured = Store.talkSec(this)
        val mode = Store.mode(this)
        if (mode == Store.MODE_AUDIO) {
            Live.status = "Reproduciendo el audio a $who"
            detail = playAudioInCall()
            diag("después del audio a ${c.phone}: $detail")
            talkMs = 1500L
        } else if (mode == Store.MODE_SPEAK) {
            val msg = Store.message(this)
            Live.status = "Diciendo el mensaje a $who"
            detail = Live.service?.speak(msg, Store.kwOpen(this), Store.kwSend(this))
                ?: "La accesibilidad no está activa"
            diag("después de enviar el mensaje a ${c.phone}: $detail")
            // ~12 caracteres por segundo de voz + margen
            talkMs = (if (configured > 0) configured else msg.length / 12 + 6) * 1000L
        } else {
            Live.status = "En llamada con $who (audio externo)"
            detail = "solo marcar"
            talkMs = (if (configured > 0) configured else 30) * 1000L
        }

        waitUntil(talkMs) { !inCall() }
        val clientHungUp = !inCall()
        if (!clientHungUp) hangUp()
        waitUntil(8000) { !inCall() }
        Live.inCall = false
        return "atendió" to (detail + if (clientHungUp) " | el cliente cortó antes del final" else "")
    }

    /**
     * Android no deja meter audio dentro de una llamada. Vuelta: activar el altavoz de la llamada
     * y reproducir el audio por el parlante para que lo capte el micrófono.
     */
    private fun playAudioInCall(): String {
        val f = Store.audioFile(this)
        if (!f.exists()) return "No hay audio cargado"
        val am = getSystemService(AudioManager::class.java)
        val notes = mutableListOf<String>()

        @Suppress("DEPRECATION")
        val speakerOn = am.isSpeakerphoneOn
        if (!speakerOn) {
            val ok = Live.service?.pressSpeaker(Store.kwSpeaker(this)) == true
            notes.add(if (ok) "altavoz activado" else "NO pude activar el altavoz")
            pause(900)
        }

        val stream = AudioManager.STREAM_MUSIC
        val oldVol = am.getStreamVolume(stream)
        try { am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0) } catch (_: Exception) {}

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
            val dur = mp.duration.toLong().coerceAtLeast(1000L)
            waitUntil(dur + 500L) { !inCall() }
            notes.add(0, "audio reproducido (${dur / 1000} s)")
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            notes.add(0, "error de audio: ${e.message}")
        } finally {
            try { mp.stop() } catch (_: Exception) {}
            mp.release()
            try { am.setStreamVolume(stream, oldVol, 0) } catch (_: Exception) {}
        }
        return notes.joinToString(", ")
    }

    // ---------- Campaña ----------

    private fun waitForHours() {
        while (true) {
            val start = Store.hourStart(this)
            val end = Store.hourEnd(this)
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (h in start until end) return
            Live.status = "Fuera de horario ($start a $end h). Esperando..."
            pause(60_000)
        }
    }

    private fun runCampaign() {
        val contacts = Store.loadContacts(this)
        if (contacts.isEmpty()) {
            Live.status = "No hay clientes cargados"
            return
        }
        val blocked = Store.blockedKeys(this)
        var i = Store.index(this)
        while (i < contacts.size) {
            if (Live.stopRequested) throw InterruptedException()
            waitForHours()
            val c = contacts[i]
            if (Store.key(c.phone) in blocked) {
                Store.appendResult(this, c, "omitido", "está en la lista de bajas")
                i++
                Store.setIndex(this, i)
                continue
            }
            val (result, detail) = doCall(c)
            Store.appendResult(this, c, result, detail)
            i++
            Store.setIndex(this, i)
            Live.status = "$i/${contacts.size} - último: $result. Pausa..."
            if (i < contacts.size) pause(Store.pauseSec(this) * 1000L)
        }
        Live.status = "Campaña terminada (${contacts.size} contactos)"
    }

    private fun runTest(phone: String) {
        val c = Contact(phone, "PRUEBA")
        val (result, detail) = doCall(c)
        Store.appendResult(this, c, result, detail)
        Live.status = "Prueba terminada: $result - $detail"
    }
}
