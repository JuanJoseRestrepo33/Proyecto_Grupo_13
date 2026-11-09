import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import type { PolizaResumen } from '../../api/tipos'
import { formatearDia, formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import { InsigniaPoliza } from './InsigniaPoliza'
import { nombrePoliza } from './nombrePoliza'
import estilos from './Polizas.module.css'

/** Tabla de pólizas: número, producto, estado con texto (P12), prima, vigencia y enlace al detalle. */
export function TablaPolizas({ polizas }: { polizas: PolizaResumen[] }) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const columna = (clave: 'numero' | 'producto' | 'estado' | 'prima' | 'vigencia') => t(`polizas.columnas.${clave}`)

  return (
    <div className={estilos.contenedorTabla}>
      <table className={estilos.tablaDatos}>
        <caption className="sr-only">{t('polizas.titulo')}</caption>
        <thead>
          <tr>
            <th scope="col">{columna('numero')}</th>
            <th scope="col">{columna('producto')}</th>
            <th scope="col">{columna('estado')}</th>
            <th scope="col">{columna('prima')}</th>
            <th scope="col">{columna('vigencia')}</th>
            <th scope="col">
              <span className="sr-only">{t('polizas.columnas.acciones')}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {polizas.map((poliza) => (
            <tr key={poliza.numero}>
              <th scope="row" className={estilos.numero} data-etiqueta={columna('numero')}>
                {poliza.numero}
              </th>
              <td data-etiqueta={columna('producto')}>{nombrePoliza(poliza, t)}</td>
              <td data-etiqueta={columna('estado')}>
                <InsigniaPoliza estado={poliza.estado} />
              </td>
              <td data-etiqueta={columna('prima')}>
                {poliza.prima_mensual
                  ? t('polizas.primaPorMes', { monto: formatearMoneda(poliza.prima_mensual, idioma) })
                  : t('polizas.noDisponible')}
              </td>
              <td data-etiqueta={columna('vigencia')}>
                {t('polizas.rangoVigencia', {
                  inicio: formatearDia(poliza.inicio, idioma),
                  fin: formatearDia(poliza.fin, idioma),
                })}
              </td>
              <td>
                <Link className={estilos.enlaceDetalle} to={`/polizas/${encodeURIComponent(poliza.numero)}`}>
                  {t('polizas.verDetalle')}{' '}
                  <span className="sr-only">{t('polizas.deLaPoliza', { numero: poliza.numero })}</span>
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
