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

    /**
     * Prueba de captura del audio que llega del otro lado (para detectar contestadores).
     * No graba nada: calcula el volumen cada 250 ms y lo devuelve como texto.
     */
    override fun captureLevels(seconds: Int): String {
        val rate = 16000
        val shellCtx = try { context?.createPackageContext("com.android.shell", 0) } catch (e: Throwable) { null }
        val sources = listOf(3 to "VOICE_DOWNLINK", 4 to "VOICE_CALL")
        val errors = StringBuilder()
        for ((src, name) in sources) {
            var rec: AudioRecord? = null
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
                rec = b.build()
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    errors.append(name).append(": no inicializa; ")
                    continue
                }
                rec.startRecording()
                val frame = rate / 4
                val buf = ShortArray(frame)
                val levels = ArrayList<Double>()
                val end = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < end) {
                    var got = 0
                    while (got < frame) {
                        val n = rec.read(buf, got, frame - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got <= 0) break
                    var sum = 0.0
                    for (i in 0 until got) {
                        val v = buf[i] / 32768.0
                        sum += v * v
                    }
                    val rms = Math.sqrt(sum / got)
                    levels.add(if (rms > 0) 20 * Math.log10(rms) else -100.0)
                }
                if (levels.isEmpty()) {
                    errors.append(name).append(": sin datos; ")
                    continue
                }
                val max = levels.maxOrNull() ?: -100.0
                val avg = levels.average()
                val voice = levels.count { it > -45 } * 100 / levels.size
                val chars = " .:-=+*#"
                val spark = levels.joinToString("") { l ->
                    chars[((l + 70) / 50 * 7).toInt().coerceIn(0, 7)].toString()
                }
                return "OK fuente=$name máx=${max.toInt()}dB prom=${avg.toInt()}dB voz=$voice% [$spark]"
            } catch (e: Throwable) {
                errors.append(name).append(": ").append(cause(e)).append("; ")
            } finally {
                try { rec?.stop() } catch (_: Throwable) {}
                try { rec?.release() } catch (_: Throwable) {}
            }
        }
        return "FALLÓ: $errors"
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
