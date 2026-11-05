package com.lallamada.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Contact(val phone: String, val name: String)

/** Estado compartido entre la pantalla, el servicio de campaña y el de accesibilidad. */
object Live {
    @Volatile var service: CallAccessibilityService? = null
    @Volatile var running = false
    @Volatile var stopRequested = false
    @Volatile var inCall = false
    @Volatile var status: String = "Detenido"
    @Volatile var worker: Thread? = null
    @Volatile var remoteStatus: String = "Control remoto apagado"
}

/** Configuración, lista de clientes y resultados guardados en el teléfono. */
object Store {
    const val MODE_AUDIO = "audio"
    const val MODE_SPEAK = "hablar"
    const val MODE_DIAL_ONLY = "solo_marcar"
    const val DEFAULT_SPEAKER = "Altavoz,Speaker,Parlante,Manos libres"

    const val DEFAULT_OPEN =
        "Bixby,Llamada de texto,Text call,Escribir respuesta,Responder escribiendo,Escribir,Type responses,Type,Teclado,Keyboard"
    const val DEFAULT_SEND = "Enviar,Send"
    const val DEFAULT_HANGUP = "Finalizar llamada,Finalizar,Colgar,Cortar,End call"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun str(ctx: Context, key: String, def: String): String = p(ctx).getString(key, def) ?: def
    fun int(ctx: Context, key: String, def: Int): Int = p(ctx).getInt(key, def)
    fun bool(ctx: Context, key: String, def: Boolean): Boolean = p(ctx).getBoolean(key, def)
    fun putStr(ctx: Context, key: String, v: String) = p(ctx).edit().putString(key, v).apply()
    fun putInt(ctx: Context, key: String, v: Int) = p(ctx).edit().putInt(key, v).apply()
    fun putBool(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()

    fun message(ctx: Context) = str(ctx, "message", "")
    fun mode(ctx: Context) = str(ctx, "mode", MODE_AUDIO)
    fun kwSpeaker(ctx: Context) = splitList(str(ctx, "kwSpeaker", DEFAULT_SPEAKER))
    fun audioFile(ctx: Context) = File(ctx.filesDir, "mensaje_audio")
    fun audioName(ctx: Context) = str(ctx, "audioName", "")
    /** Refuerzo de volumen en milibeles (LoudnessEnhancer). 1000 mB = +10 dB. */
    fun boostMb(ctx: Context) = int(ctx, "boost", 1500)
    const val OUT_MEDIA = "media"
    const val OUT_CALL = "call"
    fun audioOut(ctx: Context) = str(ctx, "audioOut", OUT_MEDIA)
    const val METHOD_DIRECT = "directo"
    const val METHOD_SPEAKER = "altavoz"
    const val METHOD_MTK = "mtk"
    fun audioMethod(ctx: Context) = str(ctx, "audioMethod", METHOD_SPEAKER)
    fun ringTimeout(ctx: Context) = int(ctx, "ring", 35)
    fun pauseSec(ctx: Context) = int(ctx, "pause", 20)
    fun talkSec(ctx: Context) = int(ctx, "talk", 0)
    fun hourStart(ctx: Context) = int(ctx, "hStart", 9)
    fun hourEnd(ctx: Context) = int(ctx, "hEnd", 20)
    fun kwOpen(ctx: Context) = splitList(str(ctx, "kwOpen", DEFAULT_OPEN))
    fun kwSend(ctx: Context) = splitList(str(ctx, "kwSend", DEFAULT_SEND))
    fun kwHangup(ctx: Context) = splitList(str(ctx, "kwHangup", DEFAULT_HANGUP))
    fun diagnostic(ctx: Context) = bool(ctx, "diag", true)
    fun index(ctx: Context) = int(ctx, "index", 0)

    // Detección de contestador (configurable desde el panel)
    fun amdEnabled(ctx: Context) = bool(ctx, "amdEnabled", false)
    fun amdOnMachine(ctx: Context) = str(ctx, "amdOnMachine", "cortar")
    fun amdThresholdDb(ctx: Context) = int(ctx, "amdThresholdDb", -45)
    fun amdInitialSilenceMs(ctx: Context) = int(ctx, "amdInitialSilenceMs", 1500)
    fun amdSilenceEndMs(ctx: Context) = int(ctx, "amdSilenceEndMs", 700)
    fun amdMachineMs(ctx: Context) = int(ctx, "amdMachineMs", 3000)
    fun amdMaxMs(ctx: Context) = int(ctx, "amdMaxMs", 5000)
    fun answerDelayMs(ctx: Context) = int(ctx, "answerDelayMs", 1000)

    // Detección de buzón leyendo los subtítulos instantáneos (configurable desde el panel)
    const val DEFAULT_CAP_MACHINE =
        "mensaje,buzon,casilla,despues del tono,deje,grabe,no esta disponible,no se encuentra,fuera del area," +
            "apagado,no puede atender,voicemail,leave a message,after the tone,not available,mailbox,record your"
    const val DEFAULT_CAP_HUMAN = "hola,si,quien,diga,alo,bueno,buenas,buen dia,que tal,hello,hi,yes,who"
    fun capEnabled(ctx: Context) = bool(ctx, "capEnabled", false)
    fun capListenMs(ctx: Context) = int(ctx, "capListenMs", 5000)
    fun capSilentMs(ctx: Context) = int(ctx, "capSilentMs", 3500)
    fun capSilentIsMachine(ctx: Context) = bool(ctx, "capSilentIsMachine", true)
    fun capMachineWords(ctx: Context) = splitList(str(ctx, "capMachineWords", DEFAULT_CAP_MACHINE)).map { norm(it) }
    fun capHumanWords(ctx: Context) = splitList(str(ctx, "capHumanWords", DEFAULT_CAP_HUMAN)).map { norm(it) }

    /** Minúsculas y sin acentos, para comparar texto. */
    fun norm(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
    fun setIndex(ctx: Context, i: Int) = putInt(ctx, "index", i)

    fun splitList(s: String): List<String> =
        s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Clave de comparación: últimos 8 dígitos (tolera 0, 15, +54, 9, etc.). */
    fun key(phone: String): String = phone.filter { it.isDigit() }.takeLast(8)

    fun blockedKeys(ctx: Context): Set<String> =
        str(ctx, "blocklist", "")
            .split('\n', ',', ';', '\t')
            .map { key(it) }
            .filter { it.length == 8 }
            .toSet()

    // ---------- Clientes ----------

    fun parseContacts(text: String): List<Contact> =
        text.replace("\u0000", "").replace("\uFEFF", "").lineSequence().mapNotNull { line ->
            var phone: String? = null
            var name = ""
            for (raw in line.split(',', ';', '\t')) {
                val s = raw.trim().trim('"').trim()
                if (s.isEmpty()) continue
                val digits = s.filter { it.isDigit() }
                val looksLikePhone = digits.length >= 8 && s.all { it.isDigit() || it in "+-() ." }
                if (phone == null && looksLikePhone) {
                    phone = (if (s.startsWith("+")) "+" else "") + digits
                } else if (name.isEmpty() && digits.isEmpty()) {
                    name = s
                }
            }
            phone?.let { Contact(it, name) }
        }.distinctBy { key(it.phone) }.toList()

    private fun contactsFile(ctx: Context) = File(ctx.filesDir, "clientes.tsv")

    fun saveContacts(ctx: Context, list: List<Contact>) {
        contactsFile(ctx).writeText(list.joinToString("\n") { "${it.phone}\t${it.name.replace('\t', ' ')}" })
        setIndex(ctx, 0)
    }

    fun loadContacts(ctx: Context): List<Contact> {
        val f = contactsFile(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts[0].isBlank()) null else Contact(parts[0], parts.getOrElse(1) { "" })
        }
    }

    // ---------- Resultados ----------

    fun resultsFile(ctx: Context) = File(ctx.filesDir, "resultados.csv")
    fun diagFile(ctx: Context) = File(ctx.filesDir, "diagnostico.txt")

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun csv(s: String) = "\"" + s.replace("\"", "'") + "\""

    @Synchronized
    fun appendResult(ctx: Context, c: Contact, result: String, detail: String) {
        val f = resultsFile(ctx)
        if (!f.exists()) f.writeText("fecha,telefono,nombre,resultado,detalle\n")
        f.appendText(listOf(now(), c.phone, c.name, result, detail).joinToString(",") { csv(it) } + "\n")
    }

    /** Últimos resultados como listas de campos (fecha, teléfono, nombre, resultado, detalle). */
    fun lastResults(ctx: Context, n: Int): List<List<String>> {
        val f = resultsFile(ctx)
        if (!f.exists()) return emptyList()
        val re = Regex("\"([^\"]*)\"")
        return f.readLines().drop(1).takeLast(n).reversed().map { line ->
            re.findAll(line).map { it.groupValues[1] }.toList()
        }
    }

    fun resultCount(ctx: Context): Int {
        val f = resultsFile(ctx)
        return if (f.exists()) (f.readLines().size - 1).coerceAtLeast(0) else 0
    }

    @Synchronized
    fun appendDiag(ctx: Context, text: String) {
        val f = diagFile(ctx)
        if (f.exists() && f.length() > 3_000_000) f.delete()
        f.appendText("\n########## ${now()} ##########\n$text\n")
    }
}
