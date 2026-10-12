import { bff } from '../api/cliente'
import { FINALIDADES, PRIMA_ESTANDAR, PRODUCTOS } from './datos'

const finalidadCotizacion = FINALIDADES[0]!

async function otorgarCotizacion() {
  const { data, response } = await bff.POST('/consentimientos', {
    body: {
      finalidad: 'cotizacion',
      entidades_origen: ['ent-andes'],
      version_texto: finalidadCotizacion.version_texto,
    },
  })
  expect(response.status).toBe(201)
  return data!
}

async function cotizarAuto() {
  const { data, response } = await bff.POST('/cotizaciones', { body: { producto: 'auto', datos: {} } })
  expect(response.status).toBe(201)
  return data!
}

describe('mocks del BFF web', () => {
  it('expone la sesión y los catálogos del contrato', async () => {
    expect((await bff.GET('/sesion')).data?.cliente.nombre).toBe('Ana')
    expect((await bff.GET('/consentimientos/finalidades')).data).toHaveLength(3)
    expect((await bff.GET('/cotizaciones/productos')).data).toEqual(PRODUCTOS)
  })

  it('empieza sin consentimientos: ninguna finalidad viene otorgada por defecto', async () => {
    expect((await bff.GET('/consentimientos')).data).toEqual([])
  })

  it('reproduce el guion del sprint: estándar → personalizada → revocado → estándar', async () => {
    const sinConsentimiento = await cotizarAuto()
    expect(sinConsentimiento.tipo_tarifa).toBe('estandar')
    expect(sinConsentimiento.motivo_tarifa).toBe('sin_consentimiento')
    expect(sinConsentimiento.prima_mensual).toEqual({ valor: PRIMA_ESTANDAR.auto, moneda: 'COP' })

    const consentimiento = await otorgarCotizacion()
    expect(consentimiento.estado).toBe('vigente')
    expect(consentimiento.canal).toBe('web')
    expect(new Date(consentimiento.vence_en).getTime()).toBeGreaterThan(Date.now())

    const conConsentimiento = await cotizarAuto()
    expect(conConsentimiento.tipo_tarifa).toBe('personalizada')
    expect(conConsentimiento.motivo_tarifa).toBeNull()
    expect(conConsentimiento.prima_mensual.valor).toBeLessThan(PRIMA_ESTANDAR.auto)

    const revocado = await bff.POST('/consentimientos/{id}/revocacion', {
      params: { path: { id: consentimiento.id } },
    })
    expect(revocado.data?.estado).toBe('revocado')
    expect(revocado.data?.revocado_en).not.toBeNull()

    expect((await cotizarAuto()).tipo_tarifa).toBe('estandar')

    const eventos = await bff.GET('/consentimientos/{id}/eventos', {
      params: { path: { id: consentimiento.id } },
    })
    expect(eventos.data?.map((e) => e.tipo)).toEqual(['otorgado', 'revocado'])
    expect(eventos.data?.every((e) => e.version_texto === finalidadCotizacion.version_texto)).toBe(true)
  })

  it('los factores de la prima explican el precio y sus pesos suman 1', async () => {
    for (const cotizacion of [await cotizarAuto(), (await otorgarCotizacion(), await cotizarAuto())]) {
      const suma = cotizacion.factores.reduce((total, f) => total + f.peso, 0)
      expect(suma).toBeCloseTo(1)
    }
  })

  it('renueva un consentimiento vigente y no uno revocado', async () => {
    const { id } = await otorgarCotizacion()
    const renovado = await bff.POST('/consentimientos/{id}/renovacion', { params: { path: { id } } })
    expect(renovado.response.status).toBe(200)
    expect(renovado.data?.estado).toBe('vigente')

    await bff.POST('/consentimientos/{id}/revocacion', { params: { path: { id } } })
    const otraVez = await bff.POST('/consentimientos/{id}/renovacion', { params: { path: { id } } })
    expect(otraVez.response.status).toBe(409)
    expect(otraVez.error?.codigo).toBe('consentimiento_revocado')
  })

  it('consulta una cotización emitida por su id', async () => {
    const { id } = await cotizarAuto()
    const consulta = await bff.GET('/cotizaciones/{id}', { params: { path: { id } } })
    expect(consulta.data?.id).toBe(id)
    expect(new Date(consulta.data!.vigente_hasta).getTime()).toBeGreaterThan(Date.now())
  })

  describe('errores del contrato', () => {
    it('409 al otorgar dos veces la misma finalidad o revocar algo ya revocado', async () => {
      const { id } = await otorgarCotizacion()
      const duplicado = await bff.POST('/consentimientos', {
        body: { finalidad: 'cotizacion', entidades_origen: ['ent-sur'], version_texto: finalidadCotizacion.version_texto },
      })
      expect(duplicado.error?.codigo).toBe('consentimiento_ya_vigente')

      await bff.POST('/consentimientos/{id}/revocacion', { params: { path: { id } } })
      const otraVez = await bff.POST('/consentimientos/{id}/revocacion', { params: { path: { id } } })
      expect(otraVez.response.status).toBe(409)
    })

    it.each([
      [{ finalidad: 'inexistente', entidades_origen: ['ent-andes'], version_texto: 'x' }, 'finalidad_invalida'],
      [{ finalidad: 'cotizacion', entidades_origen: [], version_texto: finalidadCotizacion.version_texto }, 'entidades_invalidas'],
      [{ finalidad: 'cotizacion', entidades_origen: ['ent-falsa'], version_texto: finalidadCotizacion.version_texto }, 'entidades_invalidas'],
      [{ finalidad: 'cotizacion', entidades_origen: ['ent-andes'], version_texto: 'cotizacion-2020.1' }, 'version_texto_desactualizada'],
    ])('422 ante una solicitud de consentimiento inválida (%#)', async (cuerpo, codigo) => {
      // @ts-expect-error: se envían datos que el contrato no admite, a propósito.
      const { response, error } = await bff.POST('/consentimientos', { body: cuerpo })
      expect(response.status).toBe(422)
      expect(error?.codigo).toBe(codigo)
    })

    it('422 al cotizar un producto inexistente', async () => {
      // @ts-expect-error: producto fuera del contrato, a propósito.
      const { response } = await bff.POST('/cotizaciones', { body: { producto: 'yate', datos: {} } })
      expect(response.status).toBe(422)
    })

    it('404 para recursos que no existen', async () => {
      const opciones = { params: { path: { id: 'no-existe' } } }
      expect((await bff.POST('/consentimientos/{id}/revocacion', opciones)).response.status).toBe(404)
      expect((await bff.POST('/consentimientos/{id}/renovacion', opciones)).response.status).toBe(404)
      expect((await bff.GET('/consentimientos/{id}/eventos', opciones)).response.status).toBe(404)
      expect((await bff.GET('/cotizaciones/{id}', opciones)).response.status).toBe(404)
    })
  })
})
