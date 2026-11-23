import { useTranslation } from 'react-i18next'

import type { Pago } from '../../api/tipos'
import { Aviso, type TipoAviso } from '../../componentes/Aviso'
import estilos from '../cotizacion/Cotizacion.module.css'

const TIPO: Record<Pago['estado'], TipoAviso> = { aprobado: 'exito', rechazado: 'error', pendiente: 'advertencia' }

/** Respuesta de la pasarela con su siguiente paso. Las acciones las pone la pantalla. */
export function ResultadoPago({ pago }: { pago: Pago }) {
  const { t } = useTranslation()
  return (
    <div className={estilos.pila}>
      <Aviso tipo={TIPO[pago.estado]} rol="status">
        <p>
          <strong>{t(`suscripcion.resultado.${pago.estado}`)}</strong>{' '}
          {t(`suscripcion.resultado.${pago.estado}Detalle`)}
        </p>
      </Aviso>
      <dl className={estilos.detalles}>
        <dt>{t('suscripcion.resultado.referencia')}</dt>
        <dd>{pago.referencia}</dd>
      </dl>
    </div>
  )
}
