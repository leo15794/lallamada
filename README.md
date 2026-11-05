# La Llamada

App Android que llama de una lista de clientes con el chip del celular, espera a que atiendan, dice un mensaje de promoción y corta. Registra el resultado de cada llamada.

## Cómo dice el mensaje

Android no deja que una app ponga audio dentro de una llamada telefónica. Por eso La Llamada usa la función del propio teléfono para **escribir respuestas durante la llamada**:

- **Samsung:** Bixby Text Call ("Llamada de texto").
- **Otros (Pixel, Motorola, etc.):** Subtítulos instantáneos → "Escribir respuestas durante llamadas".

Con el servicio de accesibilidad, la app toca ese botón, pega el mensaje y lo envía. El teléfono lo lee con voz sintética.

**Requisito:** esa función tiene que existir y andar en tu celular. Probala primero a mano.

Modo alternativo, **"Solo marcar"**: la app marca, espera que atiendan, mantiene la llamada N segundos y corta. Sirve si metés el audio grabado por cable desde otro aparato.

## Instalación

1. Desde el celular, entrá a **Releases** de este repositorio y descargá `lallamada.apk`.
2. Abrila y permití "instalar apps de fuentes desconocidas" cuando te lo pida.
3. Abrí La Llamada → **Dar permisos de llamadas** → aceptá todo.
4. **Activar accesibilidad** → La Llamada → activar.
   - Si aparece gris ("configuración restringida"): Ajustes → Aplicaciones → La Llamada → menú ⋮ → **Permitir configuración restringida**, y volvé a intentar.
5. Desactivá el ahorro de batería para La Llamada (Ajustes → Aplicaciones → La Llamada → Batería → Sin restricciones).

## Uso

1. **Cargar lista de clientes**: CSV o TXT, un cliente por línea (`teléfono, nombre`). Desde Excel: Guardar como → CSV.
2. Cargá la **lista de bajas** (números que nunca hay que llamar, incluido el Registro No Llame).
3. Escribí el **mensaje**.
4. Hacé una **llamada de prueba** a tu propio número, con el modo diagnóstico activado.
5. **Iniciar campaña.** Dejá el celular enchufado y quieto: la app mantiene la pantalla prendida.
6. **Exportar resultados** cuando quieras.

## Si no dice el mensaje

Activá el **modo diagnóstico**, hacé una llamada de prueba y tocá **Exportar diagnóstico**. Ese archivo muestra qué hay en la pantalla de llamada, y con eso se ajustan las palabras clave (sección "Ajustes avanzados") o el código para tu modelo.

## Límites

- Una llamada a la vez (un chip = una línea).
- Si atiende un contestador, el mensaje queda en el contestador.
- Las operadoras pueden bloquear líneas con patrones de llamadas automáticas. Usá pausas razonables entre llamadas.
- Las llamadas promocionales en Argentina están reguladas (Ley 26.951, Registro No Llame). Consultá el registro y respetá las bajas.

## Compilación

GitHub Actions compila la APK en cada push a `main` y la publica en Releases con la etiqueta `latest`. La clave de firma está en `signing/`, así que cada versión nueva se instala encima de la anterior.

## Panel web (control remoto)

La carpeta `panel/` es una web (Vercel + base de datos Neon) para manejar el celular desde una PC:
subir clientes y audio, configurar ritmo y horario, iniciar/detener, hacer llamadas de prueba y ver resultados en vivo.

En la app: tarjeta **Control remoto** → dirección del panel + contraseña → **Activar control remoto**.
El celular consulta el panel cada 4 segundos. Tiene que estar enchufado, con Wi-Fi y con Shizuku iniciado.

La contraseña está en la variable `PANEL_PASSWORD` del proyecto en Vercel (no está en este repositorio).
