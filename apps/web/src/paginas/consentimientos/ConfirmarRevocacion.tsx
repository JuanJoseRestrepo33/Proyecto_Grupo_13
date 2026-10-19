import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'

import type { Finalidad } from '../../api/tipos'
import { Boton } from '../../componentes/Boton'
import estilos from './Consentimientos.module.css'

interface Propiedades {
  finalidad: Finalidad
  enviando: boolean
  onConfirmar: () => void
  onCancelar: () => void
}

/** Explica la consecuencia antes de revocar y pide una confirmación explícita (WEB-F02, criterio 9). */
export function ConfirmarRevocacion({ finalidad, enviando, onConfirmar, onCancelar }: Propiedades) {
  const { t } = useTranslation()
  const titulo = useRef<HTMLHeadingElement>(null)
  const idTitulo = `${finalidad.codigo}-confirmar-titulo`

  useEffect(() => titulo.current?.focus(), [])

  return (
    <div className={estilos.confirmacion} role="group" aria-labelledby={idTitulo}>
      <h3 id={idTitulo} ref={titulo} tabIndex={-1}>
        {t('consentimientos.revocar.titulo', { finalidad: finalidad.nombre })}
      </h3>
      <p>{t(`consentimientos.revocar.consecuencia.${finalidad.codigo}`)}</p>
      <p>{t('consentimientos.revocar.propagacion')}</p>
      <div className={estilos.acciones}>
        <Boton onClick={onConfirmar} disabled={enviando}>
          {enviando ? t('consentimientos.revocar.revocando') : t('consentimientos.revocar.confirmar')}
        </Boton>
        <Boton variante="fantasma" onClick={onCancelar} disabled={enviando}>
          {t('consentimientos.revocar.cancelar')}
        </Boton>
      </div>
    </div>
  )
}
