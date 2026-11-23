import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import type { EstadoSiniestro, Requerimiento, Siniestro } from '../../api/tipos'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`

const CASO: Siniestro = {
  id: 'sin-1',
  referencia: 'SIN-2026-000123',
  numero_poliza: 'POL-2026-000101',
  tipo_evento: 'accidente_vehiculo',
  ocurrido_el: '2026-11-01',
  descripcion: 'Me golpearon el vehículo.',
  monto_estimado: { valor: 8000000, moneda: 'COP' },
  estado: 'requiere_informacion',
  siguiente_paso: 'adjuntar_evidencia',
  canal: 'movil',
  reportado_en: '2026-11-02T15:00:00Z',
  estado_actualizado_en: '2026-11-03T15:00:00Z',
}

const PENDIENTE: Requerimiento = {
  id: 'req-1',
  descripcion: 'Factura de la reparación',
  creado_en: '2026-11-03T15:00:00Z',
  pendiente: true,
  atendido_en: null,
  id_evidencia: null,
}

function sembrar(cambios: Partial<Siniestro> = {}, requerimientos: Requerimiento[] = [PENDIENTE]) {
  estado.siniestros = [{ ...CASO, ...cambios }]
  estado.seguimientos.set(CASO.id, {
    historial: [
      { id: 'e1', estado_anterior: null, estado: 'reportado', ocurrido_en: '2026-11-02T15:00:00Z', motivo: null },
      {
        id: 'e2',
        estado_anterior: 'reportado',
        estado: 'requiere_informacion',
        ocurrido_en: '2026-11-03T15:00:00Z',
        motivo: 'Falta la factura',
      },
    ],
    requerimientos: requerimientos.map((requerimiento) => ({ ...requerimiento })),
  })
}

function cambiarEstado(nuevo: EstadoSiniestro) {
  estado.siniestros[0]!.estado = nuevo
  estado.seguimientos.get(CASO.id)!.historial.push({
    id: 'e3',
    estado_anterior: estado.seguimientos.get(CASO.id)!.historial.at(-1)!.estado,
    estado: nuevo,
    ocurrido_en: '2026-11-04T15:00:00Z',
    motivo: null,
  })
}

function abrir(camino: string) {
  render(
    <MemoryRouter initialEntries={[camino]}>
      <App />
    </MemoryRouter>,
  )
}

const tituloDetalle = () => screen.findByRole('heading', { level: 1, name: 'Accidente de vehículo' })
const filaDelCaso = () => screen.findByRole('row', { name: /SIN-2026-000123/ })
const adjuntar = () => screen.findByLabelText('Archivo para responder: Factura de la reparación')
const factura = () => new File(['%PDF'], 'factura.pdf', { type: 'application/pdf' })
const botonEnviar = () => screen.getByRole('button', { name: 'Enviar respuesta' })

describe('Seguimiento del siniestro (WEB-F06)', () => {
  describe('lista', () => {
    it('muestra referencia, póliza, tipo, canal, estado y monto', async () => {
      sembrar()
      abrir('/siniestros')

      const fila = within(await filaDelCaso())
      expect(fila.getByRole('rowheader', { name: 'SIN-2026-000123' })).toBeInTheDocument()
      expect(fila.getByText('POL-2026-000101')).toBeInTheDocument()
      expect(fila.getByText('Accidente de vehículo')).toBeInTheDocument()
      expect(fila.getByText('App')).toBeInTheDocument()
      expect(fila.getByText('Requiere información')).toBeInTheDocument()
      expect(fila.getByText(/8\.000\.000/)).toBeInTheDocument()
    })

    it.each([
      ['reportado', 'Reportado', 'proceso'],
      ['en_evaluacion', 'En evaluación', 'proceso'],
      ['requiere_informacion', 'Requiere información', 'proceso'],
      ['aprobado', 'Aprobado', 'exito'],
      ['rechazado', 'Rechazado', 'rechazo'],
      ['pagado', 'Pagado', 'exito'],
    ] as const)('el estado %s se muestra con el texto %s', async (estadoCaso, texto, tono) => {
      sembrar({ estado: estadoCaso })
      abrir('/siniestros')
      expect(await screen.findByText(texto)).toHaveAttribute('data-tono', tono)
    })

    it('sin monto dice que no está disponible', async () => {
      sembrar({ monto_estimado: null })
      abrir('/siniestros')
      expect(within(await filaDelCaso()).getByText('No disponible')).toBeInTheDocument()
    })

    it('sin siniestros muestra el estado vacío que lleva a reportar', async () => {
      estado.siniestros = []
      abrir('/siniestros')

      expect(await screen.findByRole('heading', { level: 2, name: 'No tienes siniestros reportados' })).toBeInTheDocument()
      expect(within(screen.getByRole('main')).getByRole('link', { name: 'Reportar siniestro' })).toBeInTheDocument()
    })

    it('si falla la carga, permite reintentar', async () => {
      sembrar()
      servidor.use(http.get(ruta('/siniestros'), () => HttpResponse.error(), { once: true }))
      abrir('/siniestros')

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar tus siniestros.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await filaDelCaso()).toBeInTheDocument()
    })

    it('al actualizar muestra el estado nuevo y lo anuncia', async () => {
      sembrar()
      abrir('/siniestros')
      await filaDelCaso()

      cambiarEstado('en_evaluacion')
      await userEvent.click(screen.getByRole('button', { name: 'Actualizar' }))

      expect(await within(await filaDelCaso()).findByText('En evaluación')).toBeInTheDocument()
      expect(await screen.findByText('Información actualizada.')).toHaveAttribute('role', 'status')
    })

    it('mientras actualiza, el botón indica que está ocupado sin perder el foco', async () => {
      sembrar()
      abrir('/siniestros')
      await filaDelCaso()
      servidor.use(
        http.get(ruta('/siniestros'), async () => {
          await delay(30)
        }),
      )

      await userEvent.click(screen.getByRole('button', { name: 'Actualizar' }))

      const ocupado = screen.getByRole('button', { name: 'Actualizando…' })
      expect(ocupado).toBeEnabled()
      expect(ocupado).toHaveAttribute('aria-disabled', 'true')
      expect(ocupado).toHaveAttribute('aria-busy', 'true')
      expect(await screen.findByRole('button', { name: 'Actualizar' })).toHaveFocus()
    })

    it('si falla al actualizar, conserva la lista y lo explica', async () => {
      sembrar()
      abrir('/siniestros')
      await filaDelCaso()

      servidor.use(http.get(ruta('/siniestros'), () => HttpResponse.error(), { once: true }))
      await userEvent.click(screen.getByRole('button', { name: 'Actualizar' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos actualizar la información.')
      expect(await filaDelCaso()).toBeInTheDocument()
    })

    it('se llega desde la navegación principal y cada caso lleva a su detalle', async () => {
      sembrar()
      abrir('/')
      const navegacion = await screen.findByRole('navigation', { name: 'Navegación principal' })
      await userEvent.click(within(navegacion).getByRole('link', { name: 'Mis siniestros' }))

      await userEvent.click(await screen.findByRole('link', { name: 'Ver el siniestro SIN-2026-000123' }))
      expect(await tituloDetalle()).toBeInTheDocument()
    })
  })

  describe('detalle', () => {
    it('muestra estado, referencia, póliza, resumen y siguiente paso', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      expect(screen.getByText('SIN-2026-000123 · Póliza POL-2026-000101')).toBeInTheDocument()
      expect(screen.getByText('Me golpearon el vehículo.')).toBeInTheDocument()
      expect(screen.getByText('App')).toBeInTheDocument()
      expect(screen.getByText('Último cambio')).toBeInTheDocument()
      expect(screen.getByText('Siguiente paso')).toBeInTheDocument()
    })

    it('un caso cerrado no muestra siguiente paso y uno sin cambios no muestra último cambio', async () => {
      sembrar({ estado: 'pagado', estado_actualizado_en: null }, [])
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      expect(screen.queryByText('Siguiente paso')).not.toBeInTheDocument()
      expect(screen.queryByText('Último cambio')).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { level: 2, name: 'Requerimientos' })).not.toBeInTheDocument()
    })

    it('la línea de tiempo muestra los eventos en orden, con fecha y motivo, y marca el actual', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      const eventos = within(screen.getByRole('list', { name: 'Línea de tiempo' })).getAllByRole('listitem')
      expect(eventos[0]).toHaveTextContent('Reportado')
      expect(eventos[0]).toHaveTextContent(/2026/)
      expect(eventos[1]).toHaveTextContent('Requiere información')
      expect(eventos[1]).toHaveTextContent('Falta la factura')
      expect(eventos[1]).toHaveAttribute('aria-current', 'true')
      expect(eventos[0]).not.toHaveAttribute('aria-current')
    })

    it('las migas muestran la referencia y cómo volver a la lista', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      const migas = within(screen.getByRole('navigation', { name: 'Miga de pan' }))
      expect(migas.getByRole('link', { name: 'Mis siniestros' })).toHaveAttribute('href', '/siniestros')
      expect(migas.getByText('SIN-2026-000123')).toHaveAttribute('aria-current', 'page')
    })

    it('al actualizar muestra el estado nuevo y el evento en la línea de tiempo', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      cambiarEstado('en_evaluacion')
      await userEvent.click(screen.getByRole('button', { name: 'Actualizar' }))

      expect(await screen.findAllByText('En evaluación')).toHaveLength(2)
    })

    it('un siniestro que no existe lo explica y lleva a la lista', async () => {
      sembrar()
      abrir('/siniestros/sin-no-existe')

      expect(await screen.findByRole('alert')).toHaveTextContent('No encontramos este siniestro.')
      expect(screen.getByRole('link', { name: 'Volver a mis siniestros' })).toHaveAttribute('href', '/siniestros')
    })

    it('si falla la carga, permite reintentar', async () => {
      sembrar()
      servidor.use(
        http.get(ruta('/siniestros/:id'), () => HttpResponse.json({ codigo: 'error', mensaje: 'Falla' }, { status: 503 }), {
          once: true,
        }),
      )
      abrir('/siniestros/sin-1')

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar este siniestro.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await tituloDetalle()).toBeInTheDocument()
    })
  })

  describe('requerimientos', () => {
    it('dice qué falta y desde cuándo; sin archivo no se puede enviar', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      expect(screen.getByRole('heading', { level: 3, name: 'Factura de la reparación' })).toBeInTheDocument()
      expect(screen.getByText(/^Pendiente · Pedido el/)).toBeInTheDocument()
      expect(botonEnviar()).toBeDisabled()
    })

    it('responde adjuntando un archivo y queda atendido con su fecha', async () => {
      sembrar()
      abrir('/siniestros/sin-1')

      await userEvent.upload(await adjuntar(), factura())
      expect(screen.getByText(/^factura\.pdf · /)).toBeInTheDocument()
      await userEvent.click(botonEnviar())

      expect(await screen.findByText('factura.pdf: enviamos tu respuesta.')).toBeInTheDocument()
      expect(await screen.findByText(/^Atendido el/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Enviar respuesta' })).not.toBeInTheDocument()
      expect(estado.evidencias.get('sin-1')).toHaveLength(1)
      await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Requerimientos' })).toHaveFocus())
    })

    it('un doble clic envía una sola respuesta y el botón queda ocupado', async () => {
      sembrar()
      let solicitudes = 0
      servidor.events.on('request:start', ({ request }) => {
        if (request.method === 'POST' && request.url.endsWith('/evidencias')) solicitudes += 1
      })
      servidor.use(
        http.post(ruta('/siniestros/:id/evidencias'), async () => {
          await delay(30)
        }),
      )
      abrir('/siniestros/sin-1')
      await userEvent.upload(await adjuntar(), factura())

      await userEvent.dblClick(botonEnviar())

      const ocupado = screen.getByRole('button', { name: 'Enviando…' })
      expect(ocupado).toBeEnabled()
      expect(ocupado).toHaveAttribute('aria-disabled', 'true')
      expect(ocupado).toHaveAttribute('aria-busy', 'true')
      await screen.findByText('factura.pdf: enviamos tu respuesta.')
      expect(solicitudes).toBe(1)
      servidor.events.removeAllListeners()
    })

    it('rechaza un archivo que no se admite antes de enviarlo', async () => {
      sembrar()
      abrir('/siniestros/sin-1')

      const invalido = new File(['x'], 'nota.txt', { type: 'text/plain' })
      await userEvent.upload(await adjuntar(), invalido, { applyAccept: false })

      expect(await screen.findByRole('alert')).toHaveTextContent('nota.txt: este formato no se admite.')
      expect(botonEnviar()).toBeDisabled()
    })

    it('si ya estaba atendido, lo explica y muestra el caso actualizado', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await userEvent.upload(await adjuntar(), factura())

      Object.assign(estado.seguimientos.get(CASO.id)!.requerimientos[0]!, {
        pendiente: false,
        atendido_en: '2026-11-04T15:00:00Z',
        id_evidencia: 'evi-1',
      })
      await userEvent.click(botonEnviar())

      expect(await screen.findByRole('alert')).toHaveTextContent('ya no está pendiente')
      expect(await screen.findByText(/^Atendido el/)).toBeInTheDocument()
    })

    it('si ya no existe, lo explica', async () => {
      sembrar({}, [PENDIENTE, { ...PENDIENTE, id: 'req-2', descripcion: 'Foto del daño' }])
      abrir('/siniestros/sin-1')
      await userEvent.upload(await adjuntar(), factura())

      estado.seguimientos.get(CASO.id)!.requerimientos.shift()
      await userEvent.click(screen.getAllByRole('button', { name: 'Enviar respuesta' })[0]!)

      expect(await screen.findByRole('alert')).toHaveTextContent('ya no existe')
      await waitFor(() => expect(screen.queryByRole('heading', { level: 3, name: 'Factura de la reparación' })).not.toBeInTheDocument())
    })

    it('si el único requerimiento ya no existe, la explicación sigue visible', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await userEvent.upload(await adjuntar(), factura())

      estado.seguimientos.get(CASO.id)!.requerimientos.shift()
      await userEvent.click(botonEnviar())

      expect(await screen.findByRole('alert')).toHaveTextContent('ya no existe')
      await waitFor(() => expect(screen.queryByRole('heading', { level: 3 })).not.toBeInTheDocument())
      expect(screen.getByRole('alert')).toHaveTextContent('ya no existe')
      expect(screen.getByRole('heading', { level: 2, name: 'Requerimientos' })).toBeInTheDocument()
    })

    it('una respuesta enviada mientras se actualiza queda reflejada, aunque la consulta anterior llegue después', async () => {
      sembrar()
      abrir('/siniestros/sin-1')
      await userEvent.upload(await adjuntar(), factura())
      servidor.use(
        http.get(
          ruta('/siniestros/:id'),
          async () => {
            const anterior = structuredClone({ ...estado.siniestros[0]!, ...estado.seguimientos.get(CASO.id)! })
            await delay(80)
            return HttpResponse.json(anterior)
          },
          { once: true },
        ),
      )

      await userEvent.click(screen.getByRole('button', { name: 'Actualizar' }))
      await userEvent.click(botonEnviar())

      expect(await screen.findByText(/^Atendido el/)).toBeInTheDocument()
      await delay(120)
      expect(screen.getByText(/^Atendido el/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Actualizar' })).toHaveAttribute('aria-disabled', 'false')
    })

    it('un requerimiento cerrado sin respuesta lo dice y no permite responder', async () => {
      sembrar({ estado: 'en_evaluacion' }, [{ ...PENDIENTE, pendiente: false }])
      abrir('/siniestros/sin-1')
      await tituloDetalle()

      expect(screen.getByText('Cerrado sin respuesta')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Enviar respuesta' })).not.toBeInTheDocument()
    })
  })

  describe('accesibilidad e idioma', () => {
    const reglas = { rules: { 'color-contrast': { enabled: false } } }
    const violaciones = async () => (await axe.run(document.body, reglas)).violations.map((v) => v.id)

    it('la lista y el detalle no tienen violaciones de accesibilidad', async () => {
      sembrar()
      abrir('/siniestros')
      await filaDelCaso()
      expect(await violaciones()).toEqual([])

      document.body.innerHTML = ''
      abrir('/siniestros/sin-1')
      await tituloDetalle()
      expect(await violaciones()).toEqual([])
    })

    it('funciona en inglés', async () => {
      sembrar()
      abrir('/siniestros')
      await filaDelCaso()
      await userEvent.click(screen.getByRole('button', { name: 'EN' }))

      expect(screen.getByRole('heading', { level: 1, name: 'My claims' })).toBeInTheDocument()
      expect(await screen.findByText('Information required')).toBeInTheDocument()
    })
  })
})
