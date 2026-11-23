import { useTranslation } from 'react-i18next'

import type { PolizaResumen } from '../../api/tipos'
import { formatearDia, formatearMoneda, leerMonto } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Siniestro.module.css'
import type { DatosReporte } from './validacion'

interface Propiedades {
  datos: DatosReporte
  poliza: PolizaResumen
  archivos: File[]
}

/** Paso 3: lo que se va a enviar, para revisarlo antes. */
export function PasoRevision({ datos, poliza, archivos }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const monto = datos.montoEstimado.trim() ? leerMonto(datos.montoEstimado) : null

  return (
    <>
      <dl className={estilos.resumen}>
        <dt>{t('siniestro.revision.poliza')}</dt>
        <dd>
          {poliza.numero} · {t(`siniestro.productos.${poliza.producto}`, { defaultValue: poliza.producto })}
        </dd>
        <dt>{t('siniestro.revision.tipoEvento')}</dt>
        <dd>{datos.tipoEvento && t(`siniestro.tiposEvento.${datos.tipoEvento}`)}</dd>
        <dt>{t('siniestro.revision.fecha')}</dt>
        <dd>{formatearDia(datos.ocurridoEl, idioma)}</dd>
        <dt>{t('siniestro.revision.descripcion')}</dt>
        <dd>{datos.descripcion.trim()}</dd>
        <dt>{t('siniestro.revision.monto')}</dt>
        <dd>
          {monto === null ? t('siniestro.revision.sinMonto') : formatearMoneda({ valor: monto, moneda: 'COP' }, idioma)}
        </dd>
        <dt>{t('siniestro.revision.evidencias')}</dt>
        <dd>
          {archivos.length === 0
            ? t('siniestro.revision.sinEvidencias')
            : archivos.map((archivo) => archivo.name).join(', ')}
        </dd>
      </dl>
      <p className={estilos.ayuda}>{t('siniestro.revision.aviso')}</p>
    </>
  )
}
