import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { bff } from '../../api/cliente'
import type { Consentimiento, EventoConsentimiento, Finalidad } from '../../api/tipos'
import { formatearFechaHora } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Consentimientos.module.css'

type Carga = { tipo: 'cargando' } | { tipo: 'error' } | { tipo: 'listo'; eventos: EventoConsentimiento[] }

/** Todos los eventos de la finalidad, de todos sus consentimientos, del más antiguo al más reciente. */
async function obtenerEventos(consentimientos: Consentimiento[]): Promise<Carga> {
  try {
    // Del más antiguo al más reciente, para que dos eventos con la misma hora conserven ese orden.
    const enOrden = [...consentimientos].reverse()
    const respuestas = await Promise.all(
      enOrden.map((c) => bff.GET('/consentimientos/{id}/eventos', { params: { path: { id: c.id } } })),
    )
    if (respuestas.some((r) => !r.data)) return { tipo: 'error' }
    const eventos = respuestas
      .flatMap((r) => r.data!)
      .sort((a, b) => new Date(a.ocurrido_en).getTime() - new Date(b.ocurrido_en).getTime())
    return { tipo: 'listo', eventos }
  } catch {
    return { tipo: 'error' }
  }
}

interface Propiedades {
  id: string
  finalidad: Finalidad
  consentimientos: Consentimiento[]
}

/** Histórico de la finalidad, incluidos los revocados y los vencidos (WEB-F02, criterio 8). */
export function Historial({ id, finalidad, consentimientos }: Propiedades) {
  const { t, i18n } = useTranslation()
  const [carga, setCarga] = useState<Carga>({ tipo: 'cargando' })

  useEffect(() => {
    let vigente = true
    void obtenerEventos(consentimientos).then((resultado) => {
      if (vigente) setCarga(resultado)
    })
    return () => {
      vigente = false
    }
  }, [consentimientos])

  return (
    <section id={id} aria-label={t('consentimientos.historial.titulo', { finalidad: finalidad.nombre })}>
      {carga.tipo === 'cargando' && <p role="status">{t('consentimientos.historial.cargando')}</p>}
      {carga.tipo === 'error' && <p role="alert">{t('consentimientos.historial.error')}</p>}
      {carga.tipo === 'listo' && carga.eventos.length === 0 && <p>{t('consentimientos.historial.vacio')}</p>}
      {carga.tipo === 'listo' && carga.eventos.length > 0 && (
        <ol className={estilos.historial}>
          {carga.eventos.map((evento, indice) => (
            <li key={`${evento.ocurrido_en}-${evento.tipo}-${indice}`}>
              <strong>{t(`consentimientos.historial.evento.${evento.tipo}`)}</strong>
              <p>
                {t('consentimientos.historial.detalle', {
                  fecha: formatearFechaHora(evento.ocurrido_en, i18n.language as Idioma),
                  canal: t(`consentimientos.historial.canal.${evento.canal}`),
                  version: evento.version_texto,
                })}
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
