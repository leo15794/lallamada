package com.lallamada.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lee la pantalla de llamada del sistema para:
 *  - detectar cuándo atendieron (aparece el contador 00:01, 00:02...)
 *  - abrir "escribir respuesta" / Bixby Text Call, pegar el mensaje y enviarlo
 *  - colgar tocando el botón rojo si el sistema no deja colgar de otra forma
 * También genera un volcado de la pantalla para diagnóstico.
 */
class CallAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Live.service = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (Live.service === this) Live.service = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (Live.service === this) Live.service = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private val callPackageHints = listOf("dialer", "incall", "phone", "telecom", "contacts")

    /** Raíces de las ventanas visibles (sin nuestra app). onlyCall = solo apps de teléfono. */
    private fun roots(onlyCall: Boolean, includeSystemUi: Boolean = false): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val ws = try { windows } catch (e: Exception) { emptyList() }
        for (w in ws) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: ""
            if (pkg == packageName) continue
            if (!includeSystemUi && pkg == "com.android.systemui") continue
            if (onlyCall && callPackageHints.none { pkg.contains(it, ignoreCase = true) }) continue
            out.add(r)
        }
        if (out.isEmpty() && !onlyCall) {
            rootInActiveWindow?.let { r -> if (r.packageName?.toString() != packageName) out.add(r) }
        }
        return out
    }

    /** Recorre el árbol; si visit devuelve el nodo buscado, corta. */
    private fun find(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (depth > 60) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val r = find(c, depth + 1, match)
            if (r != null) return r
        }
        return null
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()

    private fun findByKeywords(rs: List<AccessibilityNodeInfo>, keywords: List<String>): AccessibilityNodeInfo? {
        // Primero coincidencia exacta, después "contiene", respetando el orden de las palabras.
        for (kw in keywords) {
            for (r in rs) {
                val n = find(r) { node ->
                    !node.isEditable && (
                        node.text?.toString()?.trim()?.equals(kw, true) == true ||
                            node.contentDescription?.toString()?.trim()?.equals(kw, true) == true)
                }
                if (n != null) return n
            }
        }
        for (kw in keywords) {
            for (r in rs) {
                val n = find(r) { node -> !node.isEditable && label(node).contains(kw, true) }
                if (n != null) return n
            }
        }
        return null
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        for (r in roots(onlyCall = false)) {
            val n = find(r) { it.isEditable }
            if (n != null) return n
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tap(n)
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun <T> waitFor(ms: Long, f: () -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            f()?.let { return it }
            Thread.sleep(300)
        }
        return null
    }

    private val timerRegex = Regex("(^|\\s)(\\d{1,2}:\\d{2})(:\\d{2})?($|\\s)")

    /** true si la pantalla de llamada muestra el contador de duración (= atendieron). */
    fun isCallConnected(): Boolean {
        val d = Date()
        val clocks = listOf("H:mm", "HH:mm", "h:mm", "hh:mm")
            .map { SimpleDateFormat(it, Locale.US).format(d) }.toSet()
        for (r in roots(onlyCall = true)) {
            val n = find(r) { node ->
                val t = node.text?.toString() ?: return@find false
                val m = timerRegex.find(t) ?: return@find false
                m.groupValues[2] !in clocks
            }
            if (n != null) return true
        }
        return false
    }

    /** Abre "escribir respuesta", pega el mensaje y lo envía. Devuelve "OK..." o el motivo del fallo. */
    fun speak(message: String, openKeywords: List<String>, sendKeywords: List<String>): String {
        var edit = findEditable()
        if (edit == null) {
            val btn = findByKeywords(roots(onlyCall = false), openKeywords)
                ?: return "No encontré el botón para escribir (revisá las palabras clave)"
            clickNode(btn)
            edit = waitFor(6000) { findEditable() }
                ?: return "Toqué '${label(btn)}' pero no apareció el campo de texto"
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "No pude escribir el mensaje en el campo de texto"
        }
        Thread.sleep(700)
        val send = findByKeywords(roots(onlyCall = false), sendKeywords)
        if (send != null && clickNode(send)) return "OK"
        if (Build.VERSION.SDK_INT >= 30 && edit.refresh() &&
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) return "OK (enter)"
        return "Escribí el mensaje pero no encontré el botón Enviar"
    }

    /** Activa el altavoz tocando su botón en la pantalla de llamada (si no está ya activo). */
    fun pressSpeaker(keywords: List<String>): Boolean {
        val btn = findByKeywords(roots(onlyCall = true), keywords) ?: return false
        var x: AccessibilityNodeInfo? = btn
        repeat(3) {
            val n = x ?: return@repeat
            if (n.isChecked || n.isSelected) return true
            x = n.parent
        }
        return clickNode(btn)
    }

    /** Cuelga tocando el botón de finalizar de la pantalla de llamada. */
    fun hangUpByUi(keywords: List<String>): Boolean {
        val btn = findByKeywords(roots(onlyCall = true), keywords) ?: return false
        return clickNode(btn)
    }

    /** Texto con todo lo que hay en pantalla (para mandar a soporte y ajustar la app). */
    fun dump(): String {
        val sb = StringBuilder()
        val ws = try { windows } catch (e: Exception) { emptyList() }
        for (w in ws) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: ""
            if (pkg == packageName) continue
            sb.append("--- ventana: ").append(pkg).append(" (tipo ").append(w.type).append(")\n")
            dumpNode(r, 0, sb)
        }
        return sb.toString()
    }

    private fun dumpNode(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (depth > 60) return
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        sb.append("  ".repeat(depth)).append(cls)
        n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        n.text?.let { sb.append(" text=\"").append(it).append('"') }
        n.contentDescription?.let { sb.append(" desc=\"").append(it).append('"') }
        if (n.isClickable) sb.append(" [click]")
        if (n.isEditable) sb.append(" [editable]")
        sb.append('\n')
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            dumpNode(c, depth + 1, sb)
        }
    }
}
