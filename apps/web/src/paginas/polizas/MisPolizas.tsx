import { type KeyboardEvent, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'

import { bff } from '../../api/cliente'
import type { PolizaResumen } from '../../api/tipos'
import { Aviso } from '../../componentes/Aviso'
import { Boton } from '../../componentes/Boton'
import { clasesBoton } from '../../componentes/clasesBoton'
import { MigasDePan } from '../../componentes/MigasDePan'
import estilosCotizacion from '../cotizacion/Cotizacion.module.css'
import estilos from './Polizas.module.css'
import { TablaPolizas } from './TablaPolizas'

const MIGAS = [{ clave: 'nav.inicio', a: '/' }, { clave: 'nav.polizas' }]

const PESTANAS = ['activas', 'vencidas', 'todas'] as const
type Pestana = (typeof PESTANAS)[number]

const FILTROS: Record<Pestana, (poliza: PolizaResumen) => boolean> = {
  activas: (poliza) => poliza.estado === 'vigente',
  vencidas: (poliza) => poliza.estado === 'vencida',
  todas: () => true,
}

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'listo'; polizas: PolizaResumen[] }

async function obtenerPolizas(): Promise<Carga> {
  try {
    const { data } = await bff.GET('/polizas')
    return data ? { tipo: 'listo', polizas: data } : { tipo: 'error' }
  } catch {
    return { tipo: 'error' }
  }
}

/** «Mis pólizas» (WEB-F04): las pólizas del cliente, filtradas por pestañas según su estado. */
export function MisPolizas() {
  const { t } = useTranslation()
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })
  const [pestana, setPestana] = useState<Pestana>('activas')

  useEffect(() => {
    let vigente = true
    void obtenerPolizas().then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [])

  function reintentar() {
    setCarga({ tipo: 'cargando' })
    void obtenerPolizas().then(setCarga)
  }

  function moverseConTeclado(evento: KeyboardEvent<HTMLButtonElement>) {
    const actual = PESTANAS.indexOf(pestana)
    const destinos: Record<string, number> = {
      ArrowRight: (actual + 1) % PESTANAS.length,
      ArrowLeft: (actual - 1 + PESTANAS.length) % PESTANAS.length,
      Home: 0,
      End: PESTANAS.length - 1,
    }
    const destino = destinos[evento.key]
    if (destino === undefined) return
    evento.preventDefault()
    const siguiente = PESTANAS[destino]!
    setPestana(siguiente)
    document.getElementById(`pestana-${siguiente}`)?.focus()
  }

  const visibles = carga.tipo === 'listo' ? carga.polizas.filter(FILTROS[pestana]) : []

  return (
    <>
      <MigasDePan migas={MIGAS} />
      <div className={estilosCotizacion.encabezado}>
        <h1>{t('polizas.titulo')}</h1>
        <p>{t('polizas.descripcion')}</p>
      </div>

      {carga.tipo === 'cargando' && <p role="status">{t('sesion.cargando')}</p>}

      {carga.tipo === 'error' && (
        <Aviso tipo="error" rol="alert">
          <p>{t('polizas.errores.cargar')}</p>
          <Boton variante="secundario" onClick={reintentar}>
            {t('polizas.reintentar')}
          </Boton>
        </Aviso>
      )}

      {carga.tipo === 'listo' && carga.polizas.length === 0 && (
        <section className={`${estilosCotizacion.tarjeta} ${estilos.vacio}`} aria-labelledby="titulo-sin-polizas">
          <h2 id="titulo-sin-polizas">{t('polizas.vacio.titulo')}</h2>
          <p>{t('polizas.vacio.detalle')}</p>
          <Link className={clasesBoton('primario')} to="/cotizacion">
            {t('polizas.vacio.cotizar')}
          </Link>
        </section>
      )}

      {carga.tipo === 'listo' && carga.polizas.length > 0 && (
        <>
          <div className={estilos.pestanas} role="tablist" aria-label={t('polizas.pestanas.etiqueta')}>
            {PESTANAS.map((clave) => (
              <button
                key={clave}
                id={`pestana-${clave}`}
                type="button"
                role="tab"
                className={estilos.pestana}
                aria-selected={pestana === clave}
                aria-controls="panel-polizas"
                tabIndex={pestana === clave ? 0 : -1}
                onClick={() => setPestana(clave)}
                onKeyDown={moverseConTeclado}
              >
                {t(`polizas.pestanas.${clave}`)}
              </button>
            ))}
          </div>
          <div id="panel-polizas" role="tabpanel" aria-labelledby={`pestana-${pestana}`}>
            {visibles.length > 0 ? (
              <TablaPolizas polizas={visibles} />
            ) : (
              <div className={estilos.contenedorTabla}>
                <p className={estilos.sinResultados}>{t('polizas.pestanas.sinResultados')}</p>
              </div>
            )}
          </div>
        </>
      )}
    </>
  )
}
