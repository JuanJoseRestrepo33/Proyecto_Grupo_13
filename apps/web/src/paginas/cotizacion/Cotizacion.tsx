import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { bff } from '../../api/cliente'
import type { CodigoProducto, Consentimiento, Cotizacion as CotizacionApi, Producto } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { leerMonto } from '../../formato'
import estilos from './Cotizacion.module.css'
import { PasoDatos } from './PasoDatos'
import { PasoOferta } from './PasoOferta'
import { PasoOpenFinance } from './PasoOpenFinance'
import { PasoProducto } from './PasoProducto'
import { Pasos } from './Pasos'
import { PASOS } from './pasos-asistente'
import { calcularEdad, type DatosAseguradoFormulario, type ErroresDatos, validarDatos } from './validacion'

const PASO_OPEN_FINANCE = 2
const PASO_OFERTA = 3

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'listo'; productos: Producto[]; consentimiento?: Consentimiento }

const DATOS_INICIALES: DatosAseguradoFormulario = { fechaNacimiento: '', ciudad: '', valorAsegurado: '' }

/** Productos del cotizador y consentimiento vigente para cotización, si lo hay. */
async function obtenerDatos(): Promise<Carga> {
  try {
    const [productos, consentimientos] = await Promise.all([
      bff.GET('/cotizaciones/productos'),
      bff.GET('/consentimientos'),
    ])
    if (!productos.data) return { tipo: 'error' }
    const vigente = consentimientos.data?.find((c) => c.finalidad === 'cotizacion' && c.estado === 'vigente')
    return { tipo: 'listo', productos: productos.data, consentimiento: vigente }
  } catch {
    return { tipo: 'error' }
  }
}

/** Asistente de cotización (WEB-F01): producto → datos → Open Finance → oferta. */
export function Cotizacion() {
  const { t } = useTranslation()
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const [paso, setPaso] = useState(0)
  const [producto, setProducto] = useState<CodigoProducto | null>(null)
  const [errorProducto, setErrorProducto] = useState<string | null>(null)
  const [datos, setDatos] = useState(DATOS_INICIALES)
  const [errores, setErrores] = useState<ErroresDatos>({})
  const [usarOpenFinance, setUsarOpenFinance] = useState(true)
  const [cotizacion, setCotizacion] = useState<CotizacionApi | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [errorCotizar, setErrorCotizar] = useState(false)
  const titulo = useRef<HTMLHeadingElement>(null)
  const pasoAnterior = useRef(paso)

  useEffect(() => {
    let vigente = true
    void obtenerDatos().then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [])

  function reintentarCarga() {
    setCarga({ tipo: 'cargando' })
    void obtenerDatos().then(setCarga)
  }

  // Al cambiar de paso, el foco va al título del paso (no al cargar la pantalla).
  useEffect(() => {
    if (pasoAnterior.current !== paso) titulo.current?.focus()
    pasoAnterior.current = paso
  }, [paso])

  async function cotizar() {
    setEnviando(true)
    setErrorCotizar(false)
    const { data } = await bff
      .POST('/cotizaciones', {
        body: {
          producto: producto!,
          asegurado: {
            edad: calcularEdad(datos.fechaNacimiento, new Date())!,
            ciudad: datos.ciudad.trim(),
            valor_asegurado: datos.valorAsegurado.trim() ? leerMonto(datos.valorAsegurado) : null,
          },
          usar_open_finance: usarOpenFinance,
        },
      })
      .catch(() => ({ data: undefined }))
    setEnviando(false)
    if (!data) {
      setErrorCotizar(true)
      return
    }
    setCotizacion(data)
    setPaso(PASO_OFERTA)
  }

  async function recotizar() {
    if (!cotizacion) return
    setEnviando(true)
    const { data } = await bff
      .POST('/cotizaciones/{id}/recotizacion', { params: { path: { id: cotizacion.id } } })
      .catch(() => ({ data: undefined }))
    setEnviando(false)
    if (data) setCotizacion(data)
    else setErrorCotizar(true)
  }

  function continuar() {
    if (paso === 0) {
      if (!producto) {
        setErrorProducto('cotizacion.errores.productoRequerido')
        return
      }
      setErrorProducto(null)
    }
    if (paso === 1) {
      const encontrados = validarDatos(datos, leerMonto)
      setErrores(encontrados)
      const primero = Object.keys(encontrados)[0]
      if (primero) {
        document.getElementById(primero)?.focus()
        return
      }
    }
    if (paso === PASO_OPEN_FINANCE) {
      void cotizar()
      return
    }
    setPaso(paso + 1)
  }

  function reiniciar() {
    setPaso(0)
    setProducto(null)
    setDatos(DATOS_INICIALES)
    setErrores({})
    setUsarOpenFinance(true)
    setCotizacion(null)
  }

  if (carga.tipo === 'cargando') return <p role="status">{t('sesion.cargando')}</p>
  if (carga.tipo === 'error') {
    return (
      <Aviso tipo="error" rol="alert">
        <p>{t('cotizacion.errores.cargar')}</p>
        <Boton variante="secundario" onClick={reintentarCarga}>
          {t('cotizacion.errores.reintentar')}
        </Boton>
      </Aviso>
    )
  }

  const productoElegido = carga.productos.find((p) => p.codigo === producto)
  const nombrePaso = PASOS[paso]!

  return (
    <>
      <div className={estilos.encabezado}>
        <h1>{t('cotizacion.titulo')}</h1>
        <p>{t('cotizacion.descripcion')}</p>
      </div>

      <Pasos actual={paso} />

      <section className={estilos.tarjeta} aria-labelledby="titulo-paso">
        <h2 id="titulo-paso" ref={titulo} tabIndex={-1}>
          {t(`cotizacion.${nombrePaso}.titulo`)}
          <span className="sr-only"> ({t('cotizacion.pasos.actual', { actual: paso + 1, total: PASOS.length })})</span>
        </h2>

        {paso === 0 && (
          <PasoProducto
            productos={carga.productos}
            seleccionado={producto}
            error={errorProducto}
            onSeleccionar={(codigo) => {
              setProducto(codigo)
              setErrorProducto(null)
            }}
          />
        )}
        {paso === 1 && (
          <PasoDatos
            datos={datos}
            errores={errores}
            valorDelPlan={productoElegido?.valor_asegurado}
            onCambiar={(cambios) => setDatos((actuales) => ({ ...actuales, ...cambios }))}
          />
        )}
        {paso === PASO_OPEN_FINANCE && (
          <PasoOpenFinance
            consentimiento={carga.consentimiento}
            usarOpenFinance={usarOpenFinance}
            onCambiar={setUsarOpenFinance}
          />
        )}
        {paso === PASO_OFERTA && cotizacion && (
          <PasoOferta
            key={cotizacion.id}
            cotizacion={cotizacion}
            recotizando={enviando}
            onRecotizar={() => void recotizar()}
          />
        )}

        {errorCotizar && (
          <Aviso tipo="error" rol="alert">
            <p>{t('cotizacion.errores.cotizar')}</p>
          </Aviso>
        )}

        <div className={estilos.navegacion}>
          {paso > 0 && paso < PASO_OFERTA ? (
            <Boton variante="fantasma" onClick={() => setPaso(paso - 1)}>
              ← {t('cotizacion.navegacion.atras')}
            </Boton>
          ) : (
            <span />
          )}
          {paso < PASO_OFERTA ? (
            <Boton key="avanzar" onClick={continuar} disabled={enviando}>
              {paso === PASO_OPEN_FINANCE
                ? enviando
                  ? t('cotizacion.navegacion.cotizando')
                  : t('cotizacion.navegacion.verOferta')
                : t('cotizacion.navegacion.continuar')}
            </Boton>
          ) : (
            <Boton key="reiniciar" variante="secundario" onClick={reiniciar}>
              {t('cotizacion.navegacion.otraCotizacion')}
            </Boton>
          )}
        </div>
      </section>
    </>
  )
}
