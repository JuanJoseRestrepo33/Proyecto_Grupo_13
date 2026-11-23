package co.solventa.feature.notificaciones

/** Qué dice Inicio sobre las notificaciones (P20: el permiso se pide en contexto y con alternativa). */
enum class EstadoPermiso {
    /** Permitidas: no hay aviso. */
    CONCEDIDO,

    /** Android 13+ y aún no se pidió: se explica para qué sirven antes de pedirlo. */
    SIN_PEDIR,

    /** Negadas o apagadas: se explica qué se pierde y dónde se sigue viendo el estado (criterio 3). */
    NEGADO,
}

/** @param pedible Android 13+, donde el permiso se pide en tiempo de ejecución. */
fun estadoPermiso(permitidas: Boolean, pedible: Boolean, pedido: Boolean): EstadoPermiso = when {
    permitidas -> EstadoPermiso.CONCEDIDO
    pedible && !pedido -> EstadoPermiso.SIN_PEDIR
    else -> EstadoPermiso.NEGADO
}
