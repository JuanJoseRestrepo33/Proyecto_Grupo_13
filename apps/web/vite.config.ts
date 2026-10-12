/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
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
