import type { ReactNode } from 'react'

import estilos from './Aviso.module.css'

export type TipoAviso = 'info' | 'exito' | 'advertencia' | 'error'

const ICONOS: Record<TipoAviso, string> = { info: 'ℹ️', exito: '✅', advertencia: '⚠️', error: '⛔' }

interface PropiedadesAviso {
  tipo: TipoAviso
  children: ReactNode
  /**
   * `alert` para lo que el usuario debe saber de inmediato (un error, una oferta vencida);
   * `status` para avisos que aparecen sin interrumpir. Sin rol si el aviso es parte fija de la pantalla.
   */
  rol?: 'alert' | 'status'
}

export function Aviso({ tipo, children, rol }: PropiedadesAviso) {
  return (
    <div className={`${estilos.aviso} ${estilos[tipo]}`} role={rol}>
      <span aria-hidden="true">{ICONOS[tipo]}</span>
      <div className={estilos.contenido}>{children}</div>
    </div>
  )
}
