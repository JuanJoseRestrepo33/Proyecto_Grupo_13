/** WEB-F13 de punta a punta: inicio de sesión válido e inválido contra el BFF y el núcleo reales (#156). */
import { expect, type Page, test } from '@playwright/test'

import { cuentaDePrueba, enviarCredenciales } from './apoyo'

const MENSAJE_GENERICO = 'El correo o la contraseña no son correctos. Revísalos e intenta de nuevo.'

/** Sin sesión, una pantalla protegida lleva al inicio de sesión. */
async function comprobarSinSesion(page: Page) {
  await page.goto('/consentimientos')
  await expect(page).toHaveURL(/\/ingresar$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Inicia sesión' })).toBeVisible()
}

test.describe('Inicio de sesión (WEB-F13)', () => {
  test('con la cuenta de prueba entra, ve su nombre y llega a la pantalla que había pedido (criterio 1)', async ({
    page,
  }) => {
    const { correo, clave } = cuentaDePrueba()
    await comprobarSinSesion(page)

    await enviarCredenciales(page, correo, clave)

    await expect(page).toHaveURL(/\/consentimientos$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Consentimientos de Open Finance' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Navegación principal' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible()

    // El encabezado muestra el nombre del cliente de la sesión abierta.
    const sesion = await page.request.get('/api/v1/web/sesion')
    expect(sesion.ok()).toBe(true)
    const { cliente } = (await sesion.json()) as { cliente: { nombre: string } }
    await expect(page.getByRole('banner').getByText(cliente.nombre, { exact: true })).toBeVisible()
  })

  test('un correo que no existe y una contraseña equivocada dan el mismo mensaje y no abren sesión (criterio 3)', async ({
    page,
  }) => {
    const { correo } = cuentaDePrueba()
    const intentos = [
      // Un correo que nunca se registró: el dominio .test está reservado para pruebas.
      { correo: `no-existe-${Date.now()}@solventa.test`, clave: 'clave-cualquiera' },
      // El correo de la cuenta de prueba con una contraseña que no es la suya.
      { correo, clave: 'clave-equivocada' },
    ]

    const mensajes: string[] = []
    for (const intento of intentos) {
      await page.goto('/ingresar')
      await enviarCredenciales(page, intento.correo, intento.clave)
      const alerta = page.getByRole('alert')
      // El aviso lleva un ícono decorativo antes del texto, oculto para los lectores de pantalla.
      await expect(alerta).toContainText(MENSAJE_GENERICO)
      mensajes.push((await alerta.textContent()) ?? '')
      await expect(page.getByLabel('Contraseña')).toHaveValue('')
    }

    // Mismo mensaje exista o no el correo: no revela qué cuentas existen.
    expect(mensajes[0]).toBe(mensajes[1])
    await comprobarSinSesion(page)
  })
})
