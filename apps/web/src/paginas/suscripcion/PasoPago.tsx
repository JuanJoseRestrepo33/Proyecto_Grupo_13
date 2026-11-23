import { useTranslation } from 'react-i18next'

import type { MedioPago } from '../../api/tipos'
import estilos from '../cotizacion/Cotizacion.module.css'

const MEDIOS: MedioPago[] = ['tarjeta_credito', 'pse']

interface Propiedades {
  medio: MedioPago | null
  error: boolean
  /** Mientras el pago se procesa no se puede cambiar el medio. */
  procesando: boolean
  onElegir: (medio: MedioPago) => void
}

/**
 * Medio de pago. El cliente no escribe aquí los datos de la tarjeta ni de la cuenta: los recibe la
 * pasarela, así Solventa no los toca.
 */
export function PasoPago({ medio, error, procesando, onElegir }: Propiedades) {
  const { t } = useTranslation()
  return (
    <div className={estilos.pila}>
      <fieldset
        className={estilos.opciones}
        disabled={procesando}
        aria-describedby={error ? 'error-medio' : undefined}
      >
        <legend className="sr-only">{t('suscripcion.pago.medios')}</legend>
        {MEDIOS.map((opcion) => (
          <label key={opcion} className={estilos.opcion}>
            <input
              type="radio"
              name="medio"
              value={opcion}
              checked={medio === opcion}
              onChange={() => onElegir(opcion)}
            />
            <strong>{t(`suscripcion.pago.${opcion}`)}</strong>
            <span>{t(`suscripcion.pago.${opcion}Ayuda`)}</span>
          </label>
        ))}
      </fieldset>
      {error && (
        <p id="error-medio" className={estilos.error} role="alert">
          {t('suscripcion.avisos.medioRequerido')}
        </p>
      )}
      <p className={estilos.ayuda}>{t('suscripcion.pago.seguridad')}</p>
    </div>
  )
}
