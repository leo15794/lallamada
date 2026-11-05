package com.lallamada.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
