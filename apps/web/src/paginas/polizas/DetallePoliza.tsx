import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'

import { bff, urlAbsolutaBff } from '../../api/cliente'
import type { PolizaDetalle } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import { MigasDePan } from '../../componentes/MigasDePan'
import { formatearDia, formatearFecha, formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import { InsigniaPoliza } from './InsigniaPoliza'
import { nombrePoliza } from './nombrePoliza'
import estilos from './Polizas.module.css'

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'noEncontrada' } | { tipo: 'listo'; poliza: PolizaDetalle }

async function obtenerPoliza(numero: string): Promise<Carga> {
  try {
    const { data, response } = await bff.GET('/polizas/{numero}', { params: { path: { numero } } })
    if (data) return { tipo: 'listo', poliza: data }
    return response.status === 404 ? { tipo: 'noEncontrada' } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

/**
 * Detalle de una póliza (WEB-F04, criterio 4): coberturas, deducible, límites y documento. Las
 * pólizas de prueba no traen todos los datos; lo que falta se dice en lugar de dejar un hueco.
 */
export function DetallePoliza() {
  const { numero = '' } = useParams()
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const noDisponible = t('polizas.noDisponible')

  useEffect(() => {
    let vigente = true
    void obtenerPoliza(numero).then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [numero])

  function reintentar() {
    setCarga({ tipo: 'cargando' })
    void obtenerPoliza(numero).then(setCarga)
  }

  const migas = [
    { clave: 'nav.inicio', a: '/' },
    { clave: 'nav.polizas', a: '/polizas' },
    { clave: 'polizas.detalle.miga', texto: carga.tipo === 'listo' ? nombrePoliza(carga.poliza, t) : undefined },
  ]

  if (carga.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>

  if (carga.tipo !== 'listo') {
    return (
      <>
        <MigasDePan migas={migas} />
        <h1>{t('polizas.detalle.miga')}</h1>
        <Aviso tipo="error" rol="alert">
          <p>{t(carga.tipo === 'noEncontrada' ? 'polizas.errores.noEncontrada' : 'polizas.errores.cargarDetalle')}</p>
          {carga.tipo === 'noEncontrada' ? (
            <Link className={clasesBoton('secundario')} to="/polizas">
              {t('polizas.detalle.volver')}
            </Link>
          ) : (
            <Boton variante="secundario" onClick={reintentar}>
              {t('polizas.reintentar')}
            </Boton>
          )}
        </Aviso>
      </>
    )
  }

  const { poliza } = carga

  return (
    <>
      <MigasDePan migas={migas} />
      <div className={estilosCotizacion.encabezado}>
        <div className={estilos.titulo}>
          <h1>{nombrePoliza(poliza, t)}</h1>
          <InsigniaPoliza estado={poliza.estado} />
        </div>
        <p className={estilos.numero}>{poliza.numero}</p>
      </div>

      <div className={estilos.acciones}>
        {poliza.documento_url ? (
          <a className={clasesBoton('secundario')} href={urlAbsolutaBff(poliza.documento_url)} download>
            {t('polizas.detalle.descargar')}
          </a>
        ) : (
          <Aviso tipo="info">
            <p>{t('polizas.detalle.sinDocumento')}</p>
          </Aviso>
        )}
      </div>

      <div className={estilos.columnas}>
        <section className={estilosCotizacion.tarjeta} aria-labelledby="titulo-informacion-general">
          <h2 id="titulo-informacion-general" className={estilos.seccionTitulo}>
            {t('polizas.detalle.informacionGeneral')}
          </h2>
          <dl className={estilosCotizacion.detalles}>
            <dt>{t('polizas.detalle.vigencia')}</dt>
            <dd>
              {t('polizas.rangoVigencia', {
                inicio: formatearDia(poliza.inicio, idioma),
                fin: formatearDia(poliza.fin, idioma),
              })}
            </dd>
            <dt>{t('polizas.detalle.prima')}</dt>
            <dd>
              {poliza.prima_mensual
                ? t('polizas.primaPorMes', { monto: formatearMoneda(poliza.prima_mensual, idioma) })
                : noDisponible}
            </dd>
            {poliza.emitida_en && (
              <>
                <dt>{t('polizas.detalle.emitidaEl')}</dt>
                <dd>{formatearFecha(poliza.emitida_en, idioma)}</dd>
              </>
            )}
          </dl>
        </section>

        <section className={estilosCotizacion.tarjeta} aria-labelledby="titulo-informacion-financiera">
          <h2 id="titulo-informacion-financiera" className={estilos.seccionTitulo}>
            {t('polizas.detalle.informacionFinanciera')}
          </h2>
          <dl className={estilosCotizacion.detalles}>
            <dt>{t('cotizacion.oferta.valorAsegurado')}</dt>
            <dd>{poliza.valor_asegurado ?? noDisponible}</dd>
            <dt>{t('cotizacion.oferta.deducible')}</dt>
            <dd>{poliza.deducible ?? noDisponible}</dd>
          </dl>
        </section>
      </div>

      <section aria-labelledby="titulo-coberturas">
        <h2 id="titulo-coberturas" className={estilos.seccionTitulo}>
          {t('cotizacion.oferta.coberturas')}
        </h2>
        <div className={estilos.contenedorTabla}>
          {poliza.coberturas.length > 0 ? (
            <table className={estilos.tablaDatos}>
              <caption className="sr-only">{t('cotizacion.oferta.coberturas')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('polizas.detalle.cobertura')}</th>
                  <th scope="col">{t('polizas.detalle.limite')}</th>
                </tr>
              </thead>
              <tbody>
                {poliza.coberturas.map((cobertura) => (
                  <tr key={cobertura.nombre}>
                    <th scope="row" data-etiqueta={t('polizas.detalle.cobertura')}>
                      {cobertura.nombre}
                    </th>
                    <td data-etiqueta={t('polizas.detalle.limite')}>{cobertura.limite}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className={estilos.sinResultados}>{noDisponible}</p>
          )}
        </div>
      </section>
    </>
  )
}
