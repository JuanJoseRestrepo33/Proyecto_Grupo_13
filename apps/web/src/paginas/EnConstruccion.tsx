import { useTranslation } from 'react-i18next'

/** Pantalla provisional: se reemplaza en la tarea "UI consentimiento". */
export function EnConstruccion({ seccion }: { seccion: 'consentimientos' }) {
  const { t } = useTranslation()
  return (
    <>
      <h1>{t(`${seccion}.titulo`)}</h1>
      <p>{t(`${seccion}.enConstruccion`)}</p>
    </>
  )
}
