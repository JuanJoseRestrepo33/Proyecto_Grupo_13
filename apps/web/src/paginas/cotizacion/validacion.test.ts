import { leerMonto } from '../../formato'
import { calcularEdad, validarDatos } from './validacion'

const hoy = new Date(2026, 9, 18)

describe('calcularEdad', () => {
  it('cuenta los años cumplidos según si ya pasó el cumpleaños', () => {
    expect(calcularEdad('2008-10-18', hoy)).toBe(18)
    expect(calcularEdad('2008-10-19', hoy)).toBe(17)
    expect(calcularEdad('2008-11-01', hoy)).toBe(17)
    expect(calcularEdad('1990-05-14', hoy)).toBe(36)
  })

  it('rechaza fechas incompletas o futuras', () => {
    expect(calcularEdad('', hoy)).toBeNull()
    expect(calcularEdad('2030-01-01', hoy)).toBeNull()
  })
})

describe('validarDatos', () => {
  const validos = { fechaNacimiento: '1990-05-14', ciudad: 'Bogotá', valorAsegurado: '' }

  it('acepta datos completos, con o sin valor asegurado', () => {
    expect(validarDatos(validos, leerMonto, hoy)).toEqual({})
    expect(validarDatos({ ...validos, valorAsegurado: '$ 60.000.000' }, leerMonto, hoy)).toEqual({})
  })

  it.each([
    [{ fechaNacimiento: '' }, 'fechaNacimiento', 'cotizacion.errores.fechaRequerida'],
    [{ fechaNacimiento: '2030-01-01' }, 'fechaNacimiento', 'cotizacion.errores.fechaInvalida'],
    [{ fechaNacimiento: '2010-01-01' }, 'fechaNacimiento', 'cotizacion.errores.menorDeEdad'],
    [{ fechaNacimiento: '1920-01-01' }, 'fechaNacimiento', 'cotizacion.errores.mayorDeEdad'],
    [{ ciudad: '  ' }, 'ciudad', 'cotizacion.errores.ciudadRequerida'],
    [{ ciudad: 'x'.repeat(81) }, 'ciudad', 'cotizacion.errores.ciudadLarga'],
    [{ valorAsegurado: '60 millones' }, 'valorAsegurado', 'cotizacion.errores.valorInvalido'],
  ])('%o → %s: %s', (cambios, campo, clave) => {
    expect(validarDatos({ ...validos, ...cambios }, leerMonto, hoy)).toEqual({ [campo]: clave })
  })
})
