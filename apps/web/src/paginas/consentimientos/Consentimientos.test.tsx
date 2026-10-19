import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import { bff } from '../../api/cliente'
import { FINALIDADES } from '../../mocks/datos'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`
const DIA = 24 * 60 * 60 * 1000

async function renderizar() {
  render(
    <MemoryRouter initialEntries={['/consentimientos']}>
      <App />
    </MemoryRouter>,
  )
  await screen.findByRole('article', { name: 'Cotización' })
}

const tarjeta = (nombre: string) => screen.getByRole('article', { name: nombre })

async function otorgarDesdeLaInterfaz(nombre = 'Cotización', entidad = 'Banco Andes S.A.') {
  const t = tarjeta(nombre)
  await userEvent.click(within(t).getByRole('checkbox', { name: entidad }))
  await userEvent.click(within(t).getByRole('checkbox', { name: /Autorizo a Solventa/ }))
  await userEvent.click(within(t).getByRole('button', { name: 'Otorgar consentimiento' }))
  await within(t).findByText(/Consentimiento otorgado/)
}

async function otorgarPorApi() {
  const { data } = await bff.POST('/consentimientos', {
    body: { finalidad: 'cotizacion', entidades_origen: ['ent-andes'], version_texto: FINALIDADES[0]!.version_texto },
  })
  return data!
}

describe('Consentimientos de Open Finance (WEB-F02)', () => {
  afterEach(() => vi.useRealTimers())

  it('criterio 1: muestra cada finalidad por separado, con alcance, vigencia y entidades, sin nada marcado', async () => {
    await renderizar()
    expect(screen.getAllByRole('article')).toHaveLength(3)

    const cotizacion = tarjeta('Cotización')
    expect(within(cotizacion).getByText('Sin otorgar')).toBeInTheDocument()
    expect(within(cotizacion).getByText('Saldos promedio · Historial de pagos · Ingresos recurrentes')).toBeInTheDocument()
    expect(within(cotizacion).getByText('12 meses')).toBeInTheDocument()
    expect(within(cotizacion).getByText('Banco Andes S.A. · Cooperativa Financiera del Sur')).toBeInTheDocument()
    expect(within(cotizacion).getByText('cotizacion-2026.1')).toBeInTheDocument()

    for (const casilla of screen.getAllByRole('checkbox')) expect(casilla).not.toBeChecked()
  })

  it('exige elegir una entidad y marcar la autorización antes de otorgar', async () => {
    await renderizar()
    const t = tarjeta('Cotización')
    await userEvent.click(within(t).getByRole('button', { name: 'Otorgar consentimiento' }))

    expect(within(t).getByText('Selecciona al menos una entidad.')).toBeInTheDocument()
    expect(within(t).getByRole('checkbox', { name: 'Banco Andes S.A.' })).toHaveFocus()

    await userEvent.click(within(t).getByRole('checkbox', { name: 'Banco Andes S.A.' }))
    await userEvent.click(within(t).getByRole('button', { name: 'Otorgar consentimiento' }))
    const autorizo = within(t).getByRole('checkbox', { name: /Autorizo a Solventa/ })
    expect(autorizo).toHaveFocus()
    expect(autorizo).toHaveAccessibleDescription('Debes marcar la autorización para continuar.')
    expect((await bff.GET('/consentimientos')).data).toEqual([])
  })

  it('al abrir la confirmación de revocar se retira el mensaje de la acción anterior', async () => {
    await renderizar()
    await otorgarDesdeLaInterfaz()
    const t = tarjeta('Cotización')
    await userEvent.click(within(t).getByRole('button', { name: 'Revocar' }))
    expect(within(t).queryByText(/Consentimiento otorgado/)).not.toBeInTheDocument()
  })

  it('criterio 2: al otorgar queda Vigente con su fecha de expiración y lo anuncia', async () => {
    await renderizar()
    await otorgarDesdeLaInterfaz()

    const t = tarjeta('Cotización')
    expect(within(t).getByText('Vigente')).toBeInTheDocument()
    expect(within(t).getByText(/^Vence el \d+ de \w+ de \d{4}\.$/)).toBeInTheDocument()
    expect(within(t).getByRole('status')).toHaveTextContent(/Consentimiento otorgado\. Vigente hasta el/)
    expect(within(t).queryByRole('button', { name: 'Otorgar consentimiento' })).not.toBeInTheDocument()

    const [guardado] = (await bff.GET('/consentimientos')).data!
    expect(guardado).toMatchObject({ finalidad: 'cotizacion', canal: 'web', estado: 'vigente' })
    expect(guardado!.entidades_origen.map((e) => e.id)).toEqual(['ent-andes'])
  })

  describe('criterios 9 y 3: revocar explica la consecuencia, pide confirmación y cambia de inmediato', () => {
    it('confirma antes de revocar y deja el estado Revocado', async () => {
      await otorgarPorApi()
      await renderizar()
      const t = tarjeta('Cotización')

      await userEvent.click(within(t).getByRole('button', { name: 'Revocar' }))
      const confirmacion = within(t).getByRole('group', { name: '¿Revocar el consentimiento de Cotización?' })
      expect(within(confirmacion).getByRole('heading')).toHaveFocus()
      expect(confirmacion).toHaveTextContent('Tus próximas cotizaciones usarán precio estándar')
      expect(confirmacion).toHaveTextContent('menos de 5 minutos')
      expect((await bff.GET('/consentimientos')).data![0]!.estado).toBe('vigente')

      await userEvent.click(within(confirmacion).getByRole('button', { name: 'Sí, revocar' }))

      expect(await within(t).findByText('Revocado', { selector: 'span' })).toBeInTheDocument()
      expect(within(t).getByRole('status')).toHaveTextContent('Consentimiento revocado.')
      expect(within(t).getByText(/^Revocado el /)).toBeInTheDocument()
      expect(within(t).getByRole('button', { name: 'Otorgar consentimiento' })).toBeInTheDocument()
      expect((await bff.GET('/consentimientos')).data![0]!.estado).toBe('revocado')
    })

    it('cancelar no revoca y devuelve el foco al botón Revocar', async () => {
      await otorgarPorApi()
      await renderizar()
      const t = tarjeta('Cotización')

      await userEvent.click(within(t).getByRole('button', { name: 'Revocar' }))
      await userEvent.click(within(t).getByRole('button', { name: 'Cancelar' }))

      expect(within(t).getByRole('button', { name: 'Revocar' })).toHaveFocus()
      expect(within(t).getByText('Vigente')).toBeInTheDocument()
    })

    it('la consecuencia se explica según la finalidad', async () => {
      await bff.POST('/consentimientos', {
        body: { finalidad: 'suscripcion', entidades_origen: ['ent-sur'], version_texto: FINALIDADES[1]!.version_texto },
      })
      await renderizar()
      const t = tarjeta('Suscripción')
      await userEvent.click(within(t).getByRole('button', { name: 'Revocar' }))
      expect(within(t).getByRole('group')).toHaveTextContent('evaluar tus solicitudes de póliza')
    })
  })

  describe('criterio 7: aviso de vencimiento y renovación sin repetir el flujo', () => {
    it('a 30 días o menos avisa y permite renovar', async () => {
      const { vence_en } = await otorgarPorApi()
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date(vence_en).getTime() - 10 * DIA)
      await renderizar()
      const t = tarjeta('Cotización')

      expect(within(t).getByText(/Vence en 10 días/)).toBeInTheDocument()
      await userEvent.click(within(t).getByRole('button', { name: 'Renovar' }))

      expect(await within(t).findByRole('status')).toHaveTextContent(/Consentimiento renovado\. Vigente hasta el/)
      expect(within(t).queryByText(/Vence en 10 días/)).not.toBeInTheDocument()
      expect(within(t).queryByRole('button', { name: 'Renovar' })).not.toBeInTheDocument()
    })

    it('un consentimiento vencido se muestra como tal y se puede renovar', async () => {
      const { vence_en } = await otorgarPorApi()
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date(vence_en).getTime() + DIA)
      await renderizar()
      const t = tarjeta('Cotización')

      expect(within(t).getByText('Vencido')).toBeInTheDocument()
      expect(within(t).getByText(/^Venció el /)).toBeInTheDocument()
      await userEvent.click(within(t).getByRole('button', { name: 'Renovar' }))
      expect(await within(t).findByText('Vigente')).toBeInTheDocument()
    })
  })

  it('criterio 8: el historial muestra todos los eventos de la finalidad en orden cronológico', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    const inicio = Date.now()
    const primero = await otorgarPorApi()
    vi.setSystemTime(inicio + 60_000)
    await bff.POST('/consentimientos/{id}/revocacion', { params: { path: { id: primero.id } } })
    vi.setSystemTime(inicio + 120_000)
    await otorgarPorApi()
    await renderizar()
    const t = tarjeta('Cotización')

    const boton = within(t).getByRole('button', { name: 'Ver historial' })
    expect(boton).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(boton)
    expect(within(t).getByRole('button', { name: 'Ocultar historial' })).toHaveAttribute('aria-expanded', 'true')

    const historial = await within(t).findByRole('list')
    const eventos = within(historial).getAllByRole('listitem')
    expect(eventos.map((e) => within(e).getByText(/^(Otorgado|Revocado|Renovado|Vencido)$/).textContent)).toEqual([
      'Otorgado',
      'Revocado',
      'Otorgado',
    ])
    expect(eventos[0]).toHaveTextContent('canal web · texto legal cotizacion-2026.1')
  })

  describe('errores', () => {
    it('explica un error conocido del BFF al otorgar', async () => {
      servidor.use(
        http.post(ruta('/consentimientos'), () =>
          HttpResponse.json({ codigo: 'version_texto_desactualizada', mensaje: 'x' }, { status: 422 }),
        ),
      )
      await renderizar()
      const t = tarjeta('Cotización')
      await userEvent.click(within(t).getByRole('checkbox', { name: 'Banco Andes S.A.' }))
      await userEvent.click(within(t).getByRole('checkbox', { name: /Autorizo a Solventa/ }))
      await userEvent.click(within(t).getByRole('button', { name: 'Otorgar consentimiento' }))

      expect(await within(t).findByRole('alert')).toHaveTextContent('El texto legal cambió.')
      expect(within(t).getByText('Sin otorgar')).toBeInTheDocument()
    })

    it('usa un mensaje general si el BFF falla sin código conocido', async () => {
      await otorgarPorApi()
      servidor.use(http.post(ruta('/consentimientos/:id/revocacion'), () => HttpResponse.json({}, { status: 500 })))
      await renderizar()
      const t = tarjeta('Cotización')
      await userEvent.click(within(t).getByRole('button', { name: 'Revocar' }))
      await userEvent.click(within(t).getByRole('button', { name: 'Sí, revocar' }))

      expect(await within(t).findByRole('alert')).toHaveTextContent('No pudimos completar la operación.')
      expect(within(t).getByRole('group')).toBeInTheDocument()
    })

    it('si no carga la pantalla permite reintentar', async () => {
      servidor.use(http.get(ruta('/consentimientos'), () => HttpResponse.json({}, { status: 500 }), { once: true }))
      render(
        <MemoryRouter initialEntries={['/consentimientos']}>
          <App />
        </MemoryRouter>,
      )
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar tus consentimientos.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findAllByRole('article')).toHaveLength(3)
    })

    it('informa si el historial no carga', async () => {
      await otorgarPorApi()
      servidor.use(http.get(ruta('/consentimientos/:id/eventos'), () => HttpResponse.json({}, { status: 500 })))
      await renderizar()
      await userEvent.click(within(tarjeta('Cotización')).getByRole('button', { name: 'Ver historial' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar el historial.')
    })
  })

  it('no tiene violaciones de accesibilidad con un consentimiento vigente, la confirmación y el historial', async () => {
    await otorgarPorApi()
    await renderizar()
    const reglas = { rules: { 'color-contrast': { enabled: false } } }
    expect((await axe.run(document.body, reglas)).violations.map((v) => v.id)).toEqual([])

    const t = tarjeta('Cotización')
    await userEvent.click(within(t).getByRole('button', { name: 'Ver historial' }))
    await within(t).findByRole('list')
    await userEvent.click(within(t).getByRole('button', { name: 'Revocar' }))
    expect((await axe.run(document.body, reglas)).violations.map((v) => v.id)).toEqual([])
  })

  it('funciona en inglés', async () => {
    await renderizar()
    await userEvent.click(screen.getByRole('button', { name: 'EN' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Open Finance consents')
    expect(within(tarjeta('Cotización')).getByText('Not granted')).toBeInTheDocument()
    expect(within(tarjeta('Cotización')).getByText('12 months')).toBeInTheDocument()
  })
})
