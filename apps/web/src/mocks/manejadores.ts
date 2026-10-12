/**
 * Mocks del BFF web (MSW). Implementan contratos/bff-web/openapi.yaml con un estado en memoria
 * que reproduce el guion del Sprint 1: sin consentimiento la cotización usa tarifa estándar,
 * al otorgarlo pasa a personalizada y al revocarlo vuelve a estándar.
 */
import { http, HttpResponse } from 'msw'

import { PREFIJO_BFF } from '../api/cliente'
import type {
  Consentimiento,
  Cotizacion,
  ErrorApi,
  EventoConsentimiento,
  FactorPrecio,
  SolicitudConsentimiento,
  SolicitudCotizacion,
} from '../api/tipos'
import {
  DESCUENTO_PERSONALIZADA,
  ENTIDADES,
  FINALIDADES,
  PRIMA_ESTANDAR,
  PRODUCTOS,
  SESION,
  VIGENCIA_COTIZACION_DIAS,
} from './datos'
import { estado, siguienteId } from './estado'

const ruta = (camino: string) => `*${PREFIJO_BFF}${camino}`

function error(status: number, codigo: string, mensaje: string) {
  return HttpResponse.json<ErrorApi>({ codigo, mensaje }, { status })
}

function sumarMeses(fecha: Date, meses: number): string {
  const resultado = new Date(fecha)
  resultado.setMonth(resultado.getMonth() + meses)
  return resultado.toISOString()
}

function registrarEvento(id: string, tipo: EventoConsentimiento['tipo'], version: string) {
  const evento: EventoConsentimiento = {
    tipo,
    ocurrido_en: new Date().toISOString(),
    canal: 'web',
    version_texto: version,
  }
  estado.eventos.set(id, [...(estado.eventos.get(id) ?? []), evento])
}

function buscarConsentimiento(id: string): Consentimiento | undefined {
  return estado.consentimientos.find((c) => c.id === id)
}

function tieneConsentimientoVigente(finalidad: Consentimiento['finalidad']): boolean {
  return estado.consentimientos.some((c) => c.finalidad === finalidad && c.estado === 'vigente')
}

function factores(personalizada: boolean): FactorPrecio[] {
  if (personalizada) {
    return [
      { codigo: 'comportamiento_financiero', nombre: 'Comportamiento financiero', peso: 0.4, efecto: 'disminuye' },
      { codigo: 'siniestralidad_sector', nombre: 'Siniestralidad del sector', peso: 0.35, efecto: 'aumenta' },
      { codigo: 'ubicacion', nombre: 'Ubicación', peso: 0.25, efecto: 'neutro' },
    ]
  }
  return [
    { codigo: 'siniestralidad_sector', nombre: 'Siniestralidad del sector', peso: 0.6, efecto: 'aumenta' },
    { codigo: 'ubicacion', nombre: 'Ubicación', peso: 0.4, efecto: 'neutro' },
  ]
}

export const manejadores = [
  http.get(ruta('/sesion'), () => HttpResponse.json(SESION)),

  http.get(ruta('/consentimientos/finalidades'), () => HttpResponse.json(FINALIDADES)),

  http.get(ruta('/consentimientos'), () => HttpResponse.json(estado.consentimientos)),

  http.post(ruta('/consentimientos'), async ({ request }) => {
    const solicitud = (await request.json()) as SolicitudConsentimiento
    const finalidad = FINALIDADES.find((f) => f.codigo === solicitud.finalidad)
    if (!finalidad) return error(422, 'finalidad_invalida', 'La finalidad no existe')

    const entidades = ENTIDADES.filter((e) => solicitud.entidades_origen?.includes(e.id))
    if (entidades.length === 0 || entidades.length !== solicitud.entidades_origen.length) {
      return error(422, 'entidades_invalidas', 'Debe autorizar al menos una entidad válida')
    }
    if (solicitud.version_texto !== finalidad.version_texto) {
      return error(422, 'version_texto_desactualizada', 'El texto legal aceptado no es el vigente')
    }
    if (tieneConsentimientoVigente(finalidad.codigo)) {
      return error(409, 'consentimiento_ya_vigente', 'Ya existe un consentimiento vigente')
    }

    const ahora = new Date()
    const consentimiento: Consentimiento = {
      id: siguienteId('con'),
      finalidad: finalidad.codigo,
      entidades_origen: entidades,
      estado: 'vigente',
      canal: 'web',
      otorgado_en: ahora.toISOString(),
      vence_en: sumarMeses(ahora, finalidad.vigencia_meses),
      revocado_en: null,
      version_texto: finalidad.version_texto,
    }
    estado.consentimientos.push(consentimiento)
    registrarEvento(consentimiento.id, 'otorgado', consentimiento.version_texto)
    return HttpResponse.json(consentimiento, { status: 201 })
  }),

  http.post(ruta('/consentimientos/:id/revocacion'), ({ params }) => {
    const consentimiento = buscarConsentimiento(String(params.id))
    if (!consentimiento) return error(404, 'consentimiento_no_encontrado', 'No existe')
    if (consentimiento.estado !== 'vigente') {
      return error(409, 'consentimiento_no_vigente', 'Solo se revoca un consentimiento vigente')
    }
    consentimiento.estado = 'revocado'
    consentimiento.revocado_en = new Date().toISOString()
    registrarEvento(consentimiento.id, 'revocado', consentimiento.version_texto)
    return HttpResponse.json(consentimiento)
  }),

  http.post(ruta('/consentimientos/:id/renovacion'), ({ params }) => {
    const consentimiento = buscarConsentimiento(String(params.id))
    if (!consentimiento) return error(404, 'consentimiento_no_encontrado', 'No existe')
    if (consentimiento.estado === 'revocado') {
      return error(409, 'consentimiento_revocado', 'Un consentimiento revocado se otorga de nuevo')
    }
    const finalidad = FINALIDADES.find((f) => f.codigo === consentimiento.finalidad)
    consentimiento.estado = 'vigente'
    consentimiento.vence_en = sumarMeses(new Date(), finalidad?.vigencia_meses ?? 12)
    registrarEvento(consentimiento.id, 'renovado', consentimiento.version_texto)
    return HttpResponse.json(consentimiento)
  }),

  http.get(ruta('/consentimientos/:id/eventos'), ({ params }) => {
    const id = String(params.id)
    if (!buscarConsentimiento(id)) return error(404, 'consentimiento_no_encontrado', 'No existe')
    return HttpResponse.json(estado.eventos.get(id) ?? [])
  }),

  http.get(ruta('/cotizaciones/productos'), () => HttpResponse.json(PRODUCTOS)),

  http.post(ruta('/cotizaciones'), async ({ request }) => {
    const solicitud = (await request.json()) as SolicitudCotizacion
    const base = PRIMA_ESTANDAR[solicitud.producto]
    if (base === undefined) return error(422, 'producto_invalido', 'El producto no existe')

    const personalizada = tieneConsentimientoVigente('cotizacion')
    const ahora = new Date()
    const vigenteHasta = new Date(ahora)
    vigenteHasta.setDate(vigenteHasta.getDate() + VIGENCIA_COTIZACION_DIAS)

    const cotizacion: Cotizacion = {
      id: siguienteId('cot'),
      producto: solicitud.producto,
      prima_mensual: {
        valor: personalizada ? Math.round(base * (1 - DESCUENTO_PERSONALIZADA)) : base,
        moneda: 'COP',
      },
      tipo_tarifa: personalizada ? 'personalizada' : 'estandar',
      motivo_tarifa: personalizada ? null : 'sin_consentimiento',
      factores: factores(personalizada),
      creada_en: ahora.toISOString(),
      vigente_hasta: vigenteHasta.toISOString(),
    }
    estado.cotizaciones.set(cotizacion.id, cotizacion)
    return HttpResponse.json(cotizacion, { status: 201 })
  }),

  http.get(ruta('/cotizaciones/:id'), ({ params }) => {
    const cotizacion = estado.cotizaciones.get(String(params.id))
    if (!cotizacion) return error(404, 'cotizacion_no_encontrada', 'No existe')
    return HttpResponse.json(cotizacion)
  }),
]
