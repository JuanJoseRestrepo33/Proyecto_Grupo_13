import { formatearFecha, formatearMoneda } from './formato'

describe('formato', () => {
  it('formatea montos en pesos según el idioma, sin decimales', () => {
    const prima = { valor: 132900, moneda: 'COP' }
    expect(formatearMoneda(prima, 'es')).toMatch(/132\.900/)
    expect(formatearMoneda(prima, 'en')).toMatch(/132,900/)
  })

  it('formatea fechas según el idioma', () => {
    const fecha = '2026-10-12T15:00:00Z'
    expect(formatearFecha(fecha, 'es')).toMatch(/octubre/)
    expect(formatearFecha(fecha, 'en')).toMatch(/October/)
  })
})
