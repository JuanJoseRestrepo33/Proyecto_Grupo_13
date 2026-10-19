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

/** Lee un monto escrito por el usuario ("$ 60.000.000", "60000000"). `null` si no es un entero. */
export function leerMonto(texto: string): number | null {
  const digitos = texto.replace(/[\s$.,]/g, '').replace(/COP$/i, '')
  return /^\d+$/.test(digitos) ? Number(digitos) : null
}
