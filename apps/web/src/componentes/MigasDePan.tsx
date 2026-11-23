import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import estilos from './MigasDePan.module.css'

export interface Miga {
  /** Clave de i18n del texto. */
  clave: string
  /** Ruta; la última miga (la pantalla actual) no la lleva. */
  a?: string
  /** Texto que no se traduce, como el número de una póliza; reemplaza al de `clave`. */
  texto?: string
}

/** Migas de pan (P02): dónde está el cliente y cómo volver. La última es la pantalla actual. */
export function MigasDePan({ migas }: { migas: Miga[] }) {
  const { t } = useTranslation()
  return (
    <nav className={estilos.migas} aria-label={t('app.migasDePan')}>
      <ol>
        {migas.map(({ clave, a, texto }) => (
          <li key={clave}>
            {a ? (
              <Link to={a}>{texto ?? t(clave)}</Link>
            ) : (
              <span aria-current="page">{texto ?? t(clave)}</span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  )
}
