import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import type { Cotizacion } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import { formatearCuentaRegresiva, formatearHora, formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Cotizacion.module.css'
import { DetallePrecio } from './DetallePrecio'
import { useCuentaRegresiva } from './useCuentaRegresiva'

interface Propiedades {
  cotizacion: Cotizacion
  recotizando: boolean
  onRecotizar: () => void
}

export function PasoOferta({ cotizacion, recotizando, onRecotizar }: Propiedades) {
  const { t, i18n } = useTranslation()
  const idioma = i18n.language as Idioma
  const segundos = useCuentaRegresiva(cotizacion.vigente_hasta)
  const vencida = cotizacion.estado === 'vencida' || segundos === 0
  const [verDetalle, setVerDetalle] = useState(false)
  const idDetalle = `detalle-${cotizacion.id}`

  return (
    <div className={estilos.pila}>
      <div>
        <h3>{cotizacion.nombre_producto}</h3>
        <p className={estilos.ayuda}>{t('cotizacion.oferta.numero', { id: cotizacion.id })}</p>
      </div>

      <p className={estilos.prima}>
        {formatearMoneda(cotizacion.prima_mensual, idioma)}
        <small>{t('cotizacion.oferta.porMes')}</small>
      </p>

      {cotizacion.ahorro_mensual.valor > 0 && (
        <p className={estilos.ahorro}>
          {t('cotizacion.oferta.ahorro', {
            ahorro: formatearMoneda(cotizacion.ahorro_mensual, idioma),
            estandar: formatearMoneda(cotizacion.prima_estandar, idioma),
          })}
        </p>
      )}

      {vencida ? (
        <Aviso tipo="error" rol="alert">
          <p>
            <strong>{t('cotizacion.oferta.vencida')}</strong> {t('cotizacion.oferta.vencidaDetalle')}
          </p>
          <Boton variante="secundario" onClick={onRecotizar} disabled={recotizando}>
            {recotizando ? t('cotizacion.oferta.recotizando') : t('cotizacion.oferta.recotizar')}
          </Boton>
        </Aviso>
      ) : (
        <p className={estilos.vigencia}>
          <span aria-hidden="true">
            {t('cotizacion.oferta.vigencia')}{' '}
            <span className={estilos.contador} role="timer">
              {formatearCuentaRegresiva(segundos)}
            </span>
          </span>
          <span className="sr-only">
            {t('cotizacion.oferta.vigenciaHasta', { hora: formatearHora(cotizacion.vigente_hasta, idioma) })}
          </span>
        </p>
      )}

      {cotizacion.tipo_tarifa === 'estandar' ? (
        // Sin el proveedor de Open Finance es una advertencia, no un error: la oferta es válida.
        <Aviso tipo={cotizacion.motivo_tarifa === 'proveedor_no_disponible' ? 'advertencia' : 'info'}>
          <p>
            <strong>{t('cotizacion.oferta.estandar.titulo')}.</strong>{' '}
            {t(`cotizacion.oferta.estandar.${cotizacion.motivo_tarifa ?? 'sin_consentimiento'}`)}{' '}
            {cotizacion.motivo_tarifa === 'sin_consentimiento' && (
              <Link to="/consentimientos">{t('cotizacion.openFinance.otorgar')}</Link>
            )}
          </p>
        </Aviso>
      ) : (
        <Aviso tipo={cotizacion.base_perfilamiento === 'open_finance_en_cache' ? 'advertencia' : 'exito'}>
          <p>{t(`cotizacion.oferta.base.${cotizacion.base_perfilamiento}`)}</p>
        </Aviso>
      )}

      <dl className={estilos.detalles}>
        <dt>{t('cotizacion.oferta.valorAsegurado')}</dt>
        <dd>{cotizacion.valor_asegurado}</dd>
        <dt>{t('cotizacion.oferta.deducible')}</dt>
        <dd>{cotizacion.deducible}</dd>
      </dl>

      <div>
        <h4>{t('cotizacion.oferta.coberturas')}</h4>
        <ul className={estilos.coberturas}>
          {cotizacion.coberturas.map((cobertura) => (
            <li key={cobertura.nombre}>
              {cobertura.nombre}: {cobertura.limite}
            </li>
          ))}
        </ul>
      </div>

      <button
        type="button"
        className={estilos.enlaceTexto}
        aria-expanded={verDetalle}
        aria-controls={idDetalle}
        onClick={() => setVerDetalle((visible) => !visible)}
      >
        {t('cotizacion.precio.porQue')}
      </button>
      {verDetalle && <DetallePrecio cotizacion={cotizacion} id={idDetalle} />}

      {!vencida && (
        <Link className={clasesBoton('primario')} to={`/suscripcion/${cotizacion.id}`}>
          {t('cotizacion.oferta.aceptar')}
        </Link>
      )}
    </div>
  )
}
