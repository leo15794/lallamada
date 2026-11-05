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
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.telecom.TelecomManager
import java.util.Calendar

/** Recorre la lista de clientes: llama, espera que atiendan, dice el mensaje, corta y anota. */
class CampaignService : Service() {

    companion object {
        const val ACTION_STOP = "com.lallamada.app.STOP"
        const val EXTRA_TEST = "test_number"
        const val ACTION_REMOTE_ON = "com.lallamada.app.REMOTE_ON"
        const val ACTION_REMOTE_OFF = "com.lallamada.app.REMOTE_OFF"
        private const val CHANNEL = "campania"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                if (!Live.running && remote == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REMOTE_ON -> {
                startInForeground()
                startRemote()
                return START_STICKY
            }
            ACTION_REMOTE_OFF -> {
                stopRemote()
                if (!Live.running) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        if (intent == null) {
            // El sistema reinició el servicio: solo se retoma el control remoto.
            if (Store.bool(this, "remoteOn", false)) {
                startInForeground()
                startRemote()
                return START_STICKY
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        startWork(intent.getStringExtra(EXTRA_TEST))
        return if (remote != null) START_STICKY else START_NOT_STICKY
    }

    // ---------- Control remoto (panel web) ----------

    private var remote: RemoteSync? = null
    private var remoteLock: PowerManager.WakeLock? = null

    private fun startRemote() {
        if (remote != null) return
        remote = RemoteSync(this).also { it.start() }
        val pm = getSystemService(PowerManager::class.java)
        remoteLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lallamada:remoto").apply { acquire() }
        updateNotification()
    }

    private fun stopRemote() {
        remote?.stop()
        remote = null
        try { remoteLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        remoteLock = null
        Live.remoteStatus = "Control remoto apagado"
    }

    override fun onDestroy() {
        stopRemote()
        super.onDestroy()
    }

    /** Pide detener la campaña o prueba en curso. */
    fun requestStop() {
        Live.stopRequested = true
        try { ShellBridge.service?.stopPlay() } catch (_: Exception) {}
        Live.worker?.interrupt()
    }

    /** Arranca la campaña (test == null) o una llamada de prueba. */
    fun startWork(test: String?) {
        if (Live.running) return
        Live.running = true
        Live.stopRequested = false
        acquireWakeLock()
        updateNotification()

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
                if (remote == null) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
        }
        Live.worker = t
        t.start()
    }

    // ---------- Notificación obligatoria del servicio ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Campaña de llamadas", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            Live.running -> "Campaña de llamadas en curso"
            remote != null -> "Control remoto activo: esperando órdenes del panel"
            else -> "La Llamada"
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("La Llamada")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateNotification() {
        try { getSystemService(NotificationManager::class.java).notify(1, buildNotification()) } catch (_: Exception) {}
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

    private fun doCall(c: Contact, captureTest: Boolean = false): Pair<String, String> {
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

        // Detección de contestador: siempre en las pruebas (para ver el análisis), y en la campaña si está activada.
        var capture = ""
        val useCaptions = Store.mode(this) == Store.MODE_AUDIO && Store.capEnabled(this)
        val useAmd = !useCaptions && Store.mode(this) == Store.MODE_AUDIO && (captureTest || Store.amdEnabled(this))
        if (useCaptions) {
            Live.status = "Leyendo subtítulos para saber quién atendió..."
            val (kind, info) = detectByCaptions()
            capture = when (kind) {
                "MACHINE" -> "BUZÓN ($info)"
                "HUMAN" -> "persona ($info)"
                else -> "subtítulos: $info"
            }
            diag("subtítulos ${c.phone}: $kind $info")
            if (!inCall()) {
                Live.inCall = false
                return "atendió" to "$capture | cortó durante la escucha"
            }
            if (kind == "MACHINE" && Store.amdOnMachine(this) == "cortar") {
                hangUp()
                waitUntil(8000) { !inCall() }
                Live.inCall = false
                return "buzón" to capture
            }
        } else if (useAmd) {
            val shell = ShellBridge.service
            if (shell == null) {
                capture = "detección: sin Shizuku"
                pause(Store.answerDelayMs(this).toLong())
            } else {
                Live.status = "Escuchando quién atendió..."
                val r = try {
                    shell.detect(
                        Store.amdThresholdDb(this), Store.amdInitialSilenceMs(this), Store.amdSilenceEndMs(this),
                        Store.amdMachineMs(this), Store.amdMaxMs(this)
                    )
                } catch (e: Exception) {
                    "FAIL|${e.message}"
                }
                val kind = r.substringBefore('|')
                val info = r.substringAfter('|')
                capture = when (kind) {
                    "MACHINE" -> "BUZÓN ($info)"
                    "HUMAN" -> "persona ($info)"
                    else -> "detección falló: $info"
                }
                diag("detección ${c.phone}: $r")
                if (!inCall()) {
                    Live.inCall = false
                    return "atendió" to "$capture | cortó durante la escucha"
                }
                if (kind == "MACHINE" && Store.amdOnMachine(this) == "cortar") {
                    hangUp()
                    waitUntil(8000) { !inCall() }
                    Live.inCall = false
                    return "buzón" to capture
                }
            }
        } else {
            pause(Store.answerDelayMs(this).toLong())
        }

        var detail: String
        val talkMs: Long
        val configured = Store.talkSec(this)
        val mode = Store.mode(this)
        if (mode == Store.MODE_AUDIO) {
            Live.status = "Reproduciendo el audio a $who"
            detail = when (Store.audioMethod(this)) {
                Store.METHOD_DIRECT -> {
                    val r = playDirect()
                    if (r.startsWith("FALLÓ")) "$r → uso altavoz: " + playAudioInCall() else r
                }
                Store.METHOD_MTK -> playMtk()
                else -> playAudioInCall()
            }
            diag("después del audio a ${c.phone}: $detail")
            talkMs = 1500L
            if (capture.isNotEmpty()) detail = "$capture | $detail"
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
    // Parámetros del sistema de audio de MediaTek: el reproductor de "sonido de fondo" (BGS)
    // del módem tiene la salida hacia la llamada en volumen cero; esto la abre.
    private val mtkOn = listOf(
        "Set_BGS_UL_Mute=0", "Set_BGS_DL_Mute=1",
        "Set_SpeechCall_UL_Mute=1", "Set_SpeechCall_DL_Mute=1"
    )
    private val mtkOff = listOf(
        "Set_SpeechCall_UL_Mute=0", "Set_SpeechCall_DL_Mute=0",
        "Set_BGS_UL_Mute=1", "Set_BGS_DL_Mute=0"
    )

    private fun setAudioParams(list: List<String>) {
        val am = getSystemService(AudioManager::class.java)
        for (kv in list) {
            try { am.setParameters(kv) } catch (_: Exception) {}
            try { ShellBridge.service?.setParams(kv) } catch (_: Exception) {}
        }
    }

    /** Método MediaTek: abre el canal de sonido de fondo hacia la llamada y reproduce el audio. */
    private fun playMtk(): String {
        val f = Store.audioFile(this)
        if (!f.exists()) return "No hay audio cargado"
        val am = getSystemService(AudioManager::class.java)
        val streams = listOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC)
        val oldVols = streams.associateWith { am.getStreamVolume(it) }
        for (s in streams) {
            try { am.setStreamVolume(s, am.getStreamMaxVolume(s), 0) } catch (_: Exception) {}
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            setAudioParams(mtkOn)
            pause(150)
            mp.start()
            val dur = mp.duration.toLong().coerceAtLeast(1000L)
            // El reproductor de fondo del módem se abre con el canal hacia la llamada cerrado:
            // repetimos la orden justo después de arrancar y cada tanto mientras suena.
            val end = System.currentTimeMillis() + dur + 500L
            var k = 0
            while (System.currentTimeMillis() < end && inCall()) {
                if (k < 5 || k % 5 == 0) setAudioParams(mtkOn)
                pause(200)
                k++
            }
            val heard = if (inCall()) dur else try { mp.currentPosition.toLong() } catch (_: Exception) { dur }
            val tail = if (ShellBridge.service != null) " (con Shizuku)" else " (sin Shizuku)"
            return if (heard >= dur - 700) "MediaTek: escuchó completo (${dur / 1000} s)$tail"
            else "MediaTek: cortó a los ${heard / 1000} s de ${dur / 1000} s$tail"
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            return "MediaTek: error de audio: ${e.message}"
        } finally {
            try { mp.stop() } catch (_: Exception) {}
            mp.release()
            setAudioParams(mtkOff)
            for ((s, v) in oldVols) {
                try { am.setStreamVolume(s, v, 0) } catch (_: Exception) {}
            }
        }
    }

    /** Audio directo a la llamada usando Shizuku (sin altavoz). */
    private fun playDirect(): String {
        val shell = ShellBridge.service ?: return "FALLÓ: Shizuku no está conectado"
        val pcm = try {
            Pcm.prepare(this)
        } catch (e: Exception) {
            return "FALLÓ: no pude convertir el audio (${e.message})"
        }
        val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        // Si el cliente corta o se pide detener, frenar la reproducción.
        val watcher = Thread {
            try {
                while (true) {
                    Thread.sleep(400)
                    if (!inCall() || Live.stopRequested) {
                        try { shell.stopPlay() } catch (_: Exception) {}
                        break
                    }
                }
            } catch (_: InterruptedException) {}
        }
        watcher.start()
        return try {
            val r = shell.injectPcm(pfd, Pcm.RATE)
            if (r.startsWith("OK")) "audio directo $r" else "FALLÓ: $r"
        } catch (e: Exception) {
            "FALLÓ: ${e.message}"
        } finally {
            watcher.interrupt()
            try { pfd.close() } catch (_: Exception) {}
        }
    }

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

        // Todo al máximo: multimedia y volumen de llamada.
        val streams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL)
        val oldVols = streams.associateWith { am.getStreamVolume(it) }
        for (s in streams) {
            try { am.setStreamVolume(s, am.getStreamMaxVolume(s), 0) } catch (_: Exception) {}
        }

        val asCall = Store.audioOut(this) == Store.OUT_CALL
        val mp = MediaPlayer()
        var enhancer: LoudnessEnhancer? = null
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (asCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            val boost = Store.boostMb(this)
            if (boost > 0) {
                try {
                    enhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                        setTargetGain(boost)
                        setEnabled(true)
                    }
                    notes.add("refuerzo +${boost / 100} dB")
                } catch (e: Exception) {
                    notes.add("sin refuerzo (${e.message})")
                }
            }
            notes.add(if (asCall) "salida: llamada" else "salida: multimedia")
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
            try { enhancer?.release() } catch (_: Exception) {}
            mp.release()
            for ((s, v) in oldVols) {
                try { am.setStreamVolume(s, v, 0) } catch (_: Exception) {}
            }
        }
        return notes.joinToString(", ")
    }

    /** Lee los subtítulos instantáneos al atender y decide si es persona o buzón. */
    private fun detectByCaptions(): Pair<String, String> {
        val svc = Live.service ?: return "UNKNOWN" to "accesibilidad inactiva"
        val machine = Store.capMachineWords(this)
        val human = Store.capHumanWords(this)
        val maxMs = Store.capListenMs(this).toLong()
        val silentMs = Store.capSilentMs(this).toLong()
        val start = System.currentTimeMillis()
        // Lo que ya estaba escrito al atender (por ejemplo, de una llamada anterior) no cuenta.
        val base = svc.captionText().orEmpty()
        var seen = false
        var heard = ""
        while (inCall()) {
            val el = System.currentTimeMillis() - start
            val t = svc.captionText()
            if (t != null) seen = true
            val all = t.orEmpty()
            heard = if (base.isNotEmpty() && all.startsWith(base)) all.removePrefix(base).trim() else if (all == base) "" else all
            val n = Store.norm(heard)
            val tokens = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
            val m = machine.firstOrNull { it.isNotEmpty() && n.contains(it) }
            if (m != null) return "MACHINE" to "dijo \"$m\" a los ${el / 100 / 10.0}s: «${heard.take(100)}»"
            val h = human.firstOrNull { w -> w.isNotEmpty() && (if (w.contains(' ')) n.contains(w) else w in tokens) }
            if (h != null) return "HUMAN" to "dijo \"$h\" a los ${el / 100 / 10.0}s: «${heard.take(100)}»"
            if (heard.isEmpty() && el >= silentMs) {
                if (!seen) return "UNKNOWN" to "no encontré los subtítulos en pantalla"
                return if (Store.capSilentIsMachine(this)) "MACHINE" to "silencio ${silentMs / 1000.0}s"
                else "HUMAN" to "silencio ${silentMs / 1000.0}s"
            }
            if (el >= maxMs) {
                return "HUMAN" to "habló sin palabras clave: «${heard.take(100)}»"
            }
            pause(200)
        }
        return "UNKNOWN" to "se cortó mientras escuchaba: «${heard.take(100)}»"
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
        val (result, detail) = doCall(c, captureTest = true)
        Store.appendResult(this, c, result, detail)
        Live.status = "Prueba terminada: $result - $detail"
    }
}
