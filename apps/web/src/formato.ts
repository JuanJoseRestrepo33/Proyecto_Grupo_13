import type { Dinero } from './api/tipos'
import { IDIOMAS, type Idioma } from './i18n'

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
