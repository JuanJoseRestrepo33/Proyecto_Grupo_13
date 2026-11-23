import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'

import type { Cotizacion } from '../../api/tipos'
import { formatearCuentaRegresiva, formatearHora, formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from '../cotizacion/Cotizacion.module.css'
import { useCuentaRegresiva } from '../cotizacion/useCuentaRegresiva'

interface Propiedades {
  cotizacion: Cotizacion
  /** Avisa cuando la oferta vence mientras el cliente la revisa. */
  onVencer: () => void
}

/** Condiciones de la oferta que el cliente va a aceptar, con su vigencia. */
export function PasoRevisar({ cotizacion, onVencer }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const segundos = useCuentaRegresiva(cotizacion.vigente_hasta)
  const vencio = segundos === 0 && cotizacion.estado === 'vigente'

  useEffect(() => {
    if (vencio) onVencer()
  }, [vencio, onVencer])

  return (
    <div className={estilos.pila}>
      <div>
        <h3>{cotizacion.nombre_producto}</h3>
        <p className={estilos.ayuda}>{t('suscripcion.revisar.numero', { id: cotizacion.id })}</p>
      </div>
      <p className={estilos.prima}>
        {formatearMoneda(cotizacion.prima_mensual, idioma)}
        <small>{t('cotizacion.oferta.porMes')}</small>
      </p>
      {cotizacion.estado === 'vigente' && segundos > 0 && (
        <p className={estilos.vigencia}>
          <span aria-hidden="true">
            {t('cotizacion.oferta.vigencia')}{' '}
            <span className={estilos.contador} role="timer">
              {formatearCuentaRegresiva(segundos)}
            </span>
          </span>
          <span className="sr-only">
            {t('cotizacion.oferta.vigenciaHasta', { hora: formatearHora(cotizacion.vigente_hasta, idioma) })}
          </span>
        </p>
      )}
      <dl className={estilos.detalles}>
        <dt>{t('cotizacion.oferta.valorAsegurado')}</dt>
        <dd>{cotizacion.valor_asegurado}</dd>
        <dt>{t('cotizacion.oferta.deducible')}</dt>
        <dd>{cotizacion.deducible}</dd>
      </dl>
      <div>
        <h4>{t('cotizacion.oferta.coberturas')}</h4>
        <ul className={estilos.coberturas}>
          {cotizacion.coberturas.map((cobertura) => (
            <li key={cobertura.nombre}>
              {cobertura.nombre}: {cobertura.limite}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
