# Funciones compartidas por demo-emulador.ps1 y prueba-consentimiento.ps1. Se carga con
# dot-sourcing (`. "$PSScriptRoot\EmuladorComun.ps1"`); Desbloquear usa $PinEmulador del script.

$ErrorActionPreference = 'Stop'
# adb devuelve UTF-8 (etiquetas con tildes) y Windows PowerShell 5.1 asume otra codificación.
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$Paquete = 'co.solventa.mobile.debug'
$EsperaMax = 60
$Proyecto = Split-Path -Parent $PSScriptRoot
$Apk = Join-Path $Proyecto 'app\build\outputs\apk\debug\app-debug.apk'
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulador = Join-Path $Sdk 'emulator\emulator.exe'
if (-not (Test-Path $Adb)) { throw "No se encontró adb en $Adb" }

function Log([string]$Mensaje) { Write-Host "`n==> $Mensaje" -ForegroundColor Cyan }
function Verificar([string]$Mensaje) { Write-Host "  ✓ $Mensaje" -ForegroundColor Green }

# Ejecuta adb sin que su stderr (progreso, avisos) aborte el script en Windows PowerShell 5.1.
function Adb {
    $ErrorActionPreference = 'Continue'
    & $Adb @args 2>$null
}

# ── Interacción con la UI por texto visible (uiautomator) ────────────────────────────────────

# Durante una transición uiautomator puede no volcar nada: se devuelve $null y se reintenta.
function Volcar-Ui {
    Adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    try { [xml]((Adb exec-out cat /sdcard/ui.xml) -join '') } catch { $null }
}

# Nodos cuyo atributo (text por defecto) coincide completo con la expresión, en orden de pantalla.
function Nodos($Ui, [string]$Texto, [string]$Atributo = 'text') {
    if (-not $Ui) { return @() }
    @($Ui.SelectNodes('//node') | Where-Object { $_.GetAttribute($Atributo) -match "^($Texto)$" })
}

# @(): con un solo nodo, PowerShell lo desenvuelve y .Count de un XmlElement no es 1.
function Contar($Ui, [string]$Texto) { @(Nodos $Ui $Texto).Count }

# @(x1, y1, x2, y2) de un nodo.
function Bordes($Nodo) {
    @([regex]::Matches($Nodo.GetAttribute('bounds'), '\d+') | ForEach-Object { [int]$_.Value })
}

# Centro @(x, y) de la coincidencia N (1 por defecto).
function Centro-De($Ui, [string]$Texto, [int]$N = 1, [string]$Atributo = 'text') {
    $nodos = @(Nodos $Ui $Texto $Atributo)
    if ($nodos.Count -lt $N) { return $null }
    $b = Bordes $nodos[$N - 1]
    @([int](($b[0] + $b[2]) / 2), [int](($b[1] + $b[3]) / 2))
}

function Esperar-Texto([string]$Texto, [int]$N = 1, [string]$Atributo = 'text') {
    $fin = (Get-Date).AddSeconds($EsperaMax)
    while ((Get-Date) -lt $fin) {
        $centro = Centro-De (Volcar-Ui) $Texto $N $Atributo
        if ($centro) { return $centro }
        Start-Sleep -Seconds 1
    }
    throw "No apareció en pantalla: $Texto"
}

function Esperar-Ausente([string]$Texto, [string]$Atributo = 'text') {
    $fin = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $fin) {
        $ui = Volcar-Ui
        if ($ui -and -not (Centro-De $ui $Texto 1 $Atributo)) { return }
        Start-Sleep -Seconds 1
    }
    throw "Sigue en pantalla: $Texto"
}

function Esperar-Conteo([string]$Texto, [int]$Esperado) {
    $fin = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $fin) {
        $ui = Volcar-Ui
        if ($ui -and (Contar $ui $Texto) -eq $Esperado) { return }
        Start-Sleep -Seconds 1
    }
    throw "Se esperaban $Esperado de: $Texto"
}

function Tocar([string]$Texto, [int]$N = 1, [string]$Atributo = 'text') {
    $c = Esperar-Texto $Texto $N $Atributo
    Adb shell input tap $c[0] $c[1]
    Start-Sleep -Milliseconds 600
}

# Toca el primer $Texto situado debajo de $Ancla (p. ej. el botón de una tarjeta cuyo título es
# $Ancla), desplazando la lista hacia abajo si aún no se ve.
function Tocar-Bajo([string]$Ancla, [string]$Texto) {
    foreach ($i in 1..4) {
        $ui = Volcar-Ui
        $anclas = @(Nodos $ui $Ancla)
        if ($anclas.Count -gt 0) {
            $y = (Bordes $anclas[0])[3]
            foreach ($n in @(Nodos $ui $Texto)) {
                $b = Bordes $n
                if ($b[1] -gt $y) {
                    Adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
                    Start-Sleep -Milliseconds 600
                    return
                }
            }
        }
        Adb shell input swipe 540 1600 540 1000 300
        Start-Sleep -Milliseconds 800
    }
    throw "No apareció «$Texto» debajo de «$Ancla»"
}

# Lleva la lista al principio. Al llegar arriba el gesto puede refrescar: es inocuo.
function Al-Inicio {
    Adb shell input swipe 540 800 540 1700 300
    Adb shell input swipe 540 800 540 1700 300
    Start-Sleep -Milliseconds 1500
}

function Escribir([string]$Etiqueta, [string]$Valor) {
    Tocar $Etiqueta
    Adb shell input text "'$($Valor -replace ' ', '%s')'"
    Adb shell input keyevent 111 # cierra el teclado para no tapar botones
    Start-Sleep -Milliseconds 300
}

# ── Emulador ─────────────────────────────────────────────────────────────────────────────────

function Hay-Emulador { [bool]((Adb devices) -match '^emulator-\d+\s+device') }

function Bloqueado { (Adb shell dumpsys window | Out-String) -match 'isKeyguardShowing=true' }

function Esperar-Desbloqueo {
    foreach ($i in 1..5) {
        if (-not (Bloqueado)) { return $true }
        Start-Sleep -Seconds 1
    }
    $false
}

# Registrar una huella obliga a tener PIN, así que tras el reposo la pantalla queda bloqueada.
function Desbloquear {
    Adb shell input keyevent KEYCODE_WAKEUP
    Adb shell wm dismiss-keyguard
    if (Esperar-Desbloqueo) { return }
    Adb emu finger touch 1 | Out-Null
    if (Esperar-Desbloqueo) { return }
    # Tras reiniciar, Android exige el PIN una vez antes de aceptar la huella.
    if ($PinEmulador) {
        Adb shell input keyevent KEYCODE_WAKEUP
        Adb shell input swipe 540 1800 540 600 300 # muestra el teclado del PIN
        Start-Sleep -Seconds 1
        Adb shell input text $PinEmulador
        Adb shell input keyevent KEYCODE_ENTER
        if (Esperar-Desbloqueo) { return }
    }
    throw 'La pantalla sigue bloqueada: desbloquéala a mano o usa -PinEmulador.'
}
