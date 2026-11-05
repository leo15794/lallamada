package com.lallamada.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Conexión con Shizuku y con el servicio que corre con permisos de depuración. */
object ShellBridge {
    const val PERMISSION_REQUEST = 77
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile var service: IShellService? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .tag("lallamada-shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Shizuku instalado y corriendo. */
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = try {
        running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(PERMISSION_REQUEST) } catch (_: Throwable) {}
    }

    /** Conecta el servicio si hay permiso. Llamar desde el hilo principal. */
    fun bind() {
        if (service != null || !granted()) return
        try { Shizuku.bindUserService(args, connection) } catch (_: Throwable) {}
    }

    /** Texto corto para mostrar en pantalla. */
    fun statusText(installed: Boolean): String = when {
        service != null -> "✓ Shizuku conectado"
        granted() -> "Conectando con Shizuku..."
        running() -> "Falta darle permiso a La Llamada en Shizuku"
        installed -> "Shizuku está instalado pero no iniciado"
        else -> "Shizuku no está instalado"
    }
}
