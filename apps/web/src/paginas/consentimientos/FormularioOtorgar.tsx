import { useState } from 'react'
import { useTranslation } from 'react-i18next'

import type { Finalidad } from '../../api/tipos'
import { Boton } from '../../componentes/Boton'
import estilos from './Consentimientos.module.css'

interface Propiedades {
  finalidad: Finalidad
  enviando: boolean
  onOtorgar: (entidades: string[]) => void
}

/** Ninguna casilla viene marcada: el consentimiento debe ser explícito (WEB-F02, criterio 1). */
export function FormularioOtorgar({ finalidad, enviando, onOtorgar }: Propiedades) {
  const { t } = useTranslation()
  const [entidades, setEntidades] = useState<string[]>([])
  const [autoriza, setAutoriza] = useState(false)
  const [errores, setErrores] = useState<{ entidades?: string; autoriza?: string }>({})
  const id = (sufijo: string) => `${finalidad.codigo}-${sufijo}`

  function alternar(entidad: string) {
    setEntidades((actuales) =>
      actuales.includes(entidad) ? actuales.filter((e) => e !== entidad) : [...actuales, entidad],
    )
  }

  function enviar() {
    const encontrados = {
      entidades: entidades.length === 0 ? t('consentimientos.otorgar.sinEntidades') : undefined,
      autoriza: !autoriza ? t('consentimientos.otorgar.sinAutorizacion') : undefined,
    }
    setErrores(encontrados)
    if (encontrados.entidades) {
      document.getElementById(id(`entidad-${finalidad.entidades_origen[0]?.id}`))?.focus()
      return
    }
    if (encontrados.autoriza) {
      document.getElementById(id('autoriza'))?.focus()
      return
    }
    onOtorgar(entidades)
  }

  return (
    <div className={estilos.formulario}>
      <fieldset aria-describedby={errores.entidades ? id('error-entidades') : undefined}>
        <legend>{t('consentimientos.otorgar.entidades')}</legend>
        {finalidad.entidades_origen.map((entidad) => (
          <div key={entidad.id} className={estilos.casilla}>
            <input
              id={id(`entidad-${entidad.id}`)}
              type="checkbox"
              checked={entidades.includes(entidad.id)}
              onChange={() => alternar(entidad.id)}
            />
            <label htmlFor={id(`entidad-${entidad.id}`)}>{entidad.nombre}</label>
          </div>
        ))}
        {errores.entidades && (
          <p id={id('error-entidades')} className={estilos.error}>
            {errores.entidades}
          </p>
        )}
      </fieldset>

      <div className={estilos.casilla}>
        <input
          id={id('autoriza')}
          type="checkbox"
          checked={autoriza}
          aria-invalid={Boolean(errores.autoriza)}
          aria-describedby={errores.autoriza ? id('error-autoriza') : undefined}
          onChange={(evento) => setAutoriza(evento.target.checked)}
        />
        <label htmlFor={id('autoriza')}>
          {t('consentimientos.otorgar.autorizo', { finalidad: finalidad.nombre, version: finalidad.version_texto })}
        </label>
      </div>
      {errores.autoriza && (
        <p id={id('error-autoriza')} className={estilos.error}>
          {errores.autoriza}
        </p>
      )}

      <div>
        <Boton onClick={enviar} disabled={enviando}>
          {enviando ? t('consentimientos.otorgar.enviando') : t('consentimientos.otorgar.boton')}
        </Boton>
      </div>
    </div>
  )
}
