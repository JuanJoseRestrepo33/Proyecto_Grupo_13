import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'

import { alVencerSesion, bff, marcarSesionNueva } from '../api/cliente'
import type { Credenciales } from '../api/tipos'
import { ContextoSesion, type EstadoSesion, type ResultadoInicio, type ValorSesion } from './contexto'

/**
 * Consulta la sesión al cargar (`GET /sesion`) y la mantiene para toda la aplicación. La web nunca
 * ve el token: la cookie `sesion` es `HttpOnly` y la maneja el navegador.
 */
export function ProveedorSesion({ children }: { children: ReactNode }) {
  const [estado, setEstado] = useState<EstadoSesion>({ tipo: 'cargando' })
  const [consulta, setConsulta] = useState(0)

  useEffect(() => {
    let vigente = true
    bff
      .GET('/sesion')
      .then(({ data, response }) => {
        if (!vigente) return
        if (data) setEstado({ tipo: 'autenticada', sesion: data })
        else setEstado(response.status === 401 ? { tipo: 'anonima' } : { tipo: 'error' })
      })
      .catch(() => {
        if (vigente) setEstado({ tipo: 'error' })
      })
    return () => {
      vigente = false
    }
  }, [consulta])

  // Solo una sesión abierta puede vencer: el 401 de la consulta inicial no es un vencimiento.
  useEffect(
    () =>
      alVencerSesion(() =>
        setEstado((actual) => (actual.tipo === 'autenticada' ? { tipo: 'anonima', motivo: 'vencida' } : actual)),
      ),
    [],
  )

  const iniciar = useCallback(async (credenciales: Credenciales): Promise<ResultadoInicio> => {
    try {
      const { data, response } = await bff.POST('/sesion', { body: credenciales })
      if (data) {
        marcarSesionNueva()
        setEstado({ tipo: 'autenticada', sesion: data })
        return 'iniciada'
      }
      return response.status === 401 || response.status === 422 ? 'credenciales' : 'servicio'
    } catch {
      return 'servicio'
    }
  }, [])

  const cerrar = useCallback(async (): Promise<boolean> => {
    try {
      const { response } = await bff.DELETE('/sesion')
      if (!response.ok) return false
      setEstado({ tipo: 'anonima', motivo: 'cerrada' })
      return true
    } catch {
      return false
    }
  }, [])

  const reintentar = useCallback(() => {
    setEstado({ tipo: 'cargando' })
    setConsulta((n) => n + 1)
  }, [])

  const valor = useMemo<ValorSesion>(
    () => ({ estado, iniciar, cerrar, reintentar }),
    [estado, iniciar, cerrar, reintentar],
  )
  return <ContextoSesion value={valor}>{children}</ContextoSesion>
}
