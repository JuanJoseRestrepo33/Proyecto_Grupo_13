import { useTranslation } from 'react-i18next'

import { useSesion } from '../sesion/contexto'

/** Va dentro de la ruta protegida: solo se muestra con la sesión abierta. */
export function Inicio() {
  const { t } = useTranslation()
  const { estado } = useSesion()
  if (estado.tipo !== 'autenticada') return null

  return (
    <>
      <h1>{t('inicio.saludo', { nombre: estado.sesion.cliente.nombre })}</h1>
      <p>{t('inicio.descripcion')}</p>
    </>
  )
}
