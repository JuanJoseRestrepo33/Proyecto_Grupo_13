import { useTranslation } from 'react-i18next'

import estilos from './Cotizacion.module.css'
import type { DatosAseguradoFormulario, ErroresDatos } from './validacion'

interface Propiedades {
  datos: DatosAseguradoFormulario
  errores: ErroresDatos
  valorDelPlan: string | undefined
  onCambiar: (cambios: Partial<DatosAseguradoFormulario>) => void
}

interface PropiedadesCampo {
  id: keyof DatosAseguradoFormulario
  etiqueta: string
  ayuda?: string
  error?: string
  children: (descripcion: string | undefined) => React.ReactNode
}

function Campo({ id, etiqueta, ayuda, error, children }: PropiedadesCampo) {
  const descripcion = [ayuda && `${id}-ayuda`, error && `${id}-error`].filter(Boolean).join(' ') || undefined
  return (
    <div className={estilos.campo}>
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

export function PasoDatos({ datos, errores, valorDelPlan, onCambiar }: Propiedades) {
  const { t } = useTranslation()
  const error = (campo: keyof DatosAseguradoFormulario) => (errores[campo] ? t(errores[campo]) : undefined)

  return (
    <div className={estilos.campos}>
      <Campo id="fechaNacimiento" etiqueta={t('cotizacion.datos.fechaNacimiento')} error={error('fechaNacimiento')}>
        {(descripcion) => (
          <input
            id="fechaNacimiento"
            type="date"
            autoComplete="bday"
            required
            value={datos.fechaNacimiento}
            aria-invalid={Boolean(errores.fechaNacimiento)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ fechaNacimiento: evento.target.value })}
          />
        )}
      </Campo>
      <Campo id="ciudad" etiqueta={t('cotizacion.datos.ciudad')} error={error('ciudad')}>
        {(descripcion) => (
          <input
            id="ciudad"
            type="text"
            autoComplete="address-level2"
            required
            maxLength={80}
            value={datos.ciudad}
            aria-invalid={Boolean(errores.ciudad)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ ciudad: evento.target.value })}
          />
        )}
      </Campo>
      <Campo
        id="valorAsegurado"
        etiqueta={t('cotizacion.datos.valorAsegurado')}
        ayuda={valorDelPlan ? t('cotizacion.datos.valorAyuda', { valor: valorDelPlan }) : undefined}
        error={error('valorAsegurado')}
      >
        {(descripcion) => (
          <input
            id="valorAsegurado"
            type="text"
            inputMode="numeric"
            value={datos.valorAsegurado}
            aria-invalid={Boolean(errores.valorAsegurado)}
            aria-describedby={descripcion}
            onChange={(evento) => onCambiar({ valorAsegurado: evento.target.value })}
          />
        )}
      </Campo>
    </div>
  )
}
