#!/usr/bin/env bash
# E2E móvil de WEB-F13 (#157): registro, inicio de sesión y sesión renovada, con la app en el emulador
# y sin mock. Cada criterio se verifica por lo que muestra la pantalla.
#
# Uso:  E2E_OTP=<código> mobile/android/scripts/prueba-sesion-staging.sh --apk <app-debug.apk> [--sin-espera]
#
#   --apk RUTA    APK a probar: el artefacto solventa-staging, o un build con -PBFF_MOCK=false.
#   --sin-espera  omite el criterio 4, que espera de verdad a que venza el token de 15 minutos.
#
# Variables:
#   E2E_OTP         obligatoria: el OTP fijo del ambiente (secreto identidad-otp-fijo).
#   E2E_URL_WEB     origen de la web de staging. Sin ella se omite el criterio 5. Necesita
#                   `npm ci` y `npx playwright install chromium` en apps/web.
#   E2E_URL_BFF, E2E_COMMIT_APK  datos del APK para la evidencia.
#   ESPERA_TOKEN    segundos hasta dar por vencido el token (por defecto 930: 15 min y medio).
#   EVIDENCIA       carpeta de la evidencia (por defecto, una nueva en el directorio temporal).
#   PIN_EMULADOR y las demás de emulador-comun.sh.
#
# La cuenta es nueva en cada ejecución y su contraseña no se imprime. La grabación se detiene mientras
# el OTP está en pantalla: ese campo no se enmascara. La huella debe estar registrada en el emulador.
set -euo pipefail

APK_PROBADO=""
ESPERAR=1
while (($#)); do
    case "$1" in
        --apk) APK_PROBADO=${2:-}; shift ;;
        --sin-espera) ESPERAR=0 ;;
        *) echo "Opción desconocida: $1" >&2; exit 2 ;;
    esac
    shift
done

# shellcheck source=emulador-comun.sh
source "$(dirname "$0")/emulador-comun.sh"

[[ -f "$APK_PROBADO" ]] || fallar "Indica el APK con --apk."
[[ -n "${E2E_OTP:-}" ]] || fallar "Falta E2E_OTP: el OTP fijo del ambiente."

ESPERA_TOKEN=${ESPERA_TOKEN:-930}
EVIDENCIA=${EVIDENCIA:-$(mktemp -d)}
SELLO=$(date +%s)
NOMBRE="Prueba Movil"
CORREO="e2e-movil-$SELLO@ejemplo.co"
CELULAR="300${SELLO: -7}"
DOCUMENTO="${SELLO: -9}3" # termina en 0-6: el KYC simulado lo aprueba
CONTRASENA="Solventa2026$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::$E2E_OTP"
    echo "::add-mask::$CONTRASENA"
fi
mkdir -p "$EVIDENCIA"
RESUMEN="$EVIDENCIA/resumen.md"
{
    echo "# E2E móvil WEB-F13 (#157)"
    echo
    echo "- Fecha: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Commit del APK: ${E2E_COMMIT_APK:-sin indicar}"
    echo "- BFF: ${E2E_URL_BFF:-sin indicar}"
    echo "- Web: ${E2E_URL_WEB:-no probada}"
    echo "- Cuenta: $CORREO"
    echo
} >"$RESUMEN"

aprobado() {
    verificar "$*"
    echo "- [x] $*" >>"$RESUMEN"
}

# ── Grabación ────────────────────────────────────────────────────────────────────────────────

GRABANDO=""
PID_GRABACION=""

grabar() { # grabar NOMBRE
    GRABANDO=$1
    # screenrecord corta solo a los 180 s: ningún tramo grabado llega a tanto.
    "$ADB" shell screenrecord --bit-rate 2000000 "/sdcard/$GRABANDO.mp4" &
    PID_GRABACION=$!
    sleep 1
}

parar_grabacion() {
    [[ -n "$GRABANDO" ]] || return 0
    "$ADB" shell pkill -INT screenrecord || true
    wait "$PID_GRABACION" || true
    sleep 1 # el archivo se termina de escribir después de la señal
    "$ADB" exec-out cat "/sdcard/$GRABANDO.mp4" >"$EVIDENCIA/$GRABANDO.mp4"
    "$ADB" shell rm -f "/sdcard/$GRABANDO.mp4"
    GRABANDO=""
}

captura() { # captura NOMBRE
    "$ADB" exec-out screencap -p >"$EVIDENCIA/$1.png"
}

# El volcado de la UI contiene el OTP y el archivo temporal se borra al salir.
limpiar() {
    parar_grabacion || true
    "$ADB" shell rm -f /sdcard/ui.xml >/dev/null 2>&1 || true
    rm -f "$UI_LOCAL"
}
trap limpiar EXIT

abrir_app() {
    desbloquear
    "$ADB" shell am start -S -n "$PAQUETE/co.solventa.mobile.MainActivity" >/dev/null
    esperar_texto "Crear cuenta"
    sleep 1 # la pantalla aparece en uiautomator un instante antes de aceptar toques
}

esperar_inicio() {
    esperar_texto "Inicio"
    esperar_ausente "Iniciar sesión"
}

tocar_sensor() {
    sleep 1.5 # el diálogo del sistema tarda en empezar a escuchar el sensor
    "$ADB" emu finger touch 1 >/dev/null
}

# ── Preparación ──────────────────────────────────────────────────────────────────────────────

arrancar_emulador
# Recién encendido, el emulador pasa unos segundos sin red y el registro fallaría.
for _ in $(seq 30); do
    "$ADB" shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1 && break
    sleep 2
done
hay_huella ||
    fallar "El emulador no tiene huellas registradas (Settings > Security > Fingerprint)."
log "Instalando el APK"
"$ADB" install -r "$(ruta_dispositivo "$APK_PROBADO")" >/dev/null
"$ADB" shell pm clear "$PAQUETE" >/dev/null
"$ADB" logcat -c

# ── Criterio 1: registro y OTP ───────────────────────────────────────────────────────────────

log "Registro de $CORREO"
abrir_app
grabar 1-registro
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
esperar_texto "Código de verificación"
parar_grabacion

escribir "Código de verificación" "$E2E_OTP"
tocar "Verificar"
esperar_ausente "Código de verificación"

grabar 2-identidad
tocar "Continuar"
tocar "Ahora no" # la huella se activa en el criterio 3, tras ingresar con contraseña
esperar_inicio
parar_grabacion
aprobado "Criterio 1: la cuenta se creó con el OTP y la app entró a Inicio"

# ── Criterio 2: ingreso con correo y contraseña ──────────────────────────────────────────────

log "Ingreso con contraseña tras borrar los datos de la app"
"$ADB" shell pm clear "$PAQUETE" >/dev/null
abrir_app
grabar 3-ingreso
escribir "Correo electrónico" "$CORREO"
escribir "Contraseña" "$CONTRASENA"
tocar "Iniciar sesión"
# ── Criterio 3: la huella activada en este ingreso ───────────────────────────────────────────
tocar "Activar"
tocar_sensor
tocar "Ir al inicio"
esperar_inicio
parar_grabacion
aprobado "Criterio 2: el ingreso con correo y contraseña llegó a Inicio sin registrarse de nuevo"

log "Ingreso con huella tras cerrar la app"
abrir_app
grabar 4-huella
tocar "Ingresar con huella o rostro"
tocar_sensor
esperar_inicio
parar_grabacion
aprobado "Criterio 3: con la huella activa entró a Inicio sin escribir la contraseña"

# ── Criterio 4: operación protegida con el token vencido ─────────────────────────────────────

if ((ESPERAR)); then
    log "Esperando $ESPERA_TOKEN s a que venza el token de acceso"
    captura 5-antes-de-la-espera
    sleep "$ESPERA_TOKEN"
    desbloquear
    grabar 6-token-vencido
    tocar "Perfil"
    tocar "Consentimientos Open Finance"
    # Hay que ver un desenlace: mientras carga, la pantalla no prueba nada. Sin Cloud SQL la lista no
    # carga, pero la renovación ya ocurrió: un fallo de sesión lleva a Ingreso.
    esperar_texto "Cotización|Iniciar sesión|No pudimos cargar tus consentimientos[^\"]*"
    captura 7-tras-la-renovacion
    parar_grabacion
    [[ -z "$(centro_de "Iniciar sesión")" ]] || fallar "La app volvió a Ingreso: no renovó la sesión."
    if [[ -n "$(centro_de "Cotización")" ]]; then
        aprobado "Criterio 4: tras $ESPERA_TOKEN s la operación protegida terminó sin pedir credenciales"
    else
        aprobado "Criterio 4: tras $ESPERA_TOKEN s la sesión se renovó sin pedir credenciales (lista sin cargar: revisar Cloud SQL)"
    fi
else
    echo "- [ ] Criterio 4: omitido (--sin-espera)" >>"$RESUMEN"
fi

# ── Criterio 5: la misma cuenta en la web ────────────────────────────────────────────────────

if [[ -n "${E2E_URL_WEB:-}" ]]; then
    log "Ingreso en la web con la cuenta creada en la app"
    # CI=1: sin trazas, que guardarían la contraseña escrita.
    (cd "$PROYECTO/../../apps/web" && CI=1 E2E_CORREO="$CORREO" E2E_CLAVE="$CONTRASENA" \
        npx playwright test e2e/sesion.spec.ts -g "criterio 1" --retries=0)
    aprobado "Criterio 5: la cuenta de la app entró a la web sin un registro adicional"
else
    echo "- [ ] Criterio 5: omitido (sin E2E_URL_WEB)" >>"$RESUMEN"
fi

# ── Criterio 6: la evidencia no filtra secretos ──────────────────────────────────────────────

REGISTRO=$(mktemp)
"$ADB" logcat -d >"$REGISTRO"
FILTRADO=0
grep -qF "$CONTRASENA" "$REGISTRO" && FILTRADO=1
grep -qE 'Bearer |token_(acceso|refresco)' "$REGISTRO" && FILTRADO=1
grep -i solventa "$REGISTRO" | grep -qw -- "$E2E_OTP" && FILTRADO=1
rm -f "$REGISTRO"
((FILTRADO == 0)) || fallar "El logcat contiene un secreto: no publiques la evidencia."
grep -qF -e "$CONTRASENA" -e "$E2E_OTP" "$RESUMEN" && fallar "El resumen contiene un secreto."
aprobado "Criterio 6: logcat y resumen sin OTP, contraseña ni tokens; la grabación se detuvo durante el OTP"

log "Listo. Evidencia en $EVIDENCIA"
