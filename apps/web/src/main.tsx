import './estilos/global.css'
import './i18n'

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'

import { App } from './App'

/** Con VITE_MOCKS=true el BFF se simula en el navegador según el contrato. */
async function iniciarMocks(): Promise<void> {
  if (import.meta.env.VITE_MOCKS !== 'true') return
  const { trabajador } = await import('./mocks/navegador')
  await trabajador.start({ onUnhandledRequest: 'bypass' })
}

void iniciarMocks().then(() => {
  createRoot(document.getElementById('raiz')!).render(
    <StrictMode>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </StrictMode>,
  )
})
