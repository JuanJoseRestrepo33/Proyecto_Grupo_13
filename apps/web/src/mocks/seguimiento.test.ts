import { bff } from '../api/cliente'
import type { Siniestro } from '../api/tipos'
import { estado } from './estado'

const SINIESTRO: Siniestro = {
  id: 'sin-1',
  referencia: 'SIN-2026-000123',
  numero_poliza: 'POL-2026-000101',
  tipo_evento: 'hurto',
  ocurrido_el: '2026-11-01',
  descripcion: 'Me robaron el vehículo.',
  monto_estimado: null,
  estado: 'requiere_informacion',
  siguiente_paso: 'adjuntar_evidencia',
  canal: 'web',
  reportado_en: '2026-11-02T10:00:00Z',
}

const detalle = (id: string) => bff.GET('/siniestros/{id}', { params: { path: { id } } })

function responder(id: string, idRequerimiento: string) {
  const formulario = new FormData()
  formulario.append('archivo', new File(['%PDF'], 'factura.pdf', { type: 'application/pdf' }))
  formulario.append('id_requerimiento', idRequerimiento)
  return fetch(`http://localhost/api/v1/web/siniestros/${id}/evidencias`, { method: 'POST', body: formulario })
}

beforeEach(() => {
  estado.siniestros = [SINIESTRO]
  estado.seguimientos.set(SINIESTRO.id, {
    historial: [],
    requerimientos: [
      { id: 'req-1', descripcion: 'Factura', creado_en: '2026-11-03T10:00:00Z', pendiente: true, atendido_en: null, id_evidencia: null },
    ],
  })
})

describe('mocks del seguimiento (WEB-F06)', () => {
  it('un siniestro sin cambios tiene solo el evento del reporte', async () => {
    estado.seguimientos.clear()
    const { data } = await detalle(SINIESTRO.id)
    expect(data?.historial).toEqual([
      expect.objectContaining({ estado_anterior: null, estado: 'reportado', ocurrido_en: SINIESTRO.reportado_en }),
    ])
    expect(data?.requerimientos).toEqual([])
  })

  it('responder un requerimiento lo deja atendido con su evidencia', async () => {
    const respuesta = await responder(SINIESTRO.id, 'req-1')
    expect(respuesta.status).toBe(201)
    const { data } = await detalle(SINIESTRO.id)
    expect(data?.requerimientos[0]).toMatchObject({ pendiente: false, atendido_en: expect.any(String), id_evidencia: expect.any(String) })
  })

  it('un requerimiento ya atendido responde 409 y uno que no existe, 404', async () => {
    await responder(SINIESTRO.id, 'req-1')
    const repetida = await responder(SINIESTRO.id, 'req-1')
    expect(repetida.status).toBe(409)
    expect(await repetida.json()).toMatchObject({ codigo: 'requerimiento_ya_atendido' })

    const inexistente = await responder(SINIESTRO.id, 'req-no-existe')
    expect(inexistente.status).toBe(404)
    expect(await inexistente.json()).toMatchObject({ codigo: 'requerimiento_no_encontrado' })
  })
})
