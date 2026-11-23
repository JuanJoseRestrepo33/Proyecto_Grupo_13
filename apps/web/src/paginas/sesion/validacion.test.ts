import { validarIngreso } from './validacion'

describe('validarIngreso', () => {
  it('acepta un correo y una contraseña, ignorando espacios alrededor del correo', () => {
    expect(validarIngreso({ correo: '  ana@solventa.test ', contrasena: 'x' })).toEqual({})
  })

  it('pide los dos campos', () => {
    expect(validarIngreso({ correo: '   ', contrasena: '' })).toEqual({
      correo: 'sesion.errores.correoRequerido',
      contrasena: 'sesion.errores.contrasenaRequerida',
    })
  })

  it.each(['ana', 'ana@solventa', 'ana @solventa.test', '@solventa.test'])('rechaza el correo %s', (correo) => {
    expect(validarIngreso({ correo, contrasena: 'x' }).correo).toBe('sesion.errores.correoInvalido')
  })
})
