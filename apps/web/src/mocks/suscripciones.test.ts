import { bff } from '../api/cliente'
import type { MedioPago } from '../api/tipos'
import { estado } from './estado'

async function cotizacionVigente() {
  const { data } = await bff.POST('/cotizaciones', {
    body: { producto: 'auto', asegurado: { edad: 35, ciudad: 'Bogotá' }, usar_open_finance: false },
  })
  return data!
}

async function suscripcionAceptada() {
  const cotizacion = await cotizacionVigente()
  const { data, response } = await bff.POST('/suscripciones', { body: { cotizacion_id: cotizacion.id } })
  expect(response.status).toBe(201)
  return data!
}

function pagar(id: string, clave: string, medio: MedioPago = 'tarjeta_credito') {
  return bff.POST('/suscripciones/{id}/pagos', {
    params: { path: { id }, header: { 'Idempotency-Key': clave } },
    body: { medio },
  })
}

describe('mocks del checkout (WEB-F03)', () => {
  describe('aceptar la oferta', () => {
    it('crea la suscripción aceptada con las condiciones de la cotización y sin pago', async () => {
      const cotizacion = await cotizacionVigente()
      const { data } = await bff.POST('/suscripciones', { body: { cotizacion_id: cotizacion.id } })
      expect(data).toMatchObject({
        cotizacion_id: cotizacion.id,
        nombre_producto: cotizacion.nombre_producto,
        prima_mensual: cotizacion.prima_mensual,
        estado: 'aceptada',
        pago: null,
      })
    })

    it('aceptar dos veces la misma cotización devuelve la misma suscripción con 200', async () => {
      const primera = await suscripcionAceptada()
      const { data, response } = await bff.POST('/suscripciones', { body: { cotizacion_id: primera.cotizacion_id } })
      expect(response.status).toBe(200)
      expect(data?.id).toBe(primera.id)
      expect(estado.suscripciones.size).toBe(1)
    })

    it('rechaza una cotización vencida con cotizacion_vencida', async () => {
      const cotizacion = await cotizacionVigente()
      vi.useFakeTimers({ toFake: ['Date'] })
      try {
        vi.setSystemTime(new Date(cotizacion.vigente_hasta))
        const { response, error } = await bff.POST('/suscripciones', { body: { cotizacion_id: cotizacion.id } })
        expect(response.status).toBe(409)
        expect(error?.codigo).toBe('cotizacion_vencida')
      } finally {
        vi.useRealTimers()
      }
    })

    it('404 si la cotización no existe', async () => {
      const { response } = await bff.POST('/suscripciones', { body: { cotizacion_id: 'cot-no-existe' } })
      expect(response.status).toBe(404)
    })
  })

  describe('pagar sin cobro doble (P18)', () => {
    it('un pago aprobado deja la suscripción pagada', async () => {
      const suscripcion = await suscripcionAceptada()
      const { data, response } = await pagar(suscripcion.id, crypto.randomUUID())
      expect(response.status).toBe(201)
      expect(data).toMatchObject({ estado: 'aprobado', monto: suscripcion.prima_mensual, reintento: false })
      const actual = await bff.GET('/suscripciones/{id}', { params: { path: { id: suscripcion.id } } })
      expect(actual.data).toMatchObject({ estado: 'pagada', pago: { id: data!.id } })
    })

    it('repetir con la misma clave devuelve el mismo pago con reintento y no cobra otra vez', async () => {
      const suscripcion = await suscripcionAceptada()
      const clave = crypto.randomUUID()
      const primero = await pagar(suscripcion.id, clave)
      const segundo = await pagar(suscripcion.id, clave)
      expect(segundo.response.status).toBe(200)
      expect(segundo.data).toEqual({ ...primero.data, reintento: true })
      expect(estado.pagos.size).toBe(1)
    })

    it('la misma clave mientras el primer intento sigue en curso responde pago_en_curso', async () => {
      const suscripcion = await suscripcionAceptada()
      const clave = crypto.randomUUID()
      const [primero, segundo] = await Promise.all([pagar(suscripcion.id, clave), pagar(suscripcion.id, clave)])
      expect(primero.response.status).toBe(201)
      expect(segundo.response.status).toBe(409)
      expect(segundo.error?.codigo).toBe('pago_en_curso')
      expect(estado.pagos.size).toBe(1)
    })

    it.each(['aprobado', 'pendiente'] as const)(
      'con un pago %s, un intento con otra clave responde pago_ya_registrado',
      async (resultado) => {
        estado.pasarela.tarjeta_credito = resultado
        const suscripcion = await suscripcionAceptada()
        await pagar(suscripcion.id, crypto.randomUUID())
        const otro = await pagar(suscripcion.id, crypto.randomUUID())
        expect(otro.response.status).toBe(409)
        expect(otro.error?.codigo).toBe('pago_ya_registrado')
        expect(estado.pagos.size).toBe(1)
      },
    )

    it('tras un rechazo, un intento nuevo con otra clave sí procede', async () => {
      estado.pasarela.tarjeta_credito = 'rechazado'
      const suscripcion = await suscripcionAceptada()
      const rechazado = await pagar(suscripcion.id, crypto.randomUUID())
      expect(rechazado.data?.estado).toBe('rechazado')

      estado.pasarela.tarjeta_credito = 'aprobado'
      const aprobado = await pagar(suscripcion.id, crypto.randomUUID())
      expect(aprobado.response.status).toBe(201)
      expect(aprobado.data?.estado).toBe('aprobado')
    })

    it('un pago pendiente (PSE) queda aprobado cuando el banco confirma al consultarlo', async () => {
      const suscripcion = await suscripcionAceptada()
      const { data } = await pagar(suscripcion.id, crypto.randomUUID(), 'pse')
      expect(data?.estado).toBe('pendiente')
      const consulta = await bff.GET('/suscripciones/{id}/pagos/{id_pago}', {
        params: { path: { id: suscripcion.id, id_pago: data!.id } },
      })
      expect(consulta.data?.estado).toBe('aprobado')
      const actual = await bff.GET('/suscripciones/{id}', { params: { path: { id: suscripcion.id } } })
      expect(actual.data?.estado).toBe('pagada')
    })

    it.each([
      ['sin clave válida', 'no-es-un-uuid', 'tarjeta_credito', 'clave_idempotencia_invalida'],
      ['con un medio no admitido', crypto.randomUUID(), 'efectivo', 'medio_invalido'],
    ])('422 %s', async (_caso, clave, medio, codigo) => {
      const suscripcion = await suscripcionAceptada()
      const { response, error } = await pagar(suscripcion.id, clave, medio as MedioPago)
      expect(response.status).toBe(422)
      expect(error?.codigo).toBe(codigo)
    })

    it('una clave de otra suscripción no se reutiliza', async () => {
      const primera = await suscripcionAceptada()
      const segunda = await suscripcionAceptada()
      const clave = crypto.randomUUID()
      await pagar(primera.id, clave)
      const { response } = await pagar(segunda.id, clave)
      expect(response.status).toBe(422)
    })

    it('404 para suscripciones y pagos que no existen', async () => {
      expect((await pagar('sus-no-existe', crypto.randomUUID())).response.status).toBe(404)
      const opciones = { params: { path: { id: 'sus-no-existe' } } }
      expect((await bff.GET('/suscripciones/{id}', opciones)).response.status).toBe(404)
      const suscripcion = await suscripcionAceptada()
      const pago = await bff.GET('/suscripciones/{id}/pagos/{id_pago}', {
        params: { path: { id: suscripcion.id, id_pago: 'pag-no-existe' } },
      })
      expect(pago.response.status).toBe(404)
    })
  })
})
