import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'

import { bff, urlBff } from '../../api/cliente'
import type { Evidencia, Siniestro } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { MigasDePan } from '../../componentes/MigasDePan'
import { formatearDia, formatearFechaHora } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosTarjeta from '../cotizacion/Cotizacion.module.css'
import { formatearTamano, type ResultadoCarga, subirEvidencia } from './evidencias'
import { SelectorArchivos } from './SelectorArchivos'
import estilos from './Siniestro.module.css'

type Carga<T> = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'listo'; datos: T }

/** Lo que pasa con cada archivo que se adjunta en esta visita. */
interface Envio {
  clave: number
  nombre: string
  resultado: ResultadoCarga | null
}

async function cargar<T>(peticion: Promise<{ data?: T }>): Promise<Carga<T>> {
  try {
    const { data } = await peticion
    return data ? { tipo: 'listo', datos: data } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

const obtenerSiniestros = () => cargar(bff.GET('/siniestros'))
const obtenerEvidencias = (id: string) =>
  cargar(bff.GET('/siniestros/{id}/evidencias', { params: { path: { id } } }))

/**
 * Evidencia de un siniestro ya reportado (MOB-F05, criterio 5): el cliente elige uno de sus
 * siniestros, reportado en la web o en la app, ve lo que ya tiene y adjunta más. Lo que se adjunta
 * queda en el mismo siniestro y se ve en los dos canales. La evidencia entregada no se puede quitar.
 */
export function EvidenciasSiniestro() {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [parametros, setParametros] = useSearchParams()
  const elegido = parametros.get('siniestro') ?? ''
  const [siniestros, setSiniestros] = useState<Carga<Siniestro[]>>({ tipo: 'cargando' })
  // Las evidencias llevan el siniestro al que pertenecen: si el cliente cambia de siniestro, las del
  // anterior no se muestran mientras llegan las nuevas.
  const [evidencias, setEvidencias] = useState<{ id: string; carga: Carga<Evidencia[]> } | null>(null)
  const [consulta, setConsulta] = useState(0)
  const [envios, setEnvios] = useState<Envio[]>([])

  useEffect(() => {
    let vigente = true
    void obtenerSiniestros().then((resultado) => {
      if (vigente) setSiniestros(resultado)
    })
    return () => {
      vigente = false
    }
  }, [])

  useEffect(() => {
    if (!elegido) return
    let vigente = true
    void obtenerEvidencias(elegido).then((carga) => {
      if (vigente) setEvidencias({ id: elegido, carga })
    })
    return () => {
      vigente = false
    }
  }, [elegido, consulta])

  const recargarEvidencias = () => setConsulta((n) => n + 1)

  function elegir(id: string) {
    setEnvios([])
    setParametros(id ? { siniestro: id } : {}, { replace: true })
  }

  async function adjuntar(archivos: File[]) {
    const id = elegido
    const inicio = Date.now()
    const nuevos = archivos.map((archivo, indice) => ({ clave: inicio + indice, nombre: archivo.name, resultado: null }))
    setEnvios((actuales) => [...nuevos, ...actuales])
    for (const [indice, archivo] of archivos.entries()) {
      const resultado = await subirEvidencia(id, archivo)
      const clave = nuevos[indice]!.clave
      setEnvios((actuales) => actuales.map((envio) => (envio.clave === clave ? { ...envio, resultado } : envio)))
    }
    recargarEvidencias()
  }

  const encabezado = (
    <>
      <MigasDePan migas={[{ clave: 'nav.inicio', a: '/' }, { clave: 'siniestro.evidencias.titulo' }]} />
      <div className={estilosTarjeta.encabezado}>
        <h1>{t('siniestro.evidencias.titulo')}</h1>
        <p>{t('siniestro.evidencias.descripcion')}</p>
      </div>
    </>
  )

  if (siniestros.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>
  if (siniestros.tipo === 'error') {
    return (
      <>
        {encabezado}
        <Aviso tipo="error" rol="alert">
          <p>{t('siniestro.evidencias.errorSiniestros')}</p>
          <Boton variante="secundario" onClick={() => void obtenerSiniestros().then(setSiniestros)}>
            {t('siniestro.errores.reintentar')}
          </Boton>
        </Aviso>
      </>
    )
  }
  if (siniestros.datos.length === 0) {
    return (
      <>
        {encabezado}
        <Aviso tipo="info">
          <p>
            {t('siniestro.evidencias.sinSiniestros')}{' '}
            <Link to="/siniestros/reportar">{t('siniestro.evidencias.reportar')}</Link>
          </p>
        </Aviso>
      </>
    )
  }

  const siniestro = siniestros.datos.find((s) => s.id === elegido)
  const subiendo = envios.some((envio) => envio.resultado === null)
  const cargaEvidencias: Carga<Evidencia[]> = evidencias?.id === elegido ? evidencias.carga : { tipo: 'cargando' }

  return (
    <>
      {encabezado}
      <section className={`${estilosTarjeta.tarjeta} ${estilosTarjeta.pila}`}>
        <div className={estilos.campo}>
          <label htmlFor="siniestro">{t('siniestro.evidencias.siniestro')}</label>
          <select id="siniestro" value={siniestro ? elegido : ''} onChange={(evento) => elegir(evento.target.value)}>
            <option value="">{t('siniestro.evidencias.elegir')}</option>
            {siniestros.datos.map((s) => (
              <option key={s.id} value={s.id}>
                {t('siniestro.evidencias.opcion', {
                  referencia: s.referencia,
                  tipo: t(`siniestro.tiposEvento.${s.tipo_evento}`),
                  fecha: formatearDia(s.ocurrido_el, idioma),
                  canal: t(`siniestro.canales.${s.canal}`),
                })}
              </option>
            ))}
          </select>
        </div>

        {siniestro && (
          <>
            <h2>{t('siniestro.evidencias.subtitulo', { referencia: siniestro.referencia })}</h2>
            <ListaEvidencias carga={cargaEvidencias} idSiniestro={siniestro.id} onReintentar={recargarEvidencias} />
            <SelectorArchivos deshabilitado={subiendo} onSeleccionar={(archivos) => void adjuntar(archivos)} />
            {envios.length > 0 && (
              <ul className={estilos.listaSimple} aria-live="polite">
                {envios.map(({ clave, nombre, resultado }) => (
                  <li key={clave}>
                    {resultado === null
                      ? t('siniestro.evidencias.subiendo', { nombre })
                      : resultado.ok
                        ? t('siniestro.confirmacion.adjuntada', { nombre })
                        : t(`siniestro.evidencia.fallo.${resultado.fallo}`, { nombre })}
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>
    </>
  )
}

interface PropiedadesLista {
  carga: Carga<Evidencia[]>
  idSiniestro: string
  onReintentar: () => void
}

/** Evidencias del siniestro, cargadas desde cualquiera de los dos canales. */
function ListaEvidencias({ carga, idSiniestro, onReintentar }: PropiedadesLista) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma

  if (carga.tipo === 'cargando') return <p role="status">{t('siniestro.evidencias.cargando')}</p>
  if (carga.tipo === 'error') {
    return (
      <Aviso tipo="error" rol="alert">
        <p>{t('siniestro.evidencias.errorEvidencias')}</p>
        <Boton variante="secundario" onClick={onReintentar}>
          {t('siniestro.errores.reintentar')}
        </Boton>
      </Aviso>
    )
  }
  if (carga.datos.length === 0) return <p>{t('siniestro.evidencias.ninguna')}</p>

  return (
    <ul className={estilos.archivos} aria-label={t('siniestro.evidencias.lista')}>
      {carga.datos.map((evidencia) => {
        const nombre = evidencia.nombre_original ?? evidencia.id
        return (
          <li key={evidencia.id} className={estilos.archivo}>
            <span>
              {nombre}{' '}
              <small>
                {t('siniestro.evidencias.detalle', {
                  tamano: formatearTamano(evidencia.tamano, idioma),
                  canal: t(`siniestro.canales.${evidencia.canal}`),
                  fecha: formatearFechaHora(evidencia.cargada_en, idioma),
                })}
              </small>
            </span>
            <a
              href={urlBff(
                `/siniestros/${encodeURIComponent(idSiniestro)}/evidencias/${encodeURIComponent(evidencia.id)}`,
              )}
              download={nombre}
              aria-label={t('siniestro.evidencias.descargarArchivo', { nombre })}
            >
              {t('siniestro.evidencias.descargar')}
            </a>
          </li>
        )
      })}
    </ul>
  )
}
