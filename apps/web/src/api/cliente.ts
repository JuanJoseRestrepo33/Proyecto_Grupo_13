import createClient from 'openapi-fetch'

import type { paths } from './esquema'

/** Prefijo del BFF web, igual al `servers.url` del contrato. */
export const PREFIJO_BFF = '/api/v1/web'

/**
 * Cliente tipado del BFF web. Cada ruta, parámetro y respuesta sale del contrato
 * (contratos/bff-web/openapi.yaml), así que un cambio incompatible rompe la compilación.
 */
export function crearClienteBff(origen = import.meta.env.VITE_BFF_URL || globalThis.location?.origin) {
  return createClient<paths>({
    baseUrl: `${origen ?? ''}${PREFIJO_BFF}`,
    credentials: 'include',
    // Se resuelve fetch en cada llamada (no al crear el cliente) para que los mocks lo intercepten.
    fetch: (solicitud) => globalThis.fetch(solicitud),
  })
}

export const bff = crearClienteBff()
