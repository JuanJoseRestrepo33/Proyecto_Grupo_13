import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import { bff } from '../../api/cliente'
import type { PolizaResumen } from '../../api/tipos'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`

const AUTO: PolizaResumen = {
  numero: 'POL-2026-000001',
  producto: 'auto',
  inicio: '2026-11-09',
  fin: '2027-11-08',
  estado: 'vigente',
  nombre_producto: 'Seguro de Auto — Plan Full',
  prima_mensual: { valor: 148000, moneda: 'COP' },
  documento_url: '/api/v1/web/polizas/POL-2026-000001/documento',
}

const HOGAR_SIN_DATOS: PolizaResumen = {
  numero: 'POL-2026-000102',
  producto: 'hogar',
  inicio: '2026-01-01',
  fin: '2026-06-30',
  estado: 'vencida',
  nombre_producto: null,
  prima_mensual: null,
  documento_url: null,
}

function sembrar() {
  estado.polizas = [AUTO, HOGAR_SIN_DATOS]
  estado.condicionesPolizas.set(AUTO.numero, {
    coberturas: [{ nombre: 'Responsabilidad civil', limite: '$1.200.000.000 COP' }],
    deducible: '5% del valor asegurado',
    valor_asegurado: '$60.000.000 COP',
    emitida_en: '2026-11-09T15:00:00Z',
  })
}

function abrir(camino: string) {
  render(
    <MemoryRouter initialEntries={[camino]}>
      <App />
    </MemoryRouter>,
  )
}

const tituloLista = () => screen.findByRole('heading', { level: 1, name: 'Mis pólizas' })
const filas = async () => within(await screen.findByRole('tabpanel')).getAllByRole('row').slice(1)
const pestana = (nombre: string) => screen.findByRole('tab', { name: nombre })

async function emitirPoliza() {
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
  return data!.poliza!.numero
}

describe('Mis pólizas (WEB-F04)', () => {
  describe('lista', () => {
    it('muestra número, producto, estado, prima y vigencia de cada póliza', async () => {
      sembrar()
      abrir('/polizas')
      await tituloLista()

      const [auto] = await filas()
      expect(within(auto!).getByRole('rowheader', { name: 'POL-2026-000001' })).toBeInTheDocument()
      expect(within(auto!).getByText('Seguro de Auto — Plan Full')).toBeInTheDocument()
      expect(within(auto!).getByText('Vigente')).toBeInTheDocument()
      expect(within(auto!).getByText(/\$\s?148\.000\/mes/)).toBeInTheDocument()
      expect(within(auto!).getByText('9 de noviembre de 2026 – 8 de noviembre de 2027')).toBeInTheDocument()
    })

    it.each([
      ['vigente', 'Vigente'],
      ['vencida', 'Vencida'],
      ['por_iniciar', 'Por iniciar'],
    ] as const)('el estado %s se muestra con el texto %s', async (estadoPoliza, texto) => {
      estado.polizas = [{ ...AUTO, estado: estadoPoliza }]
      abrir('/polizas')
      await userEvent.click(await pestana('Ver todas'))
      expect(await screen.findByText(texto)).toHaveAttribute('data-estado', estadoPoliza)
    })

    it('sin nombre comercial usa el del producto y sin prima dice que no está disponible', async () => {
      sembrar()
      abrir('/polizas')
      await userEvent.click(await pestana('Vencidas'))

      const [hogar] = await filas()
      expect(within(hogar!).getByText('Seguro de Hogar')).toBeInTheDocument()
      expect(within(hogar!).getByText('No disponible')).toBeInTheDocument()
    })

    describe('pestañas', () => {
      const POR_INICIAR: PolizaResumen = { ...AUTO, numero: 'POL-2026-000103', estado: 'por_iniciar' }
      const numeros = async () => (await filas()).map((fila) => within(fila).getByRole('rowheader').textContent)

      it('Activas es la inicial y cada pestaña muestra solo lo suyo', async () => {
        estado.polizas = [AUTO, HOGAR_SIN_DATOS, POR_INICIAR]
        abrir('/polizas')

        expect(await pestana('Activas')).toHaveAttribute('aria-selected', 'true')
        expect(await numeros()).toEqual(['POL-2026-000001'])

        await userEvent.click(await pestana('Vencidas'))
        expect(await numeros()).toEqual(['POL-2026-000102'])

        await userEvent.click(await pestana('Ver todas'))
        expect(await numeros()).toEqual(['POL-2026-000001', 'POL-2026-000102', 'POL-2026-000103'])
      })

      it('una pestaña sin pólizas lo dice', async () => {
        estado.polizas = [AUTO]
        abrir('/polizas')
        await userEvent.click(await pestana('Vencidas'))

        expect(screen.getByText('No tienes pólizas en esta pestaña.')).toBeInTheDocument()
      })

      it('se cambia de pestaña con las flechas, Inicio y Fin, y el foco la acompaña', async () => {
        sembrar()
        abrir('/polizas')
        const activas = await pestana('Activas')
        const vencidas = await pestana('Vencidas')
        const todas = await pestana('Ver todas')

        activas.focus()
        await userEvent.keyboard('{ArrowRight}')
        expect(vencidas).toHaveFocus()
        expect(vencidas).toHaveAttribute('aria-selected', 'true')
        expect(activas).toHaveAttribute('tabindex', '-1')

        await userEvent.keyboard('{End}')
        expect(todas).toHaveFocus()
        await userEvent.keyboard('{ArrowRight}')
        expect(activas).toHaveFocus()
        await userEvent.keyboard('{ArrowLeft}')
        expect(todas).toHaveFocus()
        await userEvent.keyboard('{Home}')
        expect(activas).toHaveFocus()
      })
    })

    it('una póliza recién emitida aparece de primera en Activas', async () => {
      const numero = await emitirPoliza()
      abrir('/polizas')
      await tituloLista()

      const [primera] = await filas()
      expect(within(primera!).getByRole('rowheader', { name: numero })).toBeInTheDocument()
    })

    it('sin pólizas muestra el estado vacío que lleva a cotizar', async () => {
      estado.polizas = []
      abrir('/polizas')

      expect(await screen.findByRole('heading', { level: 2, name: 'Aún no tienes pólizas' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Cotizar un seguro' })).toHaveAttribute('href', '/cotizacion')
    })

    it('si falla la carga, permite reintentar', async () => {
      sembrar()
      servidor.use(http.get(ruta('/polizas'), () => HttpResponse.error(), { once: true }))
      abrir('/polizas')

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar tus pólizas.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findByRole('rowheader', { name: 'POL-2026-000001' })).toBeInTheDocument()
    })

    it('se llega desde la navegación principal y cada póliza lleva a su detalle', async () => {
      sembrar()
      abrir('/')
      const navegacion = await screen.findByRole('navigation', { name: 'Navegación principal' })
      await userEvent.click(within(navegacion).getByRole('link', { name: 'Mis pólizas' }))
      await tituloLista()

      await userEvent.click(await screen.findByRole('link', { name: 'Ver detalle de la póliza POL-2026-000001' }))
      expect(await screen.findByRole('heading', { level: 1, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
    })
  })

  describe('detalle', () => {
    it('muestra datos, coberturas, deducible y valor asegurado', async () => {
      sembrar()
      abrir('/polizas/POL-2026-000001')

      expect(await screen.findByRole('heading', { level: 1, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
      expect(screen.getByText('Vigente')).toBeInTheDocument()
      const fila = screen.getByRole('row', { name: /Responsabilidad civil/ })
      expect(within(fila).getByRole('cell', { name: '$1.200.000.000 COP' })).toBeInTheDocument()
      expect(screen.getByText('5% del valor asegurado')).toBeInTheDocument()
      expect(screen.getByText('$60.000.000 COP')).toBeInTheDocument()
      expect(screen.getByText('Emitida el')).toBeInTheDocument()
    })

    it('las migas muestran el nombre de la póliza y cómo volver a la lista', async () => {
      sembrar()
      abrir('/polizas/POL-2026-000001')
      await screen.findByRole('heading', { level: 1, name: 'Seguro de Auto — Plan Full' })

      const migas = within(screen.getByRole('navigation', { name: 'Miga de pan' }))
      expect(migas.getByRole('link', { name: 'Mis pólizas' })).toHaveAttribute('href', '/polizas')
      expect(migas.getByText('Seguro de Auto — Plan Full')).toHaveAttribute('aria-current', 'page')
    })

    it('ofrece descargar el documento de esa póliza', async () => {
      sembrar()
      abrir('/polizas/POL-2026-000001')

      const enlace = await screen.findByRole('link', { name: 'Descargar PDF' })
      expect(enlace).toHaveAttribute('href', expect.stringMatching(/\/api\/v1\/web\/polizas\/POL-2026-000001\/documento$/))
      expect(enlace).toHaveAttribute('download')
    })

    it('sin documento no ofrece la descarga y explica que no está disponible', async () => {
      sembrar()
      abrir('/polizas/POL-2026-000102')

      expect(await screen.findByText('El documento de esta póliza no está disponible.')).toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Descargar PDF' })).not.toBeInTheDocument()
    })

    it('dice lo que falta en lugar de dejar huecos', async () => {
      sembrar()
      abrir('/polizas/POL-2026-000102')

      expect(await screen.findByRole('heading', { level: 1, name: 'Seguro de Hogar' })).toBeInTheDocument()
      expect(screen.getAllByText('No disponible')).toHaveLength(4)
      expect(screen.queryByText('Emitida el')).not.toBeInTheDocument()
    })

    it('una póliza que no existe lo explica y lleva a la lista', async () => {
      sembrar()
      abrir('/polizas/POL-NO-EXISTE')

      expect(await screen.findByRole('alert')).toHaveTextContent('No encontramos esta póliza.')
      expect(screen.getByRole('link', { name: 'Volver a mis pólizas' })).toHaveAttribute('href', '/polizas')
    })

    it('si falla la carga, permite reintentar', async () => {
      sembrar()
      servidor.use(
        http.get(ruta('/polizas/:numero'), () => HttpResponse.json({ codigo: 'error', mensaje: 'Falla' }, { status: 503 }), {
          once: true,
        }),
      )
      abrir('/polizas/POL-2026-000001')

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar esta póliza.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findByRole('heading', { level: 1, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
    })
  })

  describe('accesibilidad e idioma', () => {
    const reglas = { rules: { 'color-contrast': { enabled: false } } }
    const violaciones = async () => (await axe.run(document.body, reglas)).violations.map((v) => v.id)

    it('la lista y el detalle no tienen violaciones de accesibilidad', async () => {
      sembrar()
      abrir('/polizas')
      await screen.findByRole('rowheader', { name: 'POL-2026-000001' })
      expect(await violaciones()).toEqual([])

      document.body.innerHTML = ''
      abrir('/polizas/POL-2026-000001')
      await screen.findByRole('heading', { level: 1, name: 'Seguro de Auto — Plan Full' })
      expect(await violaciones()).toEqual([])
    })

    it('el detalle de una póliza se alcanza con el teclado', async () => {
      sembrar()
      abrir('/polizas')
      const enlace = await screen.findByRole('link', { name: 'Ver detalle de la póliza POL-2026-000001' })

      enlace.focus()
      await waitFor(() => expect(enlace).toHaveFocus())
      await userEvent.keyboard('{Enter}')
      expect(await screen.findByRole('heading', { level: 1, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
    })

    it('funciona en inglés', async () => {
      sembrar()
      abrir('/polizas')
      await tituloLista()
      await userEvent.click(screen.getByRole('button', { name: 'EN' }))

      expect(screen.getByRole('heading', { level: 1, name: 'My policies' })).toBeInTheDocument()
      await userEvent.click(await pestana('Expired'))
      expect(await screen.findByText('Home insurance')).toBeInTheDocument()
      expect(screen.getAllByText('Expired').length).toBeGreaterThan(1)
    })
  })
})
