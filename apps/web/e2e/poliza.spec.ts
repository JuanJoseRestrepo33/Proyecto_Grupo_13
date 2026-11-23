/** WEB-F04 de punta a punta: la póliza recién comprada se consulta en la lista y en su detalle (#192). */
import { expect, type Page, test } from '@playwright/test'

import {
  ciudadUnica,
  comprarPoliza,
  consentimientoDeCotizacion,
  cotizarAuto,
  descargar,
  filaDePoliza,
  iniciarSesion,
  ofertaDe,
  polizaSinDocumento,
} from './apoyo'

const dato = (page: Page, etiqueta: string) =>
  page.locator('dt', { hasText: etiqueta }).locator('xpath=following-sibling::dd[1]')

test.describe('Consulta de pólizas (WEB-F04)', () => {
  test.beforeEach(async ({ page }) => iniciarSesion(page))

  test('la póliza recién emitida se encuentra, conserva la oferta y descarga su documento', async ({ page }) => {
    test.slow()
    await consentimientoDeCotizacion(page, false)
    await cotizarAuto(page, ciudadUnica())
    const { cotizacion, numero } = await comprarPoliza(page)
    const oferta = await ofertaDe(page, cotizacion)

    await page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link', { name: 'Mis pólizas' }).click()
    await expect(page.getByRole('tabpanel').getByRole('rowheader').first()).toHaveText(numero)
    const fila = filaDePoliza(page, numero)
    await expect(fila.getByText(oferta.nombre_producto)).toBeVisible()
    await expect(fila.getByText(/^\d+ de .+ – \d+ de .+$/)).toBeVisible()
    await expect(fila.getByText('Vigente')).toBeVisible()

    await fila.getByRole('link', { name: `Ver detalle de la póliza ${numero}` }).click()
    await expect(page.getByRole('heading', { level: 1, name: oferta.nombre_producto })).toBeVisible()
    await expect(page.getByText(numero, { exact: true })).toBeVisible()
    await expect(dato(page, 'Valor asegurado')).toHaveText(oferta.valor_asegurado)
    await expect(dato(page, 'Deducible')).toHaveText(oferta.deducible)
    expect(oferta.coberturas.length).toBeGreaterThan(0)
    for (const { nombre, limite } of oferta.coberturas) {
      await expect(page.getByRole('row').filter({ hasText: nombre }).getByRole('cell', { name: limite, exact: true })).toBeVisible()
    }

    const documento = await descargar(page, 'Descargar PDF')
    expect(documento.nombre).toBe(`${numero}.pdf`)
    expect(documento.contenido.subarray(0, 5).toString()).toBe('%PDF-')
  })

  test('una póliza sin documento lo explica y no ofrece la descarga', async ({ page }) => {
    const numero = await polizaSinDocumento(page)

    await page.goto(`/polizas/${encodeURIComponent(numero)}`)

    await expect(page.getByText('El documento de esta póliza no está disponible.')).toBeVisible()
    await expect(page.getByRole('link', { name: 'Descargar PDF' })).toHaveCount(0)
  })
})
