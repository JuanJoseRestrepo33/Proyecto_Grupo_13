import { useTranslation } from 'react-i18next'

import type { Cotizacion } from '../../api/tipos'
import { formatearMoneda, formatearVariacion } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Cotizacion.module.css'

/** "¿Por qué este precio?": la prima estándar, el efecto de cada factor y el total (RC-08). */
export function DetallePrecio({ cotizacion, id }: { cotizacion: Cotizacion; id: string }) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const { prima_estandar: estandar } = cotizacion

  return (
    <div id={id} className={estilos.pila}>
      <table className={estilos.tabla}>
        <caption>{t('cotizacion.precio.titulo')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('cotizacion.precio.factor')}</th>
            <th scope="col">{t('cotizacion.precio.efecto')}</th>
            <th scope="col">{t('cotizacion.precio.monto')}</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">{t('cotizacion.precio.estandar')}</th>
            <td />
            <td>{formatearMoneda(estandar, idioma)}</td>
          </tr>
          {cotizacion.factores.map((factor) => (
            <tr key={factor.nombre}>
              <th scope="row">{factor.nombre}</th>
              <td>{formatearVariacion(factor.efecto, idioma)}</td>
              <td>{formatearMoneda({ valor: Math.round(estandar.valor * factor.efecto), moneda: estandar.moneda }, idioma)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">{t('cotizacion.precio.total')}</th>
            <td />
            <td>{formatearMoneda(cotizacion.prima_mensual, idioma)}</td>
          </tr>
        </tfoot>
      </table>
      <p className={estilos.ayuda}>{t('cotizacion.precio.version', { version: cotizacion.version_regla })}</p>
    </div>
  )
}
