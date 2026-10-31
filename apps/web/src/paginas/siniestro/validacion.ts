/** Validación del paso de información del reporte. Devuelve claves de i18n, no textos. */
import type { PolizaResumen, TipoEventoSiniestro } from '../../api/tipos'

export interface DatosReporte {
  poliza: string
  tipoEvento: TipoEventoSiniestro | ''
  /** Día del evento, como `2026-10-29`. */
  ocurridoEl: string
  descripcion: string
  /** Opcional; texto como lo escribe el cliente. */
  montoEstimado: string
}

export type ErroresReporte = Partial<Record<keyof DatosReporte, string>>

export const DESCRIPCION_MINIMA = 10
export const DESCRIPCION_MAXIMA = 2000

/** `hoy` es el día local del cliente, como `2026-10-29`: los días se comparan como texto. */
export function validarReporte(
  datos: DatosReporte,
  polizas: PolizaResumen[],
  hoy: string,
  leerMonto: (texto: string) => number | null,
): ErroresReporte {
  const errores: ErroresReporte = {}
  const poliza = polizas.find((p) => p.numero === datos.poliza)

  if (!poliza) errores.poliza = 'siniestro.errores.polizaRequerida'
  if (!datos.tipoEvento) errores.tipoEvento = 'siniestro.errores.tipoRequerido'

  if (!datos.ocurridoEl) errores.ocurridoEl = 'siniestro.errores.fechaRequerida'
  else if (datos.ocurridoEl > hoy) errores.ocurridoEl = 'siniestro.errores.fechaFutura'
  // Una póliza vencida admite el reporte si el evento ocurrió dentro de su vigencia (criterio 3).
  else if (poliza && (datos.ocurridoEl < poliza.inicio || datos.ocurridoEl > poliza.fin)) {
    errores.ocurridoEl = 'siniestro.errores.fueraDeVigencia'
  }

  const descripcion = datos.descripcion.trim().length
  if (descripcion < DESCRIPCION_MINIMA) errores.descripcion = 'siniestro.errores.descripcionCorta'
  else if (descripcion > DESCRIPCION_MAXIMA) errores.descripcion = 'siniestro.errores.descripcionLarga'

  if (datos.montoEstimado.trim() && leerMonto(datos.montoEstimado) === null) {
    errores.montoEstimado = 'siniestro.errores.montoInvalido'
  }
  return errores
}
