<#
.SYNOPSIS
Arranca el emulador, instala la app debug (BFF simulado), crea una cuenta y activa la huella.

.DESCRIPTION
La huella exige una registrada en el emulador (Settings > Security > Fingerprint). Si no hay
ninguna, el script elige "Ahora no" y lo avisa. El mock vive en memoria: al cerrar la app la
cuenta desaparece y basta con volver a ejecutar el script.
El documento debe terminar en 0-6 para que el KYC simulado lo apruebe (ver README).

.EXAMPLE
mobile\android\scripts\demo-emulador.ps1
mobile\android\scripts\demo-emulador.ps1 -SinCompilar -Correo otra@ejemplo.co
#>
param(
    [string]$Avd,
    [string]$Nombre = 'Hernan Hurtado',
    [string]$Correo = 'hernan@ejemplo.co',
    [string]$Celular = '3001234567',
    [string]$Documento = '1098765432',
    [string]$Contrasena = 'Solventa2026',
    [string]$PinEmulador, # si la huella no basta para quitar la pantalla de bloqueo
    [switch]$SinCompilar,
    [switch]$SinHuella
)
. "$PSScriptRoot\EmuladorComun.ps1"

$CodigoOtp = '123456' # fijo en MockBffMovil

# Gradle exige JDK 17+. Se respeta JAVA_HOME si cumple; si no, se busca uno instalado.
function Buscar-Jdk17 {
    $candidatos = @($env:JAVA_HOME, 'C:\Program Files\Android\Android Studio\jbr') +
        (Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java',
            'C:\Program Files\Microsoft', "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object FullName)
    foreach ($jdk in $candidatos) {
        if (-not $jdk) { continue }
        $release = Join-Path $jdk 'release'
        if ((Test-Path $release) -and (Get-Content $release -Raw) -match 'JAVA_VERSION="(\d+)' -and
            [int]$Matches[1] -ge 17) { return $jdk }
    }
    throw 'Se necesita JDK 17 o superior (JAVA_HOME o Android Studio).'
}

# ── 1. Emulador ──────────────────────────────────────────────────────────────────────────────

if (Hay-Emulador) {
    Log 'Emulador ya en ejecución'
} else {
    if (-not $Avd) { $Avd = (& $Emulador -list-avds | Select-Object -First 1) }
    if (-not $Avd) { throw 'No hay AVD; crea uno en Android Studio (API 26+).' }
    Log "Arrancando emulador $Avd"
    Start-Process -FilePath $Emulador -ArgumentList '-avd', $Avd, '-no-boot-anim'
}
Adb wait-for-device
while ((Adb shell getprop sys.boot_completed) -ne '1') { Start-Sleep -Seconds 2 }
# Con cargador conectado y stayon, la pantalla no entra en reposo durante compilación ni demo.
Adb emu power ac on | Out-Null
Adb shell svc power stayon true

# ── 2. App ───────────────────────────────────────────────────────────────────────────────────

if (-not $SinCompilar) {
    Log 'Compilando app debug'
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $Sdk }
    $env:JAVA_HOME = Buscar-Jdk17
    Push-Location $Proyecto
    try {
        & .\gradlew.bat --quiet :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación' }
    } finally { Pop-Location }
}
Log 'Instalando y abriendo la app'
Adb install -r $Apk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Falló la instalación del APK' }
# Datos limpios: sin la llave biométrica de una ejecución anterior la app vuelve a ofrecer la huella.
Adb shell pm clear $Paquete | Out-Null
Desbloquear
Adb shell am start -S -n "$Paquete/co.solventa.mobile.MainActivity" | Out-Null
# La pantalla aparece en uiautomator un instante antes de aceptar toques.
Esperar-Texto 'Crear cuenta' | Out-Null
Start-Sleep -Seconds 1

# ── 3. Cuenta ────────────────────────────────────────────────────────────────────────────────

Log "Creando la cuenta $Correo"
Tocar 'Crear cuenta'
Escribir 'Nombre completo' $Nombre
Escribir 'Correo electrónico' $Correo
Escribir 'Celular' $Celular
Tocar 'Continuar'
Escribir 'Número de documento' $Documento
Tocar 'Continuar'
Escribir 'Contraseña' $Contrasena
Escribir 'Confirma tu contraseña' $Contrasena
Tocar 'Acepto los términos.*'
Tocar 'Crear cuenta'
Escribir 'Código de verificación' $CodigoOtp
Tocar 'Verificar'
Tocar 'Continuar'

# ── 4. Huella ────────────────────────────────────────────────────────────────────────────────

Esperar-Texto 'Activar|Inicio' | Out-Null
$hayHuella = (Adb shell dumpsys fingerprint | Out-String) -match '"prints":\[\{'
if (-not (Centro-De (Volcar-Ui) 'Activar')) {
    Write-Warning 'La app no ofreció la huella: ya estaba activa en este dispositivo.'
} elseif (-not $SinHuella -and $hayHuella) {
    Log 'Activando la huella'
    Tocar 'Activar'
    Start-Sleep -Milliseconds 1500
    Adb emu finger touch 1 | Out-Null
    Tocar 'Ir al inicio'
} else {
    if (-not $SinHuella) { Write-Warning 'Sin huellas registradas en el emulador: se omite la activación.' }
    Tocar 'Ahora no'
}

Log "Listo: sesión iniciada como $Correo / $Contrasena"
