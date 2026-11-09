/** Pasos comunes de las pruebas E2E: la sesión de la cuenta de prueba y el estado que cada flujo necesita. */
import { readFile } from 'node:fs/promises'

import { expect, type Page } from '@playwright/test'

const API = '/api/v1/web'

/** Modo del proveedor de Open Finance simulado en el núcleo del ambiente. */
export const MODO_OPEN_FINANCE = process.env.E2E_OPEN_FINANCE === 'caido' ? 'caido' : 'sano'

export function cuentaDePrueba() {
  const correo = process.env.E2E_CORREO
  const clave = process.env.E2E_CLAVE
  if (!correo || !clave) throw new Error('Faltan E2E_CORREO y E2E_CLAVE: la cuenta de prueba del ambiente.')
  return { correo, clave }
}

/** Escribe las credenciales en la pantalla de inicio de sesión abierta y las envía. */
export async function enviarCredenciales(page: Page, correo: string, clave: string) {
  await page.getByLabel('Correo electrónico').fill(correo)
  await page.getByLabel('Contraseña').fill(clave)
  await page.getByRole('button', { name: 'Iniciar sesión' }).click()
}

/** Entra por la pantalla de inicio de sesión, como el cliente (WEB-F13). */
export async function iniciarSesion(page: Page) {
  const { correo, clave } = cuentaDePrueba()
  await page.goto('/ingresar')
  await enviarCredenciales(page, correo, clave)
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

/**
 * Cotiza el seguro de auto con Open Finance, como lo haría el cliente, hasta ver la oferta. El núcleo
 * reutiliza la cotización vigente de una solicitud idéntica: quien necesite una oferta nueva, por
 * ejemplo para comprarla, pasa una ciudad distinta.
 */
export async function cotizarAuto(page: Page, ciudad = 'Bogotá') {
  await page.goto('/cotizacion')
  await page.getByRole('radio', { name: /Auto/ }).check()
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByLabel('Fecha de nacimiento').fill('1990-05-14')
  await page.getByLabel('Ciudad').fill(ciudad)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: 'Ver mi oferta' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /Tu oferta/ })).toBeVisible()
}

/** Ciudad única para cada corrida: garantiza una cotización sin comprar, aun en un reintento. */
export const ciudadUnica = () => `Bogotá ${Date.now().toString(36)}`

/** Contenido del archivo que descarga el navegador al activar el enlace. */
export async function descargar(page: Page, enlace: string) {
  const [descarga] = await Promise.all([page.waitForEvent('download'), page.getByRole('link', { name: enlace }).click()])
  return { nombre: descarga.suggestedFilename(), contenido: await readFile(await descarga.path()) }
}

/** La fila de una póliza en «Mis pólizas», en la pestaña que esté abierta. */
export const filaDePoliza = (page: Page, numero: string) =>
  page.getByRole('row').filter({ has: page.getByRole('rowheader', { name: numero, exact: true }) })

/**
 * Compra una póliza de auto por la interfaz, desde la oferta hasta la emisión, con un medio que la
 * pasarela simulada aprueba. Devuelve la cotización y el número de la póliza emitida.
 */
export async function comprarPoliza(page: Page) {
  await page.getByRole('link', { name: 'Aceptar oferta' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Suscripción de tu póliza' })).toBeVisible()
  const cotizacion = new URL(page.url()).pathname.split('/').pop()!

  await page.getByRole('button', { name: 'Aceptar y continuar al pago' }).click()
  await page.getByRole('radio', { name: /^Tarjeta de crédito/ }).check()
  await page.getByRole('button', { name: /^Pagar / }).click()
  await expect(page.getByText('Pago aprobado.')).toBeVisible()
  await expect(page.getByText('Referencia de la transacción')).toBeVisible()

  await page.getByRole('button', { name: 'Continuar a la firma' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /Firma tu póliza/ })).toBeVisible()
  await page.getByRole('checkbox', { name: /Acepto los términos/ }).check()
  await page.getByRole('button', { name: 'Firmar electrónicamente' }).click()

  await expect(page.getByRole('heading', { level: 2, name: /Póliza emitida/ })).toBeVisible()
  const numero = await numeroDePolizaEmitida(page)
  return { cotizacion, numero }
}

/** El número que muestra la pantalla de emisión. */
export async function numeroDePolizaEmitida(page: Page) {
  const texto = await page.getByRole('main').locator('dd').first().innerText()
  expect(texto).toMatch(/^POL-\d{4}-\d{6}$/)
  return texto
}

/** El día que fue hace `dias` días en Bogotá, como `2026-10-29`: así nunca es una fecha futura. */
export function diaEnBogota(dias: number) {
  const fecha = new Date(Date.now() - dias * 24 * 60 * 60 * 1000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota' }).format(fecha)
}

/**
 * Una póliza de auto vigente que ya cubría ese día. Las que emite la prueba de compra empiezan hoy,
 * así que la más reciente de la cuenta de prueba no siempre sirve para un evento pasado.
 */
async function polizaDeAutoQueCubre(page: Page, dia: string) {
  const respuesta = await page.request.get(`${API}/polizas`)
  expect(respuesta.ok()).toBe(true)
  const polizas = (await respuesta.json()) as { numero: string; producto: string; inicio: string; estado: string }[]
  const poliza = polizas.find((p) => p.producto === 'auto' && p.estado === 'vigente' && p.inicio <= dia)
  expect(poliza, 'La cuenta de prueba necesita una póliza de auto vigente anterior al evento').toBeDefined()
  return poliza!.numero
}

/**
 * Llena el reporte de un siniestro sobre la póliza de auto vigente, hasta la revisión. La descripción
 * lleva una marca única para encontrar después ese siniestro entre los de la cuenta de prueba.
 */
export async function llenarReporteHastaRevision(page: Page, descripcion: string) {
  const fechaDelEvento = diaEnBogota(2)
  const numero = await polizaDeAutoQueCubre(page, fechaDelEvento)
  await page.goto('/siniestros/reportar')
  await page.getByLabel('Póliza afectada').selectOption(numero)
  await page.getByLabel('Tipo de evento').selectOption({ label: 'Accidente de vehículo' })
  await page.getByLabel('Fecha del evento').fill(fechaDelEvento)
  await page.getByLabel('Descripción de lo ocurrido').fill(descripcion)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /Evidencias/ })).toBeVisible()
  await page.getByRole('button', { name: 'Continuar' }).click()
  await expect(page.getByRole('heading', { level: 2, name: /Revisa tu reporte/ })).toBeVisible()
}

interface SiniestroResumen {
  id: string
  referencia: string
  descripcion: string
}

/** Siniestros de la cuenta de prueba cuya descripción lleva la marca, según el BFF. */
export async function siniestrosConMarca(page: Page, marca: string) {
  const respuesta = await page.request.get(`${API}/siniestros`)
  expect(respuesta.ok()).toBe(true)
  return ((await respuesta.json()) as SiniestroResumen[]).filter((s) => s.descripcion.includes(marca))
}

/** Reporta un siniestro completo y devuelve el que quedó registrado, según el BFF. */
export async function reportarSiniestro(page: Page, marca: string) {
  await llenarReporteHastaRevision(page, `Siniestro de prueba (${marca}).`)
  await page.getByRole('button', { name: 'Enviar reporte' }).click()
  await expect(page.getByRole('region', { name: 'Reporte enviado' })).toBeVisible()
  const [siniestro] = await siniestrosConMarca(page, marca)
  expect(siniestro).toBeDefined()
  return siniestro!
}

interface EvidenciaResumen {
  nombre_original: string | null
}

/** Nombres de los archivos que el BFF tiene como evidencia del siniestro. */
export async function evidenciasDe(page: Page, idSiniestro: string) {
  const respuesta = await page.request.get(`${API}/siniestros/${encodeURIComponent(idSiniestro)}/evidencias`)
  expect(respuesta.ok()).toBe(true)
  return ((await respuesta.json()) as EvidenciaResumen[]).map((e) => e.nombre_original)
}
