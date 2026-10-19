/** Estado en memoria de los mocks: simula lo que el BFF guardaría del cliente en sesión. */
import type { Consentimiento, Cotizacion, EventoConsentimiento, SolicitudCotizacion } from '../api/tipos'

/** Lo que se guarda; `por_vencer` y `dias_para_vencer` se calculan al responder. */
export type ConsentimientoGuardado = Omit<Consentimiento, 'por_vencer' | 'dias_para_vencer'>

interface EstadoMocks {
  consentimientos: ConsentimientoGuardado[]
  eventos: Map<string, EventoConsentimiento[]>
  /** Se guarda la solicitud para poder recotizar con los mismos datos. */
  cotizaciones: Map<string, { cotizacion: Cotizacion; solicitud: SolicitudCotizacion }>
  secuencia: number
}

function nuevo(): EstadoMocks {
  return { consentimientos: [], eventos: new Map(), cotizaciones: new Map(), secuencia: 0 }
}

export const estado: EstadoMocks = nuevo()

/** Vuelve al estado inicial: cliente sin consentimientos ni cotizaciones. */
export function reiniciarEstado(): void {
  Object.assign(estado, nuevo())
}

export function siguienteId(prefijo: string): string {
  estado.secuencia += 1
  return `${prefijo}-${String(estado.secuencia).padStart(4, '0')}`
}
