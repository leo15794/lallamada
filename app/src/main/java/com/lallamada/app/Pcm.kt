package com.lallamada.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Convierte el audio cargado (MP3, M4A, OGG, WAV...) a PCM 16 bits mono a 48 kHz,
 * normalizado al volumen máximo sin distorsión, listo para mandarlo a la llamada.
 */
object Pcm {
    const val RATE = 48000

    fun file(ctx: Context) = File(ctx.filesDir, "mensaje_48k.pcm")

    @Synchronized
    fun prepare(ctx: Context): File {
        val src = Store.audioFile(ctx)
        require(src.exists()) { "no hay audio cargado" }
        val out = file(ctx)
        if (out.exists() && out.length() > 0 && out.lastModified() >= src.lastModified()) return out

        val (samples, rate) = decodeMono(src)
        require(samples.isNotEmpty()) { "el audio está vacío" }
        val resampled = resample(samples, rate, RATE)

        var peak = 0f
        for (s in resampled) peak = maxOf(peak, abs(s))
        val gain = if (peak > 0f) minOf(0.95f / peak, 20f) else 1f

        BufferedOutputStream(FileOutputStream(out)).use { os ->
            for (s in resampled) {
                val v = (s * gain * 32767f).toInt().coerceIn(-32768, 32767)
                os.write(v and 0xff)
                os.write((v shr 8) and 0xff)
            }
        }
        return out
    }

    private class FloatList {
        var data = FloatArray(1 shl 16)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    private fun decodeMono(src: File): Pair<FloatArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            ex.release()
            throw IllegalArgumentException("el archivo no tiene audio reconocible")
        }
        ex.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    val ch = channels.coerceAtLeast(1)
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = ob.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until ch) s += fb.get()
                            out.add(s / ch)
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until ch) s += sb.get() / 32768f
                            out.add(s / ch)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        return out.toArray() to rate
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt()
            val i1 = minOf(i0 + 1, input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }
}
