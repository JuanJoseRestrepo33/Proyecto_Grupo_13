/** Estado en memoria de los mocks: simula lo que el BFF guardaría del cliente en sesión. */
import type {
  Consentimiento,
  Cotizacion,
  EventoConsentimiento,
  MedioPago,
  Pago,
  Sesion,
  SolicitudCotizacion,
  Suscripcion,
} from '../api/tipos'
import { SESION } from './datos'

/** Lo que se guarda; `por_vencer` y `dias_para_vencer` se calculan al responder. */
export type ConsentimientoGuardado = Omit<Consentimiento, 'por_vencer' | 'dias_para_vencer'>

/** Lo que se guarda; `reintento` depende de cada respuesta. */
export type PagoGuardado = Omit<Pago, 'reintento'>

/**
 * Respuesta del proveedor de Open Finance, con los mismos modos que el núcleo
 * (`PERFILAMIENTO_OPEN_FINANCE_MODO`): `degradado` sirve el perfil en caché y `caido` no deja
 * perfil, así que la prima es la estándar (WEB-F01, criterio 3).
 */
export type ModoOpenFinance = 'sano' | 'degradado' | 'caido'

/** Pagos por `Idempotency-Key`: `'procesando'` mientras la pasarela responde. */
export interface IntentoPago {
  suscripcionId: string
  pago: PagoGuardado | 'procesando'
}

interface EstadoMocks {
  /** Cliente de la cookie `sesion`; `null` sin sesión vigente. */
  sesion: Sesion | null
  consentimientos: ConsentimientoGuardado[]
  eventos: Map<string, EventoConsentimiento[]>
  /** Se guarda la solicitud para poder recotizar con los mismos datos. */
  cotizaciones: Map<string, { cotizacion: Cotizacion; solicitud: SolicitudCotizacion }>
  suscripciones: Map<string, Omit<Suscripcion, 'pago'> & { pagoId: string | null }>
  pagos: Map<string, PagoGuardado>
  intentos: Map<string, IntentoPago>
  /** Respuesta del proveedor de Open Finance simulado; las pruebas la cambian. */
  openFinance: ModoOpenFinance
  /** Respuesta de la pasarela simulada por medio de pago; las pruebas la cambian. */
  pasarela: Record<MedioPago, Pago['estado']>
  secuencia: number
}

function nuevo(): EstadoMocks {
  return {
    sesion: SESION,
    consentimientos: [],
    eventos: new Map(),
    cotizaciones: new Map(),
    suscripciones: new Map(),
    pagos: new Map(),
    intentos: new Map(),
    openFinance: 'sano',
    pasarela: { tarjeta_credito: 'aprobado', pse: 'pendiente' },
    secuencia: 0,
  }
}

export const estado: EstadoMocks = nuevo()

/** Vuelve al estado inicial: cliente con sesión, sin consentimientos, cotizaciones ni suscripciones. */
export function reiniciarEstado(): void {
  Object.assign(estado, nuevo())
}

export function siguienteId(prefijo: string): string {
  estado.secuencia += 1
  return `${prefijo}-${String(estado.secuencia).padStart(4, '0')}`
}
