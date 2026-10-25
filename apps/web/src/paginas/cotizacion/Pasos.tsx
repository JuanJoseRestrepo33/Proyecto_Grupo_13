import { useTranslation } from 'react-i18next'

import estilos from './Cotizacion.module.css'
import { PASOS } from './pasos-asistente'

interface Propiedades {
  actual: number
  /** Pasos en orden; por defecto, los del asistente de cotización. */
  pasos?: readonly string[]
  /** Prefijo de i18n de los nombres de los pasos. */
  claves?: string
}

/** Indicador de progreso: el paso actual lleva aria-current="step". */
export function Pasos({ actual, pasos = PASOS, claves = 'cotizacion.pasos' }: Propiedades) {
  const { t } = useTranslation()
  return (
    <ol className={estilos.pasos} aria-label={t(`${claves}.etiqueta`)}>
      {pasos.map((paso, indice) => (
        <li
          key={paso}
          className={estilos.paso}
          aria-current={indice === actual ? 'step' : undefined}
          data-estado={indice < actual ? 'hecho' : undefined}
        >
          {t(`${claves}.${paso}`)}
        </li>
      ))}
    </ol>
  )
}
