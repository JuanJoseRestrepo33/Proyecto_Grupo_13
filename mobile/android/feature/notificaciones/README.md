# feature/notificaciones · MOB-F06 · Push del siniestro

El cliente recibe una notificación en el teléfono cuando cambia el estado de su siniestro, y al tocarla
se abre el detalle de ese siniestro. El proveedor de push es **simulado** (ver «Push» en
[`contratos/bff-movil`](../../../../contratos/bff-movil/openapi.yaml)): no hay Firebase en la app.

## Cómo llega el push

| Momento | Qué hace la app |
|---|---|
| Inicio de sesión o arranque con sesión | `POST /dispositivos` con un identificador estable de la instalación como `token_push` (criterio 4). Una vez por proceso y cuenta; sin red, se reintenta en la vuelta siguiente |
| Cada 30 s mientras el proceso vive, y al volver a la app | `GET /notificaciones`. Cada una sin leer que esta instalación no haya atendido se muestra como notificación local con `titulo` y `cuerpo` (criterio 1) |
| Primera lectura en la instalación | No muestra nada: marca lo que ya había, para no inundar al cliente con avisos viejos al reinstalar |
| Toque en la notificación | Abre el detalle de `datos.id_siniestro` (con `datos.tipo` = `siniestro_estado`) y la marca leída (criterio 2). Si la sesión ya no estaba, primero se inicia sesión |
| Cerrar sesión | `DELETE /dispositivos/{id}` con el token que tenía la sesión, antes de olvidarla |

Lo atendido se guarda por cuenta en las preferencias privadas de la app. Reinstalar da un identificador
nuevo: el dispositivo se registra otra vez al iniciar sesión.

**Segundo plano.** La lectura vive lo que el proceso, como el envío de borradores: si Android mata el
proceso, el push llega al volver a abrir la app. El contrato menciona WorkManager; el intervalo mínimo de
un trabajo periódico es de 15 min, así que se dejó para cuando haga falta (con Firebase real, el push
llega sin sondeo y estas rutas no cambian).

## Permiso (P20, criterio 3)

En Android 13+ el permiso se pide en contexto, desde Inicio y explicando para qué sirve («Activar
notificaciones» o «Ahora no»). Si el cliente lo niega, o apaga las notificaciones de la app, Inicio
explica que no le avisaremos y ofrece «Ver mis siniestros», donde el estado se sigue viendo, y «Activar
en Ajustes». Sin permiso, la notificación no se muestra pero queda atendida: no aparece de golpe al
darlo.

## Piezas

- `ReceptorPush`: registro, lectura de la bandeja y baja. Sin Android: se prueba con dobles.
- `AlmacenPushAndroid`, `NotificadorAndroid`: preferencias y notificación local (canal «Estado de tus
  siniestros»).
- `AperturaPush`, `AperturasPush`: el intento de la notificación y la apertura pendiente hasta que la
  navegación principal la atiende.
- `AvisoPermisoNotificaciones`: el aviso de Inicio con su estado real.

Etiquetas para pruebas: `aviso-notificaciones`, `activar-notificaciones`, `notificaciones-ahora-no`,
`aviso-ver-siniestros`.
