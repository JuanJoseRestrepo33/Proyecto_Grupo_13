import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, useLocation } from 'react-router'

import { Boton } from '../componentes/Boton'
import { useSesion } from './contexto'

/** Lo que el inicio de sesión recibe en `location.state`: a dónde volver al entrar. */
export interface DestinoTrasIngreso {
  desde?: string
}

/**
 * Sin sesión, lleva al inicio de sesión y recuerda la página pedida para volver a ella al entrar.
 * Tras cerrar sesión no la recuerda: quien entre después empieza en el inicio.
 */
export function RutaProtegida() {
  const { t } = useTranslation()
  const { estado, reintentar } = useSesion()
  const { pathname, search } = useLocation()

  if (estado.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>
  if (estado.tipo === 'error') {
    return (
      <div role="alert">
        <p>{t('sesion.error')}</p>
        <Boton variante="secundario" onClick={reintentar}>
          {t('sesion.reintentar')}
        </Boton>
      </div>
    )
  }
  if (estado.tipo === 'anonima') {
    const destino: DestinoTrasIngreso = estado.motivo === 'cerrada' ? {} : { desde: `${pathname}${search}` }
    return <Navigate to="/ingresar" replace state={destino} />
  }
  return <Outlet />
}
