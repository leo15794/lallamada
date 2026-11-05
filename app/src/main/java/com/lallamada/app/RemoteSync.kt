package com.lallamada.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conexión con el panel web: cada pocos segundos manda el estado y los resultados nuevos,
 * y recibe órdenes (iniciar, detener, prueba, volver a empezar), configuración, clientes y audio.
 */
class RemoteSync(private val svc: CampaignService) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        Live.remoteStatus = "Conectando con el panel..."
        thread = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        while (running) {
            main.post { ShellBridge.bind() }
            try {
                syncOnce()
                val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                Live.remoteStatus = "✓ Conectado al panel (último contacto $t)"
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Live.remoteStatus = "Sin conexión con el panel: ${e.message}"
            }
            try { Thread.sleep(4000) } catch (e: InterruptedException) { break }
        }
    }

    private fun base() = Store.str(svc, "remoteUrl", "").trim().trimEnd('/')
    private fun key() = Store.str(svc, "remoteKey", "").trim()

    private fun http(method: String, path: String, body: String? = null): String {
        val b = base()
        require(b.startsWith("http")) { "falta la dirección del panel" }
        val c = URL(b + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("x-key", key())
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw IllegalStateException("contraseña incorrecta")
            if (code >= 400) throw IllegalStateException("error $code del panel")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun syncOnce() {
        // Resultados nuevos desde el último envío
        val lines = Store.resultsFile(svc).let { if (it.exists()) it.readLines().drop(1) else emptyList() }
        var sent = Store.int(svc, "remoteSent", 0)
        if (sent > lines.size) sent = 0 // se borraron los resultados en el celular
        val pending = lines.drop(sent).take(200)
        val re = Regex("\"([^\"]*)\"")
        val arr = JSONArray()
        for (l in pending) {
            val f = re.findAll(l).map { it.groupValues[1] }.toList()
            if (f.size >= 5) {
                arr.put(
                    JSONObject().put("ts", f[0]).put("phone", f[1]).put("name", f[2])
                        .put("result", f[3]).put("detail", f[4])
                )
            }
        }

        val status = JSONObject()
            .put("status", Live.status)
            .put("running", Live.running)
            .put("index", Store.index(svc))
            .put("total", Store.loadContacts(svc).size)
            .put("accessibility", Live.service != null)
            .put("shizuku", ShellBridge.service != null)
            .put("audioName", Store.audioName(svc))
            .put("hasAudio", Store.audioFile(svc).exists())
            .put("version", BuildConfig.VERSION_NAME)

        val resp = JSONObject(
            http("POST", "/api/device", JSONObject().put("status", status).put("results", arr).toString())
        )
        Store.putInt(svc, "remoteSent", sent + pending.size)

        resp.optJSONObject("config")?.let { applyConfig(it) }

        if (!Live.running) {
            val cv = resp.optLong("clientsVersion", 0L)
            if (cv > 0 && cv.toString() != Store.str(svc, "clientsVersion", "")) {
                downloadClients()
                Store.putStr(svc, "clientsVersion", cv.toString())
            }
            val av = resp.optLong("audioVersion", 0L)
            if (av > 0 && av.toString() != Store.str(svc, "audioVersion", "")) {
                downloadAudio()
                Store.putStr(svc, "audioVersion", av.toString())
            }
        }

        val cmds = resp.optJSONArray("commands") ?: JSONArray()
        for (i in 0 until cmds.length()) {
            val o = cmds.getJSONObject(i)
            val arg = o.optString("arg", "")
            when (o.optString("cmd")) {
                "start" -> svc.startWork(null)
                "stop" -> svc.requestStop()
                "test" -> {
                    val n = arg.ifBlank { Store.str(svc, "testNumber", "") }
                    if (n.isNotBlank()) svc.startWork(n)
                }
                "reset" -> if (!Live.running) Store.setIndex(svc, 0)
            }
        }
    }

    private fun applyConfig(c: JSONObject) {
        fun i(k: String, store: String, lo: Int, hi: Int) {
            if (c.has(k)) Store.putInt(svc, store, c.optInt(k).coerceIn(lo, hi))
        }
        i("ring", "ring", 10, 120)
        i("pause", "pause", 0, 3600)
        i("hStart", "hStart", 0, 24)
        i("hEnd", "hEnd", 0, 24)
        if (c.has("audioMethod")) Store.putStr(svc, "audioMethod", c.optString("audioMethod"))
        if (c.has("amdEnabled")) Store.putBool(svc, "amdEnabled", c.optBoolean("amdEnabled"))
        if (c.has("amdOnMachine")) Store.putStr(svc, "amdOnMachine", c.optString("amdOnMachine"))
        i("amdThresholdDb", "amdThresholdDb", -80, -5)
        i("amdInitialSilenceMs", "amdInitialSilenceMs", 300, 10000)
        i("amdSilenceEndMs", "amdSilenceEndMs", 200, 5000)
        i("amdMachineMs", "amdMachineMs", 500, 15000)
        i("amdMaxMs", "amdMaxMs", 1000, 20000)
        i("answerDelayMs", "answerDelayMs", 0, 10000)
        if (c.has("capEnabled")) Store.putBool(svc, "capEnabled", c.optBoolean("capEnabled"))
        if (c.has("capSilentIsMachine")) Store.putBool(svc, "capSilentIsMachine", c.optBoolean("capSilentIsMachine"))
        i("capListenMs", "capListenMs", 1000, 20000)
        i("capSilentMs", "capSilentMs", 500, 15000)
        if (c.has("capMachineWords")) Store.putStr(svc, "capMachineWords", c.optString("capMachineWords"))
        if (c.has("capHumanWords")) Store.putStr(svc, "capHumanWords", c.optString("capHumanWords"))
        if (c.has("testNumber")) Store.putStr(svc, "testNumber", c.optString("testNumber"))
        if (c.has("blocklist")) Store.putStr(svc, "blocklist", c.optString("blocklist"))
        Store.putStr(svc, "mode", Store.MODE_AUDIO)
    }

    private fun downloadClients() {
        val o = JSONObject(http("GET", "/api/clients"))
        val a = o.optJSONArray("clients") ?: JSONArray()
        val list = ArrayList<Contact>(a.length())
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val phone = c.optString("phone")
            if (phone.isNotBlank()) list.add(Contact(phone, c.optString("name")))
        }
        Store.saveContacts(svc, list)
    }

    private fun downloadAudio() {
        val o = JSONObject(http("GET", "/api/audio"))
        val b64 = o.optString("b64", "")
        if (b64.isEmpty()) return
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        Store.audioFile(svc).writeBytes(bytes)
        Pcm.file(svc).delete()
        Store.putStr(svc, "audioName", o.optString("name", "audio"))
    }
}
