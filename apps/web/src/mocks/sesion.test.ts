import { alVencerSesion, bff } from '../api/cliente'
import { CREDENCIALES_PRUEBA, SESION } from './datos'
import { estado } from './estado'

describe('mocks del BFF web · sesión (WEB-F13)', () => {
  beforeEach(() => {
    estado.sesion = null
  })

  it('inicia sesión con la cuenta de prueba y desde ahí responde las rutas protegidas', async () => {
    expect((await bff.GET('/cotizaciones/productos')).response.status).toBe(401)

    const { data, response } = await bff.POST('/sesion', { body: CREDENCIALES_PRUEBA })

    expect(response.status).toBe(200)
    expect(data).toEqual(SESION)
    expect((await bff.GET('/sesion')).data).toEqual(SESION)
    expect((await bff.GET('/cotizaciones/productos')).response.status).toBe(200)
  })

  it('acepta el correo sin importar mayúsculas ni espacios', async () => {
    const correo = `  ${CREDENCIALES_PRUEBA.correo.toUpperCase()} `
    const { response } = await bff.POST('/sesion', { body: { ...CREDENCIALES_PRUEBA, correo } })
    expect(response.status).toBe(200)
  })

  it('responde lo mismo a un correo inexistente y a una contraseña equivocada', async () => {
    const correoInexistente = await bff.POST('/sesion', {
      body: { correo: 'nadie@solventa.test', contrasena: CREDENCIALES_PRUEBA.contrasena },
    })
    const contrasenaEquivocada = await bff.POST('/sesion', {
      body: { correo: CREDENCIALES_PRUEBA.correo, contrasena: 'otra' },
    })

    for (const { response, error } of [correoInexistente, contrasenaEquivocada]) {
      expect(response.status).toBe(401)
      expect(error?.codigo).toBe('credenciales_invalidas')
    }
    expect(correoInexistente.error).toEqual(contrasenaEquivocada.error)
    expect(estado.sesion).toBeNull()
  })

  it('rechaza una solicitud sin correo o sin contraseña con 422 solicitud_invalida', async () => {
    const { response, error } = await bff.POST('/sesion', {
      body: { correo: CREDENCIALES_PRUEBA.correo } as typeof CREDENCIALES_PRUEBA,
    })
    expect(response.status).toBe(422)
    expect(error?.codigo).toBe('solicitud_invalida')
  })

  it('sin sesión, toda ruta protegida responde 401 sesion_no_vigente', async () => {
    const { response, error } = await bff.GET('/consentimientos')
    expect(response.status).toBe(401)
    expect(error?.codigo).toBe('sesion_no_vigente')
  })

  it('cierra la sesión con 204, también cuando ya no había una', async () => {
    estado.sesion = SESION
    expect((await bff.DELETE('/sesion')).response.status).toBe(204)
    expect(estado.sesion).toBeNull()
    expect((await bff.DELETE('/sesion')).response.status).toBe(204)
  })
})

describe('cliente del BFF · aviso de sesión vencida', () => {
  it('avisa ante un 401 sesion_no_vigente y no ante credenciales inválidas', async () => {
    const escucha = vi.fn()
    const cancelar = alVencerSesion(escucha)
    estado.sesion = null

    await bff.POST('/sesion', { body: { correo: 'nadie@solventa.test', contrasena: 'x' } })
    expect(escucha).not.toHaveBeenCalled()

    const { error } = await bff.GET('/consentimientos')
    expect(escucha).toHaveBeenCalledTimes(1)
    // La pantalla que hizo la llamada sigue recibiendo el cuerpo del error.
    expect(error?.codigo).toBe('sesion_no_vigente')

    cancelar()
    await bff.GET('/consentimientos')
    expect(escucha).toHaveBeenCalledTimes(1)
  })
})
