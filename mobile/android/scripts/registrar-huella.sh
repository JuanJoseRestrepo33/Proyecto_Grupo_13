#!/usr/bin/env bash
# Pone PIN y registra una huella en un emulador nuevo, recorriendo Ajustes. Para un AVD desechable o el
# de CI: en un AVD con PIN propio, regístrala a mano. Probado con API 35, imagen google_apis.
#
# Uso:  mobile/android/scripts/registrar-huella.sh
#
# Variables: PIN_EMULADOR (por defecto 1234) y las de emulador-comun.sh.
set -euo pipefail

PIN_EMULADOR=${PIN_EMULADOR:-1234}

# shellcheck source=emulador-comun.sh
source "$(dirname "$0")/emulador-comun.sh"

if hay_huella; then
    log "Ya hay una huella registrada"
    exit 0
fi

log "Registrando la huella"
# Falla si ya hay PIN: en un reintento es el mismo, y Ajustes lo pide igual.
"$ADB" shell locksettings set-pin "$PIN_EMULADOR" >/dev/null 2>&1 || true
"$ADB" shell am start -a android.settings.FINGERPRINT_SETTINGS >/dev/null
# El orden de las pantallas cambia entre versiones de Ajustes: se actúa según lo que se ve.
for _ in $(seq 40); do
    hay_huella && break
    sleep 1.5
    volcar_ui
    xy=$(centro_de "(I agree|I AGREE|Add fingerprint|Start|START|Next|NEXT)")
    if [[ -n "$(centro_de "Re-enter your PIN")" ]]; then
        "$ADB" shell input text "$PIN_EMULADOR"
        "$ADB" shell input keyevent KEYCODE_ENTER
    elif [[ -n "$xy" ]]; then
        # shellcheck disable=SC2086
        "$ADB" shell input tap $xy
    else
        "$ADB" emu finger touch 1 >/dev/null
    fi
done
hay_huella || fallar "La huella no quedó registrada."
"$ADB" shell input keyevent KEYCODE_HOME
log "Huella registrada"
