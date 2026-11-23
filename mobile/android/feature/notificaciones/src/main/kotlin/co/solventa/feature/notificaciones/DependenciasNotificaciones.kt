package co.solventa.feature.notificaciones

/**
 * Lo que la app necesita del push (MOB-F06): el [receptor] de la bandeja, el [almacen] para el aviso del
 * permiso y las [aperturas] de las notificaciones tocadas.
 */
class DependenciasNotificaciones(
    val receptor: ReceptorPush,
    val almacen: AlmacenPush,
    val aperturas: AperturasPush,
)
