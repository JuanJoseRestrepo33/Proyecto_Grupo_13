/**
 * Clave del intento de pago (`Idempotency-Key`, P18). Un mismo intento conserva su clave aunque el
 * cliente reintente, pierda la respuesta o recargue la página: así el BFF reconoce el reintento y
 * no cobra dos veces. Solo un intento nuevo (después de un rechazo) lleva una clave nueva.
 *
 * Se guarda en sessionStorage para sobrevivir a una recarga; si no está disponible, en memoria,
 * que basta para los reintentos dentro de la misma pantalla.
 */
const enMemoria = new Map<string, string>()
const llave = (suscripcionId: string) => `solventa_pago_${suscripcionId}`

function leer(suscripcionId: string): string | undefined {
  try {
    return sessionStorage.getItem(llave(suscripcionId)) ?? undefined
  } catch {
    // Almacenamiento bloqueado: se usa la copia en memoria.
    return enMemoria.get(suscripcionId)
  }
}

/** Clave del intento en curso; la crea si aún no hay ninguno. */
export function claveDelIntento(suscripcionId: string): string {
  return leer(suscripcionId) ?? nuevaClave(suscripcionId)
}

/** Empieza un intento nuevo, con su propia clave. */
export function nuevaClave(suscripcionId: string): string {
  const clave = crypto.randomUUID()
  enMemoria.set(suscripcionId, clave)
  try {
    sessionStorage.setItem(llave(suscripcionId), clave)
  } catch {
    // Almacenamiento bloqueado: queda la copia en memoria.
  }
  return clave
}
