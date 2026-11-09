import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import type { Requerimiento } from '../../api/tipos'
import { Boton } from '../../componentes/Boton'
import { formatearFecha } from '../../formato'
import type { Idioma } from '../../i18n'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import { type FalloCarga, formatearTamano, subirEvidencia } from './evidencias'
import estilos from './Seguimiento.module.css'
import { SelectorArchivos } from './SelectorArchivos'

/** Cómo terminó el envío de la respuesta: `nombre` es el archivo que se intentó enviar. */
export type ResultadoRespuesta = { nombre: string } & ({ ok: true } | { ok: false; fallo: FalloCarga })

interface Propiedades {
  idSiniestro: string
  requerimiento: Requerimiento
  onResultado: (resultado: ResultadoRespuesta) => void
}

/** Lo que se le pidió al cliente; si sigue pendiente, lo responde adjuntando un archivo (criterio 5). */
export function TarjetaRequerimiento({ idSiniestro, requerimiento, onResultado }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const [archivo, setArchivo] = useState<File | null>(null)
  const [enviando, setEnviando] = useState(false)
  const enCurso = useRef(false)
  const { descripcion, creado_en: creadoEn, pendiente, atendido_en: atendidoEn } = requerimiento

  async function enviar() {
    if (!archivo || enCurso.current) return
    enCurso.current = true
    setEnviando(true)
    const respuesta = await subirEvidencia(idSiniestro, archivo, requerimiento.id)
    enCurso.current = false
    setEnviando(false)
    if (respuesta.ok) {
      setArchivo(null)
      onResultado({ nombre: archivo.name, ok: true })
    } else {
      onResultado({ nombre: archivo.name, ok: false, fallo: respuesta.fallo })
    }
  }

  const estado = pendiente
    ? t('siniestro.seguimiento.requerimientos.pendiente', { fecha: formatearFecha(creadoEn, idioma) })
    : atendidoEn
      ? t('siniestro.seguimiento.requerimientos.atendido', { fecha: formatearFecha(atendidoEn, idioma) })
      : t('siniestro.seguimiento.requerimientos.cerrado')

  return (
    <li className={`${estilosCotizacion.tarjeta} ${estilosCotizacion.pila} ${estilos.requerimiento}`}>
      <h3>{descripcion}</h3>
      <p className={estilos.estadoRequerimiento}>{estado}</p>
      {pendiente && (
        <>
          <SelectorArchivos
            multiple={false}
            deshabilitado={enviando}
            etiqueta={t('siniestro.seguimiento.requerimientos.adjuntar', { descripcion })}
            onSeleccionar={([elegido]) => setArchivo(elegido ?? null)}
          />
          {archivo && (
            <p className={estilos.archivoElegido}>
              {archivo.name} · {formatearTamano(archivo.size, idioma)}
            </p>
          )}
          <div className={estilos.accionRespuesta}>
            <Boton onClick={() => void enviar()} disabled={!archivo} aria-disabled={enviando} aria-busy={enviando}>
              {enviando ? t('siniestro.seguimiento.requerimientos.enviando') : t('siniestro.seguimiento.requerimientos.enviar')}
            </Boton>
          </div>
        </>
      )}
    </li>
  )
}
