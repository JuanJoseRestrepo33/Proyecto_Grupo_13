/** Validación de los datos del asegurado. Devuelve claves de i18n, no textos. */

export interface DatosAseguradoFormulario {
  fechaNacimiento: string
  ciudad: string
  valorAsegurado: string
}

export type ErroresDatos = Partial<Record<keyof DatosAseguradoFormulario, string>>

export const EDAD_MINIMA = 18
export const EDAD_MAXIMA = 100

export function calcularEdad(fechaNacimiento: string, hoy: Date): number | null {
  const [anio, mes, dia] = fechaNacimiento.split('-').map(Number)
  if (!anio || !mes || !dia) return null
  const nacimiento = new Date(anio, mes - 1, dia)
  if (Number.isNaN(nacimiento.getTime()) || nacimiento > hoy) return null
  const cumplioEsteAnio =
    hoy.getMonth() > nacimiento.getMonth() ||
    (hoy.getMonth() === nacimiento.getMonth() && hoy.getDate() >= nacimiento.getDate())
  return hoy.getFullYear() - nacimiento.getFullYear() - (cumplioEsteAnio ? 0 : 1)
}

export function validarDatos(
  datos: DatosAseguradoFormulario,
  leerMonto: (texto: string) => number | null,
  hoy = new Date(),
): ErroresDatos {
  const errores: ErroresDatos = {}

  if (!datos.fechaNacimiento) {
    errores.fechaNacimiento = 'cotizacion.errores.fechaRequerida'
  } else {
    const edad = calcularEdad(datos.fechaNacimiento, hoy)
    if (edad === null) errores.fechaNacimiento = 'cotizacion.errores.fechaInvalida'
    else if (edad < EDAD_MINIMA) errores.fechaNacimiento = 'cotizacion.errores.menorDeEdad'
    else if (edad > EDAD_MAXIMA) errores.fechaNacimiento = 'cotizacion.errores.mayorDeEdad'
  }

  if (!datos.ciudad.trim()) errores.ciudad = 'cotizacion.errores.ciudadRequerida'
  else if (datos.ciudad.length > 80) errores.ciudad = 'cotizacion.errores.ciudadLarga'

  if (datos.valorAsegurado.trim() && leerMonto(datos.valorAsegurado) === null) {
    errores.valorAsegurado = 'cotizacion.errores.valorInvalido'
  }

  return errores
}
