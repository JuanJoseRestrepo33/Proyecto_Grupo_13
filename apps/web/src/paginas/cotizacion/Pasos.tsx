import { useTranslation } from 'react-i18next'

import estilos from './Cotizacion.module.css'
import { PASOS } from './pasos-asistente'

/** Indicador de progreso: el paso actual lleva aria-current="step". */
export function Pasos({ actual }: { actual: number }) {
  const { t } = useTranslation()
  return (
    <ol className={estilos.pasos} aria-label={t('cotizacion.pasos.etiqueta')}>
      {PASOS.map((paso, indice) => (
        <li
          key={paso}
          className={estilos.paso}
          aria-current={indice === actual ? 'step' : undefined}
          data-estado={indice < actual ? 'hecho' : undefined}
        >
          {t(`cotizacion.pasos.${paso}`)}
        </li>
      ))}
    </ol>
  )
}
