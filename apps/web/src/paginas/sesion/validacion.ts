export interface CamposIngreso {
  correo: string
  contrasena: string
}

/** Claves de i18n del error de cada campo. */
export type ErroresIngreso = Partial<Record<keyof CamposIngreso, string>>

/** Forma mínima de un correo; si existe o no lo decide el BFF. */
const CORREO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function validarIngreso({ correo, contrasena }: CamposIngreso): ErroresIngreso {
  const errores: ErroresIngreso = {}
  const limpio = correo.trim()
  if (!limpio) errores.correo = 'sesion.errores.correoRequerido'
  else if (!CORREO.test(limpio)) errores.correo = 'sesion.errores.correoInvalido'
  if (!contrasena) errores.contrasena = 'sesion.errores.contrasenaRequerida'
  return errores
}
