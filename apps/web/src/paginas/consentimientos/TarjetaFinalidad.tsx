import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { bff } from '../../api/cliente'
import type { Consentimiento, Finalidad } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { formatearFecha } from '../../formato'
import type { Idioma } from '../../i18n'
import { ConfirmarRevocacion } from './ConfirmarRevocacion'
import estilos from './Consentimientos.module.css'
import { claveDeError } from './errores'
import { FormularioOtorgar } from './FormularioOtorgar'
import { Historial } from './Historial'

interface Propiedades {
  finalidad: Finalidad
  /** Consentimientos de esta finalidad, del más reciente al más antiguo. */
  consentimientos: Consentimiento[]
  onActualizado: (consentimiento: Consentimiento) => void
}

type Resultado = { tipo: 'exito' | 'error'; texto: string } | null

export function TarjetaFinalidad({ finalidad, consentimientos, onActualizado }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const actual = consentimientos[0]
  const [confirmando, setConfirmando] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [resultado, setResultado] = useState<Resultado>(null)
  const [verHistorial, setVerHistorial] = useState(false)
  const botonRevocar = useRef<HTMLButtonElement>(null)
  const devolverFoco = useRef(false)
  const idTitulo = `${finalidad.codigo}-titulo`
  const idHistorial = `${finalidad.codigo}-historial`

  async function ejecutar(
    accion: () => Promise<{ data?: Consentimiento; error?: { codigo: string } }>,
    claveExito: string,
  ) {
    setEnviando(true)
    setResultado(null)
    const { data, error } = await accion().catch(() => ({ data: undefined, error: undefined }))
    setEnviando(false)
    if (data) {
      onActualizado(data)
      setResultado({ tipo: 'exito', texto: t(claveExito, { fecha: formatearFecha(data.vence_en, idioma) }) })
      return true
    }
    setResultado({ tipo: 'error', texto: t(claveDeError(error?.codigo)) })
    return false
  }

  const otorgar = (entidades: string[]) =>
    ejecutar(
      () =>
        bff.POST('/consentimientos', {
          body: { finalidad: finalidad.codigo, entidades_origen: entidades, version_texto: finalidad.version_texto },
        }),
      'consentimientos.resultado.otorgado',
    )

  const renovar = (id: string) =>
    ejecutar(
      () => bff.POST('/consentimientos/{id}/renovacion', { params: { path: { id } } }),
      'consentimientos.resultado.renovado',
    )

  async function revocar(id: string) {
    const revocado = await ejecutar(
      () => bff.POST('/consentimientos/{id}/revocacion', { params: { path: { id } } }),
      'consentimientos.resultado.revocado',
    )
    if (revocado) setConfirmando(false)
  }

  function abrirConfirmacion() {
    setResultado(null)
    setConfirmando(true)
  }

  function cancelarRevocacion() {
    devolverFoco.current = true
    setConfirmando(false)
  }

  // Al cancelar, el foco vuelve al botón "Revocar" cuando ya está de nuevo en pantalla.
  useEffect(() => {
    if (!confirmando && devolverFoco.current) {
      devolverFoco.current = false
      botonRevocar.current?.focus()
    }
  }, [confirmando])

  const estado = actual?.estado ?? 'sinOtorgar'

  return (
    <article className={estilos.tarjeta} aria-labelledby={idTitulo}>
      <div className={estilos.cabecera}>
        <div>
          <h2 id={idTitulo}>{finalidad.nombre}</h2>
          <p>{finalidad.descripcion}</p>
        </div>
        <span className={estilos.insignia} data-estado={estado}>
          {t(`consentimientos.estado.${estado}`)}
        </span>
      </div>

      <dl className={estilos.detalles}>
        <dt>{t('consentimientos.alcance')}</dt>
        <dd>{finalidad.alcance.join(' · ')}</dd>
        <dt>{t('consentimientos.vigencia')}</dt>
        <dd>{t('consentimientos.vigenciaMeses', { count: finalidad.vigencia_meses })}</dd>
        <dt>{t('consentimientos.entidades')}</dt>
        <dd>{(actual?.estado === 'vigente' ? actual.entidades_origen : finalidad.entidades_origen).map((e) => e.nombre).join(' · ')}</dd>
        <dt>{t('consentimientos.textoLegal')}</dt>
        <dd>{actual?.estado === 'vigente' ? actual.version_texto : finalidad.version_texto}</dd>
      </dl>

      {actual?.estado === 'vigente' && (
        <>
          <p className={estilos.estadoTexto}>{t('consentimientos.venceEl', { fecha: formatearFecha(actual.vence_en, idioma) })}</p>
          {actual.por_vencer && (
            <Aviso tipo="advertencia">
              <p>{t('consentimientos.porVencer', { count: actual.dias_para_vencer ?? 0 })}</p>
            </Aviso>
          )}
          {confirmando ? (
            <ConfirmarRevocacion
              finalidad={finalidad}
              enviando={enviando}
              onConfirmar={() => void revocar(actual.id)}
              onCancelar={cancelarRevocacion}
            />
          ) : (
            <div className={estilos.acciones}>
              {actual.por_vencer && (
                <Boton onClick={() => void renovar(actual.id)} disabled={enviando}>
                  {enviando ? t('consentimientos.acciones.renovando') : t('consentimientos.acciones.renovar')}
                </Boton>
              )}
              <Boton ref={botonRevocar} variante="secundario" onClick={abrirConfirmacion} disabled={enviando}>
                {t('consentimientos.acciones.revocar')}
              </Boton>
            </div>
          )}
        </>
      )}

      {actual?.estado === 'vencido' && (
        <>
          <p className={estilos.estadoTexto}>{t('consentimientos.vencioEl', { fecha: formatearFecha(actual.vence_en, idioma) })}</p>
          <div className={estilos.acciones}>
            <Boton onClick={() => void renovar(actual.id)} disabled={enviando}>
              {enviando ? t('consentimientos.acciones.renovando') : t('consentimientos.acciones.renovar')}
            </Boton>
          </div>
        </>
      )}

      {actual?.estado === 'revocado' && actual.revocado_en && (
        <p className={estilos.estadoTexto}>{t('consentimientos.revocadoEl', { fecha: formatearFecha(actual.revocado_en, idioma) })}</p>
      )}

      {(!actual || actual.estado === 'revocado') && (
        <FormularioOtorgar finalidad={finalidad} enviando={enviando} onOtorgar={(entidades) => void otorgar(entidades)} />
      )}

      {resultado && (
        <Aviso tipo={resultado.tipo} rol={resultado.tipo === 'error' ? 'alert' : 'status'}>
          <p>{resultado.texto}</p>
        </Aviso>
      )}

      {consentimientos.length > 0 && (
        <div>
          <Boton
            variante="fantasma"
            aria-expanded={verHistorial}
            aria-controls={idHistorial}
            onClick={() => setVerHistorial((visible) => !visible)}
          >
            {verHistorial ? t('consentimientos.acciones.ocultarHistorial') : t('consentimientos.acciones.verHistorial')}
          </Boton>
          {verHistorial && <Historial id={idHistorial} finalidad={finalidad} consentimientos={consentimientos} />}
        </div>
      )}
    </article>
  )
}
