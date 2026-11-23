/** WEB-F03 de punta a punta: inicio de sesión → cotizar → aceptar → pagar → firmar → consultar (#184). */
import { expect, test } from '@playwright/test'

import {
  ciudadUnica,
  comprarPoliza,
  consentimientoDeCotizacion,
  cotizarAuto,
  descargar,
  filaDePoliza,
  iniciarSesion,
  numeroDePolizaEmitida,
} from './apoyo'

test.describe('Compra de una póliza (WEB-F03)', () => {
  test('el cliente compra, recibe su documento y encuentra la póliza, sin emisión doble', async ({ page }) => {
    test.slow()
    await iniciarSesion(page)
    await consentimientoDeCotizacion(page, false)
    await cotizarAuto(page, ciudadUnica())

    const { cotizacion, numero } = await comprarPoliza(page)
    await expect(page.getByText(/^Del .+ al .+$/)).toBeVisible()

    const documento = await descargar(page, 'Descargar PDF')
    expect(documento.nombre).toBe(`${numero}.pdf`)
    expect(documento.contenido.subarray(0, 5).toString()).toBe('%PDF-')

    await page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link', { name: 'Mis pólizas' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Mis pólizas' })).toBeVisible()
    await expect(filaDePoliza(page, numero).getByText('Vigente')).toBeVisible()

    await page.goto(`/suscripcion/${cotizacion}`)
    await page.getByRole('button', { name: 'Aceptar y continuar al pago' }).click()
    await expect(page.getByText('Esta póliza ya estaba emitida. No se hizo otra firma ni otro cobro.')).toBeVisible()
    expect(await numeroDePolizaEmitida(page)).toBe(numero)

    await page.goto('/polizas')
    await page.getByRole('tab', { name: 'Ver todas' }).click()
    await expect(filaDePoliza(page, numero)).toHaveCount(1)
  })
})
