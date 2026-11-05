package com.lallamada.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class MainActivity : Activity() {

    companion object {
        private const val REQ_CONTACTS = 1
        private const val REQ_EXPORT_RESULTS = 2
        private const val REQ_EXPORT_DIAG = 3
        private const val REQ_AUDIO = 4
        private const val REQ_PERMS = 10

        private val C_PRIMARY = 0xFF0F766E.toInt()
        private val C_PRIMARY_DARK = 0xFF115E59.toInt()
        private val C_PRIMARY_SOFT = 0xFFE6F4F2.toInt()
        private val C_GREEN = 0xFF16A34A.toInt()
        private val C_RED = 0xFFDC2626.toInt()
        private val C_BG = 0xFFF1F4F6.toInt()
        private val C_TEXT = 0xFF111827.toInt()
        private val C_MUTED = 0xFF6B7280.toInt()
        private val C_BORDER = 0xFFE5E7EB.toInt()
        private val C_FIELD = 0xFFF9FAFB.toInt()

        private const val S_PRIMARY = 0
        private const val S_OUTLINE = 1
        private const val S_GOOD = 2
        private const val S_BAD = 3

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var content: LinearLayout

    // Estado
    private lateinit var stateText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var chipPerms: TextView
    private lateinit var chipAcc: TextView
    private lateinit var chipClients: TextView
    private lateinit var btnPerms: Button
    private lateinit var btnAcc: Button
    private lateinit var setupCard: LinearLayout

    // Mensaje
    private val modeTabs = mutableMapOf<String, TextView>()
    private var mode = Store.MODE_AUDIO
    private lateinit var audioPanel: LinearLayout
    private lateinit var textPanel: LinearLayout
    private lateinit var dialPanel: LinearLayout
    private lateinit var audioInfo: TextView
    private lateinit var btnPreview: Button
    private lateinit var message: EditText
    private var preview: MediaPlayer? = null
    private var previewFx: LoudnessEnhancer? = null
    private lateinit var boost: EditText
    private val outViews = mutableMapOf<String, TextView>()
    private var audioOut = Store.OUT_MEDIA
    private val methodViews = mutableMapOf<String, TextView>()
    private var audioMethod = Store.METHOD_SPEAKER
    private lateinit var directPanel: LinearLayout
    private lateinit var speakerPanel: LinearLayout
    private lateinit var shizukuStatus: TextView
    private lateinit var btnShizukuInstall: Button
    private lateinit var btnShizukuOpen: Button
    private lateinit var btnShizukuPerm: Button
    private lateinit var checkResult: TextView
    private lateinit var mtkNote: TextView

    // Clientes
    private lateinit var clientsInfo: TextView
    private lateinit var blocklist: EditText

    // Ritmo
    private lateinit var ring: EditText
    private lateinit var pauseField: EditText
    private lateinit var talk: EditText
    private lateinit var hStart: EditText
    private lateinit var hEnd: EditText

    // Prueba / resultados / avanzado
    private lateinit var testNumber: EditText
    private lateinit var resultsInfo: TextView
    private lateinit var lastResults: LinearLayout
    private lateinit var kwOpen: EditText
    private lateinit var kwSend: EditText
    private lateinit var kwHangup: EditText
    private lateinit var kwSpeaker: EditText
    private lateinit var diag: CheckBox

    private var lastResultsKey = ""

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    // ===================================================================
    // Construcción de la pantalla
    // ===================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = C_PRIMARY_DARK
        mode = Store.mode(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        root.addView(header())
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(4), px(14), px(40))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        buildStatus()
        buildSetup()
        buildMessage()
        buildClients()
        buildRhythm()
        buildTest()
        buildResults()
        buildAdvanced()

        selectMode(mode)
        refreshAudioInfo()
        if (BuildConfig.DEBUG) handleAutoTest(intent)
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C_PRIMARY)
        setPadding(px(18), px(18), px(18), px(16))
        addView(tv("📞  La Llamada", 22f, Color.WHITE, bold = true))
        addView(tv("Campañas de llamadas con mensaje", 13f, 0xCCFFFFFF.toInt()))
    }

    private fun buildStatus() {
        val c = card(null)
        stateText = tv("Detenido", 17f, C_TEXT, bold = true)
        c.addView(stateText)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        c.addView(progress, lp(top = 10))
        progressText = tv("", 13f, C_MUTED)
        c.addView(progressText, lp(top = 2))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipPerms = chip()
        chipAcc = chip()
        chipClients = chip()
        chips.addView(chipPerms, LinearLayout.LayoutParams(0, WRAP, 1f))
        chips.addView(chipAcc, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(6) })
        chips.addView(chipClients, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(6) })
        c.addView(chips, lp(top = 12))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("▶  Iniciar", S_GOOD) { startCampaign() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(btn("■  Detener", S_BAD) {
            startService(Intent(this, CampaignService::class.java).setAction(CampaignService.ACTION_STOP))
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(10) })
        c.addView(row, lp(top = 14))
    }

    private fun buildSetup() {
        setupCard = card("Preparar el teléfono", "⚙️")
        btnPerms = btn("Dar permisos de llamadas", S_PRIMARY) { askPermissions() }
        setupCard.addView(btnPerms, lp(top = 10))
        btnAcc = btn("Activar accesibilidad", S_PRIMARY) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setupCard.addView(btnAcc, lp(top = 8))
        setupCard.addView(
            tv(
                "Si dice 'ajuste restringido': Ajustes › Aplicaciones › La Llamada › ⋮ › " +
                    "Permitir ajustes restringidos. También poné Batería › Sin restricciones.",
                12f, C_MUTED
            ), lp(top = 8)
        )
    }

    private fun buildMessage() {
        val c = card("Mensaje", "🔊")
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_FIELD, 12, C_BORDER)
            setPadding(px(4), px(4), px(4), px(4))
        }
        listOf(
            Store.MODE_AUDIO to "Audio",
            Store.MODE_SPEAK to "Texto",
            Store.MODE_DIAL_ONLY to "Solo marcar"
        ).forEachIndexed { i, (key, label) ->
            val t = tv(label, 14f, C_TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(10), 0, px(10))
                setOnClickListener { selectMode(key) }
            }
            modeTabs[key] = t
            tabs.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = px(4) })
        }
        c.addView(tabs, lp(top = 12))

        // Audio
        audioPanel = vbox()
        audioInfo = tv("", 14f, C_TEXT)
        audioPanel.addView(audioInfo, lp(top = 12))
        val arow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arow.addView(btn("Elegir audio", S_PRIMARY) { pickAudio() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        btnPreview = btn("▶  Escuchar", S_OUTLINE) { togglePreview() }
        arow.addView(btnPreview, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(10) })
        audioPanel.addView(arow, lp(top = 10))

        // Método: directo (Shizuku) o altavoz
        audioPanel.addView(tv("Cómo se pasa el audio a la llamada:", 13f, C_MUTED), lp(top = 14))
        val methodTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_FIELD, 12, C_BORDER)
            setPadding(px(4), px(4), px(4), px(4))
        }
        listOf(Store.METHOD_MTK to "MediaTek", Store.METHOD_DIRECT to "Directo", Store.METHOD_SPEAKER to "Altavoz").forEachIndexed { i, (key, label) ->
            val t = tv(label, 14f, C_TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(9), 0, px(9))
                setOnClickListener { selectMethod(key) }
            }
            methodViews[key] = t
            methodTabs.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = px(4) })
        }
        audioPanel.addView(methodTabs, lp(top = 4))

        directPanel = vbox()
        mtkNote = tv(
            "Para celulares con procesador MediaTek (como el Galaxy A05): abre el canal interno de " +
                "'sonido de fondo' del módem hacia la llamada. Mientras suena el audio se silencia el micrófono. " +
                "Funciona mejor con Shizuku conectado, pero se puede probar sin él.",
            12f, C_PRIMARY_DARK
        )
        directPanel.addView(mtkNote, lp(top = 8))
        directPanel.addView(
            tv(
                "El audio entra limpio a la llamada, sin altavoz. Necesita la app Shizuku " +
                    "(gratis, sin root). Si falla en una llamada, la app usa el altavoz automáticamente.",
                12f, C_MUTED
            ), lp(top = 8)
        )
        shizukuStatus = tv("", 14f, C_TEXT, bold = true)
        directPanel.addView(shizukuStatus, lp(top = 8))
        btnShizukuInstall = btn("1. Instalar Shizuku (Play Store)", S_OUTLINE) { openShizukuStore() }
        directPanel.addView(btnShizukuInstall, lp(top = 8))
        btnShizukuOpen = btn("2. Abrir Shizuku para iniciarlo", S_OUTLINE) { openShizuku() }
        directPanel.addView(btnShizukuOpen, lp(top = 8))
        btnShizukuPerm = btn("3. Dar permiso a La Llamada", S_PRIMARY) { ShellBridge.requestPermission() }
        directPanel.addView(btnShizukuPerm, lp(top = 8))
        directPanel.addView(btn("Verificar compatibilidad", S_PRIMARY) { runCheck() }, lp(top = 8))
        checkResult = tv("", 12f, C_TEXT).apply { setTextIsSelectable(true) }
        directPanel.addView(checkResult, lp(top = 8))
        audioPanel.addView(directPanel)

        speakerPanel = vbox()
        audioPanel.addView(speakerPanel)

        boost = field(speakerPanel, "Refuerzo de volumen (0 a 3000; probá 1500, 2000, 2500)",
            Store.boostMb(this).toString(), InputType.TYPE_CLASS_NUMBER)
        speakerPanel.addView(tv("Salida del audio durante la llamada (probá cuál se escucha más fuerte):", 13f, C_MUTED), lp(top = 12))
        val outTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_FIELD, 12, C_BORDER)
            setPadding(px(4), px(4), px(4), px(4))
        }
        listOf(Store.OUT_MEDIA to "Multimedia", Store.OUT_CALL to "Llamada").forEachIndexed { i, (key, label) ->
            val t = tv(label, 14f, C_TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(9), 0, px(9))
                setOnClickListener { selectOut(key) }
            }
            outViews[key] = t
            outTabs.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = px(4) })
        }
        speakerPanel.addView(outTabs, lp(top = 4))
        selectOut(Store.audioOut(this))
        selectMethod(Store.audioMethod(this))

        audioPanel.addView(
            tv(
                "Cuando atienden, la app pone la llamada en altavoz y reproduce el audio por el parlante. " +
                    "Android no permite meter el audio directo en la llamada, así que la calidad depende del celular: " +
                    "probalo primero llamando a tu número. Sirven MP3, M4A, WAV u OGG (también audios de WhatsApp).",
                12f, C_MUTED
            ), lp(top = 8)
        )
        c.addView(audioPanel)

        // Texto
        textPanel = vbox()
        message = field(textPanel, "Texto que lee el teléfono (Bixby / escribir respuesta)", Store.message(this),
            lines = 4, hint = "Hola, te llamamos de ... con una promo de ...")
        c.addView(textPanel)

        // Solo marcar
        dialPanel = vbox()
        dialPanel.addView(
            tv(
                "La app marca, espera que atiendan y mantiene la llamada los segundos configurados. " +
                    "El audio lo ponés vos (por ejemplo, con un cable desde otro aparato).",
                13f, C_MUTED
            ), lp(top = 12)
        )
        c.addView(dialPanel)
    }

    private fun buildClients() {
        val c = card("Clientes", "👥")
        clientsInfo = tv("", 14f, C_TEXT)
        c.addView(clientsInfo, lp(top = 8))
        c.addView(btn("Cargar lista (CSV o TXT)", S_PRIMARY) { pickContacts() }, lp(top = 10))
        c.addView(btn("Volver a empezar desde el primero", S_OUTLINE) {
            Store.setIndex(this, 0)
            toast("La próxima campaña empieza desde el primer cliente")
        }, lp(top = 8))
        c.addView(tv("Un cliente por línea: teléfono, nombre. Desde Excel: Guardar como › CSV.", 12f, C_MUTED), lp(top = 8))
        val body = collapsible(c, "Lista de bajas / No Llame")
        blocklist = field(body, "Números que nunca se llaman (uno por línea)", Store.str(this, "blocklist", ""), lines = 4)
    }

    private fun buildRhythm() {
        val c = card("Ritmo y horario", "⏱️")
        val r1 = row(c)
        ring = field(col(r1, 0), "Espera a que atiendan (s)", Store.ringTimeout(this).toString(), InputType.TYPE_CLASS_NUMBER)
        pauseField = field(col(r1, 10), "Pausa entre llamadas (s)", Store.pauseSec(this).toString(), InputType.TYPE_CLASS_NUMBER)
        val r2 = row(c)
        hStart = field(col(r2, 0), "Desde (hora)", Store.hourStart(this).toString(), InputType.TYPE_CLASS_NUMBER)
        hEnd = field(col(r2, 10), "Hasta (hora)", Store.hourEnd(this).toString(), InputType.TYPE_CLASS_NUMBER)
        talk = field(c, "Duración del mensaje en modo texto / solo marcar (s, 0 = automático)",
            Store.talkSec(this).toString(), InputType.TYPE_CLASS_NUMBER)
        c.addView(tv("Más pausa entre llamadas = menos riesgo de que la operadora bloquee la línea.", 12f, C_MUTED), lp(top = 8))
    }

    private fun buildTest() {
        val c = card("Llamada de prueba", "🧪")
        testNumber = field(c, "Tu número", Store.str(this, "testNumber", ""), InputType.TYPE_CLASS_PHONE,
            hint = "Ej: 1155551234")
        c.addView(btn("Llamar de prueba", S_PRIMARY) { startTest() }, lp(top = 10))
    }

    private fun buildResults() {
        val c = card("Resultados", "📊")
        resultsInfo = tv("", 14f, C_TEXT)
        c.addView(resultsInfo, lp(top = 8))
        lastResults = vbox()
        c.addView(lastResults, lp(top = 6))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(btn("Exportar CSV", S_PRIMARY) { export(REQ_EXPORT_RESULTS, "resultados.csv", "text/csv") },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(btn("Borrar", S_OUTLINE) {
            Store.resultsFile(this).delete()
            Store.diagFile(this).delete()
            lastResultsKey = ""
            toast("Resultados borrados")
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(10) })
        c.addView(r, lp(top = 12))
    }

    private fun buildAdvanced() {
        val c = card("Avanzado", "🛠️")
        val body = collapsible(c, "Mostrar ajustes avanzados")
        kwSpeaker = field(body, "Palabras del botón Altavoz", Store.str(this, "kwSpeaker", Store.DEFAULT_SPEAKER))
        kwOpen = field(body, "Palabras del botón para escribir respuesta", Store.str(this, "kwOpen", Store.DEFAULT_OPEN), lines = 3)
        kwSend = field(body, "Palabras del botón Enviar", Store.str(this, "kwSend", Store.DEFAULT_SEND))
        kwHangup = field(body, "Palabras del botón Colgar", Store.str(this, "kwHangup", Store.DEFAULT_HANGUP))
        diag = CheckBox(this).apply {
            text = "Modo diagnóstico (guarda lo que se ve en pantalla en cada llamada)"
            setTextColor(C_TEXT)
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
            isChecked = Store.diagnostic(this@MainActivity)
        }
        body.addView(diag, lp(top = 10))
        body.addView(btn("Exportar diagnóstico", S_OUTLINE) {
            export(REQ_EXPORT_DIAG, "diagnostico.txt", "text/plain")
        }, lp(top = 8))
    }

    // ===================================================================
    // Componentes visuales
    // ===================================================================

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(top) }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radius).toFloat()
        if (stroke != null) setStroke(px(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card(title: String?, icon: String = ""): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16, C_BORDER)
            elevation = px(1).toFloat()
            setPadding(px(16), px(14), px(16), px(16))
        }
        if (title != null) c.addView(tv("$icon  $title".trim(), 17f, C_TEXT, bold = true))
        content.addView(c, lp(top = 12))
        return c
    }

    private fun chip() = tv("", 12f, C_TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(px(6), px(8), px(6), px(8))
    }

    private fun setChip(v: TextView, ok: Boolean, text: String) {
        v.text = (if (ok) "✓ " else "✗ ") + text
        v.setTextColor(if (ok) C_PRIMARY_DARK else C_RED)
        v.background = rounded(if (ok) C_PRIMARY_SOFT else 0xFFFDECEC.toInt(), 20)
    }

    private fun btn(t: String, style: Int, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = px(48)
        val bg: Int
        val fg: Int
        var border: Int? = null
        when (style) {
            S_GOOD -> { bg = C_GREEN; fg = Color.WHITE }
            S_BAD -> { bg = C_RED; fg = Color.WHITE }
            S_OUTLINE -> { bg = Color.WHITE; fg = C_PRIMARY; border = C_PRIMARY }
            else -> { bg = C_PRIMARY; fg = Color.WHITE }
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(bg, 12, border), null)
        setTextColor(fg)
        setOnClickListener { onClick() }
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        input: Int = InputType.TYPE_CLASS_TEXT,
        lines: Int = 1,
        hint: String = ""
    ): EditText {
        parent.addView(tv(label, 13f, C_MUTED), lp(top = 12))
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = if (lines > 1) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else input
            if (lines > 1) {
                minLines = lines
                gravity = Gravity.TOP or Gravity.START
            }
            background = rounded(C_FIELD, 10, C_BORDER)
            setPadding(px(12), px(10), px(12), px(10))
            textSize = 15f
            setTextColor(C_TEXT)
        }
        parent.addView(e, lp(top = 4))
        return e
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(r, lp())
        return r
    }

    private fun col(r: LinearLayout, leftMargin: Int): LinearLayout {
        val c = vbox()
        r.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { this.leftMargin = px(leftMargin) })
        return c
    }

    private fun collapsible(parent: LinearLayout, title: String): LinearLayout {
        val body = vbox().apply { visibility = View.GONE }
        val headerView = tv("▸  $title", 14f, C_PRIMARY, bold = true).apply {
            setPadding(0, px(14), 0, px(2))
        }
        headerView.setOnClickListener {
            val show = body.visibility == View.GONE
            body.visibility = if (show) View.VISIBLE else View.GONE
            headerView.text = (if (show) "▾  " else "▸  ") + title
        }
        parent.addView(headerView, lp())
        parent.addView(body, lp())
        return body
    }

    private fun selectMode(m: String) {
        mode = m
        for ((key, t) in modeTabs) {
            val sel = key == m
            t.background = if (sel) rounded(C_PRIMARY, 9) else null
            t.setTextColor(if (sel) Color.WHITE else C_MUTED)
        }
        audioPanel.visibility = if (m == Store.MODE_AUDIO) View.VISIBLE else View.GONE
        textPanel.visibility = if (m == Store.MODE_SPEAK) View.VISIBLE else View.GONE
        dialPanel.visibility = if (m == Store.MODE_DIAL_ONLY) View.VISIBLE else View.GONE
    }

    private fun selectMethod(m: String) {
        audioMethod = m
        for ((key, t) in methodViews) {
            val sel = key == m
            t.background = if (sel) rounded(C_PRIMARY, 9) else null
            t.setTextColor(if (sel) Color.WHITE else C_MUTED)
        }
        directPanel.visibility = if (m == Store.METHOD_DIRECT || m == Store.METHOD_MTK) View.VISIBLE else View.GONE
        mtkNote.visibility = if (m == Store.METHOD_MTK) View.VISIBLE else View.GONE
        speakerPanel.visibility = if (m == Store.METHOD_SPEAKER) View.VISIBLE else View.GONE
        if (m != Store.METHOD_SPEAKER) ShellBridge.bind()
    }

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(ShellBridge.SHIZUKU_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun openShizukuStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShellBridge.SHIZUKU_PACKAGE}"))
        try {
            startActivity(market)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=${ShellBridge.SHIZUKU_PACKAGE}")))
        }
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage(ShellBridge.SHIZUKU_PACKAGE)
        if (i == null) toast("Primero instalá Shizuku") else startActivity(i)
    }

    private fun refreshShizuku() {
        if (audioMethod == Store.METHOD_SPEAKER) return
        ShellBridge.bind()
        val installed = shizukuInstalled()
        val connected = ShellBridge.service != null
        shizukuStatus.text = ShellBridge.statusText(installed)
        shizukuStatus.setTextColor(if (connected) C_GREEN else C_RED)
        btnShizukuInstall.visibility = if (installed) View.GONE else View.VISIBLE
        btnShizukuOpen.visibility = if (installed && !ShellBridge.running()) View.VISIBLE else View.GONE
        btnShizukuPerm.visibility = if (ShellBridge.running() && !ShellBridge.granted()) View.VISIBLE else View.GONE
    }

    private fun runCheck() {
        val shell = ShellBridge.service
        if (shell == null) {
            toast("Primero conectá Shizuku (pasos de arriba)")
            return
        }
        checkResult.text = "Verificando..."
        Thread {
            val r = try { shell.check() } catch (e: Exception) { "error: ${e.message}" }
            val ok = r.contains("pstn_interceptable=true")
            val verdict = if (ok)
                "✅ COMPATIBLE: tu celular permite el audio directo. Hacé una llamada de prueba."
            else
                "❌ No parece compatible (pstn_interceptable no es true). Igual podés probar una llamada; si falla, se usa el altavoz."
            Store.appendDiag(this, "== verificación Shizuku ==\n$r")
            runOnUiThread {
                checkResult.text = "$verdict\n\n$r"
                checkResult.setTextColor(if (ok) C_GREEN else C_TEXT)
            }
        }.start()
    }

    private fun selectOut(o: String) {
        audioOut = o
        for ((key, t) in outViews) {
            val sel = key == o
            t.background = if (sel) rounded(C_PRIMARY, 9) else null
            t.setTextColor(if (sel) Color.WHITE else C_MUTED)
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun num(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def

    // ===================================================================
    // Estado y guardado
    // ===================================================================

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        stopPreview()
        save()
    }

    private fun save() {
        Store.putStr(this, "message", message.text.toString().trim())
        Store.putStr(this, "mode", mode)
        Store.putInt(this, "boost", num(boost, 1500).coerceIn(0, 3000))
        Store.putStr(this, "audioOut", audioOut)
        Store.putStr(this, "audioMethod", audioMethod)
        Store.putInt(this, "ring", num(ring, 35).coerceIn(10, 120))
        Store.putInt(this, "pause", num(pauseField, 20).coerceIn(0, 3600))
        Store.putInt(this, "talk", num(talk, 0).coerceIn(0, 600))
        Store.putInt(this, "hStart", num(hStart, 9).coerceIn(0, 24))
        Store.putInt(this, "hEnd", num(hEnd, 20).coerceIn(0, 24))
        Store.putStr(this, "kwOpen", kwOpen.text.toString())
        Store.putStr(this, "kwSend", kwSend.text.toString())
        Store.putStr(this, "kwHangup", kwHangup.text.toString())
        Store.putStr(this, "kwSpeaker", kwSpeaker.text.toString())
        Store.putStr(this, "blocklist", blocklist.text.toString())
        Store.putBool(this, "diag", diag.isChecked)
        Store.putStr(this, "testNumber", testNumber.text.toString().trim())
    }

    private fun refresh() {
        refreshShizuku()
        val permsOk = missingPermissions().isEmpty()
        val accOk = Live.service != null
        val contacts = Store.loadContacts(this).size
        val idx = Store.index(this).coerceAtMost(contacts)

        setChip(chipPerms, permsOk, "Permisos")
        setChip(chipAcc, accOk, "Accesibilidad")
        setChip(chipClients, contacts > 0, "$contacts clientes")
        btnPerms.text = if (permsOk) "✓ Permisos otorgados" else "Dar permisos de llamadas"
        btnAcc.text = if (accOk) "✓ Accesibilidad activa" else "Activar accesibilidad"
        setupCard.visibility = if (permsOk && accOk) View.GONE else View.VISIBLE

        stateText.text = (if (Live.running) "● " else "") + Live.status
        stateText.setTextColor(if (Live.running) C_GREEN else C_TEXT)
        progress.progress = if (contacts > 0) idx * 100 / contacts else 0
        progressText.text = if (contacts > 0) "Llamados $idx de $contacts" else "Sin clientes cargados"

        clientsInfo.text = if (contacts > 0) "$contacts clientes cargados · próximo: ${minOf(idx + 1, contacts)}"
        else "Todavía no cargaste clientes"

        val total = Store.resultCount(this)
        resultsInfo.text = "$total llamadas registradas"
        val last = Store.lastResults(this, 5)
        val key = last.joinToString("|") { it.joinToString(",") }
        if (key != lastResultsKey) {
            lastResultsKey = key
            lastResults.removeAllViews()
            for (r in last) {
                val time = r.getOrElse(0) { "" }.substringAfter(' ').take(5)
                val who = r.getOrElse(2) { "" }.ifEmpty { r.getOrElse(1) { "" } }
                val res = r.getOrElse(3) { "" }
                val color = when (res) {
                    "atendió" -> C_GREEN
                    "omitido" -> C_MUTED
                    else -> C_RED
                }
                val line = tv("$time  ·  $who  ·  $res", 13f, color)
                line.setPadding(0, px(4), 0, 0)
                lastResults.addView(line)
                val detail = r.getOrElse(4) { "" }
                if (detail.isNotEmpty()) {
                    lastResults.addView(tv(detail, 12f, C_MUTED).apply { setPadding(0, 0, 0, px(4)) })
                }
            }
        }
    }

    // ===================================================================
    // Audio
    // ===================================================================

    private fun pickAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(i, REQ_AUDIO)
    }

    private fun audioDurationSec(): Int = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(Store.audioFile(this).absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        (ms / 1000).toInt()
    } catch (e: Exception) {
        -1
    }

    private fun refreshAudioInfo() {
        val f = Store.audioFile(this)
        if (!f.exists()) {
            audioInfo.text = "No hay audio cargado"
            audioInfo.setTextColor(C_RED)
            btnPreview.isEnabled = false
            btnPreview.alpha = 0.5f
            return
        }
        val d = audioDurationSec()
        val name = Store.audioName(this).ifEmpty { "audio" }
        audioInfo.text = "🎵  $name" + if (d >= 0) "  ·  ${d / 60}:${"%02d".format(d % 60)}" else "  ·  formato no reconocido"
        audioInfo.setTextColor(if (d >= 0) C_TEXT else C_RED)
        btnPreview.isEnabled = true
        btnPreview.alpha = 1f
    }

    private fun togglePreview() {
        if (preview != null) {
            stopPreview()
            return
        }
        try {
            val mp = MediaPlayer()
            mp.setDataSource(Store.audioFile(this).absolutePath)
            mp.setOnCompletionListener { stopPreview() }
            mp.prepare()
            val b = num(boost, 1500).coerceIn(0, 3000)
            if (b > 0) {
                try {
                    previewFx = LoudnessEnhancer(mp.audioSessionId).apply {
                        setTargetGain(b)
                        setEnabled(true)
                    }
                } catch (_: Exception) {}
            }
            mp.start()
            preview = mp
            btnPreview.text = "■  Parar"
        } catch (e: Exception) {
            toast("No se pudo reproducir: ${e.message}")
        }
    }

    private fun stopPreview() {
        preview?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        preview = null
        try { previewFx?.release() } catch (_: Exception) {}
        previewFx = null
        if (::btnPreview.isInitialized) btnPreview.text = "▶  Escuchar"
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "audio"
        }
        return "audio"
    }

    // ===================================================================
    // Permisos, prueba y campaña
    // ===================================================================

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
        if (missing.isEmpty()) toast("Ya están todos los permisos")
        else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun readyToCall(): Boolean {
        save()
        if (missingPermissions().isNotEmpty()) {
            toast("Primero dá los permisos de llamadas")
            return false
        }
        if (Live.service == null) {
            toast("Primero activá la accesibilidad de La Llamada")
            return false
        }
        if (mode == Store.MODE_AUDIO && !Store.audioFile(this).exists()) {
            toast("Elegí el audio del mensaje")
            return false
        }
        if (mode == Store.MODE_SPEAK && Store.message(this).isBlank()) {
            toast("Escribí el texto del mensaje")
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
                REQ_AUDIO -> {
                    stopPreview()
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(Store.audioFile(this)).use { input.copyTo(it) }
                    }
                    Store.putStr(this, "audioName", displayName(uri))
                    refreshAudioInfo()
                    toast("Audio cargado")
                    Thread {
                        try { Pcm.prepare(this) } catch (e: Exception) {
                            runOnUiThread { toast("Ojo: no pude convertir el audio para el modo directo (${e.message})") }
                        }
                    }.start()
                }
                REQ_EXPORT_RESULTS -> copyTo(Store.resultsFile(this), uri)
                REQ_EXPORT_DIAG -> copyTo(Store.diagFile(this), uri)
            }
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
        refresh()
    }

    private fun copyTo(f: File, uri: Uri) {
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        toast("Exportado")
    }

    // ===================================================================
    // Solo versión de prueba (emulador de GitHub)
    // ===================================================================

    private fun handleAutoTest(i: Intent?) {
        if (i == null) return
        val n = i.getStringExtra("auto_test_number") ?: return
        val m = i.getStringExtra("auto_mode") ?: Store.MODE_SPEAK
        if (m == Store.MODE_AUDIO && !Store.audioFile(this).exists()) {
            writeTestTone(Store.audioFile(this))
            Store.putStr(this, "audioName", "tono-de-prueba.wav")
            refreshAudioInfo()
        }
        selectMode(m)
        message.setText(i.getStringExtra("auto_message") ?: "Mensaje de prueba")
        testNumber.setText(n)
        talk.setText("15")
        handler.postDelayed({ startTest() }, 1500)
    }

    /** WAV de 4 segundos a 440 Hz para probar el modo audio. */
    private fun writeTestTone(f: File) {
        val rate = 16000
        val samples = rate * 4
        val dataLen = samples * 2
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            fun le32(v: Int) { raf.write(v and 0xff); raf.write(v shr 8 and 0xff); raf.write(v shr 16 and 0xff); raf.write(v shr 24 and 0xff) }
            fun le16(v: Int) { raf.write(v and 0xff); raf.write(v shr 8 and 0xff) }
            raf.writeBytes("RIFF"); le32(36 + dataLen); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            raf.writeBytes("data"); le32(dataLen)
            val buf = ByteArray(dataLen)
            for (s in 0 until samples) {
                val v = (Math.sin(2.0 * Math.PI * 440.0 * s / rate) * 12000).toInt()
                buf[s * 2] = (v and 0xff).toByte()
                buf[s * 2 + 1] = (v shr 8 and 0xff).toByte()
            }
            raf.write(buf)
        }
    }
}
