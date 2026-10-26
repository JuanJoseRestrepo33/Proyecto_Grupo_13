import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, Outlet } from 'react-router'

import { cambiarIdioma, IDIOMAS, type Idioma } from '../i18n'
import { useSesion } from '../sesion/contexto'
import { Boton } from './Boton'
import estilos from './Plantilla.module.css'

const ENLACES = [
  { a: '/', clave: 'nav.inicio' },
  { a: '/cotizacion', clave: 'nav.cotizar' },
  { a: '/consentimientos', clave: 'nav.consentimientos' },
] as const

/**
 * Estructura común de todas las pantallas: salto al contenido, encabezado, navegación, idioma y
 * cuenta. La navegación y el cierre de sesión solo aparecen con la sesión abierta.
 */
export function Plantilla() {
  const { t, i18n } = useTranslation()
  const { estado, cerrar } = useSesion()
  const [cerrando, setCerrando] = useState(false)
  const [falloAlCerrar, setFalloAlCerrar] = useState(false)
  const autenticada = estado.tipo === 'autenticada'

  async function salir() {
    setCerrando(true)
    setFalloAlCerrar(false)
    const cerrada = await cerrar()
    setCerrando(false)
    setFalloAlCerrar(!cerrada)
  }

  return (
    <>
      <a className={estilos.saltar} href="#contenido">
        {t('app.saltarContenido')}
      </a>
      <header className={estilos.encabezado}>
        <Link className={estilos.marca} to="/">
          {t('app.nombre')}
        </Link>
        {autenticada && (
          <nav className={estilos.navegacion} aria-label={t('app.navegacionPrincipal')}>
            <ul>
              {ENLACES.map(({ a, clave }) => (
                <li key={a}>
                  <NavLink className={estilos.enlace} to={a} end>
                    {t(clave)}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
        )}
        <fieldset className={estilos.idiomas}>
          <legend className="sr-only">{t('app.idioma')}</legend>
          {(Object.keys(IDIOMAS) as Idioma[]).map((idioma) => (
            <button
              key={idioma}
              type="button"
              className={estilos.idioma}
              aria-pressed={i18n.language === idioma}
              lang={idioma}
              onClick={() => void cambiarIdioma(idioma)}
            >
              {idioma.toUpperCase()}
            </button>
          ))}
        </fieldset>
        {autenticada && (
          <div className={estilos.cuenta}>
            <span>{estado.sesion.cliente.nombre}</span>
            <Boton variante="secundario" disabled={cerrando} onClick={() => void salir()}>
              {cerrando ? t('sesion.cerrando') : t('sesion.cerrar')}
            </Boton>
          </div>
        )}
        {falloAlCerrar && (
          <p role="alert" className={estilos.errorCuenta}>
            {t('sesion.errores.cerrar')}
          </p>
        )}
      </header>
      <main id="contenido" className={estilos.contenido} tabIndex={-1}>
        <Outlet />
      </main>
    </>
  )
}
