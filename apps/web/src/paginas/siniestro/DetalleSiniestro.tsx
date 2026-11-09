import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'

import { bff } from '../../api/cliente'
import type { SiniestroDetalle } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import { MigasDePan } from '../../componentes/MigasDePan'
import { formatearDia, formatearFecha, formatearFechaHora, formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import estilosPolizas from '../polizas/Polizas.module.css'
import { InsigniaSiniestro } from './InsigniaSiniestro'
import estilos from './Seguimiento.module.css'
import { type ResultadoRespuesta, TarjetaRequerimiento } from './TarjetaRequerimiento'

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'noEncontrado' } | { tipo: 'listo'; siniestro: SiniestroDetalle }

const ESTADOS_FINALES: SiniestroDetalle['estado'][] = ['rechazado', 'pagado']

async function obtenerSiniestro(id: string): Promise<Carga> {
  try {
    const { data, response } = await bff.GET('/siniestros/{id}', { params: { path: { id } } })
    if (data) return { tipo: 'listo', siniestro: data }
    return response.status === 404 ? { tipo: 'noEncontrado' } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

/**
 * Detalle del siniestro (WEB-F06): estado, línea de tiempo con el historial y requerimientos. El
 * cliente actualiza el caso sin salir de la pantalla; si la consulta falla, conserva lo que ya veía.
 */
export function DetalleSiniestro() {
  const { id = '' } = useParams()
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const [actualizando, setActualizando] = useState(false)
  const [resultado, setResultado] = useState<'actualizado' | 'fallo' | null>(null)
  const [respuesta, setRespuesta] = useState<ResultadoRespuesta | null>(null)
  const ultimaConsulta = useRef(0)

  useEffect(() => {
    let vigente = true
    void obtenerSiniestro(id).then((nueva) => {
      if (vigente) setCarga(nueva)
    })
    return () => {
      vigente = false
    }
  }, [id])

  function reintentar() {
    setCarga({ tipo: 'cargando' })
    void obtenerSiniestro(id).then(setCarga)
  }

  /** Consulta el caso; si hay varias consultas a la vez, solo vale la última. */
  async function actualizar(avisar = true) {
    ultimaConsulta.current += 1
    const consulta = ultimaConsulta.current
    setActualizando(true)
    setResultado(null)
    const nueva = await obtenerSiniestro(id)
    if (consulta !== ultimaConsulta.current) return
    setActualizando(false)
    if (nueva.tipo === 'listo') {
      setCarga(nueva)
      if (avisar) setResultado('actualizado')
    } else {
      setResultado('fallo')
    }
  }

  function alResponder(nuevo: ResultadoRespuesta) {
    setRespuesta(nuevo)
    document.getElementById('titulo-requerimientos')?.focus()
    void actualizar(false)
  }

  const migas = [
    { clave: 'nav.inicio', a: '/' },
    { clave: 'nav.siniestros', a: '/siniestros' },
    { clave: 'siniestro.seguimiento.miga', texto: carga.tipo === 'listo' ? carga.siniestro.referencia : undefined },
  ]

  if (carga.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>

  if (carga.tipo !== 'listo') {
    return (
      <>
        <MigasDePan migas={migas} />
        <h1>{t('siniestro.seguimiento.miga')}</h1>
        <Aviso tipo="error" rol="alert">
          <p>{t(carga.tipo === 'noEncontrado' ? 'siniestro.seguimiento.errores.noEncontrado' : 'siniestro.seguimiento.errores.cargarDetalle')}</p>
          {carga.tipo === 'noEncontrado' ? (
            <Link className={clasesBoton('secundario')} to="/siniestros">
              {t('siniestro.seguimiento.volver')}
            </Link>
          ) : (
            <Boton variante="secundario" onClick={reintentar}>
              {t('siniestro.seguimiento.reintentar')}
            </Boton>
          )}
        </Aviso>
      </>
    )
  }

  const { siniestro } = carga
  const final = ESTADOS_FINALES.includes(siniestro.estado)
  const tituloEstado = (estado: SiniestroDetalle['estado']) => t(`siniestro.estados.${estado}`)

  return (
    <>
      <MigasDePan migas={migas} />
      <div className={estilosCotizacion.encabezado}>
        <div className={estilosPolizas.titulo}>
          <h1>{t(`siniestro.tiposEvento.${siniestro.tipo_evento}`)}</h1>
          <InsigniaSiniestro estado={siniestro.estado} />
        </div>
        <p className={estilosPolizas.numero}>
          {t('siniestro.seguimiento.referenciaPoliza', { referencia: siniestro.referencia, poliza: siniestro.numero_poliza })}
        </p>
      </div>

      <div className={estilos.encabezadoAcciones}>
        <Boton
          variante="secundario"
          onClick={() => {
            if (!actualizando) void actualizar()
          }}
          aria-disabled={actualizando}
          aria-busy={actualizando}
        >
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

      <div className={estilos.columnas}>
        <section className={estilosCotizacion.tarjeta} aria-labelledby="titulo-linea-tiempo">
          <h2 id="titulo-linea-tiempo" className={estilosPolizas.seccionTitulo}>
            {t('siniestro.seguimiento.lineaDeTiempo')}
          </h2>
          <ol className={estilos.lineaDeTiempo} aria-labelledby="titulo-linea-tiempo">
            {siniestro.historial.map((evento, indice) => (
              <li
                key={evento.id}
                className={estilos.evento}
                aria-current={indice === siniestro.historial.length - 1 ? 'true' : undefined}
              >
                <p className={estilos.fechaEvento}>{formatearFechaHora(evento.ocurrido_en, idioma)}</p>
                <p className={estilos.tituloEvento}>{tituloEstado(evento.estado)}</p>
                {evento.motivo && <p className={estilos.motivoEvento}>{evento.motivo}</p>}
              </li>
            ))}
          </ol>
        </section>

        <section className={estilosCotizacion.tarjeta} aria-labelledby="titulo-resumen">
          <h2 id="titulo-resumen" className={estilosPolizas.seccionTitulo}>
            {t('siniestro.seguimiento.resumen.titulo')}
          </h2>
          <dl className={estilosCotizacion.detalles}>
            <dt>{t('siniestro.seguimiento.resumen.fecha')}</dt>
            <dd>{formatearDia(siniestro.ocurrido_el, idioma)}</dd>
            <dt>{t('siniestro.seguimiento.resumen.descripcion')}</dt>
            <dd>{siniestro.descripcion}</dd>
            <dt>{t('siniestro.seguimiento.resumen.monto')}</dt>
            <dd>
              {siniestro.monto_estimado
                ? formatearMoneda(siniestro.monto_estimado, idioma)
                : t('siniestro.seguimiento.noDisponible')}
            </dd>
            <dt>{t('siniestro.seguimiento.resumen.canal')}</dt>
            <dd>{t(`siniestro.seguimiento.canales.${siniestro.canal}`)}</dd>
            {siniestro.estado_actualizado_en && (
              <>
                <dt>{t('siniestro.seguimiento.resumen.actualizadoEl')}</dt>
                <dd>{formatearFecha(siniestro.estado_actualizado_en, idioma)}</dd>
              </>
            )}
            {!final && (
              <>
                <dt>{t('siniestro.confirmacion.siguientePaso')}</dt>
                <dd>{t(`siniestro.siguientePaso.${siniestro.siguiente_paso}`)}</dd>
              </>
            )}
          </dl>
        </section>
      </div>

      {(siniestro.requerimientos.length > 0 || respuesta) && (
        <section aria-labelledby="titulo-requerimientos">
          <h2 id="titulo-requerimientos" className={estilosPolizas.seccionTitulo} tabIndex={-1}>
            {t('siniestro.seguimiento.requerimientos.titulo')}
          </h2>
          {respuesta && (
            <Aviso tipo={respuesta.ok ? 'exito' : 'error'} rol={respuesta.ok ? 'status' : 'alert'}>
              <p>
                {respuesta.ok
                  ? t('siniestro.seguimiento.requerimientos.respondido', { nombre: respuesta.nombre })
                  : t(`siniestro.evidencia.fallo.${respuesta.fallo}`, { nombre: respuesta.nombre })}
              </p>
            </Aviso>
          )}
          <ul className={estilos.requerimientos}>
            {siniestro.requerimientos.map((requerimiento) => (
              <TarjetaRequerimiento
                key={requerimiento.id}
                idSiniestro={siniestro.id}
                requerimiento={requerimiento}
                onResultado={alResponder}
              />
            ))}
          </ul>
        </section>
      )}
    </>
  )
}
