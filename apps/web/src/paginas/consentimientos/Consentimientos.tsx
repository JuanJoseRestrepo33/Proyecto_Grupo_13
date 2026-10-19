import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { bff } from '../../api/cliente'
import type { Consentimiento, Finalidad } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import estilos from './Consentimientos.module.css'
import { TarjetaFinalidad } from './TarjetaFinalidad'

type Carga =
  | { tipo: 'cargando' }
  | { tipo: 'error' }
  | { tipo: 'listo'; finalidades: Finalidad[]; consentimientos: Consentimiento[] }

async function obtenerDatos(): Promise<Carga> {
  try {
    const [finalidades, consentimientos] = await Promise.all([
      bff.GET('/consentimientos/finalidades'),
      bff.GET('/consentimientos'),
    ])
    if (!finalidades.data || !consentimientos.data) return { tipo: 'error' }
    return { tipo: 'listo', finalidades: finalidades.data, consentimientos: consentimientos.data }
  } catch {
    return { tipo: 'error' }
  }
}

function masRecientePrimero(a: Consentimiento, b: Consentimiento): number {
  return new Date(b.otorgado_en).getTime() - new Date(a.otorgado_en).getTime()
}

/** Gestión del consentimiento de Open Finance (WEB-F02): una tarjeta por finalidad. */
export function Consentimientos() {
  const { t } = useTranslation()
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })

  useEffect(() => {
    let vigente = true
    void obtenerDatos().then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [])

  function reintentar() {
    setCarga({ tipo: 'cargando' })
    void obtenerDatos().then(setCarga)
  }

  /** La respuesta del BFF reemplaza al consentimiento en pantalla de inmediato (criterio 3). */
  function actualizar(consentimiento: Consentimiento) {
    setCarga((actual) => {
      if (actual.tipo !== 'listo') return actual
      const otros = actual.consentimientos.filter((c) => c.id !== consentimiento.id)
      return { ...actual, consentimientos: [...otros, consentimiento] }
    })
  }

  return (
    <>
      <div className={estilos.encabezado}>
        <h1>{t('consentimientos.titulo')}</h1>
        <p>{t('consentimientos.descripcion')}</p>
      </div>

      <Aviso tipo="info">
        <p>
          <strong>{t('consentimientos.queDatos')}</strong> {t('consentimientos.queDatosDetalle')}
        </p>
      </Aviso>

      {carga.tipo === 'cargando' && <p role="status">{t('inicio.cargando')}</p>}
      {carga.tipo === 'error' && (
        <Aviso tipo="error" rol="alert">
          <p>{t('consentimientos.errores.cargar')}</p>
          <Boton variante="secundario" onClick={reintentar}>
            {t('consentimientos.errores.reintentar')}
          </Boton>
        </Aviso>
      )}
      {carga.tipo === 'listo' && (
        <div className={estilos.lista}>
          {carga.finalidades.map((finalidad) => (
            <TarjetaFinalidad
              key={finalidad.codigo}
              finalidad={finalidad}
              consentimientos={carga.consentimientos
                .filter((c) => c.finalidad === finalidad.codigo)
                .sort(masRecientePrimero)}
              onActualizado={actualizar}
            />
          ))}
        </div>
      )}
    </>
  )
}
