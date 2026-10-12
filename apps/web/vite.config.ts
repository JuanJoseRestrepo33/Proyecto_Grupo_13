/// <reference types="vitest/config" />
import { rmSync } from 'node:fs'
import { join } from 'node:path'

import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

/** El worker de MSW solo sirve en desarrollo: se quita del build para no publicarlo en producción. */
function sinWorkerDeMocks(): Plugin {
  return {
    name: 'sin-worker-de-mocks',
    apply: 'build',
    writeBundle({ dir }) {
      if (dir) rmSync(join(dir, 'mockServiceWorker.js'), { force: true })
    },
  }
}

export default defineConfig({
  plugins: [react(), sinWorkerDeMocks()],
  server: { port: 5173 },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: { modules: { classNameStrategy: 'non-scoped' } },
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/api/esquema.ts', 'src/main.tsx', 'src/mocks/navegador.ts', 'src/**/*.test.{ts,tsx}', 'src/test/**'],
      reporter: ['text', 'cobertura', 'html'],
      thresholds: { lines: 70, functions: 70, branches: 70, statements: 70 },
    },
  },
})
