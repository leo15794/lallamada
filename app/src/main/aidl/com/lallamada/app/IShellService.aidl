package com.lallamada.app;

interface IShellService {
    // Código fijo que usa Shizuku para cerrar el servicio.
    void destroy() = 16777114;

    // Diagnóstico: devuelve un texto con lo que encontró (uid, permisos, rutas de telefonía).
    String check() = 1;

    // Envía PCM 16 bits mono a la llamada en curso. Bloquea hasta terminar. Devuelve "OK..." o el error.
    String injectPcm(in ParcelFileDescriptor pcm, int sampleRate) = 2;

    // Corta la reproducción en curso.
    void stopPlay() = 3;
}
