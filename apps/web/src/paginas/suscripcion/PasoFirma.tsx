import { useTranslation } from 'react-i18next'

import type { Cotizacion } from '../../api/tipos'
import { formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import estilos from './Suscripcion.module.css'

interface Propiedades {
  cotizacion: Cotizacion
  acepta: boolean
  error: boolean
  deshabilitado: boolean
  onCambiar: (acepta: boolean) => void
}

/** Condiciones que el cliente firma; la firma exige que las acepte expresamente (WEB-F03, criterio 4). */
export function PasoFirma({ cotizacion, acepta, error, deshabilitado, onCambiar }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma

  return (
    <div className={estilosCotizacion.pila}>
      <p>{t('suscripcion.firma.descripcion')}</p>
      <dl className={estilosCotizacion.detalles}>
        <dt>{t('suscripcion.firma.producto')}</dt>
        <dd>{cotizacion.nombre_producto}</dd>
        <dt>{t('suscripcion.firma.prima')}</dt>
        <dd>{formatearMoneda(cotizacion.prima_mensual, idioma)}</dd>
        <dt>{t('cotizacion.oferta.valorAsegurado')}</dt>
        <dd>{cotizacion.valor_asegurado}</dd>
        <dt>{t('cotizacion.oferta.deducible')}</dt>
        <dd>{cotizacion.deducible}</dd>
      </dl>
      <div>
        <h3>{t('cotizacion.oferta.coberturas')}</h3>
        <ul className={estilosCotizacion.coberturas}>
          {cotizacion.coberturas.map((cobertura) => (
            <li key={cobertura.nombre}>
              {cobertura.nombre}: {cobertura.limite}
            </li>
          ))}
        </ul>
      </div>
      <div>
        <div className={estilos.casilla}>
          <input
            id="acepta-condiciones"
            type="checkbox"
            checked={acepta}
            disabled={deshabilitado}
            aria-invalid={error}
            aria-describedby={error ? 'acepta-condiciones-error' : undefined}
            onChange={(evento) => onCambiar(evento.target.checked)}
          />
          <label htmlFor="acepta-condiciones">{t('suscripcion.firma.acepta')}</label>
        </div>
        {error && (
          <p id="acepta-condiciones-error" className={estilosCotizacion.error}>
            {t('suscripcion.firma.aceptaRequerida')}
          </p>
        )}
      </div>
    </div>
  )
}
