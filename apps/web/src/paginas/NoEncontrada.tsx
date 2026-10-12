import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

export function NoEncontrada() {
  const { t } = useTranslation()
  return (
    <>
      <h1>{t('noEncontrada.titulo')}</h1>
      <Link to="/">{t('noEncontrada.volver')}</Link>
    </>
  )
}
