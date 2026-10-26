<#
.SYNOPSIS
Prueba en el emulador el consentimiento de Open Finance en la app (MOB-F02, #133).

.DESCRIPTION
Con las tres finalidades: otorga, revoca (la advertencia de tarifa solo aparece en cotización),
otorga de nuevo un revocado, vuelve a Perfil y renueva uno por vencer y uno vencido. Cada paso se
verifica por lo que muestra la pantalla.
Para llegar a "por vencer" y "vencido" adelanta el reloj del emulador con `adb root` (imágenes
"Google APIs", no "Google Play") y al terminar lo restaura; -SinReloj omite esa parte.

.EXAMPLE
mobile\android\scripts\prueba-consentimiento.ps1 -PinEmulador 1111
mobile\android\scripts\prueba-consentimiento.ps1 -SinPreparar -SinReloj
#>
param(
    [string]$PinEmulador,  # si la huella no basta para quitar la pantalla de bloqueo
    [switch]$SinPreparar,  # la app ya está en Inicio con sesión: no ejecuta demo-emulador.ps1
    [switch]$SinCompilar,  # se pasa a demo-emulador.ps1
    [switch]$SinReloj
)

if (-not $SinPreparar) {
    & "$PSScriptRoot\demo-emulador.ps1" -PinEmulador $PinEmulador -SinCompilar:$SinCompilar
}
. "$PSScriptRoot\EmuladorComun.ps1"

$script:RelojCambiado = $false

# Lleva el reloj del emulador a la fecha que devuelve $Desplazar a partir de la del dispositivo.
function Adelantar-Reloj([scriptblock]$Desplazar) {
    $segundos = [long]((Adb shell date -u +%s) -join '').Trim()
    $ahora = [DateTimeOffset]::FromUnixTimeSeconds($segundos).UtcDateTime
    $destino = & $Desplazar $ahora
    Adb shell settings put global auto_time 0
    Adb shell date -u $destino.ToString('MMddHHmmyyyy.ss') | Out-Null
    $script:RelojCambiado = $true
}

function Restaurar-Reloj {
    if (-not $script:RelojCambiado) { return }
    Adb shell date -u ((Get-Date).ToUniversalTime().ToString('MMddHHmmyyyy.ss')) | Out-Null
    Adb shell settings put global auto_time 1
    Write-Host 'Reloj del emulador restaurado.'
}

function Refrescar { # pull-to-refresh
    Adb shell input swipe 540 700 540 1700 400
    Start-Sleep -Milliseconds 1500
}

function Fallar([string]$Mensaje) { throw "✗ $Mensaje" }

# Otorga la finalidad $Finalidad, comprobando que no se envía sin selección ni aceptación.
function Otorgar([string]$Finalidad, [string[]]$Entidades) {
    Tocar-Bajo $Finalidad 'Autorizar'
    Esperar-Texto 'Autorizar acceso a datos' | Out-Null
    Tocar 'Autorizar' # sin selección no debe enviar
    Esperar-Texto 'Autorizar acceso a datos' | Out-Null
    foreach ($entidad in $Entidades) {
        Tocar "$entidad no seleccionada" 1 'content-desc'
        Esperar-Texto "$entidad seleccionada" 1 'content-desc' | Out-Null
    }
    Tocar 'Autorizar' # aún falta aceptar la versión legal
    Esperar-Texto 'Autorizar acceso a datos' | Out-Null
    Tocar 'He leído y acepto la versión .*'
    Tocar 'Autorizar'
    Esperar-Texto 'Consentimiento otorgado\.' | Out-Null
}

# Abre "Revocar" de la finalidad y comprueba si advierte la tarifa estándar (solo cotización).
function Abrir-Revocar([string]$Finalidad, [bool]$Advierte) {
    Tocar-Bajo $Finalidad 'Revocar'
    Esperar-Texto 'Revocar consentimiento' | Out-Null
    $hay = (Contar (Volcar-Ui) 'Atención: la siguiente cotización.*') -eq 1
    if ($hay -ne $Advierte) {
        Fallar "$Finalidad $(if ($Advierte) { 'debe' } else { 'no debe' }) advertir la tarifa estándar"
    }
}

$Cot = 'Cotización personalizada'
$Sus = 'Suscripción de póliza'
$Fra = 'Prevención de fraude'

try {
    # ── 1. Pantalla y estado inicial ─────────────────────────────────────────────────────────
    Log 'Perfil → Consentimientos'
    Desbloquear
    Tocar 'Perfil'
    Tocar 'Consentimientos Open Finance'
    Esperar-Texto 'Cotización personalizada' | Out-Null
    if ((Contar (Volcar-Ui) 'Sin consentimiento') -ne 3) { Fallar 'Se esperaban 3 finalidades sin consentimiento' }
    Verificar 'tres finalidades, ninguna con consentimiento'

    # ── 2. Otorgar ───────────────────────────────────────────────────────────────────────────
    Log 'Otorgar cotización'
    Otorgar $Cot @('Bancolombia', 'Nequi')
    Verificar 'sin entidades ni aceptación de la versión no se envía'
    Esperar-Texto 'Vigente' | Out-Null
    Esperar-Texto 'Bancolombia, Nequi' | Out-Null
    Verificar 'otorgado y vigente con las entidades elegidas'

    # ── 3. Revocar con advertencia de tarifa ─────────────────────────────────────────────────
    Log 'Revocar cotización'
    Abrir-Revocar $Cot $true
    Verificar 'advierte la tarifa estándar para cotización'
    Tocar 'Cancelar'
    Esperar-Ausente 'Revocar consentimiento'
    if ((Contar (Volcar-Ui) 'Vigente') -ne 1) { Fallar 'Cancelar no debía cambiar el estado' }
    Verificar 'cancelar no envía nada'
    Abrir-Revocar $Cot $true
    Tocar 'Confirmar'
    Esperar-Texto 'Consentimiento revocado\.' | Out-Null
    Esperar-Texto 'Revocado' | Out-Null
    if ((Contar (Volcar-Ui) 'Renovar') -ne 0) { Fallar 'Un revocado no se renueva' }
    Verificar 'revocado tras confirmar, sin opción de renovar'

    # ── 4. Otorgar de nuevo un revocado; las otras finalidades no advierten tarifa ───────────
    Log 'Otorgar de nuevo cotización'
    Otorgar $Cot @('Davivienda')
    Esperar-Texto 'Vigente' | Out-Null
    Verificar 'un revocado se otorga de nuevo'

    Log 'Suscripción de póliza'
    Otorgar $Sus @('Bancolombia')
    Abrir-Revocar $Sus $false
    Tocar 'Cancelar'
    Verificar 'otorgada; revocarla no advierte la tarifa'

    Log 'Prevención de fraude'
    Otorgar $Fra @('Nequi', 'Davivienda')
    Abrir-Revocar $Fra $false
    Tocar 'Confirmar'
    Esperar-Texto 'Consentimiento revocado\.' | Out-Null
    Esperar-Texto 'Revocado' | Out-Null
    Verificar 'otorgada y revocada sin advertencia de tarifa'
    Al-Inicio

    # ── 5. Volver a Perfil y regresar ────────────────────────────────────────────────────────
    Log 'Volver a Perfil y regresar'
    Tocar 'Volver a Perfil' 1 'content-desc'
    Esperar-Ausente $Cot
    if ((Contar (Volcar-Ui) 'Esta sección estará disponible próximamente\.') -ne 0) {
        Fallar 'Perfil no debe decir «próximamente»'
    }
    Tocar 'Consentimientos Open Finance'
    Esperar-Texto 'Vigente' 2 | Out-Null
    Verificar 'regresa a Perfil y al volver consulta el mismo estado'

    # ── 6. Renovar por vencer y vencido ──────────────────────────────────────────────────────
    if (-not $SinReloj) {
        Adb root | Out-Null
        Adb wait-for-device
        if (((Adb shell id -u) -join '').Trim() -ne '0') {
            Write-Warning 'Sin adb root en esta imagen: se omite la renovación.'
            $SinReloj = $true
        }
    }
    if (-not $SinReloj) {
        Log 'Renovar cotización por vencer'
        Adelantar-Reloj { param($t) $t.AddMonths(6).AddDays(-10) } # cotización dura 6 meses
        Refrescar
        Esperar-Texto 'Vence en \d+ días' | Out-Null
        Verificar 'marca por vencer con los días restantes'
        Tocar-Bajo $Cot 'Renovar'
        Esperar-Texto 'Consentimiento renovado\.' | Out-Null
        Esperar-Texto 'Nueva fecha de vencimiento: [0-9-]+' | Out-Null
        Esperar-Ausente 'Vence en \d+ días'
        Verificar 'renovado; muestra la nueva fecha de vencimiento del servidor'

        Log 'Renovar un vencido'
        Adelantar-Reloj { param($t) $t.AddMonths(6).AddDays(11) } # pasa el vencimiento de cotización y suscripción
        Refrescar
        Esperar-Conteo 'Vencido' 2
        Verificar 'cotización y suscripción aparecen vencidas'
        Tocar-Bajo $Sus 'Renovar'
        Esperar-Conteo 'Vencido' 1
        Esperar-Texto 'Nueva fecha de vencimiento: [0-9-]+' | Out-Null
        Verificar 'un vencido se renueva en una acción y queda vigente'
    }

    Log 'Prueba de consentimiento superada'
} finally {
    Restaurar-Reloj
}
