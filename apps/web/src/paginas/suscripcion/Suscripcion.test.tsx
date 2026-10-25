import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import { bff } from '../../api/cliente'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`

async function cotizar() {
  const { data } = await bff.POST('/cotizaciones', {
    body: { producto: 'auto', asegurado: { edad: 35, ciudad: 'Bogotá' }, usar_open_finance: false },
  })
  return data!
}

async function abrirCheckout(cotizacionId?: string) {
  const id = cotizacionId ?? (await cotizar()).id
  render(
    <MemoryRouter initialEntries={[`/suscripcion/${id}`]}>
      <App />
    </MemoryRouter>,
  )
  await screen.findByRole('heading', { level: 1, name: 'Suscripción de tu póliza' })
  return id
}

const botonPagar = () => screen.getByRole('button', { name: /^Pagar \$/ })

async function aceptarYElegir(medio: 'Tarjeta de crédito' | 'PSE (débito a tu cuenta)' = 'Tarjeta de crédito') {
  await userEvent.click(screen.getByRole('button', { name: 'Aceptar y continuar al pago' }))
  await screen.findByRole('heading', { level: 2, name: /Medio de pago/ })
  await userEvent.click(screen.getByRole('radio', { name: new RegExp(medio.replace(/[()]/g, '\\$&')) }))
}

/** Solicitudes de pago que llegaron al BFF, con su clave de idempotencia. */
function registrarPagos() {
  const claves: string[] = []
  servidor.events.on('request:start', ({ request }) => {
    if (request.method === 'POST' && /\/suscripciones\/[^/]+\/pagos$/.test(request.url)) {
      claves.push(request.headers.get('Idempotency-Key') ?? '')
    }
  })
  return claves
}

beforeEach(() => sessionStorage.clear())
afterEach(() => servidor.events.removeAllListeners())

describe('Checkout de la suscripción (WEB-F03, rebanada del Sprint 1)', () => {
  it('se llega desde la oferta del cotizador con "Aceptar oferta"', async () => {
    render(
      <MemoryRouter initialEntries={['/cotizacion']}>
        <App />
      </MemoryRouter>,
    )
    await userEvent.click(await screen.findByRole('radio', { name: /Auto/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Continuar' }))
    await userEvent.type(screen.getByLabelText('Fecha de nacimiento'), '1990-05-14')
    await userEvent.type(screen.getByLabelText('Ciudad'), 'Bogotá')
    await userEvent.click(screen.getByRole('button', { name: 'Continuar' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ver mi oferta' }))

    await userEvent.click(await screen.findByRole('link', { name: 'Aceptar oferta' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Suscripción de tu póliza' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
  })

  describe('criterio 3: las migas de pan muestran el paso y cómo volver', () => {
    it('Inicio / Cotizar / Suscripción, con la pantalla actual marcada', async () => {
      await abrirCheckout()
      const migas = screen.getByRole('navigation', { name: 'Miga de pan' })
      expect(within(migas).getByRole('link', { name: 'Inicio' })).toHaveAttribute('href', '/')
      expect(within(migas).getByRole('link', { name: 'Cotizar' })).toHaveAttribute('href', '/cotizacion')
      expect(within(migas).getByText('Suscripción')).toHaveAttribute('aria-current', 'page')
    })

    it('el indicador marca el paso actual y se puede volver del pago a la revisión', async () => {
      await abrirCheckout()
      const pasos = () => screen.getByRole('list', { name: 'Pasos de la suscripción' })
      expect(within(pasos()).getByText('Revisar oferta')).toHaveAttribute('aria-current', 'step')

      await userEvent.click(screen.getByRole('button', { name: 'Aceptar y continuar al pago' }))
      await screen.findByRole('heading', { level: 2, name: /Medio de pago/ })
      expect(within(pasos()).getByText('Pago')).toHaveAttribute('aria-current', 'step')
      expect(screen.getByRole('heading', { level: 2 })).toHaveFocus()

      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      expect(within(pasos()).getByText('Revisar oferta')).toHaveAttribute('aria-current', 'step')
    })

    it('la miga Cotizar lleva de vuelta al cotizador', async () => {
      await abrirCheckout()
      await userEvent.click(within(screen.getByRole('navigation', { name: 'Miga de pan' })).getByRole('link', { name: 'Cotizar' }))
      expect(await screen.findByRole('heading', { level: 1, name: 'Cotizar un seguro' })).toBeInTheDocument()
    })
  })

  describe('criterio 1: al enviar el pago el botón queda ocupado y no permite un segundo envío', () => {
    it('mientras se procesa, el botón está deshabilitado y se avisa que no se pulse de nuevo', async () => {
      await abrirCheckout()
      await aceptarYElegir()
      await userEvent.click(botonPagar())

      const ocupado = screen.getByRole('button', { name: 'Procesando pago…' })
      expect(ocupado).toBeDisabled()
      expect(ocupado).toHaveAttribute('aria-busy', 'true')
      expect(screen.getByRole('status')).toHaveTextContent('No cierres esta ventana ni pulses de nuevo.')

      expect(await screen.findByText('Pago aprobado.')).toBeInTheDocument()
    })

    it('un doble clic envía una sola solicitud de pago', async () => {
      const claves = registrarPagos()
      await abrirCheckout()
      await aceptarYElegir()
      await userEvent.dblClick(botonPagar())

      await screen.findByText('Pago aprobado.')
      expect(claves).toHaveLength(1)
      expect(estado.pagos.size).toBe(1)
    })

    it('exige elegir un medio de pago antes de enviar', async () => {
      const claves = registrarPagos()
      await abrirCheckout()
      await userEvent.click(screen.getByRole('button', { name: 'Aceptar y continuar al pago' }))
      await userEvent.click(await screen.findByRole('button', { name: /^Pagar \$/ }))

      expect(screen.getByRole('alert')).toHaveTextContent('Elige un medio de pago para continuar.')
      expect(screen.getByRole('radio', { name: /Tarjeta de crédito/ })).toHaveFocus()
      expect(claves).toHaveLength(0)
    })
  })

  describe('criterio 2: un reintento de la misma operación no genera un segundo cobro', () => {
    it('si la respuesta se perdió, el reintento usa la misma clave y muestra el pago ya hecho', async () => {
      const claves = registrarPagos()
      servidor.use(http.post(ruta('/suscripciones/:id/pagos'), () => HttpResponse.error(), { once: true }))
      await abrirCheckout()
      await aceptarYElegir()
      await userEvent.click(botonPagar())

      expect(await screen.findByRole('status')).toHaveTextContent('Puedes reintentar con seguridad')
      await userEvent.click(screen.getByRole('button', { name: 'Consultar estado del pago' }))

      expect(await screen.findByText('Pago aprobado.')).toBeInTheDocument()
      expect(claves).toHaveLength(2)
      expect(claves[1]).toBe(claves[0])
      expect(estado.pagos.size).toBe(1)
    })

    it('si el primer envío sí llegó, el reintento lo reconoce y lo comunica', async () => {
      await abrirCheckout()
      await aceptarYElegir()
      // El primer envío llegó al BFF y se cobró, pero el cliente no recibió la respuesta.
      const [suscripcionId] = [...estado.suscripciones.keys()]
      const clave = crypto.randomUUID()
      sessionStorage.setItem(`solventa_pago_${suscripcionId}`, clave)
      await bff.POST('/suscripciones/{id}/pagos', {
        params: { path: { id: suscripcionId! }, header: { 'Idempotency-Key': clave } },
        body: { medio: 'tarjeta_credito' },
      })

      await userEvent.click(botonPagar())

      expect(await screen.findByText('Este pago ya se había procesado. No se hizo un segundo cobro.')).toBeInTheDocument()
      expect(screen.getByText('Pago aprobado.')).toBeInTheDocument()
      expect(estado.pagos.size).toBe(1)
    })

    it('si el pago sigue en curso, lo comunica y permite consultar sin cobrar otra vez', async () => {
      servidor.use(
        http.post(
          ruta('/suscripciones/:id/pagos'),
          () => HttpResponse.json({ codigo: 'pago_en_curso', mensaje: 'En curso' }, { status: 409 }),
          { once: true },
        ),
      )
      await abrirCheckout()
      await aceptarYElegir()
      await userEvent.click(botonPagar())

      expect(await screen.findByText(/Tu pago ya está en curso. No se hará un segundo cobro/)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Consultar estado del pago' }))
      expect(await screen.findByText('Pago aprobado.')).toBeInTheDocument()
      expect(estado.pagos.size).toBe(1)
    })

    it('volver a aceptar una suscripción ya pagada muestra ese pago en lugar de cobrar otro', async () => {
      const id = await abrirCheckout()
      await aceptarYElegir()
      await userEvent.click(botonPagar())
      await screen.findByText('Pago aprobado.')

      // El cliente recarga la pantalla y vuelve a aceptar la misma oferta.
      document.body.innerHTML = ''
      await abrirCheckout(id)
      await userEvent.click(screen.getByRole('button', { name: 'Aceptar y continuar al pago' }))

      expect(await screen.findByText('Esta suscripción ya tiene un pago registrado. No se hizo un segundo cobro.')).toBeInTheDocument()
      expect(estado.pagos.size).toBe(1)
    })
  })

  describe('estados de la pasarela con su siguiente paso', () => {
    it('rechazado: explica que no se cobró y un intento nuevo usa otra clave', async () => {
      const claves = registrarPagos()
      estado.pasarela.tarjeta_credito = 'rechazado'
      await abrirCheckout()
      await aceptarYElegir()
      await userEvent.click(botonPagar())

      expect(await screen.findByText('Pago rechazado por el banco emisor.')).toBeInTheDocument()
      expect(screen.getByText(/No se realizó ningún cobro/)).toBeInTheDocument()

      estado.pasarela.tarjeta_credito = 'aprobado'
      await userEvent.click(screen.getByRole('button', { name: 'Intentar de nuevo' }))
      await userEvent.click(botonPagar())

      expect(await screen.findByText('Pago aprobado.')).toBeInTheDocument()
      expect(claves).toHaveLength(2)
      expect(claves[1]).not.toBe(claves[0])
    })

    it('pendiente (PSE): pide no pagar de nuevo y permite consultar hasta que se aprueba', async () => {
      await abrirCheckout()
      await aceptarYElegir('PSE (débito a tu cuenta)')
      await userEvent.click(botonPagar())

      expect(await screen.findByText('Tu banco aún no confirma el pago.')).toBeInTheDocument()
      expect(screen.getByText(/No hagas otro pago/)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Consultar estado del pago' }))

      expect(await screen.findByText('Pago aprobado.')).toBeInTheDocument()
      expect(screen.getByText(/^TX-/)).toBeInTheDocument()
    })
  })

  describe('oferta que no se puede aceptar', () => {
    it('cotización vencida: no se puede aceptar y se ofrece volver a cotizar', async () => {
      const cotizacion = await cotizar()
      servidor.use(
        http.get(ruta('/cotizaciones/:id'), () =>
          HttpResponse.json({ ...cotizacion, estado: 'vencida', vigente_hasta: new Date(Date.now() - 1000).toISOString() }),
        ),
      )
      await abrirCheckout(cotizacion.id)

      expect(screen.getByRole('button', { name: 'Aceptar y continuar al pago' })).toBeDisabled()
      expect(screen.getByRole('alert')).toHaveTextContent('La cotización venció.')
      expect(screen.getByRole('link', { name: 'Volver a cotizar' })).toHaveAttribute('href', '/cotizacion')
    })

    it('si vence justo al aceptar, el BFF la rechaza y se ofrece volver a cotizar', async () => {
      servidor.use(
        http.post(
          ruta('/suscripciones'),
          () => HttpResponse.json({ codigo: 'cotizacion_vencida', mensaje: 'Vencida' }, { status: 409 }),
          { once: true },
        ),
      )
      await abrirCheckout()
      await userEvent.click(screen.getByRole('button', { name: 'Aceptar y continuar al pago' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('La cotización venció.')
      expect(screen.getByRole('link', { name: 'Volver a cotizar' })).toBeInTheDocument()
    })

    it('una cotización que no existe lo explica y ofrece cotizar', async () => {
      await abrirCheckout('cot-no-existe')
      expect(screen.getByRole('alert')).toHaveTextContent('No encontramos esta cotización.')
      expect(screen.getByRole('link', { name: 'Volver a cotizar' })).toBeInTheDocument()
    })

    it('si falla la carga, permite reintentar', async () => {
      const cotizacion = await cotizar()
      servidor.use(http.get(ruta('/cotizaciones/:id'), () => HttpResponse.error(), { once: true }))
      await abrirCheckout(cotizacion.id)
      expect(screen.getByRole('alert')).toHaveTextContent('No pudimos cargar tu cotización.')

      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findByRole('heading', { level: 3, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
    })
  })

  it('no tiene violaciones de accesibilidad en la revisión, el pago y el resultado', async () => {
    const reglas = { rules: { 'color-contrast': { enabled: false } } }
    const violaciones = async () => (await axe.run(document.body, reglas)).violations.map((v) => v.id)

    await abrirCheckout()
    expect(await violaciones()).toEqual([])
    await aceptarYElegir()
    expect(await violaciones()).toEqual([])
    await userEvent.click(botonPagar())
    await screen.findByText('Pago aprobado.')
    expect(await violaciones()).toEqual([])
  })

  it('funciona en inglés', async () => {
    await abrirCheckout()
    await userEvent.click(screen.getByRole('button', { name: 'EN' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Your policy purchase' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Accept and continue to payment' })).toBeInTheDocument()
  })
})
