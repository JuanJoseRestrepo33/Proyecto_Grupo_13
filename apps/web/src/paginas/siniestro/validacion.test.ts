import type { PolizaResumen } from '../../api/tipos'
import { leerMonto } from '../../formato'
import { type DatosReporte, validarReporte } from './validacion'

const HOY = '2026-10-29'
const POLIZAS: PolizaResumen[] = [
  { numero: 'POL-1', producto: 'auto', inicio: '2026-01-01', fin: '2026-12-31', estado: 'vigente' },
  { numero: 'POL-2', producto: 'hogar', inicio: '2025-01-01', fin: '2025-12-31', estado: 'vencida' },
]
const VALIDOS: DatosReporte = {
  poliza: 'POL-1',
  tipoEvento: 'hurto',
  ocurridoEl: '2026-10-20',
  descripcion: 'Me robaron el vehículo frente a mi casa.',
  montoEstimado: '',
}
const validar = (cambios: Partial<DatosReporte>) => validarReporte({ ...VALIDOS, ...cambios }, POLIZAS, HOY, leerMonto)

describe('validarReporte', () => {
  it('acepta un reporte completo, con o sin monto estimado', () => {
    expect(validar({})).toEqual({})
    expect(validar({ montoEstimado: '$ 2.500.000' })).toEqual({})
  })

  it('pide póliza, tipo de evento, fecha y descripción', () => {
    expect(validar({ poliza: '', tipoEvento: '', ocurridoEl: '', descripcion: '   ' })).toEqual({
      poliza: 'siniestro.errores.polizaRequerida',
      tipoEvento: 'siniestro.errores.tipoRequerido',
      ocurridoEl: 'siniestro.errores.fechaRequerida',
      descripcion: 'siniestro.errores.descripcionCorta',
    })
  })

  it('rechaza una fecha posterior a hoy y acepta la de hoy', () => {
    expect(validar({ ocurridoEl: '2026-10-30' }).ocurridoEl).toBe('siniestro.errores.fechaFutura')
    expect(validar({ ocurridoEl: HOY })).toEqual({})
  })

  it('en una póliza vencida acepta un evento dentro de su vigencia y rechaza uno posterior', () => {
    expect(validar({ poliza: 'POL-2', ocurridoEl: '2025-12-31' })).toEqual({})
    expect(validar({ poliza: 'POL-2', ocurridoEl: '2026-01-01' }).ocurridoEl).toBe('siniestro.errores.fueraDeVigencia')
    expect(validar({ poliza: 'POL-1', ocurridoEl: '2025-12-31' }).ocurridoEl).toBe('siniestro.errores.fueraDeVigencia')
  })

  it('exige al menos 10 caracteres de descripción sin contar los espacios de los extremos', () => {
    expect(validar({ descripcion: '  123456789  ' }).descripcion).toBe('siniestro.errores.descripcionCorta')
    expect(validar({ descripcion: '1234567890' })).toEqual({})
    expect(validar({ descripcion: 'x'.repeat(2001) }).descripcion).toBe('siniestro.errores.descripcionLarga')
  })

  it('rechaza un monto que no es un número entero de pesos', () => {
    expect(validar({ montoEstimado: 'mucho' }).montoEstimado).toBe('siniestro.errores.montoInvalido')
    expect(validar({ montoEstimado: '-200' }).montoEstimado).toBe('siniestro.errores.montoInvalido')
  })
})
