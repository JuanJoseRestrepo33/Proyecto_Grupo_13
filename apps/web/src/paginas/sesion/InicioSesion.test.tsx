import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from '../../App'
import { CREDENCIALES_PRUEBA, SESION } from '../../mocks/datos'
import { estado } from '../../mocks/estado'
import { servidor } from '../../mocks/servidor'

function renderizar(ruta: string) {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

async function ingresar(correo: string, contrasena: string) {
  if (correo) await userEvent.type(screen.getByLabelText('Correo electrónico'), correo)
  if (contrasena) await userEvent.type(screen.getByLabelText('Contraseña'), contrasena)
  await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
}

const tituloIngreso = () => screen.findByRole('heading', { level: 1, name: 'Inicia sesión' })

describe('Inicio de sesión (WEB-F13)', () => {
  describe('sin sesión', () => {
    beforeEach(() => {
      estado.sesion = null
    })

    // Si una aserción falla, el listener de una prueba no debe quedar vivo para las siguientes.
    afterEach(() => servidor.events.removeAllListeners())

    it('lleva al inicio de sesión desde una ruta protegida, sin la navegación principal', async () => {
      renderizar('/consentimientos')
      expect(await tituloIngreso()).toBeInTheDocument()
      expect(screen.queryByRole('navigation', { name: 'Navegación principal' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Cerrar sesión' })).not.toBeInTheDocument()
    })

    it('al iniciar sesión vuelve a la página que se había pedido', async () => {
      renderizar('/cotizacion')
      await tituloIngreso()
      await ingresar(CREDENCIALES_PRUEBA.correo, CREDENCIALES_PRUEBA.contrasena)

      expect(await screen.findByRole('heading', { level: 1, name: 'Cotizar un seguro' })).toBeInTheDocument()
      expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument()
      expect(screen.getByText('Ana')).toBeInTheDocument()
    })

    it('entrando directo al inicio de sesión, al iniciar va al inicio', async () => {
      renderizar('/ingresar')
      await tituloIngreso()
      await ingresar(CREDENCIALES_PRUEBA.correo, CREDENCIALES_PRUEBA.contrasena)
      expect(await screen.findByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
    })

    it('valida los campos antes de enviar y lleva el foco al primero con error', async () => {
      const enviado = vi.fn()
      servidor.events.on('request:start', enviado)
      renderizar('/ingresar')
      await tituloIngreso()
      enviado.mockClear()

      await ingresar('', '')
      const correo = screen.getByLabelText('Correo electrónico')
      expect(correo).toHaveAccessibleDescription('Escribe tu correo electrónico.')
      expect(correo).toHaveAttribute('aria-invalid', 'true')
      expect(correo).toHaveFocus()
      expect(screen.getByLabelText('Contraseña')).toHaveAccessibleDescription('Escribe tu contraseña.')

      await userEvent.type(correo, 'ana@solventa')
      await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
      expect(correo).toHaveAccessibleDescription('Escribe un correo válido, por ejemplo nombre@correo.com.')
      expect(enviado).not.toHaveBeenCalled()
    })

    it.each([
      ['un correo que no existe', 'nadie@solventa.test', CREDENCIALES_PRUEBA.contrasena],
      ['una contraseña equivocada', CREDENCIALES_PRUEBA.correo, 'equivocada'],
    ])('con %s muestra el mismo mensaje genérico y borra la contraseña', async (_caso, correo, contrasena) => {
      renderizar('/ingresar')
      await tituloIngreso()
      await ingresar(correo, contrasena)

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'El correo o la contraseña no son correctos. Revísalos e intenta de nuevo.',
      )
      expect(screen.getByLabelText('Contraseña')).toHaveValue('')
      expect(screen.getByLabelText('Contraseña')).toHaveFocus()
      expect(screen.getByLabelText('Correo electrónico')).toHaveValue(correo)
    })

    it('si el BFF falla, lo dice sin culpar a las credenciales', async () => {
      servidor.use(http.post('*/api/v1/web/sesion', () => HttpResponse.json({}, { status: 503 })))
      renderizar('/ingresar')
      await tituloIngreso()
      await ingresar(CREDENCIALES_PRUEBA.correo, CREDENCIALES_PRUEBA.contrasena)
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos iniciar sesión en este momento')
    })

    it('deshabilita el botón mientras envía para no iniciar sesión dos veces', async () => {
      let solicitudes = 0
      servidor.use(
        http.post('*/api/v1/web/sesion', async () => {
          solicitudes += 1
          await new Promise((listo) => setTimeout(listo, 50))
          return HttpResponse.json({}, { status: 503 })
        }),
      )
      renderizar('/ingresar')
      await tituloIngreso()
      await userEvent.type(screen.getByLabelText('Correo electrónico'), CREDENCIALES_PRUEBA.correo)
      await userEvent.type(screen.getByLabelText('Contraseña'), `${CREDENCIALES_PRUEBA.contrasena}{Enter}`)

      expect(screen.getByRole('button', { name: 'Iniciando sesión…' })).toBeDisabled()
      await screen.findByRole('alert')
      expect(solicitudes).toBe(1)
    })

    it('no tiene violaciones de accesibilidad detectables con axe', async () => {
      const { container } = renderizar('/ingresar')
      await tituloIngreso()
      await ingresar('', '')
      const { violations } = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
      expect(violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
    })
  })

  describe('con sesión', () => {
    it('si ya hay sesión, el inicio de sesión lleva al inicio', async () => {
      renderizar('/ingresar')
      expect(await screen.findByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
    })

    it('al cerrar sesión vuelve al inicio de sesión y, al entrar de nuevo, empieza en el inicio', async () => {
      renderizar('/consentimientos')
      await userEvent.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))

      expect(await tituloIngreso()).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent('Cerraste sesión')
      expect(estado.sesion).toBeNull()

      await ingresar(CREDENCIALES_PRUEBA.correo, CREDENCIALES_PRUEBA.contrasena)
      expect(await screen.findByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
    })

    it('si no puede cerrar la sesión, lo avisa y la deja abierta', async () => {
      servidor.use(http.delete('*/api/v1/web/sesion', () => HttpResponse.error()))
      renderizar('/')
      await userEvent.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cerrar tu sesión')
      expect(screen.getByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeEnabled()
    })

    it('si la sesión vence, pide iniciar sesión de nuevo y luego vuelve a la misma página', async () => {
      renderizar('/cotizacion')
      await screen.findByRole('heading', { level: 1, name: 'Cotizar un seguro' })
      estado.sesion = null

      await userEvent.click(screen.getByRole('link', { name: 'Consentimientos' }))

      expect(await tituloIngreso()).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent('Tu sesión venció')
      await ingresar(CREDENCIALES_PRUEBA.correo, CREDENCIALES_PRUEBA.contrasena)
      expect(
        await screen.findByRole('heading', { level: 1, name: 'Consentimientos de Open Finance' }),
      ).toBeInTheDocument()
    })

    it('el error de cierre no sigue en pantalla si la sesión vence después', async () => {
      servidor.use(http.delete('*/api/v1/web/sesion', () => HttpResponse.error()))
      renderizar('/')
      await userEvent.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cerrar tu sesión')

      estado.sesion = null
      await userEvent.click(screen.getByRole('link', { name: 'Consentimientos' }))

      expect(await tituloIngreso()).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent('Tu sesión venció')
      expect(screen.queryByText('No pudimos cerrar tu sesión. Intenta de nuevo.')).not.toBeInTheDocument()

      await ingresar(CREDENCIALES_PRUEBA.correo, CREDENCIALES_PRUEBA.contrasena)
      await screen.findByRole('heading', { level: 1, name: 'Consentimientos de Open Finance' })
      expect(screen.queryByText('No pudimos cerrar tu sesión. Intenta de nuevo.')).not.toBeInTheDocument()
    })

    it('tras un intento fallido, ya no muestra el aviso de sesión vencida junto al error', async () => {
      renderizar('/cotizacion')
      await screen.findByRole('heading', { level: 1, name: 'Cotizar un seguro' })
      estado.sesion = null
      await userEvent.click(screen.getByRole('link', { name: 'Consentimientos' }))
      expect(await screen.findByRole('status')).toHaveTextContent('Tu sesión venció')

      await ingresar(CREDENCIALES_PRUEBA.correo, 'equivocada')

      expect(await screen.findByRole('alert')).toHaveTextContent('El correo o la contraseña no son correctos')
      expect(screen.queryByText('Tu sesión venció. Inicia sesión de nuevo para continuar.')).not.toBeInTheDocument()
    })

    it('entrando a /ingresar con sesión, no muestra el formulario mientras consulta la sesión', async () => {
      servidor.use(
        http.get('*/api/v1/web/sesion', async () => {
          await delay(30)
          return HttpResponse.json(SESION)
        }),
      )
      renderizar('/ingresar')

      expect(screen.getByRole('status')).toHaveTextContent('Cargando tu información')
      expect(screen.queryByLabelText('Correo electrónico')).not.toBeInTheDocument()
      expect(await screen.findByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
    })

    it('si la consulta de la sesión falla, ofrece reintentar', async () => {
      servidor.use(http.get('*/api/v1/web/sesion', () => HttpResponse.json({}, { status: 500 }), { once: true }))
      renderizar('/')
      expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar tu información')

      await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
      expect(await screen.findByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
    })
  })
})
