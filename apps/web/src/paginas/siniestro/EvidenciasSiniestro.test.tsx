import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import type { Siniestro } from '../../api/tipos'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`

/** Siniestro reportado desde la app, con una foto tomada allí. */
const DESDE_LA_APP: Siniestro = {
  id: 'sin-0100',
  referencia: 'SIN-2026-000100',
  numero_poliza: 'POL-2026-000101',
  tipo_evento: 'hurto',
  ocurrido_el: '2026-10-20',
  descripcion: 'Me robaron el celular en el bus.',
  monto_estimado: null,
  estado: 'reportado',
  siguiente_paso: 'adjuntar_evidencia',
  canal: 'movil',
  reportado_en: '2026-10-20T15:00:00Z',
}

function sembrar() {
  estado.siniestros.push(DESDE_LA_APP)
  estado.evidencias.set(DESDE_LA_APP.id, [
    {
      evidencia: {
        id: 'evi-0001',
        tipo_contenido: 'image/jpeg',
        tamano: 2 * 1024 * 1024,
        nombre_original: 'foto-camara.jpg',
        canal: 'movil',
        cargada_en: '2026-10-20T15:05:00Z',
        tomada_en: '2026-10-20T14:58:00Z',
        latitud: 4.6,
        longitud: -74.08,
        lugar: null,
      },
      contenido: new Blob(['jpeg']),
    },
  ])
}

function renderizar(ruta = '/siniestros/evidencias') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

const selectorSiniestro = () => screen.findByLabelText('Siniestro')
const selectorArchivos = () => screen.getByLabelText('Adjuntar fotos, videos o documentos')

describe('Evidencias de un siniestro ya reportado (MOB-F05, criterio 5)', () => {
  beforeEach(sembrar)

  it('muestra los siniestros de los dos canales y la evidencia que se cargó desde la app', async () => {
    renderizar()
    const opciones = within(await selectorSiniestro()).getAllByRole('option')
    expect(opciones.map((o) => o.textContent)).toEqual([
      'Elige un siniestro',
      'SIN-2026-000100 · Hurto · 20 de octubre de 2026 (la app)',
    ])

    await userEvent.selectOptions(await selectorSiniestro(), DESDE_LA_APP.id)

    expect(await screen.findByRole('heading', { level: 2, name: 'Evidencias de SIN-2026-000100' })).toBeInTheDocument()
    const lista = await screen.findByRole('list', { name: 'Evidencias adjuntas' })
    expect(lista).toHaveTextContent(/foto-camara\.jpg \(2\s?MB · desde la app · /)
    expect(within(lista).getByRole('link', { name: 'Descargar foto-camara.jpg' })).toHaveAttribute(
      'href',
      'http://localhost:3000/api/v1/web/siniestros/sin-0100/evidencias/evi-0001',
    )
  })

  it('adjunta evidencia desde la web al mismo siniestro y la muestra junto a la de la app', async () => {
    renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)
    await screen.findByRole('list', { name: 'Evidencias adjuntas' })

    await userEvent.upload(selectorArchivos(), new File(['%PDF'], 'factura.pdf', { type: 'application/pdf' }))

    expect(await screen.findByText('factura.pdf: adjuntada.')).toBeInTheDocument()
    const lista = screen.getByRole('list', { name: 'Evidencias adjuntas' })
    await within(lista).findByText(/factura\.pdf/)
    expect(within(lista).getAllByRole('listitem')).toHaveLength(2)
    expect(lista).toHaveTextContent(/desde la web/)
    expect(estado.evidencias.get(DESDE_LA_APP.id)?.map((e) => e.evidencia.canal)).toEqual(['movil', 'web'])
  })

  it('rechaza antes de enviar un archivo no admitido, sin tocar el siniestro', async () => {
    renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)
    await screen.findByRole('list', { name: 'Evidencias adjuntas' })

    await userEvent
      .setup({ applyAccept: false })
      .upload(selectorArchivos(), new File(['GIF89a'], 'animacion.gif', { type: 'image/gif' }))

    expect(screen.getByRole('alert')).toHaveTextContent('animacion.gif: este formato no se admite.')
    expect(estado.evidencias.get(DESDE_LA_APP.id)).toHaveLength(1)
  })

  it('explica por qué no se adjuntó un archivo que el BFF rechazó', async () => {
    servidor.use(
      http.post(ruta('/siniestros/:id/evidencias'), () =>
        HttpResponse.json({ codigo: 'siniestro_cerrado', mensaje: 'Cerrado' }, { status: 409 }),
      ),
    )
    renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)
    await screen.findByRole('list', { name: 'Evidencias adjuntas' })

    await userEvent.upload(selectorArchivos(), new File(['x'], 'foto.jpg', { type: 'image/jpeg' }))

    expect(await screen.findByText('foto.jpg: no se adjuntó porque el siniestro ya está cerrado.')).toBeInTheDocument()
  })

  it('mientras sube un archivo no deja cambiar de siniestro, y al terminar muestra su resultado', async () => {
    let responder: () => void = () => {}
    const respuesta = new Promise<void>((listo) => {
      responder = listo
    })
    servidor.use(
      http.post(ruta('/siniestros/:id/evidencias'), async () => {
        await respuesta
        return HttpResponse.json({ codigo: 'almacenamiento_no_disponible', mensaje: 'Sin almacén' }, { status: 503 })
      }),
    )
    renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)
    await screen.findByRole('list', { name: 'Evidencias adjuntas' })

    await userEvent.upload(selectorArchivos(), new File(['x'], 'foto.jpg', { type: 'image/jpeg' }))

    expect(await screen.findByText('foto.jpg: adjuntando…')).toBeInTheDocument()
    expect(screen.getByLabelText('Siniestro')).toBeDisabled()

    responder()

    expect(await screen.findByText(/foto\.jpg: no se adjuntó porque el almacén de evidencias no respondió/)).toBeInTheDocument()
    expect(screen.getByLabelText('Siniestro')).toBeEnabled()
  })

  it('sin siniestros lo explica y ofrece reportar uno', async () => {
    estado.siniestros = []
    renderizar()
    expect(await screen.findByText(/Aún no has reportado siniestros/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Reportar un siniestro' })).toHaveAttribute('href', '/siniestros/reportar')
  })

  it('si no cargan los siniestros o las evidencias, permite reintentar', async () => {
    servidor.use(http.get(ruta('/siniestros'), () => HttpResponse.json({}, { status: 503 }), { once: true }))
    servidor.use(http.get(ruta('/siniestros/:id/evidencias'), () => HttpResponse.json({}, { status: 503 }), { once: true }))
    renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)

    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar tus siniestros.')
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar las evidencias de este siniestro.')
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByRole('list', { name: 'Evidencias adjuntas' })).toBeInTheDocument()
  })

  it('está en la navegación principal y no tiene violaciones de accesibilidad detectables con axe', async () => {
    const { container } = renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)
    await screen.findByRole('list', { name: 'Evidencias adjuntas' })
    expect(screen.getByRole('link', { name: 'Evidencias' })).toHaveAttribute('aria-current', 'page')
    const { violations } = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
  })

  it('funciona en inglés', async () => {
    renderizar(`/siniestros/evidencias?siniestro=${DESDE_LA_APP.id}`)
    await screen.findByRole('list', { name: 'Evidencias adjuntas' })
    await userEvent.click(screen.getByRole('button', { name: 'EN' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Evidence for your claims')
    expect(screen.getByRole('list', { name: 'Attached evidence' })).toHaveTextContent(/from the app/)
  })
})
