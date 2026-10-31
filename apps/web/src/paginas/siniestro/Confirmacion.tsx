import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import type { Siniestro } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import estilosTarjeta from '../cotizacion/Cotizacion.module.css'
import type { ResultadoCarga } from './evidencias'
import estilos from './Siniestro.module.css'

export interface ResultadoArchivo {
  archivo: File
  resultado: ResultadoCarga
}

interface Propiedades {
  siniestro: Siniestro
  /** Lo que pasó con cada evidencia elegida en el reporte. */
  resultados: ResultadoArchivo[]
  onReportarOtro: () => void
}

/** El reporte quedó registrado: referencia, estado y siguiente paso del caso (criterio 1). */
export function Confirmacion({ siniestro, resultados, onReportarOtro }: Propiedades) {
  const { t } = useTranslation()
  const titulo = useRef<HTMLHeadingElement>(null)

  // El foco va al resultado: un lector de pantalla anuncia que el reporte se envió.
  useEffect(() => titulo.current?.focus(), [])

  return (
    <section className={`${estilosTarjeta.tarjeta} ${estilosTarjeta.pila}`} aria-labelledby="titulo-confirmacion">
      <h2 id="titulo-confirmacion" ref={titulo} tabIndex={-1}>
        {t('siniestro.confirmacion.titulo')}
      </h2>
      <Aviso tipo="exito">
        <p>{t('siniestro.confirmacion.detalle')}</p>
      </Aviso>
      <dl className={estilos.resumen}>
        <dt>{t('siniestro.confirmacion.referencia')}</dt>
        <dd className={estilos.referencia}>{siniestro.referencia}</dd>
        <dt>{t('siniestro.confirmacion.estado')}</dt>
        <dd>{t(`siniestro.estados.${siniestro.estado}`)}</dd>
        <dt>{t('siniestro.confirmacion.siguientePaso')}</dt>
        <dd>{t(`siniestro.siguientePaso.${siniestro.siguiente_paso}`)}</dd>
      </dl>
      {resultados.length > 0 && (
        <div>
          <h3>{t('siniestro.confirmacion.evidencias')}</h3>
          <ul className={estilos.listaSimple}>
            {resultados.map(({ archivo, resultado }, indice) => (
              <li key={`${archivo.name}-${indice}`}>
                {resultado.ok
                  ? t('siniestro.confirmacion.adjuntada', { nombre: archivo.name })
                  : t(`siniestro.evidencia.fallo.${resultado.fallo}`, { nombre: archivo.name })}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className={estilos.acciones}>
        <Link className={clasesBoton('primario')} to={`/siniestros/evidencias?siniestro=${encodeURIComponent(siniestro.id)}`}>
          {t('siniestro.confirmacion.agregarEvidencia')}
        </Link>
        <Link className={clasesBoton('secundario')} to="/">
          {t('siniestro.confirmacion.inicio')}
        </Link>
        <Boton variante="secundario" onClick={onReportarOtro}>
          {t('siniestro.confirmacion.otro')}
        </Boton>
      </div>
    </section>
  )
}
