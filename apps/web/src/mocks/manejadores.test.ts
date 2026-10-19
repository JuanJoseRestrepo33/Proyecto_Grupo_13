import { bff } from '../api/cliente'
import type { SolicitudCotizacion } from '../api/tipos'
import { FINALIDADES, PRODUCTOS, VIGENCIA_COTIZACION_MS } from './datos'

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

const PRIMA_AUTO = PRODUCTOS[0]!.prima_estandar.valor

const SOLICITUD_AUTO: SolicitudCotizacion = {
  producto: 'auto',
  asegurado: { edad: 35, ciudad: 'Bogotá', valor_asegurado: 60000000 },
  usar_open_finance: true,
}

async function cotizarAuto(cambios: Partial<SolicitudCotizacion> = {}) {
  const { data, response } = await bff.POST('/cotizaciones', { body: { ...SOLICITUD_AUTO, ...cambios } })
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
    expect(sinConsentimiento.prima_mensual).toEqual({ valor: PRIMA_AUTO, moneda: 'COP' })
    expect(sinConsentimiento.ahorro_mensual.valor).toBe(0)
    expect(sinConsentimiento.base_perfilamiento).toBe('estandar')

    const consentimiento = await otorgarCotizacion()
    expect(consentimiento.estado).toBe('vigente')
    expect(consentimiento.canal).toBe('web')
    expect(new Date(consentimiento.vence_en).getTime()).toBeGreaterThan(Date.now())

    const conConsentimiento = await cotizarAuto()
    expect(conConsentimiento.tipo_tarifa).toBe('personalizada')
    expect(conConsentimiento.motivo_tarifa).toBeNull()
    expect(conConsentimiento.base_perfilamiento).toBe('open_finance')
    expect(conConsentimiento.prima_mensual.valor).toBeLessThan(PRIMA_AUTO)
    expect(conConsentimiento.ahorro_mensual.valor).toBe(PRIMA_AUTO - conConsentimiento.prima_mensual.valor)

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

  it('la prima es la estándar más la suma de los efectos de sus factores', async () => {
    await otorgarCotizacion()
    const cotizacion = await cotizarAuto()
    const efectoTotal = cotizacion.factores.reduce((total, f) => total + f.efecto, 0)
    expect(cotizacion.prima_mensual.valor).toBe(Math.round(PRIMA_AUTO * (1 + efectoTotal)))
    expect(cotizacion.version_regla).toBe('rating-v3.2')
    expect(cotizacion.coberturas.length).toBeGreaterThan(0)
  })

  it('cotiza a tarifa estándar si el cliente pide no usar Open Finance, aun con consentimiento', async () => {
    await otorgarCotizacion()
    const cotizacion = await cotizarAuto({ usar_open_finance: false })
    expect(cotizacion.tipo_tarifa).toBe('estandar')
    expect(cotizacion.motivo_tarifa).toBe('open_finance_desactivado')
  })

  it('la oferta vence a los 5 minutos y se recotiza con los mismos datos', async () => {
    const original = await cotizarAuto()
    const vigencia = new Date(original.vigente_hasta).getTime() - new Date(original.creada_en).getTime()
    expect(vigencia).toBe(VIGENCIA_COTIZACION_MS)

    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      vi.setSystemTime(new Date(original.vigente_hasta))
      const vencida = await bff.GET('/cotizaciones/{id}', { params: { path: { id: original.id } } })
      expect(vencida.data?.estado).toBe('vencida')

      const nueva = await bff.POST('/cotizaciones/{id}/recotizacion', { params: { path: { id: original.id } } })
      expect(nueva.response.status).toBe(201)
      expect(nueva.data?.id).not.toBe(original.id)
      expect(nueva.data?.estado).toBe('vigente')
      expect(nueva.data?.producto).toBe(original.producto)
    } finally {
      vi.useRealTimers()
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

  describe('vencimiento del consentimiento', () => {
    const DIA = 24 * 60 * 60 * 1000

    afterEach(() => vi.useRealTimers())

    it('recién otorgado no está por vencer y dice cuántos días le quedan', async () => {
      const consentimiento = await otorgarCotizacion()
      expect(consentimiento.por_vencer).toBe(false)
      expect(consentimiento.dias_para_vencer).toBeGreaterThan(300)
    })

    it('a 30 días o menos del vencimiento queda por vencer, y al renovarlo deja de estarlo', async () => {
      const { id, vence_en } = await otorgarCotizacion()
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date(vence_en).getTime() - 10 * DIA)

      const [porVencer] = (await bff.GET('/consentimientos')).data!
      expect(porVencer).toMatchObject({ estado: 'vigente', por_vencer: true, dias_para_vencer: 10 })

      const renovado = await bff.POST('/consentimientos/{id}/renovacion', { params: { path: { id } } })
      expect(renovado.data).toMatchObject({ estado: 'vigente', por_vencer: false })
    })

    it('al pasar la fecha queda vencido, con su evento, y se puede renovar', async () => {
      const { id, vence_en } = await otorgarCotizacion()
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date(vence_en).getTime() + DIA)

      const [vencido] = (await bff.GET('/consentimientos')).data!
      expect(vencido).toMatchObject({ estado: 'vencido', por_vencer: false, dias_para_vencer: null })
      expect((await cotizarAuto()).tipo_tarifa).toBe('estandar')

      const eventos = (await bff.GET('/consentimientos/{id}/eventos', { params: { path: { id } } })).data!
      expect(eventos.map((e) => [e.tipo, e.canal])).toEqual([
        ['otorgado', 'web'],
        ['vencido', 'sistema'],
      ])
      expect(eventos[1]!.ocurrido_en).toBe(vence_en)

      const renovado = await bff.POST('/consentimientos/{id}/renovacion', { params: { path: { id } } })
      expect(renovado.data?.estado).toBe('vigente')
    })

    it('no renueva uno vencido si ya hay otro vigente para la misma finalidad', async () => {
      const { id, vence_en } = await otorgarCotizacion()
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date(vence_en).getTime() + DIA)
      await otorgarCotizacion()

      const renovado = await bff.POST('/consentimientos/{id}/renovacion', { params: { path: { id } } })
      expect(renovado.response.status).toBe(409)
      expect(renovado.error?.codigo).toBe('consentimiento_ya_vigente')
    })
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
      const { response } = await bff.POST('/cotizaciones', { body: { ...SOLICITUD_AUTO, producto: 'yate' } })
      expect(response.status).toBe(422)
    })

    it.each([
      { edad: 17, ciudad: 'Bogotá' },
      { edad: 101, ciudad: 'Bogotá' },
      { edad: 30, ciudad: '   ' },
      { edad: 30, ciudad: 'Bogotá', valor_asegurado: -1 },
    ])('422 si los datos del asegurado no son válidos: %o', async (asegurado) => {
      const { response, error } = await bff.POST('/cotizaciones', { body: { ...SOLICITUD_AUTO, asegurado } })
      expect(response.status).toBe(422)
      expect(error?.codigo).toBe('asegurado_invalido')
    })

    it('404 para recursos que no existen', async () => {
      const opciones = { params: { path: { id: 'no-existe' } } }
      expect((await bff.POST('/consentimientos/{id}/revocacion', opciones)).response.status).toBe(404)
      expect((await bff.POST('/consentimientos/{id}/renovacion', opciones)).response.status).toBe(404)
      expect((await bff.GET('/consentimientos/{id}/eventos', opciones)).response.status).toBe(404)
      expect((await bff.GET('/cotizaciones/{id}', opciones)).response.status).toBe(404)
      expect((await bff.POST('/cotizaciones/{id}/recotizacion', opciones)).response.status).toBe(404)
    })
  })
})
