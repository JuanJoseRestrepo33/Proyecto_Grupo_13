/** WEB-F01 de punta a punta: inicio de sesión → cotizar, contra el BFF y el núcleo reales (#166). */
import { expect, test } from '@playwright/test'

import { consentimientoDeCotizacion, cotizarAuto, iniciarSesion, MODO_OPEN_FINANCE } from './apoyo'

test.describe('Cotización (WEB-F01)', () => {
  test.beforeEach(async ({ page }) => iniciarSesion(page))

  test('con consentimiento vigente: prima personalizada con su explicación (criterio 1)', async ({ page }) => {
    test.skip(MODO_OPEN_FINANCE !== 'sano', 'Con Open Finance caído, la prima con consentimiento es la estándar.')
    await consentimientoDeCotizacion(page, true)
    await cotizarAuto(page)

    await expect(page.getByText('Precio calculado con tu perfil de Open Finance.')).toBeVisible()
    await expect(page.getByText(/^Ahorras .+ al mes frente al precio estándar/)).toBeVisible()
    await page.getByRole('button', { name: '¿Por qué este precio?' }).click()
    await expect(page.getByRole('table', { name: 'Cómo se calculó tu prima' })).toBeVisible()
    await expect(page.getByText(/^Calculado con la regla .+\.$/)).toBeVisible()
  })

  test('sin consentimiento: tarifa estándar explicada, sin presentarla como error (criterio 2)', async ({ page }) => {
    await consentimientoDeCotizacion(page, false)
    await cotizarAuto(page)

    await expect(page.getByText('Precio estándar.')).toBeVisible()
    await expect(page.getByText(/no tienes un consentimiento vigente para cotización/)).toBeVisible()
    await expect(page.getByRole('link', { name: 'Otorgar consentimiento' })).toBeVisible()
    await expect(page.getByText(/^Ahorras /)).toHaveCount(0)
    await expect(page.getByRole('alert')).toHaveCount(0)
  })

  // Parte B de WEB-F01 (#123, criterio 3): en staging se activa cuando el núcleo permita poner el
  // proveedor simulado en `caido` sin redesplegarlo. Mientras tanto corre contra un núcleo local.
  test('con Open Finance caído: tarifa estándar con el motivo, sin culpar al consentimiento (criterio 3)', async ({
    page,
  }) => {
    test.skip(
      MODO_OPEN_FINANCE !== 'caido',
      'Parte B de WEB-F01: requiere el núcleo con Open Finance caído (E2E_OPEN_FINANCE=caido).',
    )
    await consentimientoDeCotizacion(page, true)
    await cotizarAuto(page)

    await expect(page.getByText(/Tu entidad financiera no respondió y no teníamos tu perfil guardado/)).toBeVisible()
    await expect(page.getByText(/no tienes un consentimiento vigente/)).toHaveCount(0)
    await expect(page.getByRole('alert')).toHaveCount(0)
  })
})
