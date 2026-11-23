import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

import type { PolizaResumen } from '../../api/tipos'
import { formatearDia } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Siniestro.module.css'
import { DESCRIPCION_MAXIMA, type DatosReporte, type ErroresReporte } from './validacion'

const TIPOS_EVENTO = [
  'accidente_vehiculo',
  'hurto',
  'dano_hogar',
  'retraso_vuelo',
  'equipo_danado_o_perdido',
  'cancelacion_viaje',
  'otro',
] as const

interface Propiedades {
  polizas: PolizaResumen[]
  datos: DatosReporte
  errores: ErroresReporte
  hoy: string
  onCambiar: (cambios: Partial<DatosReporte>) => void
}

interface PropiedadesCampo {
  id: keyof DatosReporte
  etiqueta: string
  ayuda?: string
  error?: string
  ancho?: boolean
  children: (descripcion: string | undefined) => ReactNode
}

function Campo({ id, etiqueta, ayuda, error, ancho, children }: PropiedadesCampo) {
  const descripcion = [ayuda && `${id}-ayuda`, error && `${id}-error`].filter(Boolean).join(' ') || undefined
  return (
    <div className={`${estilos.campo} ${ancho ? estilos.campoAncho : ''}`.trim()}>
      <label htmlFor={id}>{etiqueta}</label>
      {children(descripcion)}
      {ayuda && (
        <p id={`${id}-ayuda`} className={estilos.ayuda}>
          {ayuda}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className={estilos.error}>
          {error}
        </p>
      )}
    </div>
  )
}

/** Paso 1: póliza afectada, tipo de evento, fecha, descripción y monto estimado (opcional). */
export function PasoInformacion({ polizas, datos, errores, hoy, onCambiar }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const poliza = polizas.find((p) => p.numero === datos.poliza)
  const vigencia = poliza
    ? { inicio: formatearDia(poliza.inicio, idioma), fin: formatearDia(poliza.fin, idioma) }
    : undefined
  const error = (campo: keyof DatosReporte) => (errores[campo] ? t(errores[campo], vigencia) : undefined)

  return (
    <div className={estilos.campos}>
      <Campo
        id="poliza"
        etiqueta={t('siniestro.informacion.poliza')}
        ayuda={vigencia && t('siniestro.informacion.vigencia', vigencia)}
        error={error('poliza')}
      >
        {(descripcion) => (
          <select
            id="poliza"
            required
            value={datos.poliza}
            aria-invalid={Boolean(errores.poliza)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ poliza: evento.target.value })}
          >
            <option value="">{t('siniestro.informacion.elegirPoliza')}</option>
            {polizas.map((p) => (
              <option key={p.numero} value={p.numero}>
                {t('siniestro.informacion.opcionPoliza', {
                  numero: p.numero,
                  producto: t(`siniestro.productos.${p.producto}`, { defaultValue: p.producto }),
                  estado: t(`siniestro.estadoPoliza.${p.estado}`),
                })}
              </option>
            ))}
          </select>
        )}
      </Campo>

      <Campo id="tipoEvento" etiqueta={t('siniestro.informacion.tipoEvento')} error={error('tipoEvento')}>
        {(descripcion) => (
          <select
            id="tipoEvento"
            required
            value={datos.tipoEvento}
            aria-invalid={Boolean(errores.tipoEvento)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ tipoEvento: evento.target.value as DatosReporte['tipoEvento'] })}
          >
            <option value="">{t('siniestro.informacion.elegirTipo')}</option>
            {TIPOS_EVENTO.map((tipo) => (
              <option key={tipo} value={tipo}>
                {t(`siniestro.tiposEvento.${tipo}`)}
              </option>
            ))}
          </select>
        )}
      </Campo>

      <Campo id="ocurridoEl" etiqueta={t('siniestro.informacion.fecha')} error={error('ocurridoEl')}>
        {(descripcion) => (
          <input
            id="ocurridoEl"
            type="date"
            required
            max={hoy}
            value={datos.ocurridoEl}
            aria-invalid={Boolean(errores.ocurridoEl)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ ocurridoEl: evento.target.value })}
          />
        )}
      </Campo>

      <Campo
        id="montoEstimado"
        etiqueta={t('siniestro.informacion.monto')}
        ayuda={t('siniestro.informacion.montoAyuda')}
        error={error('montoEstimado')}
      >
        {(descripcion) => (
          <input
            id="montoEstimado"
            type="text"
            inputMode="numeric"
            value={datos.montoEstimado}
            aria-invalid={Boolean(errores.montoEstimado)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ montoEstimado: evento.target.value })}
          />
        )}
      </Campo>

      <Campo
        id="descripcion"
        etiqueta={t('siniestro.informacion.descripcion')}
        ayuda={t('siniestro.informacion.descripcionAyuda')}
        error={error('descripcion')}
        ancho
      >
        {(descripcion) => (
          <textarea
            id="descripcion"
            required
            maxLength={DESCRIPCION_MAXIMA}
            value={datos.descripcion}
            aria-invalid={Boolean(errores.descripcion)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ descripcion: evento.target.value })}
          />
        )}
      </Campo>
    </div>
  )
}
