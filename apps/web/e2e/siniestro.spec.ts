/** WEB-F05 de punta a punta: reporte de siniestro en tres pasos y doble envío (#171). */
import { expect, test } from '@playwright/test'

import { iniciarSesion, llenarReporteHastaRevision, siniestrosConMarca } from './apoyo'

/** Marca única por prueba: los siniestros de la cuenta de prueba se acumulan entre ejecuciones. */
const marca = () => `E2E-${Date.now()}`

test.describe('Reporte de siniestro (WEB-F05)', () => {
  test.beforeEach(async ({ page }) => iniciarSesion(page))

  test('en tres pasos sobre una póliza vigente, con referencia, estado y siguiente paso (criterio 1)', async ({
    page,
  }) => {
    const id = marca()
    await llenarReporteHastaRevision(page, `Choque leve en un parqueadero (${id}).`)
    await expect(page.getByText(`Choque leve en un parqueadero (${id}).`)).toBeVisible()

    await page.getByRole('button', { name: 'Enviar reporte' }).click()

    const confirmacion = page.getByRole('region', { name: 'Reporte enviado' })
    await expect(confirmacion).toBeVisible()
    const referencia = confirmacion.getByText(/^SIN-\d{4}-\d+$/)
    await expect(referencia).toBeVisible()
    await expect(confirmacion).toContainText('Reportado')
    await expect(confirmacion).toContainText(/Adjunta las fotos|Estamos evaluando tu caso/)

    const reportados = await siniestrosConMarca(page, id)
    expect(reportados.map((s) => s.referencia)).toEqual([await referencia.textContent()])
  })

  test('un doble clic en "Enviar reporte" deja un solo siniestro con una sola referencia (criterio 4)', async ({
    page,
  }) => {
    const id = marca()
    await llenarReporteHastaRevision(page, `Rayón en la puerta trasera (${id}).`)

    await page.getByRole('button', { name: 'Enviar reporte' }).dblclick()

    const confirmacion = page.getByRole('region', { name: 'Reporte enviado' })
    await expect(confirmacion).toBeVisible()
    const referencia = await confirmacion.getByText(/^SIN-\d{4}-\d+$/).textContent()
    const reportados = await siniestrosConMarca(page, id)
    expect(reportados.map((s) => s.referencia)).toEqual([referencia])
  })
})
