import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'

import { App } from './App'
import { servidor } from './mocks/servidor'

function renderizar(ruta = '/') {
  return render(
    <MemoryRouter initialEntries={[ruta]}>
      <App />
    </MemoryRouter>,
  )
}

describe('App', () => {
  it('saluda al cliente con los datos de la sesión del BFF', async () => {
    renderizar()
    expect(screen.getByRole('status')).toHaveTextContent('Cargando')
    expect(await screen.findByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeInTheDocument()
  })

  it('informa el error si el BFF no responde', async () => {
    servidor.use(http.get('*/api/v1/web/sesion', () => HttpResponse.json({}, { status: 500 })))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos cargar')
  })

  it('ofrece saltar al contenido y una navegación principal con la página actual marcada', async () => {
    renderizar('/consentimientos')
    expect(screen.getByRole('link', { name: 'Saltar al contenido principal' })).toHaveAttribute('href', '#contenido')
    const navegacion = screen.getByRole('navigation', { name: 'Navegación principal' })
    expect(navegacion).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Consentimientos' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Consentimientos de Open Finance')
  })

  it('navega entre secciones', async () => {
    renderizar()
    await userEvent.click(screen.getByRole('link', { name: 'Cotizar' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Cotizar un seguro')
  })

  it('cambia el idioma de toda la interfaz y lo recuerda', async () => {
    renderizar('/cotizacion')
    await userEvent.click(screen.getByRole('button', { name: 'EN' }))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Get an insurance quote')
    expect(screen.getByRole('button', { name: 'EN' })).toHaveAttribute('aria-pressed', 'true')
    expect(document.documentElement.lang).toBe('en')
    expect(localStorage.getItem('solventa_locale')).toBe('en')
  })

  it('muestra una página de no encontrada para rutas desconocidas', () => {
    renderizar('/no-existe')
    expect(screen.getByRole('heading', { name: 'Página no encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver al inicio' })).toHaveAttribute('href', '/')
  })

  it('no tiene violaciones de accesibilidad detectables con axe', async () => {
    const { container } = renderizar()
    await screen.findByRole('heading', { level: 1 })
    const { violations } = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(violations.map((v) => `${v.id}: ${v.help}`)).toEqual([])
  })
})
