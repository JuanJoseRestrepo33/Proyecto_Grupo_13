import { useTranslation } from 'react-i18next'

import { urlAbsolutaBff } from '../../api/cliente'
import type { PolizaEmitida } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { clasesBoton } from '../../componentes/clasesBoton'
import { formatearDia } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import estilos from './Suscripcion.module.css'

/** La póliza quedó emitida: número, vigencia y documento (WEB-F03, criterio 4). */
export function ResultadoEmision({ poliza, producto }: { poliza: PolizaEmitida; producto: string }) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma

  return (
    <div className={estilosCotizacion.pila}>
      <Aviso tipo="exito" rol="status">
        <p>
          <strong>{t('suscripcion.emision.exito')}</strong> {t('suscripcion.emision.exitoDetalle')}
        </p>
      </Aviso>
      <dl className={estilosCotizacion.detalles}>
        <dt>{t('suscripcion.emision.numero')}</dt>
        <dd>{poliza.numero}</dd>
        <dt>{t('suscripcion.firma.producto')}</dt>
        <dd>{producto}</dd>
        <dt>{t('suscripcion.emision.vigencia')}</dt>
        <dd>
          {t('suscripcion.emision.vigenciaDetalle', {
            inicio: formatearDia(poliza.inicio, idioma),
            fin: formatearDia(poliza.fin, idioma),
          })}
        </dd>
      </dl>
      <div className={estilos.acciones}>
        <a className={clasesBoton('primario')} href={urlAbsolutaBff(poliza.documento_url)} download>
          {t('suscripcion.emision.descargar')}
        </a>
      </div>
    </div>
  )
}
