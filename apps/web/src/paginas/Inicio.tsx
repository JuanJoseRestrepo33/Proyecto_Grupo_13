import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { bff } from '../api/cliente'
import type { Sesion } from '../api/tipos'

type EstadoCarga = { tipo: 'cargando' } | { tipo: 'listo'; sesion: Sesion } | { tipo: 'error' }

export function Inicio() {
  const { t } = useTranslation()
  const [carga, setCarga] = useState<EstadoCarga>({ tipo: 'cargando' })

  useEffect(() => {
    let vigente = true
    bff
      .GET('/sesion')
      .then(({ data }) => {
        if (vigente) setCarga(data ? { tipo: 'listo', sesion: data } : { tipo: 'error' })
      })
      .catch(() => {
        if (vigente) setCarga({ tipo: 'error' })
      })
    return () => {
      vigente = false
    }
  }, [])

  if (carga.tipo === 'cargando') return <p role="status">{t('inicio.cargando')}</p>
  if (carga.tipo === 'error') return <p role="alert">{t('inicio.error')}</p>

  return (
    <>
      <h1>{t('inicio.saludo', { nombre: carga.sesion.cliente.nombre })}</h1>
      <p>{t('inicio.descripcion')}</p>
    </>
  )
}
