import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import { diaRelativo } from '../../formato'
import { polizasDePrueba } from '../../mocks/datos'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`
const [VIGENTE, VENCIDA] = polizasDePrueba()

async function renderizar() {
  const resultado = render(
    <MemoryRouter initialEntries={['/siniestros/reportar']}>
      <App />
    </MemoryRouter>,
  )
  await screen.findByRole('heading', { level: 2, name: /Información del evento/ })
  return resultado
}

const continuar = () => userEvent.click(screen.getByRole('button', { name: 'Continuar' }))
const enviar = () => userEvent.click(screen.getByRole('button', { name: 'Enviar reporte' }))

async function llenarInformacion({ poliza = VIGENTE!.numero, dia = diaRelativo(-2) } = {}) {
  await userEvent.selectOptions(screen.getByLabelText('Póliza afectada'), poliza)
  await userEvent.selectOptions(screen.getByLabelText('Tipo de evento'), 'accidente_vehiculo')
  await userEvent.type(screen.getByLabelText('Fecha del evento'), dia)
  await userEvent.type(screen.getByLabelText('Descripción de lo ocurrido'), 'Choque leve en un parqueadero.')
}

async function llegarARevision(opciones?: Parameters<typeof llenarInformacion>[0]) {
  await llenarInformacion(opciones)
  await continuar()
  await continuar()
  await screen.findByRole('heading', { level: 2, name: /Revisa tu reporte/ })
}

/** Guarda la `Idempotency-Key` de cada envío del reporte. */
function registrarClaves() {
  const claves: string[] = []
  servidor.events.on('request:start', ({ request }) => {
    if (request.method === 'POST' && request.url.endsWith('/siniestros')) {
      claves.push(request.headers.get('Idempotency-Key') ?? '')
    }
  })
  return claves
}

describe('Reporte de siniestro (WEB-F05)', () => {
  afterEach(() => servidor.events.removeAllListeners())

  describe('criterio 1: tres pasos y confirmación con referencia', () => {
    it('reporta sobre una póliza vigente y muestra la referencia, el estado y el siguiente paso', async () => {
      await renderizar()
      expect(screen.getByRole('list', { name: 'Pasos del reporte' })).toHaveTextContent('InformaciónEvidenciaRevisión')
      await llenarInformacion()
      await userEvent.type(screen.getByLabelText('Monto estimado (opcional)'), '1.500.000')
      await continuar()

      expect(await screen.findByRole('heading', { level: 2, name: /Evidencias/ })).toHaveFocus()
      expect(screen.getByText(/Este paso es opcional/)).toBeInTheDocument()
      await continuar()

      const resumen = await screen.findByRole('heading', { level: 2, name: /Revisa tu reporte/ })
      expect(resumen).toHaveFocus()
      const detalle = resumen.closest('section')!
      expect(detalle).toHaveTextContent(`${VIGENTE!.numero} · Auto`)
      expect(detalle).toHaveTextContent('Accidente de vehículo')
      expect(detalle).toHaveTextContent('Choque leve en un parqueadero.')
      expect(detalle).toHaveTextContent(/\$\s?1\.500\.000/)
      await enviar()

      const confirmacion = await screen.findByRole('heading', { level: 2, name: 'Reporte enviado' })
      expect(confirmacion).toHaveFocus()
      const tarjeta = confirmacion.closest('section')!
      expect(within(tarjeta).getByText(/^SIN-2026-\d{6}$/)).toBeInTheDocument()
      expect(tarjeta).toHaveTextContent('Reportado')
      expect(tarjeta).toHaveTextContent(/Adjunta las fotos, facturas o documentos/)
      expect(estado.siniestros).toHaveLength(1)
      expect(estado.siniestros[0]).toMatchObject({
        numero_poliza: VIGENTE!.numero,
        tipo_evento: 'accidente_vehiculo',
        monto_estimado: { valor: 1500000, moneda: 'COP' },
      })
    })

    it('al volver conserva los datos, y "Reportar otro siniestro" empieza con el formulario vacío', async () => {
      await renderizar()
      await llegarARevision()
      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      expect(screen.getByLabelText('Póliza afectada')).toHaveValue(VIGENTE!.numero)
      expect(screen.getByLabelText('Descripción de lo ocurrido')).toHaveValue('Choque leve en un parqueadero.')

      await continuar()
      await continuar()
      await enviar()
      await userEvent.click(await screen.findByRole('button', { name: 'Reportar otro siniestro' }))

      expect(await screen.findByRole('heading', { level: 2, name: /Información del evento/ })).toHaveFocus()
      expect(screen.getByLabelText('Póliza afectada')).toHaveValue('')
    })
  })

  describe('criterio 2: el paso de información no avanza sin los datos obligatorios', () => {
    it('explica qué falta en cada campo y lleva el foco al primero', async () => {
      const enviados = registrarClaves()
      await renderizar()
      await continuar()

      const poliza = screen.getByLabelText('Póliza afectada')
      expect(poliza).toHaveFocus()
      expect(poliza).toHaveAccessibleDescription('Elige la póliza afectada.')
      expect(screen.getByLabelText('Tipo de evento')).toHaveAccessibleDescription('Elige el tipo de evento.')
      expect(screen.getByLabelText('Fecha del evento')).toHaveAccessibleDescription('Indica la fecha del evento.')
      expect(screen.getByLabelText('Descripción de lo ocurrido')).toHaveAccessibleDescription(
        /Describe lo ocurrido con al menos 10 caracteres/,
      )
      expect(screen.getByRole('heading', { level: 2, name: /Información del evento/ })).toBeInTheDocument()
      expect(enviados).toEqual([])
    })

    it('rechaza una fecha futura', async () => {
      await renderizar()
      await llenarInformacion({ dia: diaRelativo(1) })
      await continuar()

      const fecha = screen.getByLabelText('Fecha del evento')
      expect(fecha).toHaveFocus()
      expect(fecha).toHaveAccessibleDescription('La fecha del evento no puede ser posterior a hoy.')
    })
  })

  describe('criterio 3: póliza vencida', () => {
    it('acepta un evento ocurrido dentro de su vigencia', async () => {
      await renderizar()
      await llegarARevision({ poliza: VENCIDA!.numero, dia: VENCIDA!.fin })
      await enviar()
      expect(await screen.findByRole('heading', { level: 2, name: 'Reporte enviado' })).toBeInTheDocument()
    })

    it('explica en el campo por qué no procede un evento posterior a la vigencia', async () => {
      await renderizar()
      await llenarInformacion({ poliza: VENCIDA!.numero, dia: diaRelativo(-1) })
      await continuar()

      expect(screen.getByLabelText('Fecha del evento')).toHaveAccessibleDescription(
        /El evento debe haber ocurrido entre el .+ y el .+, mientras la póliza estaba vigente\./,
      )
      expect(screen.getByLabelText('Póliza afectada')).toHaveAccessibleDescription(/Cobertura del .+ al .+\./)
    })

    it('si el BFF responde evento_fuera_de_vigencia, lo explica sin registrar el reporte y deja corregir', async () => {
      servidor.use(
        http.post(ruta('/siniestros'), () =>
          HttpResponse.json(
            {
              codigo: 'evento_fuera_de_vigencia',
              mensaje: 'Fuera de vigencia',
              vigente_desde: '2025-01-01',
              vigente_hasta: '2025-12-31',
            },
            { status: 409 },
          ),
        ),
      )
      await renderizar()
      await llegarARevision()
      await enviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Este evento no está cubierto: ocurrió fuera de la vigencia de la póliza (del 1 de enero de 2025 al 31 de diciembre de 2025). No se registró ningún reporte.',
      )
      expect(estado.siniestros).toEqual([])
      await userEvent.click(screen.getByRole('button', { name: 'Revisar la información' }))
      expect(screen.getByRole('heading', { level: 2, name: /Información del evento/ })).toHaveFocus()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
  })

  describe('criterio 4: un doble clic o un reintento no crean otro siniestro', () => {
    it('ante un doble clic envía una sola solicitud y deja el botón ocupado', async () => {
      const claves = registrarClaves()
      await renderizar()
      await llegarARevision()
      await userEvent.dblClick(screen.getByRole('button', { name: 'Enviar reporte' }))

      expect(screen.getByRole('button', { name: 'Enviando reporte…' })).toBeDisabled()
      await screen.findByRole('heading', { level: 2, name: 'Reporte enviado' })
      expect(claves).toHaveLength(1)
      expect(estado.siniestros).toHaveLength(1)
    })

    it('si no hubo respuesta, el reintento lleva la misma clave', async () => {
      const claves = registrarClaves()
      servidor.use(http.post(ruta('/siniestros'), () => HttpResponse.error(), { once: true }))
      await renderizar()
      await llegarARevision()
      await enviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Intenta de nuevo: si ya había llegado, verás el mismo número de referencia.',
      )
      await enviar()
      await screen.findByRole('heading', { level: 2, name: 'Reporte enviado' })
      expect(claves).toHaveLength(2)
      expect(claves[1]).toBe(claves[0])
      expect(estado.siniestros).toHaveLength(1)
    })

    it('si el cliente vuelve a editar, el envío siguiente lleva otra clave', async () => {
      const claves = registrarClaves()
      servidor.use(http.post(ruta('/siniestros'), () => HttpResponse.error(), { once: true }))
      await renderizar()
      await llegarARevision()
      await enviar()
      await screen.findByRole('alert')

      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      await userEvent.type(screen.getByLabelText('Descripción de lo ocurrido'), ' Sin heridos.')
      await continuar()
      await continuar()
      await enviar()

      await screen.findByRole('heading', { level: 2, name: 'Reporte enviado' })
      expect(claves).toHaveLength(2)
      expect(claves[1]).not.toBe(claves[0])
    })
  })

  describe('pólizas', () => {
    it('sin pólizas lo explica y ofrece cotizar', async () => {
      estado.polizas = []
      render(
        <MemoryRouter initialEntries={['/siniestros/reportar']}>
          <App />
        </MemoryRouter>,
      )
      expect(await screen.findByText(/No tienes pólizas/)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Cotizar un seguro' })).toHaveAttribute('href', '/cotizacion')
    })

    it('si no cargan, permite reintentar', async () => {
      servidor.use(http.get(ruta('/polizas'), () => HttpResponse.json({}, { status: 503 }), { once: true }))
      render(
        <MemoryRouter initialEntries={['/siniestros/reportar']}>
          <App />
        </MemoryRouter>,
      )
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar tus pólizas.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findByRole('heading', { level: 2, name: /Información del evento/ })).toBeInTheDocument()
    })

    it('muestra cada póliza con su producto y estado, y la vigencia de la elegida', async () => {
      await renderizar()
      const opciones = within(screen.getByLabelText('Póliza afectada')).getAllByRole('option')
      expect(opciones.map((o) => o.textContent)).toEqual([
        'Elige una póliza',
        `${VIGENTE!.numero} · Auto (vigente)`,
        `${VENCIDA!.numero} · Hogar (vencida)`,
      ])
    })
  })

  it('está en la navegación principal', async () => {
    await renderizar()
    expect(screen.getByRole('link', { name: 'Reportar siniestro' })).toHaveAttribute('aria-current', 'page')
  })

  it('no tiene violaciones de accesibilidad en el primer paso con errores ni en la confirmación', async () => {
    const { container } = await renderizar()
    const reglas = { rules: { 'color-contrast': { enabled: false } } }
    await continuar()
    expect((await axe.run(container, reglas)).violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])

    await llegarARevision()
    await enviar()
    await screen.findByRole('heading', { level: 2, name: 'Reporte enviado' })
    expect((await axe.run(container, reglas)).violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
  })

  it('funciona en inglés', async () => {
    await renderizar()
    await userEvent.click(screen.getByRole('button', { name: 'EN' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Report a claim')
    expect(screen.getByLabelText('Affected policy')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    expect(screen.getByLabelText('Affected policy')).toHaveAccessibleDescription('Choose the affected policy.')
  })
})
