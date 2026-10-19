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
  DatosAsegurado,
  ErrorApi,
  EventoConsentimiento,
  Producto,
  SolicitudConsentimiento,
  SolicitudCotizacion,
} from '../api/tipos'
import {
  ENTIDADES,
  FACTORES_ESTANDAR,
  FACTORES_OPEN_FINANCE,
  FINALIDADES,
  PRODUCTOS,
  SESION,
  VERSION_REGLA,
  VIGENCIA_COTIZACION_MS,
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

function aseguradoValido(asegurado: DatosAsegurado | undefined): boolean {
  if (!asegurado) return false
  const { edad, ciudad, valor_asegurado: valor } = asegurado
  const edadValida = Number.isInteger(edad) && edad >= 18 && edad <= 100
  const ciudadValida = typeof ciudad === 'string' && ciudad.trim().length > 0 && ciudad.length <= 80
  const valorValido = valor === undefined || valor === null || (Number.isInteger(valor) && valor >= 0)
  return edadValida && ciudadValida && valorValido
}

/** Cálculo aditivo: prima = estándar × (1 + suma de efectos de los factores). */
function cotizar(producto: Producto, solicitud: SolicitudCotizacion): Cotizacion {
  const conConsentimiento = tieneConsentimientoVigente('cotizacion')
  const personalizada = solicitud.usar_open_finance && conConsentimiento
  const factores = personalizada ? FACTORES_OPEN_FINANCE : FACTORES_ESTANDAR
  const efectoTotal = factores.reduce((total, f) => total + f.efecto, 0)
  const estandar = producto.prima_estandar
  const prima = Math.round(estandar.valor * (1 + efectoTotal))
  const ahora = new Date()

  const cotizacion: Cotizacion = {
    id: siguienteId('cot'),
    producto: producto.codigo,
    nombre_producto: producto.nombre,
    estado: 'vigente',
    prima_mensual: { valor: prima, moneda: estandar.moneda },
    prima_estandar: estandar,
    ahorro_mensual: { valor: Math.max(estandar.valor - prima, 0), moneda: estandar.moneda },
    tipo_tarifa: personalizada ? 'personalizada' : 'estandar',
    base_perfilamiento: personalizada ? 'open_finance' : 'estandar',
    motivo_tarifa: personalizada
      ? null
      : solicitud.usar_open_finance
        ? 'sin_consentimiento'
        : 'open_finance_desactivado',
    factores,
    version_regla: VERSION_REGLA,
    valor_asegurado: producto.valor_asegurado,
    deducible: producto.deducible,
    coberturas: producto.coberturas,
    creada_en: ahora.toISOString(),
    vigente_hasta: new Date(ahora.getTime() + VIGENCIA_COTIZACION_MS).toISOString(),
  }
  estado.cotizaciones.set(cotizacion.id, { cotizacion, solicitud })
  return cotizacion
}

/** El estado (vigente o vencida) se calcula al responder. */
function conEstadoActual(cotizacion: Cotizacion): Cotizacion {
  const vencida = Date.now() >= new Date(cotizacion.vigente_hasta).getTime()
  return { ...cotizacion, estado: vencida ? 'vencida' : 'vigente' }
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
    const producto = PRODUCTOS.find((p) => p.codigo === solicitud.producto)
    if (!producto) return error(422, 'producto_invalido', 'El producto no existe')
    if (!aseguradoValido(solicitud.asegurado)) {
      return error(422, 'asegurado_invalido', 'Edad entre 18 y 100 años y ciudad obligatoria')
    }
    return HttpResponse.json(cotizar(producto, solicitud), { status: 201 })
  }),

  http.get(ruta('/cotizaciones/:id'), ({ params }) => {
    const guardada = estado.cotizaciones.get(String(params.id))
    if (!guardada) return error(404, 'cotizacion_no_encontrada', 'No existe')
    return HttpResponse.json(conEstadoActual(guardada.cotizacion))
  }),

  http.post(ruta('/cotizaciones/:id/recotizacion'), ({ params }) => {
    const guardada = estado.cotizaciones.get(String(params.id))
    if (!guardada) return error(404, 'cotizacion_no_encontrada', 'No existe')
    const producto = PRODUCTOS.find((p) => p.codigo === guardada.cotizacion.producto)!
    return HttpResponse.json(cotizar(producto, guardada.solicitud), { status: 201 })
  }),
]
