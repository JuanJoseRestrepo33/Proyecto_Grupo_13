# MOB-F01 · Onboarding y autenticación biométrica

Registro, verificación del correo, resultado de la verificación de identidad (KYC/AML) e ingreso con
contraseña o biometría. Trabaja contra el contrato [`contratos/bff-movil`](../../../../contratos/bff-movil/openapi.yaml);
en debug, el mock de `app/src/debug` responde los escenarios descritos en el README de `mobile/android`.

## Flujo

```
Ingreso ──Crear cuenta──▶ Registro (3 pasos) ──▶ OTP ──▶ Resultado KYC ──▶ Oferta de biometría ──▶ Inicio
   │                                                       │   (solo verificados con sensor)
   └──contraseña o huella──▶ verificado ──▶ (oferta) ──▶ Inicio
                             no verificado ──▶ Resultado KYC
```

`SolventaRaiz` (en `app`) arma la navegación; este módulo expone una `Ruta…` por pantalla, que crea su
ViewModel, y la pantalla sin estado propio que usan las pruebas.

| Paquete | Qué resuelve | Criterios |
|---|---|---|
| `registro` | Asistente de 3 pasos (datos, documento, contraseña) con validación en línea: el error aparece al salir del campo o al intentar avanzar, y se actualiza mientras se corrige. Los rechazos del BFF (`409`, `422`) vuelven al paso del campo afectado. | 1, P04, P06 |
| `otp` | Código de 6 dígitos, 3 intentos por código. Al tercer fallo el código se bloquea (no la cuenta) y se ofrece pedir otro; el reenvío respeta `reenvio_disponible_en` y `Retry-After`. La pantalla recuerda que la cuenta está *pendiente de verificación*. | 1 |
| `identidad` | Consulta la verificación y, si no existe, la solicita. Aprobada → continuar; rechazada → motivo en lenguaje claro y reintento si el proveedor lo permite; en revisión → «te avisaremos». `en_proceso` no bloquea: se explica, se ofrece ir al inicio y se reconsulta a los 3 s. | 3, 7, 8 (parcial) |
| `ingreso` | Contraseña (sin bloqueo por intentos) y, si hay biometría activa, huella o rostro. Tres lecturas fallidas, bloqueo del sensor, llave invalidada o token vencido llevan al formulario de contraseña con una explicación. | 5 |
| `biometria` | Oferta con explicación (qué implica, privacidad, respaldo), `BiometricPrompt` y almacén del token. | 4, 5 |
| `sesion` | Sesión en memoria; el token de acceso nunca se persiste. | — |

Todos los textos están en `src/main/res/values/strings.xml`; los botones y controles miden al menos
48 dp (los `Button` de Material miden 40 dp por defecto: `BotonPrincipal`, `BotonSecundario` y
`BotonTexto` fijan el mínimo).

## Biometría: Solventa nunca recibe el dato biométrico

`AlmacenTokenKeystore` guarda el **token de refresco** con cifrado híbrido:

- Un par RSA-2048 del Android Keystore (`solventa.ingreso_biometrico`). La llave privada exige
  biometría fuerte **en cada uso** y se invalida si se enrola otra huella o rostro.
- Cada token se cifra con una llave AES-256-GCM nueva, envuelta con la llave pública. Cifrar no exige
  biometría, así que el token rotado tras cada renovación se guarda sin pedir la huella otra vez.
- En `SharedPreferences` solo queda el sobre cifrado (llave envuelta, IV y texto cifrado).

Ingresar con biometría: `BiometricPrompt` desbloquea el `Cipher` de la llave privada (`CryptoObject`),
se descifra el token y se envía a `POST /sesiones/renovacion`. El BFF rota el token y el nuevo se guarda.
Si el BFF lo rechaza (`401`), el token se borra y queda la contraseña. Activar la biometría guarda el
token y lo lee de vuelta con una lectura biométrica, lo que confirma que sensor y llave funcionan antes
de darla por activa. Cualquier fallo del Keystore borra el sobre y vuelve a la contraseña.

`SesionBiometrica` concentra las reglas del diálogo, sin dependencias de Android, para probarlas en
JVM: cancela al tercer `onAuthenticationFailed`, trata el bloqueo del sensor como fallo y entrega un
solo resultado aunque el sistema avise después de la cancelación.

## Pruebas

- **JVM** (`src/test`): validación, ViewModels con un `BffApi` falso (`Dobles.kt`), reglas del
  diálogo biométrico y traducción de errores del BFF.
- **Compose + Robolectric** (`src/testDebug`): cada pantalla, incluidos los objetivos táctiles de 48 dp.
- **De punta a punta** (`app/src/testDebug/.../SolventaRaizTest.kt`): registro → OTP → KYC → oferta →
  inicio contra `MockBffMovil`, ingreso con huella tras reabrir la app y p95 del registro
  (`RegistroRendimientoTest`, ≤ 3 s).

`AlmacenTokenKeystore` y `BiometriaAndroid` dependen del Keystore y del sensor reales; Robolectric no
los simula. Se verifican en dispositivo (ver pendientes en el README de `mobile/android`).

## Fuera de alcance (Sprint 1)

Captura guiada de documento y selfie (criterios 2 y 6), cola ante caída del proveedor (criterio 8
completo), KYC real, recuperación de contraseña, login social, backend de registro y el registro en el
log de auditoría (lo escribe el BFF, criterio 9).
