/**
 * Pruebas E2E de la web (Playwright) contra un ambiente con el BFF y el núcleo reales: staging en el
 * job `e2e` de CD, o un ambiente local. Nunca contra los mocks.
 *
 *   E2E_URL_WEB        Origen de la web (por defecto, el servidor de desarrollo local).
 *   E2E_CORREO/CLAVE   Cuenta de prueba del ambiente (en CD, HUMO_CORREO y HUMO_CLAVE).
 *   E2E_OPEN_FINANCE   Modo del proveedor simulado en el núcleo del ambiente: `sano` (por defecto)
 *                      o `caido`. Cada flujo de cotización corre solo en el modo que le corresponde.
 */
import { defineConfig, devices } from '@playwright/test'

const enCI = Boolean(process.env.CI)

export default defineConfig({
  testDir: './e2e',
  // Todas las pruebas comparten la cuenta de prueba del ambiente: van una a la vez.
  fullyParallel: false,
  workers: 1,
  forbidOnly: enCI,
  retries: enCI ? 1 : 0,
  // En CI no hay reporte HTML ni trazas: registran lo que se escribe en cada campo, incluida la
  // clave de la cuenta de prueba, y quedarían en el artefacto del job. GitHub oculta los secretos
  // en los registros, no en los artefactos. Las capturas sí sirven: el campo de clave sale oculto.
  reporter: enCI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: process.env.E2E_URL_WEB ?? 'http://localhost:5173',
    locale: 'es-CO',
    timezoneId: 'America/Bogota',
    trace: enCI ? 'off' : 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
