#!/usr/bin/env bash
# Prueba automática en el emulador de Android (GitHub Actions).
# Instala la app, da permisos, activa la accesibilidad, hace una llamada de prueba
# simulada, la "atiende" desde la consola del emulador y guarda capturas y resultados.
set -x
PKG=com.lallamada.app
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=emu-out
NUM=1155550000
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

adb install -r "$APK"
for p in CALL_PHONE READ_PHONE_STATE ANSWER_PHONE_CALLS POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p
done
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.CallAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell svc power stayon true

adb shell am start -n $PKG/.MainActivity
sleep 10
shot 01-inicio

adb shell am start -n $PKG/.MainActivity --es auto_test_number $NUM --es auto_message "Hola, esta es una prueba de La Llamada"
sleep 12
shot 02-marcando
adb emu gsm list > "$OUT/gsm-list.txt" 2>&1
adb emu gsm accept $NUM
sleep 6
shot 03-atendida
sleep 12
shot 04-durante
sleep 25
shot 05-final
adb shell am start -n $PKG/.MainActivity
sleep 4
shot 06-app

adb shell run-as $PKG cat files/resultados.csv > "$OUT/resultados.csv" 2>&1
adb shell run-as $PKG cat files/diagnostico.txt > "$OUT/diagnostico.txt" 2>&1
adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb logcat -d | tail -400 > "$OUT/logcat.txt" 2>&1
exit 0
