/** MOB-F05 de punta a punta: evidencia de un siniestro reportado, válida e inválida (#178). */
import { expect, test } from '@playwright/test'

import { evidenciasDe, iniciarSesion, reportarSiniestro } from './apoyo'

/** PNG real de 1×1: el BFF comprueba el formato con el contenido del archivo, no con su extensión. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  'base64',
)

test.describe('Evidencia del siniestro (MOB-F05)', () => {
  test.beforeEach(async ({ page }) => iniciarSesion(page))

  test('un archivo admitido queda en la evidencia del siniestro reportado (criterio 5)', async ({ page }) => {
    const siniestro = await reportarSiniestro(page, `E2E-${Date.now()}`)
    await page.goto(`/siniestros/evidencias?siniestro=${encodeURIComponent(siniestro.id)}`)
    await expect(page.getByRole('heading', { level: 2, name: `Evidencias de ${siniestro.referencia}` })).toBeVisible()

    await page
      .getByLabel('Adjuntar fotos, videos o documentos')
      .setInputFiles({ name: 'foto-danos.png', mimeType: 'image/png', buffer: PNG })

    await expect(page.getByText('foto-danos.png: adjuntada.')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Evidencias adjuntas' })).toContainText('foto-danos.png')
    expect(await evidenciasDe(page, siniestro.id)).toEqual(['foto-danos.png'])
  })

  test('un formato no admitido o un archivo de más de 10 MB se rechazan antes de enviar, con el límite (criterio 4)', async ({
    page,
  }) => {
    const siniestro = await reportarSiniestro(page, `E2E-${Date.now()}`)
    await page.goto(`/siniestros/evidencias?siniestro=${encodeURIComponent(siniestro.id)}`)
    await expect(page.getByRole('heading', { level: 2, name: `Evidencias de ${siniestro.referencia}` })).toBeVisible()
    const cargas: string[] = []
    page.on('request', (solicitud) => {
      if (solicitud.method() === 'POST' && solicitud.url().includes('/evidencias')) cargas.push(solicitud.url())
    })

    // setInputFiles no aplica el filtro `accept` del selector, igual que "Todos los archivos" del navegador.
    await page.getByLabel('Adjuntar fotos, videos o documentos').setInputFiles([
      { name: 'animacion.gif', mimeType: 'image/gif', buffer: Buffer.from('GIF89a') },
      { name: 'video-largo.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(12 * 1024 * 1024) },
    ])

    const alerta = page.getByRole('alert')
    await expect(alerta).toContainText('animacion.gif: este formato no se admite. Usa JPEG, PNG, HEIC, MP4 o PDF.')
    await expect(alerta).toContainText(/video-largo\.mp4: pesa 12\s?MB y el máximo es 10\s?MB\./)
    expect(cargas).toEqual([])
    expect(await evidenciasDe(page, siniestro.id)).toEqual([])
  })
})
