package com.lallamada.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQ_CONTACTS = 1
        private const val REQ_EXPORT_RESULTS = 2
        private const val REQ_EXPORT_DIAG = 3
        private const val REQ_PERMS = 10
    }

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var message: EditText
    private lateinit var modeSpeak: RadioButton
    private lateinit var modeDial: RadioButton
    private lateinit var ring: EditText
    private lateinit var pauseField: EditText
    private lateinit var talk: EditText
    private lateinit var hStart: EditText
    private lateinit var hEnd: EditText
    private lateinit var kwOpen: EditText
    private lateinit var kwSend: EditText
    private lateinit var kwHangup: EditText
    private lateinit var blocklist: EditText
    private lateinit var diag: CheckBox
    private lateinit var testNumber: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad * 3)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        root.addView(statusView)

        section("1. Preparar el teléfono")
        button("Dar permisos de llamadas") { askPermissions() }
        button("Activar accesibilidad de La Llamada") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        note(
            "Si la opción aparece gris o dice 'configuración restringida': Ajustes > Aplicaciones > " +
                "La Llamada > menú ⋮ (arriba a la derecha) > 'Permitir configuración restringida'. " +
                "Después volvé a activar la accesibilidad."
        )

        section("2. Clientes")
        button("Cargar lista de clientes (CSV o TXT)") { pickContacts() }
        note("Un cliente por línea: teléfono y nombre separados por coma o punto y coma. Desde Excel: Guardar como > CSV.")
        label("Lista de bajas / No Llame (un número por línea; nunca se llaman):")
        blocklist = edit(Store.str(this, "blocklist", ""), multiline = true)

        section("3. Mensaje")
        message = edit(Store.message(this), multiline = true, hint = "Hola, te llamamos de ... con una promo de ...")
        val group = RadioGroup(this)
        modeSpeak = RadioButton(this).apply { text = "Decir el mensaje (voz del sistema)"; id = View.generateViewId() }
        modeDial = RadioButton(this).apply { text = "Solo marcar (el audio lo pongo yo por cable)"; id = View.generateViewId() }
        group.addView(modeSpeak)
        group.addView(modeDial)
        root.addView(group)
        if (Store.mode(this) == Store.MODE_DIAL_ONLY) modeDial.isChecked = true else modeSpeak.isChecked = true

        section("4. Ritmo y horario")
        label("Segundos de espera para que atiendan:")
        ring = numberField(Store.ringTimeout(this))
        label("Segundos de pausa entre llamadas (más pausa = menos riesgo de bloqueo):")
        pauseField = numberField(Store.pauseSec(this))
        label("Segundos que dura el mensaje (0 = calcular solo):")
        talk = numberField(Store.talkSec(this))
        label("Llamar desde la hora / hasta la hora (0-24):")
        hStart = numberField(Store.hourStart(this))
        hEnd = numberField(Store.hourEnd(this))

        section("5. Ajustes avanzados")
        label("Palabras del botón para escribir respuesta (separadas por coma):")
        kwOpen = edit(Store.str(this, "kwOpen", Store.DEFAULT_OPEN), multiline = true)
        label("Palabras del botón Enviar:")
        kwSend = edit(Store.str(this, "kwSend", Store.DEFAULT_SEND))
        label("Palabras del botón Colgar:")
        kwHangup = edit(Store.str(this, "kwHangup", Store.DEFAULT_HANGUP))
        diag = CheckBox(this).apply {
            text = "Modo diagnóstico (guarda lo que se ve en pantalla en cada llamada)"
            isChecked = Store.diagnostic(this@MainActivity)
        }
        root.addView(diag)

        section("6. Probar con una sola llamada")
        testNumber = edit(Store.str(this, "testNumber", ""), hint = "Tu número para probar")
        testNumber.inputType = InputType.TYPE_CLASS_PHONE
        button("Llamar de prueba") { startTest() }

        section("7. Campaña")
        button("▶ INICIAR CAMPAÑA") { startCampaign() }
        button("■ DETENER") {
            startService(Intent(this, CampaignService::class.java).setAction(CampaignService.ACTION_STOP))
        }
        button("Volver a empezar desde el primer cliente") {
            Store.setIndex(this, 0)
            toast("La próxima campaña empieza desde el primero")
        }

        section("8. Resultados")
        button("Exportar resultados (CSV)") { export(REQ_EXPORT_RESULTS, "resultados.csv", "text/csv") }
        button("Exportar diagnóstico (para soporte)") { export(REQ_EXPORT_DIAG, "diagnostico.txt", "text/plain") }
        button("Borrar resultados y diagnóstico") {
            Store.resultsFile(this).delete()
            Store.diagFile(this).delete()
            toast("Borrado")
        }

        if (BuildConfig.DEBUG) handleAutoTest(intent)
    }

    /** Solo en la versión de prueba: permite que el emulador de GitHub dispare una llamada de prueba. */
    private fun handleAutoTest(i: Intent?) {
        if (i == null) return
        val n = i.getStringExtra("auto_test_number") ?: return
        message.setText(i.getStringExtra("auto_message") ?: "Mensaje de prueba")
        testNumber.setText(n)
        talk.setText("15")
        handler.postDelayed({ startTest() }, 1500)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        save()
    }

    // ---------- UI helpers ----------

    private fun section(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }

    private fun label(t: String) {
        root.addView(TextView(this).apply { text = t; setPadding(0, (8 * dp).toInt(), 0, 0) })
    }

    private fun note(t: String) {
        root.addView(TextView(this).apply { text = t; textSize = 12f; alpha = 0.7f })
    }

    private fun button(t: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun edit(value: String, multiline: Boolean = false, hint: String = ""): EditText {
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
            }
        }
        root.addView(e)
        return e
    }

    private fun numberField(v: Int): EditText {
        val e = edit(v.toString())
        e.inputType = InputType.TYPE_CLASS_NUMBER
        return e
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun num(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def

    // ---------- Lógica ----------

    private fun save() {
        Store.putStr(this, "message", message.text.toString().trim())
        Store.putStr(this, "mode", if (modeDial.isChecked) Store.MODE_DIAL_ONLY else Store.MODE_SPEAK)
        Store.putInt(this, "ring", num(ring, 35).coerceIn(10, 120))
        Store.putInt(this, "pause", num(pauseField, 20).coerceIn(0, 3600))
        Store.putInt(this, "talk", num(talk, 0).coerceIn(0, 600))
        Store.putInt(this, "hStart", num(hStart, 9).coerceIn(0, 24))
        Store.putInt(this, "hEnd", num(hEnd, 20).coerceIn(0, 24))
        Store.putStr(this, "kwOpen", kwOpen.text.toString())
        Store.putStr(this, "kwSend", kwSend.text.toString())
        Store.putStr(this, "kwHangup", kwHangup.text.toString())
        Store.putStr(this, "blocklist", blocklist.text.toString())
        Store.putBool(this, "diag", diag.isChecked)
        Store.putStr(this, "testNumber", testNumber.text.toString().trim())
    }

    private val neededPermissions: Array<String>
        get() {
            val l = mutableListOf(
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
            return l.toTypedArray()
        }

    private fun missingPermissions() =
        neededPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun askPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) toast("Ya están todos los permisos") else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun refresh() {
        val perms = missingPermissions()
        val contacts = Store.loadContacts(this).size
        val next = Store.index(this)
        statusView.text = buildString {
            append(if (perms.isEmpty()) "✅ Permisos OK\n" else "❌ Faltan permisos (${perms.size})\n")
            append(if (Live.service != null) "✅ Accesibilidad activa\n" else "❌ Accesibilidad inactiva\n")
            append("Clientes cargados: $contacts")
            if (contacts > 0) append(" — próximo: ${minOf(next + 1, contacts)}")
            append("\nLlamadas registradas: ${Store.resultCount(this@MainActivity)}")
            append("\n\nEstado: ${if (Live.running) "▶ " else ""}${Live.status}")
        }
    }

    private fun readyToCall(): Boolean {
        save()
        if (missingPermissions().isNotEmpty()) {
            toast("Primero tocá 'Dar permisos de llamadas'")
            return false
        }
        if (Live.service == null) {
            toast("Primero activá la accesibilidad de La Llamada")
            return false
        }
        if (Store.mode(this) == Store.MODE_SPEAK && Store.message(this).isBlank()) {
            toast("Escribí el mensaje")
            return false
        }
        if (Live.running) {
            toast("Ya hay una campaña en curso")
            return false
        }
        return true
    }

    private fun startTest() {
        if (!readyToCall()) return
        val n = testNumber.text.toString().trim()
        if (n.filter { it.isDigit() }.length < 6) {
            toast("Poné un número para la prueba")
            return
        }
        startForegroundService(Intent(this, CampaignService::class.java).putExtra(CampaignService.EXTRA_TEST, n))
    }

    private fun startCampaign() {
        if (!readyToCall()) return
        val contacts = Store.loadContacts(this)
        if (contacts.isEmpty()) {
            toast("Cargá la lista de clientes")
            return
        }
        if (Store.index(this) >= contacts.size) {
            toast("Ya se llamó a toda la lista. Tocá 'Volver a empezar' si querés repetir.")
            return
        }
        startForegroundService(Intent(this, CampaignService::class.java))
    }

    private fun pickContacts() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_CONTACTS)
    }

    private fun export(req: Int, name: String, mime: String) {
        val f = if (req == REQ_EXPORT_RESULTS) Store.resultsFile(this) else Store.diagFile(this)
        if (!f.exists()) {
            toast("Todavía no hay nada para exportar")
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, req)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQ_CONTACTS -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (text.startsWith("PK")) {
                        toast("Eso es un Excel (.xlsx). Guardalo como CSV y cargalo de nuevo.")
                        return
                    }
                    val list = Store.parseContacts(text)
                    Store.saveContacts(this, list)
                    toast("Cargados ${list.size} clientes")
                }
                REQ_EXPORT_RESULTS -> copyTo(Store.resultsFile(this), uri)
                REQ_EXPORT_DIAG -> copyTo(Store.diagFile(this), uri)
            }
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
        refresh()
    }

    private fun copyTo(f: File, uri: android.net.Uri) {
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        toast("Exportado")
    }
}
