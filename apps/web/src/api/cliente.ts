import createClient from 'openapi-fetch'

import type { paths } from './esquema'
import type { ErrorApi } from './tipos'

/** Prefijo del BFF web, igual al `servers.url` del contrato. */
export const PREFIJO_BFF = '/api/v1/web'

/** Código del 401 de toda ruta protegida: falta la cookie, venció o su firma no es válida. */
export const SESION_NO_VIGENTE = 'sesion_no_vigente'

const alVencer = new Set<() => void>()

/** Sube con cada inicio de sesión; cada solicitud recuerda con cuál salió. */
let sesionActual = 0
const sesionDeSolicitud = new Map<string, number>()

/**
 * Avisa cuando el BFF responde 401 `sesion_no_vigente` a cualquier llamada, para llevar al cliente
 * a iniciar sesión de nuevo (WEB-F13, criterio 4). Devuelve la función que cancela el aviso.
 */
export function alVencerSesion(escucha: () => void): () => void {
  alVencer.add(escucha)
  return () => alVencer.delete(escucha)
}

/**
 * Se llama al iniciar sesión. Una solicitud que salió antes y responde 401 después ya no avisa:
 * hablaba de la sesión anterior, no de la nueva.
 */
export function marcarSesionNueva(): void {
  sesionActual += 1
}

/**
 * Cliente tipado del BFF web. Cada ruta, parámetro y respuesta sale del contrato
 * (contratos/bff-web/openapi.yaml), así que un cambio incompatible rompe la compilación.
 */
export function crearClienteBff(origen = import.meta.env.VITE_BFF_URL || globalThis.location?.origin) {
  const cliente = createClient<paths>({
    baseUrl: `${origen ?? ''}${PREFIJO_BFF}`,
    credentials: 'include',
    // Se resuelve fetch en cada llamada (no al crear el cliente) para que los mocks lo intercepten.
    fetch: (solicitud) => globalThis.fetch(solicitud),
  })
  cliente.use({
    onRequest({ id }) {
      sesionDeSolicitud.set(id, sesionActual)
    },
    async onResponse({ id, response }) {
      const sesion = sesionDeSolicitud.get(id)
      sesionDeSolicitud.delete(id)
      if (response.status !== 401 || sesion !== sesionActual) return
      // Se lee una copia: la pantalla que hizo la llamada sigue recibiendo la respuesta intacta.
      const cuerpo = (await response.clone().json().catch(() => null)) as ErrorApi | null
      if (cuerpo?.codigo === SESION_NO_VIGENTE) alVencer.forEach((escucha) => escucha())
    },
    onError({ id }) {
      sesionDeSolicitud.delete(id)
    },
  })
  return cliente
}

export const bff = crearClienteBff()
