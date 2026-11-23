import {
  formatearCuentaRegresiva,
  formatearFecha,
  formatearHora,
  formatearMoneda,
  formatearVariacion,
  leerMonto,
} from './formato'

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

  it('formatea variaciones con signo y sin signo para cero', () => {
    expect(formatearVariacion(-0.06, 'es')).toMatch(/-6\s?%/)
    expect(formatearVariacion(0.01, 'en')).toBe('+1%')
    expect(formatearVariacion(0, 'en')).toBe('0%')
  })

  it('formatea la cuenta regresiva como m:ss y nunca negativa', () => {
    expect(formatearCuentaRegresiva(300)).toBe('5:00')
    expect(formatearCuentaRegresiva(61)).toBe('1:01')
    expect(formatearCuentaRegresiva(-5)).toBe('0:00')
  })

  it('formatea la hora según el idioma', () => {
    expect(formatearHora('2026-10-12T15:30:00', 'en')).toMatch(/3:30/)
  })

  it('lee montos escritos con símbolos y separadores', () => {
    expect(leerMonto('$ 60.000.000')).toBe(60000000)
    expect(leerMonto('60,000,000')).toBe(60000000)
    expect(leerMonto('1500000 COP')).toBe(1500000)
    expect(leerMonto('1.500')).toBe(1500)
    expect(leerMonto('sesenta')).toBeNull()
    expect(leerMonto('')).toBeNull()
  })

  it.each(['1500000,50', '250000.75', '1.500,50', '1,500.50', '1.000,000', '1.5', '60.000,000', '.500', '1..000'])(
    'no lee %s: con decimales o separadores mal puestos no es un entero de pesos',
    (texto) => {
      expect(leerMonto(texto)).toBeNull()
    },
  )
})
