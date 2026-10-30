import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'

import { bff } from '../../api/cliente'
import type { Cotizacion, MedioPago, Pago, Suscripcion as SuscripcionApi } from '../../api/tipos'
import { Aviso, type TipoAviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import { MigasDePan } from '../../componentes/MigasDePan'
import { formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from '../cotizacion/Cotizacion.module.css'
import { Pasos } from '../cotizacion/Pasos'
import { claveDelIntento, nuevaClave } from './claveIdempotencia'
import { PasoPago } from './PasoPago'
import { PasoRevisar } from './PasoRevisar'
import { ResultadoPago } from './ResultadoPago'

const PASOS = ['revisar', 'pago', 'resultado'] as const
const [REVISAR, PAGO, RESULTADO] = [0, 1, 2]

const MIGAS = [
  { clave: 'nav.inicio', a: '/' },
  { clave: 'nav.cotizar', a: '/cotizacion' },
  { clave: 'nav.suscripcion' },
]

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'noEncontrada' } | { tipo: 'listo'; cotizacion: Cotizacion }

/** Avisos de la pantalla; la clave es la de i18n en `suscripcion.avisos`. */
type ClaveAviso = 'reintento' | 'pagoEnCurso' | 'pagoYaRegistrado' | 'sinConfirmacion' | 'cotizacionVencida' | 'error'

const TIPO_AVISO: Record<ClaveAviso, TipoAviso> = {
  reintento: 'info',
  pagoEnCurso: 'advertencia',
  pagoYaRegistrado: 'info',
  sinConfirmacion: 'advertencia',
  cotizacionVencida: 'error',
  error: 'error',
}

async function obtenerCotizacion(id: string): Promise<Carga> {
  try {
    const { data, response } = await bff.GET('/cotizaciones/{id}', { params: { path: { id } } })
    if (data) return { tipo: 'listo', cotizacion: data }
    return response.status === 404 ? { tipo: 'noEncontrada' } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

/**
 * Checkout de la oferta aceptada (WEB-F03, rebanada): revisar → pagar → confirmación.
 *
 * Cada intento de pago lleva su `Idempotency-Key` (P18). Mientras el pago se procesa el botón queda
 * ocupado (P10), y cualquier reintento del mismo intento reutiliza la clave, así el BFF devuelve el
 * mismo pago en lugar de cobrar otra vez. La firma y la emisión llegan con el resto de WEB-F03.
 */
export function Suscripcion() {
  const { cotizacion: cotizacionId = '' } = useParams()
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const [paso, setPaso] = useState(REVISAR)
  const [suscripcion, setSuscripcion] = useState<SuscripcionApi | null>(null)
  const [medio, setMedio] = useState<MedioPago | null>(null)
  const [errorMedio, setErrorMedio] = useState(false)
  const [pago, setPago] = useState<Pago | null>(null)
  const [aviso, setAviso] = useState<ClaveAviso | null>(null)
  const [enviando, setEnviando] = useState(false)
  // Candado síncrono: un doble clic llega antes de que React deshabilite el botón.
  const enCurso = useRef(false)
  const titulo = useRef<HTMLHeadingElement>(null)
  const pasoAnterior = useRef(paso)

  useEffect(() => {
    let vigente = true
    void obtenerCotizacion(cotizacionId).then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [cotizacionId])

  useEffect(() => {
    if (pasoAnterior.current !== paso) titulo.current?.focus()
    pasoAnterior.current = paso
  }, [paso])

  const marcarVencida = useCallback(() => setAviso('cotizacionVencida'), [])

  /** Ejecuta una operación con el botón ocupado; ignora los clics mientras hay una en curso. */
  async function ocupado(operacion: () => Promise<void>) {
    if (enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    try {
      await operacion()
    } finally {
      enCurso.current = false
      setEnviando(false)
    }
  }

  function mostrarPago(nuevo: Pago, claveAviso: ClaveAviso | null = null) {
    setPago(nuevo)
    setAviso(claveAviso)
    setPaso(RESULTADO)
  }

  const aceptar = () =>
    ocupado(async () => {
      setAviso(null)
      const { data, error } = await bff
        .POST('/suscripciones', { body: { cotizacion_id: cotizacionId } })
        .catch(() => ({ data: undefined, error: undefined }))
      if (!data) {
        setAviso(error?.codigo === 'cotizacion_vencida' ? 'cotizacionVencida' : 'error')
        return
      }
      setSuscripcion(data)
      // Ya aceptada antes (recarga o vuelta atrás): si ya pagó, se muestra ese pago, no se cobra otro.
      if (data.pago && data.pago.estado !== 'rechazado') mostrarPago(data.pago, 'pagoYaRegistrado')
      else setPaso(PAGO)
    })

  const pagar = () =>
    ocupado(async () => {
      if (!suscripcion) return
      if (!medio) {
        setErrorMedio(true)
        document.querySelector<HTMLInputElement>('input[name="medio"]')?.focus()
        return
      }
      setAviso(null)
      const resultado = await bff
        .POST('/suscripciones/{id}/pagos', {
          params: { path: { id: suscripcion.id }, header: { 'Idempotency-Key': claveDelIntento(suscripcion.id) } },
          body: { medio },
        })
        .catch(() => null)
      if (!resultado) {
        // Sin respuesta no se sabe si se cobró: el reintento usa la misma clave y es seguro.
        setAviso('sinConfirmacion')
        return
      }
      const { data, error } = resultado
      if (data) {
        mostrarPago(data, data.reintento ? 'reintento' : null)
      } else if (error?.codigo === 'pago_en_curso') {
        setAviso('pagoEnCurso')
      } else if (error?.codigo === 'pago_ya_registrado') {
        const actual = await bff
          .GET('/suscripciones/{id}', { params: { path: { id: suscripcion.id } } })
          .catch(() => ({ data: undefined }))
        if (actual.data?.pago) mostrarPago(actual.data.pago, 'pagoYaRegistrado')
        else setAviso('error')
      } else {
        setAviso('error')
      }
    })

  const consultarPago = () =>
    ocupado(async () => {
      if (!suscripcion || !pago) return
      const { data } = await bff
        .GET('/suscripciones/{id}/pagos/{id_pago}', { params: { path: { id: suscripcion.id, id_pago: pago.id } } })
        .catch(() => ({ data: undefined }))
      if (data) mostrarPago(data)
      else setAviso('error')
    })

  function intentarDeNuevo() {
    if (suscripcion) nuevaClave(suscripcion.id)
    setPago(null)
    setAviso(null)
    setPaso(PAGO)
  }

  function reintentarCarga() {
    setCarga({ tipo: 'cargando' })
    void obtenerCotizacion(cotizacionId).then(setCarga)
  }

  const encabezado = (
    <>
      <MigasDePan migas={MIGAS} />
      <div className={estilos.encabezado}>
        <h1>{t('suscripcion.titulo')}</h1>
        <p>{t('suscripcion.descripcion')}</p>
      </div>
    </>
  )

  if (carga.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>
  if (carga.tipo !== 'listo') {
    return (
      <>
        {encabezado}
        <Aviso tipo="error" rol="alert">
          <p>{t(carga.tipo === 'noEncontrada' ? 'suscripcion.avisos.noEncontrada' : 'suscripcion.avisos.cargar')}</p>
          {carga.tipo === 'noEncontrada' ? (
            <Link className={clasesBoton('secundario')} to="/cotizacion">
              {t('suscripcion.recotizar')}
            </Link>
          ) : (
            <Boton variante="secundario" onClick={reintentarCarga}>
              {t('suscripcion.reintentar')}
            </Boton>
          )}
        </Aviso>
      </>
    )
  }

  const { cotizacion } = carga
  const vencida = cotizacion.estado === 'vencida' || aviso === 'cotizacionVencida'
  // Una oferta que ya llega vencida se explica de entrada, no solo con el botón deshabilitado.
  const avisoActual: ClaveAviso | null = aviso ?? (paso === REVISAR && vencida ? 'cotizacionVencida' : null)
  const monto = formatearMoneda(suscripcion?.prima_mensual ?? cotizacion.prima_mensual, idioma)
  const nombrePaso = PASOS[paso]!

  return (
    <>
      {encabezado}
      <Pasos actual={paso} pasos={PASOS} claves="suscripcion.pasos" />

      <section className={estilos.tarjeta} aria-labelledby="titulo-paso">
        <h2 id="titulo-paso" ref={titulo} tabIndex={-1}>
          {t(`suscripcion.${nombrePaso}.titulo`)}
          <span className="sr-only"> ({t('suscripcion.pasos.actual', { actual: paso + 1, total: PASOS.length })})</span>
        </h2>

        {paso === REVISAR && <PasoRevisar cotizacion={cotizacion} onVencer={marcarVencida} />}
        {paso === PAGO && (
          <PasoPago
            medio={medio}
            error={errorMedio}
            procesando={enviando}
            onElegir={(elegido) => {
              setMedio(elegido)
              setErrorMedio(false)
            }}
          />
        )}
        {paso === RESULTADO && pago && <ResultadoPago pago={pago} />}

        {enviando && paso === PAGO && (
          <p role="status" className={estilos.ayuda}>
            {t('suscripcion.pago.procesandoDetalle')}
          </p>
        )}

        {avisoActual && (
          <Aviso tipo={TIPO_AVISO[avisoActual]} rol={TIPO_AVISO[avisoActual] === 'error' ? 'alert' : 'status'}>
            <p>
              {avisoActual === 'cotizacionVencida' ? (
                <>
                  <strong>{t('suscripcion.avisos.cotizacionVencida')}</strong>{' '}
                  {t('suscripcion.avisos.cotizacionVencidaDetalle')}
                </>
              ) : (
                t(`suscripcion.avisos.${avisoActual}`)
              )}
            </p>
            {avisoActual === 'cotizacionVencida' && (
              <Link className={clasesBoton('secundario')} to="/cotizacion">
                {t('suscripcion.recotizar')}
              </Link>
            )}
          </Aviso>
        )}

        <div className={estilos.navegacion}>
          {paso === PAGO && !enviando ? (
            <Boton variante="fantasma" onClick={() => setPaso(REVISAR)}>
              ← {t('suscripcion.atras')}
            </Boton>
          ) : (
            <span />
          )}

          {paso === REVISAR && (
            <Boton onClick={() => void aceptar()} disabled={enviando || vencida}>
              {enviando ? t('suscripcion.revisar.aceptando') : t('suscripcion.revisar.aceptar')}
            </Boton>
          )}
          {paso === PAGO && (
            <Boton onClick={() => void pagar()} disabled={enviando} aria-busy={enviando}>
              {enviando
                ? t('suscripcion.pago.procesando')
                : aviso === 'pagoEnCurso' || aviso === 'sinConfirmacion'
                  ? t('suscripcion.pago.consultar')
                  : t('suscripcion.pago.pagar', { monto })}
            </Boton>
          )}
          {paso === RESULTADO && pago?.estado === 'rechazado' && (
            <Boton onClick={intentarDeNuevo}>{t('suscripcion.resultado.intentarDeNuevo')}</Boton>
          )}
          {paso === RESULTADO && pago?.estado === 'pendiente' && (
            <Boton onClick={() => void consultarPago()} disabled={enviando} aria-busy={enviando}>
              {t('suscripcion.pago.consultar')}
            </Boton>
          )}
          {paso === RESULTADO && pago?.estado === 'aprobado' && (
            <Link className={clasesBoton('secundario')} to="/">
              {t('suscripcion.resultado.volverInicio')}
            </Link>
          )}
        </div>
      </section>
    </>
  )
}
