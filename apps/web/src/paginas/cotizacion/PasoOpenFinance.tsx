import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import type { Consentimiento } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { formatearFecha } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Cotizacion.module.css'

interface Propiedades {
  consentimiento: Consentimiento | undefined
  usarOpenFinance: boolean
  onCambiar: (usar: boolean) => void
}

export function PasoOpenFinance({ consentimiento, usarOpenFinance, onCambiar }: Propiedades) {
  const { t, i18n } = useTranslation()

  return (
    <div className={estilos.pila}>
      <div className={estilos.interruptor}>
        <input
          id="usarOpenFinance"
          type="checkbox"
          role="switch"
          checked={Boolean(consentimiento) && usarOpenFinance}
          disabled={!consentimiento}
          aria-describedby="usarOpenFinance-ayuda"
          onChange={(evento) => onCambiar(evento.target.checked)}
        />
        <div>
          <label htmlFor="usarOpenFinance">
            <strong>{t('cotizacion.openFinance.usar')}</strong>
          </label>
          <p id="usarOpenFinance-ayuda" className={estilos.ayuda}>
            {t('cotizacion.openFinance.usarAyuda')}
          </p>
        </div>
      </div>

      {consentimiento ? (
        <Aviso tipo="exito">
          <p>
            {t('cotizacion.openFinance.vigente', {
              fecha: formatearFecha(consentimiento.vence_en, i18n.language as Idioma),
            })}
          </p>
        </Aviso>
      ) : (
        <Aviso tipo="advertencia">
          <p>
            <strong>{t('cotizacion.openFinance.sinConsentimiento')}</strong>{' '}
            {t('cotizacion.openFinance.sinConsentimientoDetalle')}{' '}
            <Link to="/consentimientos">{t('cotizacion.openFinance.otorgar')}</Link>
          </p>
        </Aviso>
      )}

      <Aviso tipo="info">
        <p>{t('cotizacion.openFinance.openData')}</p>
      </Aviso>
    </div>
  )
}
