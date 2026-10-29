import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import { bff } from '../../api/cliente'
import type { Cotizacion } from '../../api/tipos'
import { FINALIDADES } from '../../mocks/datos'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

const ruta = (camino: string) => `*/api/v1/web${camino}`

async function renderizar() {
  render(
    <MemoryRouter initialEntries={['/cotizacion']}>
      <App />
    </MemoryRouter>,
  )
  await screen.findByRole('heading', { level: 2, name: /Selecciona el producto/ })
}

async function otorgarConsentimiento() {
  await bff.POST('/consentimientos', {
    body: { finalidad: 'cotizacion', entidades_origen: ['ent-andes'], version_texto: FINALIDADES[0]!.version_texto },
  })
}

const continuar = () => userEvent.click(screen.getByRole('button', { name: 'Continuar' }))

async function elegirAutoYLlenarDatos() {
  await userEvent.click(screen.getByRole('radio', { name: /Auto/ }))
  await continuar()
  await userEvent.type(screen.getByLabelText('Fecha de nacimiento'), '1990-05-14')
  await userEvent.type(screen.getByLabelText('Ciudad'), 'Bogotá')
  await continuar()
}

async function verOferta() {
  await userEvent.click(screen.getByRole('button', { name: 'Ver mi oferta' }))
  await screen.findByRole('heading', { level: 2, name: /Tu oferta/ })
}

function cotizacionDePrueba(cambios: Partial<Cotizacion> = {}): Cotizacion {
  const ahora = Date.now()
  return {
    id: 'cot-9000',
    producto: 'auto',
    nombre_producto: 'Seguro de Auto — Plan Full',
    estado: 'vigente',
    prima_mensual: { valor: 133200, moneda: 'COP' },
    prima_estandar: { valor: 148000, moneda: 'COP' },
    ahorro_mensual: { valor: 14800, moneda: 'COP' },
    tipo_tarifa: 'personalizada',
    base_perfilamiento: 'open_finance_en_cache',
    motivo_tarifa: null,
    factores: [{ nombre: 'Comportamiento de pago (Open Finance)', efecto: -0.1 }],
    version_regla: 'rating-v3.2',
    valor_asegurado: '$60.000.000 COP',
    deducible: '5% del valor asegurado',
    coberturas: [{ nombre: 'Responsabilidad civil', limite: '$1.200.000.000 COP' }],
    creada_en: new Date(ahora).toISOString(),
    vigente_hasta: new Date(ahora + 5 * 60 * 1000).toISOString(),
    ...cambios,
  }
}

describe('Asistente de cotización (WEB-F01)', () => {
  describe('criterio 1: cada paso valida antes de avanzar y se puede volver sin perder datos', () => {
    it('muestra los productos con su precio desde y exige elegir uno', async () => {
      await renderizar()
      expect(screen.getByRole('radio', { name: /Auto.*Desde \$\s?148\.000\/mes/ })).toBeInTheDocument()
      expect(screen.getAllByRole('radio')).toHaveLength(4)

      await continuar()
      expect(screen.getByRole('alert')).toHaveTextContent('Selecciona un producto para continuar.')
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Selecciona el producto')

      await userEvent.click(screen.getByRole('radio', { name: /Hogar/ }))
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('marca el paso actual y lleva el foco al título del paso al avanzar', async () => {
      await renderizar()
      const pasos = screen.getByRole('list', { name: 'Pasos de la cotización' })
      expect(within(pasos).getByText('Producto')).toHaveAttribute('aria-current', 'step')

      await userEvent.click(screen.getByRole('radio', { name: /Auto/ }))
      await continuar()

      expect(within(pasos).getByText('Tus datos')).toHaveAttribute('aria-current', 'step')
      expect(screen.getByRole('heading', { level: 2 })).toHaveFocus()
    })

    it('valida los datos del asegurado, explica cada error y enfoca el primero', async () => {
      await renderizar()
      await userEvent.click(screen.getByRole('radio', { name: /Auto/ }))
      await continuar()
      await continuar()

      const fecha = screen.getByLabelText('Fecha de nacimiento')
      expect(fecha).toHaveFocus()
      expect(fecha).toHaveAttribute('aria-invalid', 'true')
      expect(fecha).toHaveAccessibleDescription('Escribe tu fecha de nacimiento.')
      expect(screen.getByLabelText('Ciudad')).toHaveAccessibleDescription('Escribe tu ciudad.')

      const haceDiezAnios = new Date().getFullYear() - 10
      await userEvent.type(fecha, `${haceDiezAnios}-01-01`)
      await userEvent.type(screen.getByLabelText('Ciudad'), 'Cali')
      await userEvent.type(screen.getByLabelText('Valor asegurado deseado (opcional)'), 'mucho')
      await continuar()

      expect(fecha).toHaveAccessibleDescription('Debes tener al menos 18 años para cotizar.')
      expect(screen.getByLabelText('Valor asegurado deseado (opcional)')).toHaveAccessibleDescription(
        /Valor del plan: \$60\.000\.000 COP.*solo con números/,
      )
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Información del asegurado')
    })

    it('al volver conserva el producto y los datos diligenciados', async () => {
      await renderizar()
      await elegirAutoYLlenarDatos()
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Open Finance')

      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      expect(screen.getByLabelText('Ciudad')).toHaveValue('Bogotá')
      expect(screen.getByLabelText('Fecha de nacimiento')).toHaveValue('1990-05-14')

      await userEvent.click(screen.getByRole('button', { name: /Atrás/ }))
      expect(screen.getByRole('radio', { name: /Auto/ })).toBeChecked()
    })
  })

  describe('criterio 3: sin datos de Open Finance la tarifa es estándar, sin presentarlo como error', () => {
    it('sin consentimiento avisa en el paso de Open Finance y la oferta explica el precio estándar', async () => {
      await renderizar()
      await elegirAutoYLlenarDatos()

      expect(screen.getByRole('switch', { name: /Usar mis datos financieros/ })).toBeDisabled()
      expect(screen.getByText('No tienes un consentimiento vigente.')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Otorgar consentimiento' })).toHaveAttribute('href', '/consentimientos')

      await verOferta()
      expect(screen.getByText(/\$\s?148\.000/)).toBeInTheDocument()
      expect(screen.getByText(/Precio estándar\./)).toBeInTheDocument()
      expect(screen.getByText(/no tienes un consentimiento vigente para cotización/)).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.queryByText(/Ahorras/)).not.toBeInTheDocument()
    })

    it('con consentimiento, si el cliente desactiva Open Finance, cotiza estándar y dice por qué', async () => {
      await otorgarConsentimiento()
      await renderizar()
      await elegirAutoYLlenarDatos()

      const interruptor = screen.getByRole('switch', { name: /Usar mis datos financieros/ })
      expect(interruptor).toBeChecked()
      await userEvent.click(interruptor)
      await verOferta()

      expect(screen.getByText(/elegiste cotizar sin ellos/)).toBeInTheDocument()
    })

    it('con Open Finance caído y sin perfil guardado, cotiza estándar y lo explica sin culpar al consentimiento', async () => {
      estado.openFinance = 'caido'
      await otorgarConsentimiento()
      await renderizar()
      await elegirAutoYLlenarDatos()
      await verOferta()

      expect(screen.getByText(/\$\s?148\.000/)).toBeInTheDocument()
      expect(screen.getByText(/Precio estándar\./)).toBeInTheDocument()
      expect(screen.getByText(/Tu entidad financiera no respondió y no teníamos tu perfil guardado/)).toBeInTheDocument()
      expect(screen.queryByText(/no tienes un consentimiento vigente/)).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Otorgar consentimiento' })).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
  })

  describe('oferta personalizada y criterio 2: detalle del precio', () => {
    it('con consentimiento muestra la prima personalizada, el ahorro y las coberturas', async () => {
      await otorgarConsentimiento()
      await renderizar()
      await elegirAutoYLlenarDatos()
      expect(screen.getByText(/Tienes un consentimiento vigente para cotización/)).toBeInTheDocument()
      await verOferta()

      expect(screen.getByRole('heading', { level: 3, name: 'Seguro de Auto — Plan Full' })).toBeInTheDocument()
      expect(screen.getByText(/\$\s?133\.200/)).toBeInTheDocument()
      expect(screen.getByText(/Ahorras \$\s?14\.800 al mes frente al precio estándar de \$\s?148\.000/)).toBeInTheDocument()
      expect(screen.getByText(/Precio calculado con tu perfil de Open Finance/)).toBeInTheDocument()
      expect(screen.getByText('Responsabilidad civil: $1.200.000.000 COP')).toBeInTheDocument()
      expect(screen.getByText(/Válida por/)).toBeInTheDocument()
    })

    it('"¿Por qué este precio?" despliega cada factor con su efecto y el total', async () => {
      await otorgarConsentimiento()
      await renderizar()
      await elegirAutoYLlenarDatos()
      await verOferta()

      const boton = screen.getByRole('button', { name: '¿Por qué este precio?' })
      expect(boton).toHaveAttribute('aria-expanded', 'false')
      await userEvent.click(boton)
      expect(boton).toHaveAttribute('aria-expanded', 'true')

      const tabla = screen.getByRole('table', { name: 'Cómo se calculó tu prima' })
      const fila = within(tabla).getByRole('row', { name: /Comportamiento de pago/ })
      expect(fila).toHaveTextContent(/-6\s?%/)
      expect(fila).toHaveTextContent(/-\$\s?8\.880/)
      expect(within(tabla).getByRole('row', { name: /Tu prima mensual/ })).toHaveTextContent(/\$\s?133\.200/)
      expect(screen.getByText('Calculado con la regla rating-v3.2.')).toBeInTheDocument()
    })

    it('indica cuando el perfil vino de la caché porque el proveedor estaba degradado', async () => {
      estado.openFinance = 'degradado'
      await otorgarConsentimiento()
      await renderizar()
      await elegirAutoYLlenarDatos()
      await verOferta()

      expect(screen.getByText(/usamos tu perfil financiero guardado/)).toBeInTheDocument()
      expect(screen.getByText(/Ahorras/)).toBeInTheDocument()
      // La hora en español ya termina en "a. m." o "p. m.": el texto no agrega otro punto.
      expect(screen.getByText(/La oferta es válida hasta las/).textContent).toMatch(/[ap]\.\s?m\.$/)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
  })

  describe('criterio 4: al vencer la oferta se ofrece recotizar', () => {
    it('muestra la oferta vencida y recotiza con los mismos datos', async () => {
      const vencida = cotizacionDePrueba({ estado: 'vencida', vigente_hasta: new Date(Date.now() - 1000).toISOString() })
      servidor.use(http.post(ruta('/cotizaciones'), () => HttpResponse.json(vencida, { status: 201 }), { once: true }))
      servidor.use(
        http.post(ruta('/cotizaciones/:id/recotizacion'), () =>
          HttpResponse.json(cotizacionDePrueba({ id: 'cot-9001' }), { status: 201 }),
        ),
      )
      await renderizar()
      await elegirAutoYLlenarDatos()
      await verOferta()

      expect(screen.getByRole('alert')).toHaveTextContent('La cotización venció.')
      await userEvent.click(screen.getByRole('button', { name: 'Recotizar' }))

      expect(await screen.findByText('Cotización cot-9001')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(screen.getByText(/Válida por/)).toBeInTheDocument()
    })

    it('la cuenta regresiva llega a cero y la oferta pasa a vencida sin recargar', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      try {
        servidor.use(
          http.post(ruta('/cotizaciones'), () =>
            HttpResponse.json(cotizacionDePrueba({ vigente_hasta: new Date(Date.now() + 2000).toISOString() }), {
              status: 201,
            }),
          ),
        )
        await renderizar()
        await elegirAutoYLlenarDatos()
        await verOferta()
        // El contador visual está oculto a lectores de pantalla (cambia cada segundo); se anuncia la hora límite.
        expect(screen.getByRole('timer', { hidden: true })).toHaveTextContent(/0:0[12]/)

        await vi.advanceTimersByTimeAsync(3000)
        expect(await screen.findByRole('alert')).toHaveTextContent('La cotización venció.')
      } finally {
        vi.useRealTimers()
      }
    })

    it('si la recotización falla lo informa', async () => {
      const vencida = cotizacionDePrueba({ estado: 'vencida', vigente_hasta: new Date(Date.now() - 1000).toISOString() })
      servidor.use(
        http.post(ruta('/cotizaciones'), () => HttpResponse.json(vencida, { status: 201 })),
        http.post(ruta('/cotizaciones/:id/recotizacion'), () => HttpResponse.json({}, { status: 500 })),
      )
      await renderizar()
      await elegirAutoYLlenarDatos()
      await verOferta()
      await userEvent.click(screen.getByRole('button', { name: 'Recotizar' }))
      expect(await screen.findByText('No pudimos calcular tu oferta. Intenta de nuevo.')).toBeInTheDocument()
    })
  })

  describe('errores y reinicio', () => {
    it('si el BFF no carga los productos permite reintentar', async () => {
      servidor.use(http.get(ruta('/cotizaciones/productos'), () => HttpResponse.json({}, { status: 500 }), { once: true }))
      render(
        <MemoryRouter initialEntries={['/cotizacion']}>
          <App />
        </MemoryRouter>,
      )
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar los productos.')
      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findAllByRole('radio')).toHaveLength(4)
    })

    it('si la cotización falla se queda en el paso de Open Finance y lo informa', async () => {
      servidor.use(http.post(ruta('/cotizaciones'), () => HttpResponse.json({}, { status: 500 })))
      await renderizar()
      await elegirAutoYLlenarDatos()
      await userEvent.click(screen.getByRole('button', { name: 'Ver mi oferta' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos calcular tu oferta.')
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Open Finance')
    })

    it('"Cotizar otro producto" vuelve al inicio con el formulario vacío', async () => {
      await renderizar()
      await elegirAutoYLlenarDatos()
      await verOferta()
      await userEvent.click(screen.getByRole('button', { name: 'Cotizar otro producto' }))

      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Selecciona el producto')
      expect(screen.getByRole('radio', { name: /Auto/ })).not.toBeChecked()
    })
  })

  it('no tiene violaciones de accesibilidad en el primer paso ni en la oferta', async () => {
    await otorgarConsentimiento()
    await renderizar()
    const reglas = { rules: { 'color-contrast': { enabled: false } } }
    expect((await axe.run(document.body, reglas)).violations.map((v) => v.id)).toEqual([])

    await elegirAutoYLlenarDatos()
    await verOferta()
    await userEvent.click(screen.getByRole('button', { name: '¿Por qué este precio?' }))
    expect((await axe.run(document.body, reglas)).violations.map((v) => v.id)).toEqual([])
  })

  it('funciona en inglés', async () => {
    await renderizar()
    await userEvent.click(screen.getByRole('button', { name: 'EN' }))
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Choose the product you want to quote')
    expect(screen.getByRole('radio', { name: /Auto.*From/ })).toBeInTheDocument()
  })
})
