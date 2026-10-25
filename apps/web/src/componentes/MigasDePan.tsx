import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import estilos from './MigasDePan.module.css'

export interface Miga {
  /** Clave de i18n del texto. */
  clave: string
  /** Ruta; la última miga (la pantalla actual) no la lleva. */
  a?: string
}

/** Migas de pan (P02): dónde está el cliente y cómo volver. La última es la pantalla actual. */
export function MigasDePan({ migas }: { migas: Miga[] }) {
  const { t } = useTranslation()
  return (
    <nav className={estilos.migas} aria-label={t('app.migasDePan')}>
      <ol>
        {migas.map(({ clave, a }) => (
          <li key={clave}>
            {a ? (
              <Link to={a}>{t(clave)}</Link>
            ) : (
              <span aria-current="page">{t(clave)}</span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  )
}
