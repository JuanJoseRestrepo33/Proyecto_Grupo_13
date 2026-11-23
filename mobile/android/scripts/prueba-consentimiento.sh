#!/usr/bin/env bash
# Prueba en el emulador el consentimiento de Open Finance en la app (MOB-F02, #133) con las tres
# finalidades: otorgar, revocar (la advertencia de tarifa solo aparece en cotización), otorgar de
# nuevo un revocado, volver a Perfil y renovar uno por vencer y uno vencido. Cada paso se verifica
# por lo que muestra la pantalla.
#
# Uso:  mobile/android/scripts/prueba-consentimiento.sh [--sin-preparar] [--sin-compilar] [--sin-reloj]
#
#   --sin-preparar  no ejecuta demo-emulador.sh: la app ya debe estar en Inicio con sesión.
#   --sin-compilar  se pasa a demo-emulador.sh.
#   --sin-reloj     omite la renovación. Para llegar a "por vencer" y "vencido" se adelanta el
#                   reloj del emulador con `adb root` (imágenes "Google APIs", no "Google Play");
#                   al terminar se restaura la hora.
#
# Variables: las de demo-emulador.sh (PIN_EMULADOR, CORREO...).
set -euo pipefail

PREPARAR=1
RELOJ=1
ARGS_DEMO=()
for arg in "$@"; do
    case "$arg" in
        --sin-preparar) PREPARAR=0 ;;
        --sin-reloj) RELOJ=0 ;;
        --sin-compilar) ARGS_DEMO+=("$arg") ;;
        *) echo "Opción desconocida: $arg" >&2; exit 2 ;;
    esac
done

DIR=$(dirname "$0")
((PREPARAR)) && "$DIR/demo-emulador.sh" "${ARGS_DEMO[@]}"

# shellcheck source=emulador-comun.sh
source "$DIR/emulador-comun.sh"

RELOJ_CAMBIADO=0
restaurar_reloj() {
    ((RELOJ_CAMBIADO)) || return 0
    "$ADB" shell date -u "$(date -u +%m%d%H%M%Y.%S)" >/dev/null
    "$ADB" shell settings put global auto_time 1
    echo "Reloj del emulador restaurado."
}
trap 'restaurar_reloj; rm -f "$UI_LOCAL"' EXIT

# Lleva el reloj del emulador a "fecha del dispositivo + desplazamiento" (sintaxis de `date -d`).
adelantar_reloj() {
    local ahora destino
    ahora=$("$ADB" shell date -u +%s | tr -d '\r')
    destino=$(date -u -d "$(date -u -d "@$ahora" '+%F %T') UTC $1" +%m%d%H%M%Y.%S)
    "$ADB" shell settings put global auto_time 0
    "$ADB" shell date -u "$destino" >/dev/null
    RELOJ_CAMBIADO=1
}

esperar_conteo() { # esperar_conteo REGEX N
    local fin=$((SECONDS + 10))
    while ((SECONDS < fin)); do
        volcar_ui && [[ $(contar "$1") == "$2" ]] && return 0
        sleep 1
    done
    fallar "Se esperaban $2 de: $1"
}

refrescar() { # pull-to-refresh
    "$ADB" shell input swipe 540 700 540 1700 400
    sleep 1.5
}

otorgar() { # otorgar "Título de la finalidad" "Entidad"...
    local finalidad=$1
    shift
    tocar_bajo "$finalidad" "Autorizar"
    esperar_texto "Autorizar acceso a datos"
    tocar "Autorizar" # sin selección no debe enviar
    esperar_texto "Autorizar acceso a datos"
    local entidad
    for entidad in "$@"; do
        tocar "$entidad no seleccionada" 1 content-desc
        esperar_texto "$entidad seleccionada" 1 content-desc
    done
    tocar "Autorizar" # aún falta aceptar la versión legal
    esperar_texto "Autorizar acceso a datos"
    tocar "He leído y acepto la versión .*"
    tocar "Autorizar"
    esperar_texto "Consentimiento otorgado\."
}

# Abre "Revocar" de la finalidad y comprueba si advierte la tarifa estándar (solo cotización).
abrir_revocar() { # abrir_revocar "Título" si|no
    tocar_bajo "$1" "Revocar"
    esperar_texto "Revocar consentimiento"
    local advierte
    advierte=$(contar "Atención: la siguiente cotización.*")
    if [[ $2 == si ]]; then
        [[ $advierte == 1 ]] || fallar "$1 debe advertir la tarifa estándar"
    else
        [[ $advierte == 0 ]] || fallar "$1 no debe advertir la tarifa estándar"
    fi
}

COT="Cotización personalizada"
SUS="Suscripción de póliza"
FRA="Prevención de fraude"

# ── 1. Pantalla y estado inicial ─────────────────────────────────────────────────────────────

log "Perfil → Consentimientos"
desbloquear
tocar "Perfil"
tocar "Consentimientos Open Finance"
esperar_texto "Cotización personalizada"
[[ $(contar "Sin consentimiento") == 3 ]] || fallar "Se esperaban 3 finalidades sin consentimiento"
verificar "tres finalidades, ninguna con consentimiento"

# ── 2. Otorgar ───────────────────────────────────────────────────────────────────────────────

log "Otorgar cotización"
otorgar "$COT" Bancolombia Nequi
verificar "sin entidades ni aceptación de la versión no se envía"
esperar_texto "Vigente"
esperar_texto "Bancolombia, Nequi"
verificar "otorgado y vigente con las entidades elegidas"

# ── 3. Revocar con advertencia de tarifa ─────────────────────────────────────────────────────

log "Revocar cotización"
abrir_revocar "$COT" si
verificar "advierte la tarifa estándar para cotización"
tocar "Cancelar"
esperar_ausente "Revocar consentimiento"
[[ $(contar "Vigente") == 1 ]] || fallar "Cancelar no debía cambiar el estado"
verificar "cancelar no envía nada"
abrir_revocar "$COT" si
tocar "Confirmar"
esperar_texto "Consentimiento revocado\."
esperar_texto "Revocado"
[[ $(contar "Renovar") == 0 ]] || fallar "Un revocado no se renueva"
verificar "revocado tras confirmar, sin opción de renovar"

# ── 4. Otorgar de nuevo un revocado; las otras finalidades no advierten tarifa ───────────────

log "Otorgar de nuevo cotización"
otorgar "$COT" Davivienda
esperar_texto "Vigente"
verificar "un revocado se otorga de nuevo"

log "Suscripción de póliza"
otorgar "$SUS" Bancolombia
abrir_revocar "$SUS" no
tocar "Cancelar"
verificar "otorgada; revocarla no advierte la tarifa"

log "Prevención de fraude"
otorgar "$FRA" Nequi Davivienda
abrir_revocar "$FRA" no
tocar "Confirmar"
esperar_texto "Consentimiento revocado\."
esperar_texto "Revocado"
verificar "otorgada y revocada sin advertencia de tarifa"
al_inicio

# ── 5. Volver a Perfil y regresar ────────────────────────────────────────────────────────────

log "Volver a Perfil y regresar"
tocar "Volver a Perfil" 1 content-desc
esperar_ausente "$COT"
[[ $(contar "Esta sección estará disponible próximamente\.") == 0 ]] || fallar "Perfil no debe decir «próximamente»"
tocar "Consentimientos Open Finance"
esperar_texto "Vigente" 2
verificar "regresa a Perfil y al volver consulta el mismo estado"

# ── 6. Renovar por vencer y vencido ──────────────────────────────────────────────────────────

if ((RELOJ)); then
    "$ADB" root >/dev/null 2>&1 || true
    "$ADB" wait-for-device
    if [[ "$("$ADB" shell id -u | tr -d '\r')" != 0 ]]; then
        echo "Sin adb root en esta imagen: se omite la renovación." >&2
        RELOJ=0
    fi
fi
if ((RELOJ)); then
    log "Renovar cotización por vencer"
    adelantar_reloj "+6 months -10 days" # cotización dura 6 meses
    refrescar
    esperar_texto "Vence en [0-9]+ días"
    verificar "marca por vencer con los días restantes"
    tocar_bajo "$COT" "Renovar"
    esperar_texto "Consentimiento renovado\."
    esperar_texto "Nueva fecha de vencimiento: [0-9-]+"
    esperar_ausente "Vence en [0-9]+ días"
    verificar "renovado; muestra la nueva fecha de vencimiento del servidor"

    log "Renovar un vencido"
    adelantar_reloj "+6 months +11 days" # pasa el vencimiento de cotización y suscripción
    refrescar
    esperar_conteo "Vencido" 2
    verificar "cotización y suscripción aparecen vencidas"
    tocar_bajo "$SUS" "Renovar"
    esperar_conteo "Vencido" 1
    esperar_texto "Nueva fecha de vencimiento: [0-9-]+"
    verificar "un vencido se renueva en una acción y queda vigente"
fi

log "Prueba de consentimiento superada"
