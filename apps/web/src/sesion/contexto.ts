import { createContext, useContext } from 'react'

import type { Credenciales, Sesion } from '../api/tipos'

/**
 * Sesión del cliente en la web. `anonima` lleva el motivo cuando la sesión terminó en esta visita:
 * `vencida` si el BFF respondió 401 `sesion_no_vigente` y `cerrada` si el cliente la cerró.
 */
export type EstadoSesion =
  | { tipo: 'cargando' }
  | { tipo: 'autenticada'; sesion: Sesion }
  | { tipo: 'anonima'; motivo?: 'vencida' | 'cerrada' }
  | { tipo: 'error' }

/** `credenciales` agrupa el 401 y el 422: el mensaje no revela si el correo existe (WEB-F13, criterio 3). */
export type ResultadoInicio = 'iniciada' | 'credenciales' | 'servicio'

export interface ValorSesion {
  estado: EstadoSesion
  iniciar: (credenciales: Credenciales) => Promise<ResultadoInicio>
  /**
   * `false` si el BFF no respondió o respondió con un error (cualquier estado que no sea 2xx): la
   * cookie puede seguir vigente, así que la sesión sigue abierta.
   */
  cerrar: () => Promise<boolean>
  reintentar: () => void
}

export const ContextoSesion = createContext<ValorSesion | null>(null)

export function useSesion(): ValorSesion {
  const valor = useContext(ContextoSesion)
  if (!valor) throw new Error('useSesion se usa dentro de <ProveedorSesion>')
  return valor
}
