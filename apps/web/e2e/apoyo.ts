/** Pasos comunes de las pruebas E2E: la sesión de la cuenta de prueba y el estado que cada flujo necesita. */
import { expect, type Page } from '@playwright/test'

const API = '/api/v1/web'

/** Modo del proveedor de Open Finance simulado en el núcleo del ambiente. */
export const MODO_OPEN_FINANCE = process.env.E2E_OPEN_FINANCE === 'caido' ? 'caido' : 'sano'

function cuentaDePrueba() {
  const correo = process.env.E2E_CORREO
  const clave = process.env.E2E_CLAVE
  if (!correo || !clave) throw new Error('Faltan E2E_CORREO y E2E_CLAVE: la cuenta de prueba del ambiente.')
  return { correo, clave }
}

/** Entra por la pantalla de inicio de sesión, como el cliente (WEB-F13). */
export async function iniciarSesion(page: Page) {
  const { correo, clave } = cuentaDePrueba()
  await page.goto('/ingresar')
  await page.getByLabel('Correo electrónico').fill(correo)
  await page.getByLabel('Contraseña').fill(clave)
  await page.getByRole('button', { name: 'Iniciar sesión' }).click()
  await expect(page.getByRole('heading', { level: 1, name: /^Hola, / })).toBeVisible()
}

interface Consentimiento {
  id: string
  finalidad: string
  estado: string
}

/**
 * Deja el consentimiento de cotización como lo necesita la prueba, con la misma sesión del navegador.
 * Es preparación, no lo que se prueba: por eso va directo al BFF y no por la pantalla.
 */
export async function consentimientoDeCotizacion(page: Page, vigente: boolean) {
  const respuesta = await page.request.get(`${API}/consentimientos`)
  expect(respuesta.ok()).toBe(true)
  const actual = ((await respuesta.json()) as Consentimiento[]).find(
    (c) => c.finalidad === 'cotizacion' && c.estado === 'vigente',
  )

  if (vigente && !actual) {
    const finalidades = (await (await page.request.get(`${API}/consentimientos/finalidades`)).json()) as {
      codigo: string
      version_texto: string
      entidades_origen: { id: string }[]
    }[]
    const cotizacion = finalidades.find((f) => f.codigo === 'cotizacion')!
    const otorgado = await page.request.post(`${API}/consentimientos`, {
      data: {
        finalidad: 'cotizacion',
        entidades_origen: [cotizacion.entidades_origen[0]!.id],
        version_texto: cotizacion.version_texto,
      },
    })
    expect(otorgado.status()).toBe(201)
  }
  if (!vigente && actual) {
    const revocado = await page.request.post(`${API}/consentimientos/${actual.id}/revocacion`)
    expect(revocado.ok()).toBe(true)
  }
}

/** Cotiza el seguro de auto con Open Finance, como lo haría el cliente, hasta ver la oferta. */
export async function cotizarAuto(page: Page) {
  await page.goto('/cotizacion')
  await page.getByRole('radio', { name: /Auto/ }).check()
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByLabel('Fecha de nacimiento').fill('1990-05-14')
  await page.getByLabel('Ciudad').fill('Bogotá')
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: 'Ver mi oferta' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /Tu oferta/ })).toBeVisible()
}
