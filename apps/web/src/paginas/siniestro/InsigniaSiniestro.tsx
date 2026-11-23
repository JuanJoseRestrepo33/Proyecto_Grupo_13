import { useTranslation } from 'react-i18next'

import type { EstadoSiniestro } from '../../api/tipos'
import estilosPolizas from '../polizas/Polizas.module.css'
import estilos from './Seguimiento.module.css'

const PRESENTACION: Record<EstadoSiniestro, { simbolo: string; tono: 'proceso' | 'exito' | 'rechazo' }> = {
  reportado: { simbolo: '●', tono: 'proceso' },
  en_evaluacion: { simbolo: '◐', tono: 'proceso' },
  requiere_informacion: { simbolo: '!', tono: 'proceso' },
  aprobado: { simbolo: '✓', tono: 'exito' },
  rechazado: { simbolo: '✕', tono: 'rechazo' },
  pagado: { simbolo: '✓', tono: 'exito' },
}

/** Estado del caso con su texto, nunca solo con color (WEB-F06, criterio 1). */
export function InsigniaSiniestro({ estado }: { estado: EstadoSiniestro }) {
  const { t } = useTranslation()
  const { simbolo, tono } = PRESENTACION[estado]
  return (
    <span className={`${estilosPolizas.insignia} ${estilos.insignia}`} data-tono={tono}>
      <span aria-hidden="true">{simbolo}</span>
      {t(`siniestro.estados.${estado}`)}
    </span>
  )
}
