package co.solventa.feature.siniestros

import co.solventa.core.network.BffApi
import co.solventa.feature.siniestros.evidencia.AlmacenEvidencias
import co.solventa.feature.siniestros.evidencia.LectorUbicacion
import java.time.LocalDate
import java.util.UUID

/**
 * Lo que el reporte de siniestros (WEB-F05) necesita de la app; las pruebas lo arman con dobles.
 * El token de acceso viaja en cada llamada vía NetworkClient; un 401 se delega al flujo de sesión.
 *
 * @param hoy día local del dispositivo: el selector no deja elegir uno posterior.
 * @param nuevaClave genera la `Idempotency-Key` de cada reporte (UUID v4).
 * @param almacen archivos temporales de la evidencia (MOB-F05).
 * @param ubicacion lectura al tomar una foto, con el permiso concedido.
 */
class DependenciasSiniestros(
    val api: BffApi,
    val hoy: () -> LocalDate = LocalDate::now,
    val nuevaClave: () -> String = { UUID.randomUUID().toString() },
    val almacen: AlmacenEvidencias = AlmacenEvidencias.Nulo,
    val ubicacion: LectorUbicacion = LectorUbicacion { null },
)
