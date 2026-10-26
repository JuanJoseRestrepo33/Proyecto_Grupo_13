import { type FormEvent, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation } from 'react-router'

import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { useSesion } from '../../sesion/contexto'
import type { DestinoTrasIngreso } from '../../sesion/RutaProtegida'
import estilos from './InicioSesion.module.css'
import { type CamposIngreso, type ErroresIngreso, validarIngreso } from './validacion'

const MENSAJE_FALLO = { credenciales: 'sesion.errores.credenciales', servicio: 'sesion.errores.servicio' } as const

/** Correo y contraseña de la misma cuenta de la app (WEB-F13, criterio 1). */
export function InicioSesion() {
  const { t } = useTranslation()
  const { estado, iniciar } = useSesion()
  const { state } = useLocation() as { state: DestinoTrasIngreso | null }
  const [campos, setCampos] = useState<CamposIngreso>({ correo: '', contrasena: '' })
  const [errores, setErrores] = useState<ErroresIngreso>({})
  const [fallo, setFallo] = useState<keyof typeof MENSAJE_FALLO | null>(null)
  const [enviando, setEnviando] = useState(false)
  const contrasena = useRef<HTMLInputElement>(null)

  if (estado.tipo === 'autenticada') return <Navigate to={state?.desde ?? '/'} replace />

  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault()
    if (enviando) return
    const encontrados = validarIngreso(campos)
    setErrores(encontrados)
    setFallo(null)
    const primero = (['correo', 'contrasena'] as const).find((campo) => encontrados[campo])
    if (primero) {
      document.getElementById(primero)?.focus()
      return
    }
    setEnviando(true)
    const resultado = await iniciar({ correo: campos.correo.trim(), contrasena: campos.contrasena })
    // Con la sesión iniciada este componente se reemplaza por la redirección.
    if (resultado === 'iniciada') return
    setEnviando(false)
    setFallo(resultado)
    if (resultado === 'credenciales') {
      setCampos((actuales) => ({ ...actuales, contrasena: '' }))
      contrasena.current?.focus()
    }
  }

  const aviso = estado.tipo === 'anonima' ? estado.motivo : undefined
  const descripcion = (campo: keyof CamposIngreso) => (errores[campo] ? `${campo}-error` : undefined)

  return (
    <div className={estilos.contenedor}>
      <h1>{t('sesion.titulo')}</h1>
      <p className={estilos.descripcion}>{t('sesion.descripcion')}</p>

      {aviso && (
        <Aviso tipo="info" rol="status">
          {t(`sesion.avisos.${aviso}`)}
        </Aviso>
      )}

      <form className={estilos.tarjeta} noValidate onSubmit={(evento) => void enviar(evento)}>
        {fallo && (
          <Aviso tipo="error" rol="alert">
            {t(MENSAJE_FALLO[fallo])}
          </Aviso>
        )}

        <div className={estilos.campo}>
          <label htmlFor="correo">{t('sesion.correo')}</label>
          <input
            id="correo"
            type="email"
            autoComplete="username"
            inputMode="email"
            required
            value={campos.correo}
            aria-invalid={Boolean(errores.correo)}
            aria-describedby={descripcion('correo')}
            onChange={(evento) => setCampos((actuales) => ({ ...actuales, correo: evento.target.value }))}
          />
          {errores.correo && (
            <p id="correo-error" className={estilos.error}>
              {t(errores.correo)}
            </p>
          )}
        </div>

        <div className={estilos.campo}>
          <label htmlFor="contrasena">{t('sesion.contrasena')}</label>
          <input
            ref={contrasena}
            id="contrasena"
            type="password"
            autoComplete="current-password"
            required
            value={campos.contrasena}
            aria-invalid={Boolean(errores.contrasena)}
            aria-describedby={descripcion('contrasena')}
            onChange={(evento) => setCampos((actuales) => ({ ...actuales, contrasena: evento.target.value }))}
          />
          {errores.contrasena && (
            <p id="contrasena-error" className={estilos.error}>
              {t(errores.contrasena)}
            </p>
          )}
        </div>

        <Boton type="submit" disabled={enviando}>
          {enviando ? t('sesion.enviando') : t('sesion.boton')}
        </Boton>
      </form>
    </div>
  )
}
