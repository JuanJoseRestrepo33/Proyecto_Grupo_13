import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import type { Siniestro } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import estilosTarjeta from '../cotizacion/Cotizacion.module.css'
import estilos from './Siniestro.module.css'

interface Propiedades {
  siniestro: Siniestro
  onReportarOtro: () => void
}

/** El reporte quedó registrado: referencia, estado y siguiente paso del caso (criterio 1). */
export function Confirmacion({ siniestro, onReportarOtro }: Propiedades) {
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
      <div className={estilos.acciones}>
        <Link className={clasesBoton('primario')} to="/">
          {t('siniestro.confirmacion.inicio')}
        </Link>
        <Boton variante="secundario" onClick={onReportarOtro}>
          {t('siniestro.confirmacion.otro')}
        </Boton>
      </div>
    </section>
  )
}
