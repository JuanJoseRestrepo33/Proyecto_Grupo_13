import { bff } from '../api/cliente'
import { polizasDePrueba } from './datos'
import { estado } from './estado'

async function polizaEmitida() {
  const { data: cotizacion } = await bff.POST('/cotizaciones', {
    body: { producto: 'auto', asegurado: { edad: 35, ciudad: 'Bogotá' }, usar_open_finance: false },
  })
  const { data: suscripcion } = await bff.POST('/suscripciones', { body: { cotizacion_id: cotizacion!.id } })
  const id = suscripcion!.id
  await bff.POST('/suscripciones/{id}/pagos', {
    params: { path: { id }, header: { 'Idempotency-Key': crypto.randomUUID() } },
    body: { medio: 'tarjeta_credito' },
  })
  const { data } = await bff.POST('/suscripciones/{id}/firma', {
    params: { path: { id } },
    body: { acepta_condiciones: true },
  })
  return { cotizacion: cotizacion!, numero: data!.poliza!.numero }
}

const detalle = (numero: string) => bff.GET('/polizas/{numero}', { params: { path: { numero } } })

describe('mocks de pólizas (WEB-F04)', () => {
  it('la póliza emitida queda de primera en la lista', async () => {
    const { numero } = await polizaEmitida()
    const { data } = await bff.GET('/polizas')
    expect(data?.[0]?.numero).toBe(numero)
  })

  it('el detalle de una póliza emitida trae las condiciones de la cotización', async () => {
    const { cotizacion, numero } = await polizaEmitida()
    const { data } = await detalle(numero)
    expect(data).toMatchObject({
      numero,
      coberturas: cotizacion.coberturas,
      deducible: cotizacion.deducible,
      valor_asegurado: cotizacion.valor_asegurado,
      emitida_en: expect.any(String),
    })
  })

  it('el detalle de una póliza de prueba no tiene condiciones ni documento', async () => {
    const [semilla] = polizasDePrueba()
    const { data } = await detalle(semilla!.numero)
    expect(data).toMatchObject({ coberturas: [], deducible: null, valor_asegurado: null, emitida_en: null })
    expect(data?.documento_url).toBeUndefined()
  })

  it('404 poliza_no_encontrada si no existe', async () => {
    const { response, error } = await detalle('POL-NO-EXISTE')
    expect(response.status).toBe(404)
    expect(error?.codigo).toBe('poliza_no_encontrada')
    expect(estado.condicionesPolizas.has('POL-NO-EXISTE')).toBe(false)
  })
})
