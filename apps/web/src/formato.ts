import type { Dinero } from './api/tipos'
import { IDIOMAS, type Idioma } from './i18n'

/** `valor` llega en la unidad mayor de la moneda (pesos para COP), como define el contrato. */
export function formatearMoneda({ valor, moneda }: Dinero, idioma: Idioma): string {
  return new Intl.NumberFormat(IDIOMAS[idioma], {
    style: 'currency',
    currency: moneda,
    maximumFractionDigits: 0,
  }).format(valor)
}

export function formatearFecha(iso: string, idioma: Idioma): string {
  return new Intl.DateTimeFormat(IDIOMAS[idioma], { dateStyle: 'long' }).format(new Date(iso))
}

/** Un día sin hora (`2026-10-29`), tal cual: no pasa por UTC, que lo correría un día en Colombia. */
export function formatearDia(dia: string, idioma: Idioma): string {
  const [anio, mes, fecha] = dia.split('-').map(Number)
  return new Intl.DateTimeFormat(IDIOMAS[idioma], { dateStyle: 'long' }).format(new Date(anio!, mes! - 1, fecha))
}

/** El día de `fecha` en la zona del navegador, como `2026-10-29`. */
export function diaLocal(fecha = new Date()): string {
  const dosDigitos = (n: number) => String(n).padStart(2, '0')
  return `${fecha.getFullYear()}-${dosDigitos(fecha.getMonth() + 1)}-${dosDigitos(fecha.getDate())}`
}

/** El día que está `dias` antes (negativo) o después de hoy, como `2026-10-29`. */
export function diaRelativo(dias: number, hoy = new Date()): string {
  const fecha = new Date(hoy.getFullYear(), hoy.getMonth(), hoy.getDate() + dias)
  return diaLocal(fecha)
}

export function formatearHora(iso: string, idioma: Idioma): string {
  return new Intl.DateTimeFormat(IDIOMAS[idioma], { timeStyle: 'short' }).format(new Date(iso))
}

/** Variación relativa con signo: -0.06 → "−6 %" (según el idioma). Cero sin signo. */
export function formatearVariacion(efecto: number, idioma: Idioma): string {
  return new Intl.NumberFormat(IDIOMAS[idioma], {
    style: 'percent',
    maximumFractionDigits: 1,
    signDisplay: 'exceptZero',
  }).format(efecto)
}

/** Segundos como m:ss, para la vigencia de una oferta. */
export function formatearCuentaRegresiva(segundos: number): string {
  const restantes = Math.max(0, Math.floor(segundos))
  return `${Math.floor(restantes / 60)}:${String(restantes % 60).padStart(2, '0')}`
}

/** Pesos enteros: solo dígitos, o grupos de miles con un único separador ("60.000.000", "60,000,000"). */
const MONTO_ENTERO = /^(?:\d+|\d{1,3}(?:([.,])\d{3})(?:\1\d{3})*)$/

/**
 * Lee un monto escrito por el usuario ("$ 60.000.000", "60000000", "1500000 COP"). `null` si no es
 * un entero de pesos: con decimales ("1500000,50", "250000.75") no se adivina, porque quitar el
 * separador lo multiplicaría por cien.
 */
export function leerMonto(texto: string): number | null {
  const limpio = texto.replace(/[\s$]/g, '').replace(/COP$/i, '')
  return MONTO_ENTERO.test(limpio) ? Number(limpio.replace(/[.,]/g, '')) : null
}

export function formatearFechaHora(iso: string, idioma: Idioma): string {
  return new Intl.DateTimeFormat(IDIOMAS[idioma], { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}
