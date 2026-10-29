import { bff } from '../api/cliente'
import type { ErrorVigencia, SolicitudSiniestro } from '../api/tipos'
import { diaRelativo } from '../formato'
import { polizasDePrueba } from './datos'
import { estado } from './estado'

const [VIGENTE, VENCIDA] = polizasDePrueba()

const SOLICITUD: SolicitudSiniestro = {
  numero_poliza: VIGENTE!.numero,
  tipo_evento: 'accidente_vehiculo',
  ocurrido_el: diaRelativo(-2),
  descripcion: 'Choque leve en un parqueadero.',
  monto_estimado: { valor: 1500000, moneda: 'COP' },
}

function reportar(cambios: Partial<SolicitudSiniestro> = {}, clave: string = crypto.randomUUID()) {
  return bff.POST('/siniestros', {
    params: { header: { 'Idempotency-Key': clave } },
    body: { ...SOLICITUD, ...cambios },
  })
}

describe('mocks del BFF web · siniestros (WEB-F05)', () => {
  it('lista las pólizas del cliente, una vigente y una vencida', async () => {
    const { data } = await bff.GET('/polizas')
    expect(data?.map((p) => p.estado)).toEqual(['vigente', 'vencida'])
  })

  it('reporta un siniestro con referencia y siguiente paso, y lo lista', async () => {
    const { data, response } = await reportar()

    expect(response.status).toBe(201)
    expect(data).toMatchObject({ estado: 'reportado', siguiente_paso: 'adjuntar_evidencia', canal: 'web' })
    expect(data?.referencia).toMatch(/^SIN-2026-\d{6}$/)
    expect((await bff.GET('/siniestros')).data).toEqual([data])
    expect((await bff.GET('/siniestros/{id}', { params: { path: { id: data!.id } } })).data).toEqual(data)
  })

  it('con la misma clave y los mismos datos devuelve el mismo siniestro con 200, sin crear otro', async () => {
    const clave = crypto.randomUUID()
    const primero = await reportar({}, clave)
    const [segundo, tercero] = await Promise.all([reportar({}, clave), reportar({}, clave)])

    for (const repetido of [segundo, tercero]) {
      expect(repetido.response.status).toBe(200)
      expect(repetido.data?.referencia).toBe(primero.data?.referencia)
    }
    expect(estado.siniestros).toHaveLength(1)
  })

  it('con la misma clave y otros datos responde 409 clave_idempotencia_en_uso', async () => {
    const clave = crypto.randomUUID()
    await reportar({}, clave)
    const { response, error } = await reportar({ descripcion: 'Otra descripción distinta.' }, clave)
    expect(response.status).toBe(409)
    expect(error?.codigo).toBe('clave_idempotencia_en_uso')
  })

  it('sobre una póliza vencida acepta un evento dentro de su vigencia', async () => {
    const { response } = await reportar({ numero_poliza: VENCIDA!.numero, ocurrido_el: VENCIDA!.fin })
    expect(response.status).toBe(201)
  })

  it('rechaza un evento fuera de la vigencia con las fechas de la póliza, sin registrarlo', async () => {
    const { response, error } = await reportar({ numero_poliza: VENCIDA!.numero, ocurrido_el: diaRelativo(-1) })

    expect(response.status).toBe(409)
    expect(error).toMatchObject<Partial<ErrorVigencia>>({
      codigo: 'evento_fuera_de_vigencia',
      vigente_desde: VENCIDA!.inicio,
      vigente_hasta: VENCIDA!.fin,
    })
    expect(estado.siniestros).toEqual([])
  })

  it.each([
    ['una fecha futura', { ocurrido_el: diaRelativo(1) }, 422, 'fecha_futura'],
    ['una póliza que no es del cliente', { numero_poliza: 'POL-OTRO' }, 404, 'poliza_no_encontrada'],
    ['una descripción corta', { descripcion: 'corta' }, 422, 'solicitud_invalida'],
  ] as const)('rechaza %s', async (_caso, cambios, estadoHttp, codigo) => {
    const { response, error } = await reportar(cambios)
    expect(response.status).toBe(estadoHttp)
    expect(error?.codigo).toBe(codigo)
  })

  it('exige la clave de idempotencia como UUID', async () => {
    const { response, error } = await reportar({}, 'no-es-uuid')
    expect(response.status).toBe(422)
    expect(error?.codigo).toBe('clave_idempotencia_invalida')
  })
})
