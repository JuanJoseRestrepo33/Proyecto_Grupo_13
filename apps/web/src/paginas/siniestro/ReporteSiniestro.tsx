import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import { bff } from '../../api/cliente'
import type { ErrorVigencia, PolizaResumen, Siniestro } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { MigasDePan } from '../../componentes/MigasDePan'
import { diaLocal, formatearDia, leerMonto } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosTarjeta from '../cotizacion/Cotizacion.module.css'
import { Pasos } from '../cotizacion/Pasos'
import { ArchivosPendientes } from './ArchivosPendientes'
import { Confirmacion, type ResultadoArchivo } from './Confirmacion'
import { subirEvidencia } from './evidencias'
import { PasoInformacion } from './PasoInformacion'
import { PasoRevision } from './PasoRevision'
import { SelectorArchivos } from './SelectorArchivos'
import estilos from './Siniestro.module.css'
import { type DatosReporte, type ErroresReporte, validarReporte } from './validacion'

/** Pasos del asistente de reporte, en orden. Cada uno tiene su clave en i18n. */
const PASOS = ['informacion', 'evidencia', 'revision'] as const
const INFORMACION = 0
const REVISION = 2

const DATOS_INICIALES: DatosReporte = { poliza: '', tipoEvento: '', ocurridoEl: '', descripcion: '', montoEstimado: '' }

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'listo'; polizas: PolizaResumen[] }

/** Por qué no se registró el envío. Fuera de vigencia trae las fechas de la póliza (criterio 3). */
type Fallo =
  | { tipo: 'error' }
  | { tipo: 'polizaNoEncontrada' }
  | { tipo: 'fueraDeVigencia'; desde: string; hasta: string }

async function obtenerPolizas(): Promise<Carga> {
  try {
    const { data } = await bff.GET('/polizas')
    return data ? { tipo: 'listo', polizas: data } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

/**
 * Reporte de siniestro (WEB-F05): información → evidencia (opcional) → revisión → confirmación.
 *
 * Cada envío lleva su `Idempotency-Key` (P10, P18). La clave nace al llegar a la revisión y se
 * conserva en los reintentos: un doble clic o una respuesta perdida no crean otro siniestro, el BFF
 * devuelve el mismo con su misma referencia (criterio 4). Volver a editar crea un envío nuevo.
 */
export function ReporteSiniestro() {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const [paso, setPaso] = useState(INFORMACION)
  const [datos, setDatos] = useState(DATOS_INICIALES)
  const [errores, setErrores] = useState<ErroresReporte>({})
  const [enviando, setEnviando] = useState(false)
  const [fallo, setFallo] = useState<Fallo | null>(null)
  const [siniestro, setSiniestro] = useState<Siniestro | null>(null)
  /** Evidencias elegidas en el paso 2; se adjuntan al siniestro cuando queda registrado. */
  const [archivos, setArchivos] = useState<File[]>([])
  const [adjuntando, setAdjuntando] = useState(false)
  const [resultados, setResultados] = useState<ResultadoArchivo[]>([])
  const clave = useRef<string | null>(null)
  // Candado síncrono: un doble clic llega antes de que React deshabilite el botón.
  const enCurso = useRef(false)
  const titulo = useRef<HTMLHeadingElement>(null)
  const pasoAnterior = useRef(paso)

  useEffect(() => {
    let vigente = true
    void obtenerPolizas().then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [])

  // Al cambiar de paso, el foco va al título del paso (no al cargar la pantalla).
  useEffect(() => {
    if (pasoAnterior.current !== paso) titulo.current?.focus()
    pasoAnterior.current = paso
  }, [paso])

  function reintentarCarga() {
    setCarga({ tipo: 'cargando' })
    void obtenerPolizas().then(setCarga)
  }

  function irA(destino: number) {
    setFallo(null)
    setPaso(destino)
  }

  function continuar() {
    if (carga.tipo !== 'listo') return
    if (paso === INFORMACION) {
      const encontrados = validarReporte(datos, carga.polizas, diaLocal(), leerMonto)
      setErrores(encontrados)
      const primero = (Object.keys(DATOS_INICIALES) as (keyof DatosReporte)[]).find((campo) => encontrados[campo])
      if (primero) {
        document.getElementById(primero)?.focus()
        return
      }
    }
    // Un envío nuevo por cada revisión: lo que se revisa es lo que se envía con esa clave.
    if (paso + 1 === REVISION) clave.current = crypto.randomUUID()
    irA(paso + 1)
  }

  async function enviar() {
    if (enCurso.current || !clave.current) return
    enCurso.current = true
    setEnviando(true)
    setFallo(null)
    const monto = datos.montoEstimado.trim() ? leerMonto(datos.montoEstimado) : null
    const resultado = await bff
      .POST('/siniestros', {
        params: { header: { 'Idempotency-Key': clave.current } },
        body: {
          numero_poliza: datos.poliza,
          tipo_evento: datos.tipoEvento as Siniestro['tipo_evento'],
          ocurrido_el: datos.ocurridoEl,
          descripcion: datos.descripcion.trim(),
          monto_estimado: monto === null ? null : { valor: monto, moneda: 'COP' },
        },
      })
      .catch(() => null)
    const data = resultado?.data
    if (data) {
      // El siniestro ya está registrado: si falla un archivo, se puede adjuntar después.
      setAdjuntando(true)
      const subidos: ResultadoArchivo[] = []
      for (const archivo of archivos) subidos.push({ archivo, resultado: await subirEvidencia(data.id, archivo) })
      setResultados(subidos)
      setSiniestro(data)
      return
    }
    enCurso.current = false
    setEnviando(false)

    // Sin respuesta no se sabe si llegó: el reintento lleva la misma clave y es seguro.
    if (!resultado) return setFallo({ tipo: 'error' })
    const { error } = resultado

    const codigo = error?.codigo
    if (codigo === 'evento_fuera_de_vigencia') {
      const { vigente_desde: desde, vigente_hasta: hasta } = error as ErrorVigencia
      if (desde && hasta) return setFallo({ tipo: 'fueraDeVigencia', desde, hasta })
    }
    if (codigo === 'poliza_no_encontrada') return setFallo({ tipo: 'polizaNoEncontrada' })
    if (codigo === 'fecha_futura') {
      setErrores({ ocurridoEl: 'siniestro.errores.fechaFutura' })
      return irA(INFORMACION)
    }
    setFallo({ tipo: 'error' })
  }

  function reiniciar() {
    setDatos(DATOS_INICIALES)
    setErrores({})
    setFallo(null)
    setSiniestro(null)
    setArchivos([])
    setResultados([])
    setAdjuntando(false)
    setEnviando(false)
    enCurso.current = false
    clave.current = null
    setPaso(INFORMACION)
  }

  const encabezado = (
    <>
      <MigasDePan migas={[{ clave: 'nav.inicio', a: '/' }, { clave: 'siniestro.titulo' }]} />
      <div className={estilosTarjeta.encabezado}>
        <h1>{t('siniestro.titulo')}</h1>
        <p>{t('siniestro.descripcion')}</p>
      </div>
    </>
  )

  if (carga.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>
  if (carga.tipo === 'error') {
    return (
      <>
        {encabezado}
        <Aviso tipo="error" rol="alert">
          <p>{t('siniestro.errores.cargar')}</p>
          <Boton variante="secundario" onClick={reintentarCarga}>
            {t('siniestro.errores.reintentar')}
          </Boton>
        </Aviso>
      </>
    )
  }
  if (carga.polizas.length === 0) {
    return (
      <>
        {encabezado}
        <Aviso tipo="info">
          <p>
            {t('siniestro.sinPolizas')} <Link to="/cotizacion">{t('siniestro.cotizar')}</Link>
          </p>
        </Aviso>
      </>
    )
  }
  if (siniestro) {
    return (
      <>
        {encabezado}
        <Confirmacion siniestro={siniestro} resultados={resultados} onReportarOtro={reiniciar} />
      </>
    )
  }

  const poliza = carga.polizas.find((p) => p.numero === datos.poliza)
  const nombrePaso = PASOS[paso]!

  return (
    <>
      {encabezado}
      <Pasos actual={paso} pasos={PASOS} claves="siniestro.pasos" />

      <section className={`${estilosTarjeta.tarjeta} ${estilosTarjeta.pila}`} aria-labelledby="titulo-paso">
        <h2 id="titulo-paso" ref={titulo} tabIndex={-1}>
          {t(`siniestro.${nombrePaso}.titulo`)}
          <span className="sr-only"> ({t('siniestro.pasos.actual', { actual: paso + 1, total: PASOS.length })})</span>
        </h2>

        {paso === INFORMACION && (
          <PasoInformacion
            polizas={carga.polizas}
            datos={datos}
            errores={errores}
            hoy={diaLocal()}
            onCambiar={(cambios) => setDatos((actuales) => ({ ...actuales, ...cambios }))}
          />
        )}
        {paso === 1 && (
          <>
            <Aviso tipo="info">
              <p>{t('siniestro.evidencia.opcional')}</p>
              <p>{t('siniestro.evidencia.app')}</p>
            </Aviso>
            <SelectorArchivos onSeleccionar={(nuevos) => setArchivos((actuales) => [...actuales, ...nuevos])} />
            <ArchivosPendientes
              archivos={archivos}
              onQuitar={(indice) => setArchivos((actuales) => actuales.filter((_, i) => i !== indice))}
            />
          </>
        )}
        {paso === REVISION && poliza && <PasoRevision datos={datos} poliza={poliza} archivos={archivos} />}

        {fallo && (
          <Aviso tipo="error" rol="alert">
            {fallo.tipo === 'fueraDeVigencia' ? (
              <p>
                {t('siniestro.errores.fueraDeVigenciaServidor', {
                  inicio: formatearDia(fallo.desde, idioma),
                  fin: formatearDia(fallo.hasta, idioma),
                })}
              </p>
            ) : (
              <p>{t(`siniestro.errores.${fallo.tipo === 'error' ? 'enviar' : 'polizaNoEncontrada'}`)}</p>
            )}
            {fallo.tipo !== 'error' && (
              <Boton variante="secundario" onClick={() => irA(INFORMACION)}>
                {t('siniestro.errores.corregir')}
              </Boton>
            )}
          </Aviso>
        )}

        <div className={`${estilosTarjeta.navegacion} ${estilos.acciones}`}>
          {paso > INFORMACION ? (
            <Boton variante="fantasma" disabled={enviando} onClick={() => irA(paso - 1)}>
              ← {t('siniestro.navegacion.atras')}
            </Boton>
          ) : (
            <span />
          )}
          {paso < REVISION ? (
            <Boton key="continuar" onClick={continuar}>
              {t('siniestro.navegacion.continuar')}
            </Boton>
          ) : (
            <Boton key="enviar" onClick={() => void enviar()} disabled={enviando}>
              {adjuntando
                ? t('siniestro.navegacion.adjuntando', { total: archivos.length })
                : enviando
                  ? t('siniestro.navegacion.enviando')
                  : t('siniestro.navegacion.enviar')}
            </Boton>
          )}
        </div>
      </section>
    </>
  )
}
