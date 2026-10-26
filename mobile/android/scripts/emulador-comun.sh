# Funciones compartidas por demo-emulador.sh y prueba-consentimiento.sh. Se carga con `source`.
#
# Variables opcionales:
#   ANDROID_HOME   SDK de Android. En WSL se usa el SDK de Windows, porque el emulador corre allí.
#   PIN_EMULADOR   PIN de la pantalla de bloqueo, si la huella no basta para desbloquear.

PAQUETE=co.solventa.mobile.debug
ESPERA_MAX=${ESPERA_MAX:-60}
PROYECTO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
APK="$PROYECTO/app/build/outputs/apk/debug/app-debug.apk"
UI_LOCAL=$(mktemp)
trap 'rm -f "$UI_LOCAL"' EXIT

log() { printf '\n==> %s\n' "$*"; }
fallar() { echo "✗ $*" >&2; exit 1; }

# ── Herramientas: Linux nativo o WSL con el SDK de Windows ───────────────────────────────────

if grep -qi microsoft /proc/version 2>/dev/null; then
    WIN_HOME=$(wslpath "$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")
    SDK=${ANDROID_SDK_WINDOWS:-$WIN_HOME/AppData/Local/Android/Sdk}
    ADB="$SDK/platform-tools/adb.exe"
    EMULADOR="$SDK/emulator/emulator.exe"
    ruta_dispositivo() { wslpath -w "$1"; }
    # Misma llave debug que Android Studio en Windows; si no, `install -r` falla por firma distinta.
    [[ -f "$WIN_HOME/.android/debug.keystore" ]] && export ANDROID_USER_HOME="$WIN_HOME/.android"
else
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
    ADB="$SDK/platform-tools/adb"
    EMULADOR="$SDK/emulator/emulator"
    ruta_dispositivo() { printf '%s' "$1"; }
fi
[[ -x "$ADB" ]] || fallar "No se encontró adb en $ADB"

# ── Interacción con la UI por texto visible (uiautomator) ────────────────────────────────────

volcar_ui() {
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    # Un nodo por línea: así un `.*` del patrón no puede extenderse hasta otro nodo.
    "$ADB" exec-out cat /sdcard/ui.xml | sed 's/<node /\n<node /g' >"$UI_LOCAL"
}

# Bordes "x1 y1 x2 y2" de cada nodo cuyo atributo (text por defecto) coincide completo con la
# expresión (ERE, sin comillas), en orden de pantalla.
nodos() { # nodos REGEX [ATRIBUTO]
    grep -oE "${2:-text}=\"($1)\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$UI_LOCAL" |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' || true
}

contar() { nodos "$@" | wc -l | tr -d ' '; }

# Centro "x y" de la coincidencia N (1 por defecto).
centro_de() { # centro_de REGEX [N] [ATRIBUTO]
    nodos "$1" "${3:-text}" | sed -n "${2:-1}p" | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

esperar_texto() { # esperar_texto REGEX [N] [ATRIBUTO]
    local fin=$((SECONDS + ESPERA_MAX))
    while ((SECONDS < fin)); do
        if volcar_ui && [[ -n "$(centro_de "$@")" ]]; then return 0; fi
        sleep 1
    done
    fallar "No apareció en pantalla: $1"
}

esperar_ausente() { # esperar_ausente REGEX [ATRIBUTO]
    local fin=$((SECONDS + 10))
    while ((SECONDS < fin)); do
        if volcar_ui && [[ -z "$(centro_de "$1" 1 "${2:-text}")" ]]; then return 0; fi
        sleep 1
    done
    fallar "Sigue en pantalla: $1"
}

tocar() { # tocar REGEX [N] [ATRIBUTO]
    esperar_texto "$@"
    # shellcheck disable=SC2046
    "$ADB" shell input tap $(centro_de "$@")
    sleep 0.6
}

# Toca el primer REGEX situado debajo del texto ANCLA (p. ej. el botón de una tarjeta cuyo título
# es ANCLA), desplazando la lista hacia abajo si aún no se ve.
tocar_bajo() { # tocar_bajo ANCLA REGEX
    local i y xy
    for i in 1 2 3 4; do
        if volcar_ui; then
            y=$(nodos "$1" | head -n1 | awk '{ print $4 }')
            if [[ -n "$y" ]]; then
                xy=$(nodos "$2" | awk -v y="$y" '$2 > y { print int(($1 + $3) / 2), int(($2 + $4) / 2); exit }')
                if [[ -n "$xy" ]]; then
                    # shellcheck disable=SC2086
                    "$ADB" shell input tap $xy
                    sleep 0.6
                    return 0
                fi
            fi
        fi
        "$ADB" shell input swipe 540 1600 540 1000 300
        sleep 0.8
    done
    fallar "No apareció «$2» debajo de «$1»"
}

# Lleva la lista al principio. Al llegar arriba el gesto puede refrescar: es inocuo.
al_inicio() {
    "$ADB" shell input swipe 540 800 540 1700 300
    "$ADB" shell input swipe 540 800 540 1700 300
    sleep 1.5
}

escribir() { # escribir "Etiqueta del campo" "valor"
    tocar "$1"
    "$ADB" shell input text "'${2// /%s}'"
    "$ADB" shell input keyevent 111 # cierra el teclado para no tapar botones
    sleep 0.3
}

verificar() { printf '  ✓ %s\n' "$*"; }

# ── Emulador ─────────────────────────────────────────────────────────────────────────────────

# Sin `grep -q`: con pipefail, cortar la tubería antes de tiempo la daría por fallida.
bloqueado() { "$ADB" shell dumpsys window | grep 'isKeyguardShowing=true' >/dev/null; }

esperar_desbloqueo() {
    local i
    for i in 1 2 3 4 5; do bloqueado || return 0; sleep 1; done
    return 1
}

# Registrar una huella obliga a tener PIN, así que tras el reposo la pantalla queda bloqueada.
desbloquear() {
    "$ADB" shell input keyevent KEYCODE_WAKEUP
    "$ADB" shell wm dismiss-keyguard
    esperar_desbloqueo && return 0
    "$ADB" emu finger touch 1 >/dev/null
    esperar_desbloqueo && return 0
    # Tras reiniciar, Android exige el PIN una vez antes de aceptar la huella.
    if [[ -n "${PIN_EMULADOR:-}" ]]; then
        "$ADB" shell input keyevent KEYCODE_WAKEUP
        "$ADB" shell input swipe 540 1800 540 600 300 # muestra el teclado del PIN
        sleep 1
        "$ADB" shell input text "$PIN_EMULADOR"
        "$ADB" shell input keyevent KEYCODE_ENTER
        esperar_desbloqueo && return 0
    fi
    fallar "La pantalla sigue bloqueada: desbloquéala a mano o define PIN_EMULADOR."
}

hay_emulador() { "$ADB" devices | grep -E '^emulator-[0-9]+\s+device' >/dev/null; }
