import './estilos/global.css'
import './i18n'

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'

import { App } from './App'

/**
 * Con VITE_MOCKS=true el BFF se simula en el navegador según el contrato, solo en desarrollo.
 * La condición usa import.meta.env.DEV directamente para que Vite elimine los mocks de cualquier
 * build de producción, aunque VITE_MOCKS esté en true (el worker no se publica en producción).
 */
async function iniciarMocks(): Promise<void> {
  if (!import.meta.env.DEV || import.meta.env.VITE_MOCKS !== 'true') return
  const { trabajador } = await import('./mocks/navegador')
  await trabajador.start({ onUnhandledRequest: 'bypass' })
}

function montar(): void {
  createRoot(document.getElementById('raiz')!).render(
    <StrictMode>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </StrictMode>,
  )
}

// Si los mocks no arrancan, la app se monta igual y muestra sus estados de error en vez de quedar en blanco.
void iniciarMocks()
  .catch((error: unknown) => console.error('No se pudieron iniciar los mocks del BFF', error))
  .then(montar)
