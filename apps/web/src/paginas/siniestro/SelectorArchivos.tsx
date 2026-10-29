import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { Aviso } from '../../componentes/Aviso'
import type { Idioma } from '../../i18n'
import { ACEPTA, formatearTamano, type Rechazo, revisarArchivo, TAMANO_MAXIMO } from './evidencias'
import estilos from './Siniestro.module.css'

interface Propiedades {
  /** Los archivos que se pueden enviar; los demás se explican aquí mismo. */
  onSeleccionar: (archivos: File[]) => void
  deshabilitado?: boolean
}

/**
 * Selector de evidencias. Un archivo de formato no admitido, vacío o de más de 10 MB se rechaza
 * antes de enviarlo, con el límite explicado (MOB-F05, criterio 4).
 */
export function SelectorArchivos({ onSeleccionar, deshabilitado }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const id = useId()
  const [rechazados, setRechazados] = useState<{ nombre: string; rechazo: Rechazo; tamano: string }[]>([])
  const limite = formatearTamano(TAMANO_MAXIMO, idioma)

  function elegir(lista: FileList | null) {
    const archivos = [...(lista ?? [])]
    const revisados = archivos.map((archivo) => ({ archivo, rechazo: revisarArchivo(archivo) }))
    setRechazados(
      revisados
        .filter(({ rechazo }) => rechazo)
        .map(({ archivo, rechazo }) => ({
          nombre: archivo.name,
          rechazo: rechazo!,
          tamano: formatearTamano(archivo.size, idioma),
        })),
    )
    const validos = revisados.filter(({ rechazo }) => !rechazo).map(({ archivo }) => archivo)
    if (validos.length > 0) onSeleccionar(validos)
  }

  return (
    <div className={estilos.campo}>
      <label htmlFor={id}>{t('siniestro.evidencia.elegir')}</label>
      <input
        id={id}
        type="file"
        multiple
        accept={ACEPTA}
        disabled={deshabilitado}
        aria-describedby={`${id}-ayuda`}
        onChange={(evento) => {
          elegir(evento.target.files)
          // Se vacía para poder volver a elegir el mismo archivo después de quitarlo.
          evento.target.value = ''
        }}
      />
      <p id={`${id}-ayuda`} className={estilos.ayuda}>
        {t('siniestro.evidencia.limites', { limite })}
      </p>
      {rechazados.length > 0 && (
        <Aviso tipo="error" rol="alert">
          <ul className={estilos.listaSimple}>
            {rechazados.map(({ nombre, rechazo, tamano }) => (
              <li key={nombre}>{t(`siniestro.evidencia.rechazo.${rechazo}`, { nombre, tamano, limite })}</li>
            ))}
          </ul>
        </Aviso>
      )}
    </div>
  )
}
