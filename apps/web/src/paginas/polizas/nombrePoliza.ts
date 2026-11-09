import type { TFunction } from 'i18next'

import type { PolizaResumen } from '../../api/tipos'

/** Nombre comercial de la póliza o, si el catálogo no lo tiene, el nombre genérico de su producto. */
export function nombrePoliza(poliza: PolizaResumen, t: TFunction): string {
  return (
    poliza.nombre_producto ??
    t('polizas.seguroDe', {
      producto: t(`siniestro.productos.${poliza.producto}`, { defaultValue: poliza.producto }),
    })
  )
}
