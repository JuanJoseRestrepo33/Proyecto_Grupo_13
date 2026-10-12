import { setupServer } from 'msw/node'

import { manejadores } from './manejadores'

/** Servidor de mocks para las pruebas (Node). */
export const servidor = setupServer(...manejadores)
