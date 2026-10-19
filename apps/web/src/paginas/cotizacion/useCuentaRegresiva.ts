import { useEffect, useState } from 'react'

function restantes(hasta: string): number {
  return Math.max(0, Math.ceil((new Date(hasta).getTime() - Date.now()) / 1000))
}

/**
 * Segundos que faltan para `hasta`, actualizados cada segundo hasta llegar a cero.
 * Quien lo usa remonta el componente (con `key`) cuando cambia la oferta.
 */
export function useCuentaRegresiva(hasta: string): number {
  const [segundos, setSegundos] = useState(() => restantes(hasta))

  useEffect(() => {
    const intervalo = setInterval(() => {
      const actuales = restantes(hasta)
      setSegundos(actuales)
      if (actuales === 0) clearInterval(intervalo)
    }, 1000)
    return () => clearInterval(intervalo)
  }, [hasta])

  return segundos
}
