package com.lallamada.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException

/**
 * Corre en un proceso aparte con permisos de "depuración" (usuario shell, uid 2000), lanzado por Shizuku.
 * Desde Android 13 el sistema tiene una función oculta para inyectar audio en el canal de subida
 * de una llamada celular (AudioManager#getCallUplinkInjectionAudioTrack), que requiere el permiso
 * CALL_AUDIO_INTERCEPTION. El usuario shell lo tiene en muchos equipos; las apps comunes no.
 */
class ShellService() : IShellService.Stub() {

    private var context: Context? = null
    @Volatile private var stop = false

    constructor(ctx: Context) : this() {
        context = ctx
    }

    override fun destroy() {
        System.exit(0)
    }

    private fun audioManager(): AudioManager? {
        val ctx = context ?: return null
        // Contexto a nombre del paquete shell, para que el sistema no rechace el uid 2000.
        val shellCtx = try { ctx.createPackageContext("com.android.shell", 0) } catch (e: Throwable) { null }
        return (shellCtx ?: ctx).getSystemService(AudioManager::class.java)
    }

    private fun exec(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.take(8000)
    } catch (e: Throwable) {
        "error: ${e.message}"
    }

    private fun cause(e: Throwable): String {
        val c = if (e is InvocationTargetException) e.targetException ?: e else e
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    override fun check(): String {
        val sb = StringBuilder()
        sb.append("uid=").append(Process.myUid()).append('\n')
        val am = audioManager()
        if (am == null) {
            sb.append("contexto: NO (Shizuku viejo; actualizalo)\n")
        } else {
            try {
                val m = AudioManager::class.java.getMethod("isPstnCallAudioInterceptable")
                sb.append("pstn_interceptable=").append(m.invoke(am)).append('\n')
            } catch (e: Throwable) {
                sb.append("pstn_interceptable=ERROR ").append(cause(e)).append('\n')
            }
        }
        sb.append("--- permisos del shell ---\n")
        sb.append(
            exec(
                "dumpsys package com.android.shell | grep -E " +
                    "'CALL_AUDIO_INTERCEPTION|MODIFY_PHONE_STATE|CAPTURE_AUDIO_OUTPUT|MODIFY_AUDIO_ROUTING' | sort -u"
            )
        )
        sb.append("--- rutas de telefonía ---\n")
        sb.append(exec("dumpsys media.audio_policy | grep -iE 'TELEPHONY_TX|TELEPHONY_RX' | sort -u | head -20"))
        return sb.toString()
    }

    override fun setParams(keyValues: String) {
        audioManager()?.setParameters(keyValues)
    }

    private var lastOpenError = ""

    /** Abre la captura del audio que llega de la llamada (lo que dice el otro). */
    private fun openDownlink(rate: Int): AudioRecord? {
        val shellCtx = try { context?.createPackageContext("com.android.shell", 0) } catch (e: Throwable) { null }
        val errors = StringBuilder()
        for ((src, name) in listOf(3 to "VOICE_DOWNLINK", 4 to "VOICE_CALL")) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val b = AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, rate))
                if (shellCtx != null && Build.VERSION.SDK_INT >= 31) b.setContext(shellCtx)
                val rec = b.build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    lastOpenError = name
                    return rec
                }
                rec.release()
                errors.append(name).append(": no inicializa; ")
            } catch (e: Throwable) {
                errors.append(name).append(": ").append(cause(e)).append("; ")
            }
        }
        lastOpenError = errors.toString()
        return null
    }

    private fun frameDb(rec: AudioRecord, buf: ShortArray): Double? {
        var got = 0
        while (got < buf.size) {
            val n = rec.read(buf, got, buf.size - got)
            if (n <= 0) break
            got += n
        }
        if (got <= 0) return null
        var sum = 0.0
        for (i in 0 until got) {
            val v = buf[i] / 32768.0
            sum += v * v
        }
        val rms = Math.sqrt(sum / got)
        return if (rms > 0) 20 * Math.log10(rms) else -100.0
    }

    private fun sparkChar(db: Double): Char {
        val chars = " .:-=+*#"
        return chars[((db + 70) / 50 * 7).toInt().coerceIn(0, 7)]
    }

    /** Prueba: volumen de lo que llega de la llamada cada 250 ms durante unos segundos. No graba nada. */
    override fun captureLevels(seconds: Int): String {
        val rate = 16000
        val rec = openDownlink(rate) ?: return "FALLÓ: $lastOpenError"
        val source = lastOpenError
        try {
            rec.startRecording()
            val buf = ShortArray(rate / 4)
            val levels = ArrayList<Double>()
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) levels.add(frameDb(rec, buf) ?: break)
            if (levels.isEmpty()) return "FALLÓ: sin datos"
            val voice = levels.count { it > -45 } * 100 / levels.size
            val spark = levels.joinToString("") { sparkChar(it).toString() }
            return "OK fuente=$source máx=${(levels.maxOrNull() ?: -100.0).toInt()}dB " +
                "prom=${levels.average().toInt()}dB voz=$voice% [$spark]"
        } catch (e: Throwable) {
            return "FALLÓ: ${cause(e)}"
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Detección de contestador escuchando al que atendió (cuadros de 50 ms):
     *  - habló y después hizo silencio (silenceEndMs) antes de llegar a machineMs → persona
     *  - no dijo nada durante initialSilenceMs → persona callada
     *  - acumuló machineMs de voz → contestador
     */
    override fun detect(thresholdDb: Int, initialSilenceMs: Int, silenceEndMs: Int, machineMs: Int, maxMs: Int): String {
        val rate = 16000
        val frameMs = 50
        val rec = openDownlink(rate) ?: return "FAIL|no pude escuchar la llamada: $lastOpenError"
        try {
            rec.startRecording()
            val buf = ShortArray(rate * frameMs / 1000)
            var elapsed = 0
            var speechMs = 0
            var silenceMs = 0
            var heard = false
            var maxDb = -100.0
            var verdict = ""
            var why = ""
            val spark = StringBuilder()
            var pair = -100.0
            while (elapsed < maxMs) {
                val db = frameDb(rec, buf) ?: break
                elapsed += frameMs
                maxDb = maxOf(maxDb, db)
                pair = maxOf(pair, db)
                if (elapsed % 100 == 0) {
                    spark.append(sparkChar(pair))
                    pair = -100.0
                }
                if (db > thresholdDb) {
                    speechMs += frameMs
                    silenceMs = 0
                    heard = true
                } else {
                    silenceMs += frameMs
                }
                if (speechMs >= machineMs) { verdict = "MACHINE"; why = "habló de corrido"; break }
                if (!heard && silenceMs >= initialSilenceMs) { verdict = "HUMAN"; why = "atendió en silencio"; break }
                if (heard && silenceMs >= silenceEndMs) { verdict = "HUMAN"; why = "saludó y esperó"; break }
            }
            if (verdict.isEmpty()) {
                if (speechMs >= machineMs * 2 / 3) { verdict = "MACHINE"; why = "tiempo máximo, mucha voz" }
                else { verdict = "HUMAN"; why = "tiempo máximo" }
            }
            return "$verdict|$why en ${elapsed}ms, voz ${speechMs}ms, máx ${maxDb.toInt()}dB [$spark]"
        } catch (e: Throwable) {
            return "FAIL|${cause(e)}"
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    override fun stopPlay() {
        stop = true
    }

    override fun injectPcm(pcm: ParcelFileDescriptor, sampleRate: Int): String {
        stop = false
        val am = audioManager() ?: return "sin contexto (actualizá Shizuku)"
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track: AudioTrack = try {
            val m = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            m.invoke(am, format) as AudioTrack
        } catch (e: Throwable) {
            pcm.close()
            return "no pude abrir la ruta de la llamada: ${cause(e)}"
        }
        var written = 0L
        try {
            track.play()
            FileInputStream(pcm.fileDescriptor).use { input ->
                val buf = ByteArray(sampleRate / 10 * 2) // 100 ms
                while (!stop) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && !stop) {
                        val w = track.write(buf, off, n - off)
                        if (w < 0) return "error al escribir audio ($w)"
                        off += w
                        written += w
                    }
                }
            }
            if (!stop) Thread.sleep(400) // dejar que salga lo último del buffer
            return "OK ${written / 2 / sampleRate} s"
        } catch (e: Throwable) {
            return "error: ${cause(e)}"
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            track.release()
            try { pcm.close() } catch (_: Throwable) {}
        }
    }
}
