import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import { bff } from '../../api/cliente'
import type { Siniestro } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import { MigasDePan } from '../../componentes/MigasDePan'
import { formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import estilosPolizas from '../polizas/Polizas.module.css'
import { InsigniaSiniestro } from './InsigniaSiniestro'
import estilos from './Seguimiento.module.css'

const MIGAS = [{ clave: 'nav.inicio', a: '/' }, { clave: 'nav.siniestros' }]

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'listo'; siniestros: Siniestro[] }

async function obtenerSiniestros(): Promise<Carga> {
  try {
    const { data } = await bff.GET('/siniestros')
    return data ? { tipo: 'listo', siniestros: data } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

/** «Mis siniestros» (WEB-F06): los casos del cliente, reportados en la web o en la app, con su estado. */
export function MisSiniestros() {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const [actualizando, setActualizando] = useState(false)
  const [resultado, setResultado] = useState<'actualizado' | 'fallo' | null>(null)

  useEffect(() => {
    let vigente = true
    void obtenerSiniestros().then((nueva) => {
      if (vigente) setCarga(nueva)
    })
    return () => {
      vigente = false
    }
  }, [])

  function reintentar() {
    setCarga({ tipo: 'cargando' })
    void obtenerSiniestros().then(setCarga)
  }

  async function actualizar() {
    if (actualizando) return
    setActualizando(true)
    setResultado(null)
    const nueva = await obtenerSiniestros()
    setActualizando(false)
    if (nueva.tipo === 'listo') {
      setCarga(nueva)
      setResultado('actualizado')
    } else {
      setResultado('fallo')
    }
  }

  const columna = (clave: 'referencia' | 'poliza' | 'tipo' | 'canal' | 'estado' | 'monto') =>
    t(`siniestro.seguimiento.columnas.${clave}`)

  return (
    <>
      <MigasDePan migas={MIGAS} />
      <div className={estilosCotizacion.encabezado}>
        <h1>{t('siniestro.seguimiento.titulo')}</h1>
        <p>{t('siniestro.seguimiento.descripcion')}</p>
      </div>

      {carga.tipo === 'cargando' && <p role="status">{t('sesion.cargando')}</p>}

      {carga.tipo === 'error' && (
        <Aviso tipo="error" rol="alert">
          <p>{t('siniestro.seguimiento.errores.cargar')}</p>
          <Boton variante="secundario" onClick={reintentar}>
            {t('siniestro.seguimiento.reintentar')}
          </Boton>
        </Aviso>
      )}

      {carga.tipo === 'listo' && carga.siniestros.length === 0 && (
        <section className={`${estilosCotizacion.tarjeta} ${estilosPolizas.vacio}`} aria-labelledby="titulo-sin-siniestros">
          <h2 id="titulo-sin-siniestros">{t('siniestro.seguimiento.vacio.titulo')}</h2>
          <p>{t('siniestro.seguimiento.vacio.detalle')}</p>
          <Link className={clasesBoton('primario')} to="/siniestros/reportar">
            {t('siniestro.seguimiento.reportar')}
          </Link>
        </section>
      )}

      {carga.tipo === 'listo' && carga.siniestros.length > 0 && (
        <>
          <div className={estilos.encabezadoAcciones}>
            <Link className={clasesBoton('primario')} to="/siniestros/reportar">
              {t('siniestro.seguimiento.reportar')}
            </Link>
            <Boton variante="secundario" onClick={() => void actualizar()} aria-disabled={actualizando} aria-busy={actualizando}>
              {actualizando ? t('siniestro.seguimiento.actualizando') : t('siniestro.seguimiento.actualizar')}
            </Boton>
          </div>
          <p role="status" className="sr-only">
            {resultado === 'actualizado' ? t('siniestro.seguimiento.actualizado') : ''}
          </p>
          {resultado === 'fallo' && (
            <Aviso tipo="error" rol="alert">
              <p>{t('siniestro.seguimiento.errores.actualizar')}</p>
            </Aviso>
          )}
          <div className={estilosPolizas.contenedorTabla}>
            <table className={estilosPolizas.tablaDatos}>
              <caption className="sr-only">{t('siniestro.seguimiento.titulo')}</caption>
              <thead>
                <tr>
                  <th scope="col">{columna('referencia')}</th>
                  <th scope="col">{columna('poliza')}</th>
                  <th scope="col">{columna('tipo')}</th>
                  <th scope="col">{columna('canal')}</th>
                  <th scope="col">{columna('estado')}</th>
                  <th scope="col">{columna('monto')}</th>
                  <th scope="col">
                    <span className="sr-only">{t('siniestro.seguimiento.columnas.acciones')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {carga.siniestros.map((siniestro) => (
                  <tr key={siniestro.id}>
                    <th scope="row" className={estilosPolizas.numero} data-etiqueta={columna('referencia')}>
                      {siniestro.referencia}
                    </th>
                    <td className={estilosPolizas.numero} data-etiqueta={columna('poliza')}>
                      {siniestro.numero_poliza}
                    </td>
                    <td data-etiqueta={columna('tipo')}>{t(`siniestro.tiposEvento.${siniestro.tipo_evento}`)}</td>
                    <td data-etiqueta={columna('canal')}>{t(`siniestro.seguimiento.canales.${siniestro.canal}`)}</td>
                    <td data-etiqueta={columna('estado')}>
                      <InsigniaSiniestro estado={siniestro.estado} />
                    </td>
                    <td data-etiqueta={columna('monto')}>
                      {siniestro.monto_estimado
                        ? formatearMoneda(siniestro.monto_estimado, idioma)
                        : t('siniestro.seguimiento.noDisponible')}
                    </td>
                    <td>
                      <Link className={estilosPolizas.enlaceDetalle} to={`/siniestros/${encodeURIComponent(siniestro.id)}`}>
                        {t('siniestro.seguimiento.ver')}{' '}
                        <span className="sr-only">
                          {t('siniestro.seguimiento.delSiniestro', { referencia: siniestro.referencia })}
                        </span>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  )
}
