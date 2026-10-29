import { useTranslation } from 'react-i18next'

import { Boton } from '../../componentes/Boton'
import type { Idioma } from '../../i18n'
import { formatearTamano } from './evidencias'
import estilos from './Siniestro.module.css'

interface Propiedades {
  archivos: File[]
  onQuitar: (indice: number) => void
}

/** Archivos elegidos que aún no se han enviado: se pueden quitar sin tocar el servidor. */
export function ArchivosPendientes({ archivos, onQuitar }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  if (archivos.length === 0) return null

  return (
    <ul className={estilos.archivos} aria-label={t('siniestro.evidencia.elegidos')}>
      {archivos.map((archivo, indice) => (
        <li key={`${archivo.name}-${indice}`} className={estilos.archivo}>
          <span>
            {archivo.name} <small>({formatearTamano(archivo.size, idioma)})</small>
          </span>
          <Boton
            variante="fantasma"
            aria-label={t('siniestro.evidencia.quitarArchivo', { nombre: archivo.name })}
            onClick={() => onQuitar(indice)}
          >
            {t('siniestro.evidencia.quitar')}
          </Boton>
        </li>
      ))}
    </ul>
  )
}
