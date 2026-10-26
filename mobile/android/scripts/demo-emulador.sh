#!/usr/bin/env bash
# Arranca el emulador, instala la app debug (BFF simulado), crea una cuenta y activa la huella.
#
# Uso:  mobile/android/scripts/demo-emulador.sh [--sin-compilar] [--sin-huella]
#
# Variables opcionales:
#   AVD            nombre del AVD (por defecto: el primero de `emulator -list-avds`)
#   ANDROID_HOME   SDK de Android. En WSL se usa el SDK de Windows, porque el emulador corre allí.
#   PIN_EMULADOR   PIN de la pantalla de bloqueo, si la huella no basta para desbloquear.
#   NOMBRE, CORREO, CELULAR, DOCUMENTO, CONTRASENA  datos de la cuenta.
#     DOCUMENTO debe terminar en 0-6 para que el KYC simulado la apruebe (ver README).
#
# La huella exige una registrada en el emulador (Settings > Security > Fingerprint). Si no
# hay ninguna, el script elige "Ahora no" y lo avisa. El mock vive en memoria: al cerrar la
# app la cuenta desaparece y basta con volver a ejecutar el script.
set -euo pipefail

NOMBRE=${NOMBRE:-Hernan Hurtado}
CORREO=${CORREO:-hernan@ejemplo.co}
CELULAR=${CELULAR:-3001234567}
DOCUMENTO=${DOCUMENTO:-1098765432}
CONTRASENA=${CONTRASENA:-Solventa2026}
CODIGO_OTP=123456 # fijo en MockBffMovil

COMPILAR=1
HUELLA=1
for arg in "$@"; do
    case "$arg" in
        --sin-compilar) COMPILAR=0 ;;
        --sin-huella) HUELLA=0 ;;
        *) echo "Opción desconocida: $arg" >&2; exit 2 ;;
    esac
done

# shellcheck source=emulador-comun.sh
source "$(dirname "$0")/emulador-comun.sh"

# ── 1. Emulador ──────────────────────────────────────────────────────────────────────────────

if hay_emulador; then
    log "Emulador ya en ejecución"
else
    AVD=${AVD:-$("$EMULADOR" -list-avds | tr -d '\r' | head -n1)}
    [[ -n "$AVD" ]] || fallar "No hay AVD; crea uno en Android Studio (API 26+)."
    log "Arrancando emulador $AVD"
    nohup "$EMULADOR" -avd "$AVD" -no-boot-anim >/dev/null 2>&1 &
fi
"$ADB" wait-for-device
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 2; done
# Con cargador conectado y stayon, la pantalla no entra en reposo durante compilación ni demo.
"$ADB" emu power ac on >/dev/null
"$ADB" shell svc power stayon true

# ── 2. App ───────────────────────────────────────────────────────────────────────────────────

if ((COMPILAR)); then
    log "Compilando app debug"
    java_mayor=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p')
    ((${java_mayor:-0} >= 17)) || fallar "Se necesita JDK 17+ (JAVA_HOME); o usa --sin-compilar."
    (cd "$PROYECTO" && ./gradlew --quiet :app:assembleDebug)
fi
log "Instalando y abriendo la app"
"$ADB" install -r "$(ruta_dispositivo "$APK")" >/dev/null
# Datos limpios: sin la llave biométrica de una ejecución anterior la app vuelve a ofrecer la huella.
"$ADB" shell pm clear "$PAQUETE" >/dev/null
desbloquear
"$ADB" shell am start -S -n "$PAQUETE/co.solventa.mobile.MainActivity" >/dev/null
# La pantalla aparece en uiautomator un instante antes de aceptar toques.
esperar_texto "Crear cuenta"
sleep 1

# ── 3. Cuenta ────────────────────────────────────────────────────────────────────────────────

log "Creando la cuenta $CORREO"
tocar "Crear cuenta"
escribir "Nombre completo" "$NOMBRE"
escribir "Correo electrónico" "$CORREO"
escribir "Celular" "$CELULAR"
tocar "Continuar"
escribir "Número de documento" "$DOCUMENTO"
tocar "Continuar"
escribir "Contraseña" "$CONTRASENA"
escribir "Confirma tu contraseña" "$CONTRASENA"
tocar "Acepto los términos[^\"]*"
tocar "Crear cuenta"
escribir "Código de verificación" "$CODIGO_OTP"
tocar "Verificar"
tocar "Continuar"

# ── 4. Huella ────────────────────────────────────────────────────────────────────────────────

esperar_texto "Activar|Inicio"
if [[ -z "$(centro_de "Activar")" ]]; then
    echo "La app no ofreció la huella: ya estaba activa en este dispositivo." >&2
elif ((HUELLA)) && "$ADB" shell dumpsys fingerprint | grep '"prints":\[{' >/dev/null; then
    log "Activando la huella"
    tocar "Activar"
    sleep 1.5
    "$ADB" emu finger touch 1 >/dev/null
    tocar "Ir al inicio"
else
    ((HUELLA)) && echo "Sin huellas registradas en el emulador: se omite la activación." >&2
    tocar "Ahora no"
fi

log "Listo: sesión iniciada como $CORREO / $CONTRASENA"
