import { useTranslation } from 'react-i18next'

import type { PolizaResumen } from '../../api/tipos'
import estilos from './Polizas.module.css'

const SIMBOLO: Record<PolizaResumen['estado'], string> = { vigente: '●', vencida: '○', por_iniciar: '◐' }

/** Estado de la póliza con insignia y texto, nunca solo con color (P12). */
export function InsigniaPoliza({ estado }: { estado: PolizaResumen['estado'] }) {
  const { t } = useTranslation()
  return (
    <span className={estilos.insignia} data-estado={estado}>
      <span aria-hidden="true">{SIMBOLO[estado]}</span>
      {t(`polizas.estados.${estado}`)}
    </span>
  )
}
