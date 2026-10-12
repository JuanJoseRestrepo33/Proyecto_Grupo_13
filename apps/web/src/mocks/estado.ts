/** Estado en memoria de los mocks: simula lo que el BFF guardaría del cliente en sesión. */
import type { Consentimiento, Cotizacion, EventoConsentimiento } from '../api/tipos'

interface EstadoMocks {
  consentimientos: Consentimiento[]
  eventos: Map<string, EventoConsentimiento[]>
  cotizaciones: Map<string, Cotizacion>
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
