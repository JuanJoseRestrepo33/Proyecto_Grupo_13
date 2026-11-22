/**
 * WEB-F06 de punta a punta (#201): un cambio de estado le llega al cliente. Se reporta un caso en la
 * web, el núcleo lo pasa a «En evaluación» con la operación interna y, al actualizar, la lista y el
 * detalle muestran el estado nuevo, y el historial trae el reporte y el cambio con su fecha.
 *
 * La operación interna no tiene interfaz ni ruta en el BFF: se llama al núcleo con `X-Token-Interno`
 * desde Node, no desde el navegador, para que el token nunca quede en la página ni en una captura.
 */
import { expect, test, type Page } from '@playwright/test'

import { iniciarSesion, reportarSiniestro } from './apoyo'

/** URL del núcleo y token de la operación interna (en CD, NUCLEO_URL y SINIESTROS_TOKEN_INTERNO). */
function operacionInterna() {
  const nucleo = process.env.E2E_NUCLEO_URL
  const token = process.env.E2E_TOKEN_INTERNO
  if (!nucleo || !token) {
    throw new Error('Faltan E2E_NUCLEO_URL y E2E_TOKEN_INTERNO: sin la operación interna no hay cambio de estado.')
  }
  return { nucleo: nucleo.replace(/\/+$/, ''), token }
}

/** Sin reintentos: un cambio que sí se aplicó respondería 409 al repetirlo y ocultaría el fallo real. */
async function cambiarEstado(idSiniestro: string, estado: string) {
  const { nucleo, token } = operacionInterna()
  const ruta = `/v1/siniestros/${encodeURIComponent(idSiniestro)}/estado`
  const respuesta = await fetch(nucleo + ruta, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Token-Interno': token },
    body: JSON.stringify({ estado }),
  })
  // El error dice la ruta y el código, nunca el cuerpo ni el token.
  expect(respuesta.ok, `La operación interna ${ruta} (${estado}) respondió ${respuesta.status}`).toBe(true)
}

/** La fila del caso en «Mis siniestros», buscada por su referencia. */
const filaDelCaso = (page: Page, referencia: string) =>
  page.getByRole('row').filter({ has: page.getByRole('rowheader', { name: referencia, exact: true }) })

const marca = () => `E2E-SEG-${Date.now()}`

test.describe('Seguimiento del siniestro (WEB-F06)', () => {
  test.beforeEach(async ({ page }) => {
    operacionInterna() // falla antes de reportar si falta el acceso: nunca se omite en silencio
    await iniciarSesion(page)
  })

  test('el cambio de estado en el núcleo se ve en la lista, el detalle y el historial', async ({ page }) => {
    const caso = await test.step('1 · reportar un siniestro desde la web', () => reportarSiniestro(page, marca()))

    await test.step('2 · «Mis siniestros» lo muestra «Reportado» (criterio 1)', async () => {
      await page.goto('/siniestros')
      await expect(page.getByRole('heading', { level: 1, name: 'Mis siniestros' })).toBeVisible()
      await expect(filaDelCaso(page, caso.referencia)).toContainText('Reportado')
    })

    await test.step('3 · el núcleo lo pasa a «En evaluación»', () => cambiarEstado(caso.id, 'en_evaluacion'))

    await test.step('4 · al actualizar, la lista muestra «En evaluación» (criterio 2)', async () => {
      await page.getByRole('button', { name: 'Actualizar' }).click()
      await expect(page.getByRole('status').filter({ hasText: 'Información actualizada.' })).toBeAttached()
      await expect(filaDelCaso(page, caso.referencia)).toContainText('En evaluación')
    })

    await test.step('5 · el detalle muestra «En evaluación» (criterio 2)', async () => {
      await page.getByRole('link', { name: `Ver el siniestro ${caso.referencia}` }).click()
      await expect(page.getByRole('main')).toContainText(caso.referencia)
      await expect(page.getByRole('heading', { level: 1 }).locator('..')).toContainText('En evaluación')
    })

    await test.step('6 · el historial trae el reporte y el cambio, cada uno con su fecha (criterio 3)', async () => {
      const eventos = page.getByRole('list', { name: 'Línea de tiempo' }).getByRole('listitem')
      await expect(eventos).toHaveCount(2)
      const anio = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota', year: 'numeric' }).format(new Date())
      await expect(eventos.nth(0)).toContainText('Reportado')
      await expect(eventos.nth(0)).toContainText(anio)
      await expect(eventos.nth(1)).toContainText('En evaluación')
      await expect(eventos.nth(1)).toContainText(anio)
      await expect(eventos.nth(1)).toHaveAttribute('aria-current', 'true')
    })
  })
})
