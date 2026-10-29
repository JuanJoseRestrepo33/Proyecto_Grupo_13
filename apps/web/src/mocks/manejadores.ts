/**
 * Mocks del BFF web (MSW). Implementan contratos/bff-web/openapi.yaml con un estado en memoria
 * que reproduce el guion del Sprint 1: sin consentimiento la cotización usa tarifa estándar,
 * al otorgarlo pasa a personalizada y al revocarlo vuelve a estándar. El checkout (WEB-F03)
 * paga contra una pasarela simulada que responde según `estado.pasarela`, y el proveedor de
 * Open Finance responde según `estado.openFinance`. Los siniestros (WEB-F05) se reportan sobre
 * `estado.polizas`, con la misma `Idempotency-Key` reconocida como reintento. Sin sesión
 * (`estado.sesion` en `null`), toda ruta protegida responde 401 `sesion_no_vigente` (WEB-F13).
 */
import { delay, http, HttpResponse } from 'msw'

import { PREFIJO_BFF, SESION_NO_VIGENTE } from '../api/cliente'
import type {
  Consentimiento,
  Credenciales,
  ErrorVigencia,
  Cotizacion,
  DatosAsegurado,
  ErrorApi,
  EventoConsentimiento,
  Pago,
  Producto,
  SolicitudConsentimiento,
  SolicitudCotizacion,
  SolicitudPago,
  SolicitudSiniestro,
  SolicitudSuscripcion,
  Siniestro,
  Suscripcion,
} from '../api/tipos'
import { diaLocal } from '../formato'
import {
  CREDENCIALES_PRUEBA,
  ENTIDADES,
  FACTORES_ESTANDAR,
  FACTORES_OPEN_FINANCE,
  FINALIDADES,
  PRODUCTOS,
  SESION,
  VERSION_REGLA,
  VIGENCIA_COTIZACION_MS,
} from './datos'
import { type ConsentimientoGuardado, estado, type PagoGuardado, siguienteId } from './estado'

const ruta = (camino: string) => `*${PREFIJO_BFF}${camino}`

function error(status: number, codigo: string, mensaje: string) {
  return HttpResponse.json<ErrorApi>({ codigo, mensaje }, { status })
}

function sumarMeses(fecha: Date, meses: number): string {
  const resultado = new Date(fecha)
  resultado.setMonth(resultado.getMonth() + meses)
  return resultado.toISOString()
}

const DIA_MS = 24 * 60 * 60 * 1000
/** Días antes del vencimiento en que se avisa y se ofrece renovar (WEB-F02, criterio 7). */
const DIAS_AVISO_VENCIMIENTO = 30

function registrarEvento(
  id: string,
  tipo: EventoConsentimiento['tipo'],
  version: string,
  { canal = 'web', ocurridoEn = new Date().toISOString() }: { canal?: EventoConsentimiento['canal']; ocurridoEn?: string } = {},
) {
  const evento: EventoConsentimiento = { tipo, ocurrido_en: ocurridoEn, canal, version_texto: version }
  estado.eventos.set(id, [...(estado.eventos.get(id) ?? []), evento])
}

/** Los vigentes cuya fecha ya pasó quedan vencidos; el evento lleva la fecha en que vencieron. */
function actualizarVencimientos(): void {
  const ahora = Date.now()
  for (const consentimiento of estado.consentimientos) {
    if (consentimiento.estado === 'vigente' && ahora >= new Date(consentimiento.vence_en).getTime()) {
      consentimiento.estado = 'vencido'
      registrarEvento(consentimiento.id, 'vencido', consentimiento.version_texto, {
        canal: 'sistema',
        ocurridoEn: consentimiento.vence_en,
      })
    }
  }
}

function respuesta(consentimiento: ConsentimientoGuardado): Consentimiento {
  const vigente = consentimiento.estado === 'vigente'
  const dias = vigente
    ? Math.max(0, Math.ceil((new Date(consentimiento.vence_en).getTime() - Date.now()) / DIA_MS))
    : null
  return {
    ...consentimiento,
    dias_para_vencer: dias,
    por_vencer: dias !== null && dias <= DIAS_AVISO_VENCIMIENTO,
  }
}

function buscarConsentimiento(id: string): ConsentimientoGuardado | undefined {
  actualizarVencimientos()
  return estado.consentimientos.find((c) => c.id === id)
}

function tieneConsentimientoVigente(finalidad: Consentimiento['finalidad']): boolean {
  actualizarVencimientos()
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

/** Por qué la prima es la estándar; `null` si se puede personalizar. */
function motivoTarifa(solicitud: SolicitudCotizacion): Cotizacion['motivo_tarifa'] {
  if (!solicitud.usar_open_finance) return 'open_finance_desactivado'
  if (!tieneConsentimientoVigente('cotizacion')) return 'sin_consentimiento'
  if (estado.openFinance === 'caido') return 'proveedor_no_disponible'
  return null
}

/** Cálculo aditivo: prima = estándar × (1 + suma de efectos de los factores). */
function cotizar(producto: Producto, solicitud: SolicitudCotizacion): Cotizacion {
  const motivo = motivoTarifa(solicitud)
  const personalizada = motivo === null
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
    base_perfilamiento: !personalizada
      ? 'estandar'
      : estado.openFinance === 'degradado'
        ? 'open_finance_en_cache'
        : 'open_finance',
    motivo_tarifa: motivo,
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

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const MEDIOS: SolicitudPago['medio'][] = ['tarjeta_credito', 'pse']
const TIPOS_EVENTO: SolicitudSiniestro['tipo_evento'][] = [
  'accidente_vehiculo',
  'hurto',
  'dano_hogar',
  'retraso_vuelo',
  'equipo_danado_o_perdido',
  'cancelacion_viaje',
  'otro',
]
/** Lo que tarda el núcleo en registrar un reporte; visible en el navegador, corto en las pruebas. */
const LATENCIA_REPORTE_MS = import.meta.env.MODE === 'test' ? 20 : 600

function solicitudSiniestroValida(solicitud: Partial<SolicitudSiniestro>): boolean {
  const { numero_poliza: poliza, tipo_evento: tipo, ocurrido_el: dia, descripcion, monto_estimado: monto } = solicitud
  return (
    typeof poliza === 'string' &&
    poliza.length > 0 &&
    TIPOS_EVENTO.includes(tipo!) &&
    typeof dia === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(dia) &&
    typeof descripcion === 'string' &&
    descripcion.length >= 10 &&
    descripcion.length <= 2000 &&
    (monto === undefined || monto === null || (Number.isInteger(monto.valor) && monto.valor >= 0))
  )
}
/** Lo que tarda la pasarela simulada; visible en el navegador, corto en las pruebas. */
const LATENCIA_PASARELA_MS = import.meta.env.MODE === 'test' ? 20 : 1200

function suscripcionConPago(id: string): Suscripcion | undefined {
  const guardada = estado.suscripciones.get(id)
  if (!guardada) return undefined
  const { pagoId, ...suscripcion } = guardada
  const pago = pagoId ? estado.pagos.get(pagoId) : undefined
  return { ...suscripcion, pago: pago ? { ...pago, reintento: false } : null }
}

/** Registra el resultado de la pasarela; con el pago aprobado la suscripción queda pagada. */
function guardarPago(suscripcionId: string, pago: PagoGuardado): void {
  estado.pagos.set(pago.id, pago)
  const suscripcion = estado.suscripciones.get(suscripcionId)!
  suscripcion.pagoId = pago.id
  if (pago.estado === 'aprobado') suscripcion.estado = 'pagada'
}

/** Mismo resultado para un correo que no existe y para una contraseña equivocada (WEB-F13, criterio 3). */
function credencialesValidas({ correo, contrasena }: Partial<Credenciales>): boolean {
  return (
    correo?.trim().toLowerCase() === CREDENCIALES_PRUEBA.correo && contrasena === CREDENCIALES_PRUEBA.contrasena
  )
}

export const manejadores = [
  http.post(ruta('/sesion'), async ({ request }) => {
    const credenciales = (await request.json().catch(() => null)) as Partial<Credenciales> | null
    if (typeof credenciales?.correo !== 'string' || typeof credenciales.contrasena !== 'string') {
      return error(422, 'solicitud_invalida', 'Faltan el correo o la contraseña')
    }
    if (!credencialesValidas(credenciales)) {
      return error(401, 'credenciales_invalidas', 'Correo o contraseña incorrectos')
    }
    estado.sesion = SESION
    return HttpResponse.json(SESION)
  }),

  http.delete(ruta('/sesion'), () => {
    estado.sesion = null
    return new HttpResponse(null, { status: 204 })
  }),

  // Las rutas de arriba no exigen sesión; las de abajo sí. Con sesión, este manejador no responde
  // y la solicitud sigue al manejador de su ruta.
  http.all(ruta('/*'), () => {
    if (!estado.sesion) return error(401, SESION_NO_VIGENTE, 'No hay una sesión vigente')
  }),

  http.get(ruta('/sesion'), () => HttpResponse.json(estado.sesion)),

  http.get(ruta('/consentimientos/finalidades'), () => HttpResponse.json(FINALIDADES)),

  http.get(ruta('/consentimientos'), () => {
    actualizarVencimientos()
    return HttpResponse.json(estado.consentimientos.map(respuesta))
  }),

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
    const consentimiento: ConsentimientoGuardado = {
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
    return HttpResponse.json(respuesta(consentimiento), { status: 201 })
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
    return HttpResponse.json(respuesta(consentimiento))
  }),

  http.post(ruta('/consentimientos/:id/renovacion'), ({ params }) => {
    const consentimiento = buscarConsentimiento(String(params.id))
    if (!consentimiento) return error(404, 'consentimiento_no_encontrado', 'No existe')
    if (consentimiento.estado === 'revocado') {
      return error(409, 'consentimiento_revocado', 'Un consentimiento revocado se otorga de nuevo')
    }
    if (consentimiento.estado === 'vencido' && tieneConsentimientoVigente(consentimiento.finalidad)) {
      return error(409, 'consentimiento_ya_vigente', 'Ya hay otro consentimiento vigente para la finalidad')
    }
    const finalidad = FINALIDADES.find((f) => f.codigo === consentimiento.finalidad)
    consentimiento.estado = 'vigente'
    consentimiento.vence_en = sumarMeses(new Date(), finalidad?.vigencia_meses ?? 12)
    registrarEvento(consentimiento.id, 'renovado', consentimiento.version_texto)
    return HttpResponse.json(respuesta(consentimiento))
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

  http.post(ruta('/suscripciones'), async ({ request }) => {
    const { cotizacion_id: cotizacionId } = (await request.json()) as SolicitudSuscripcion
    const guardada = estado.cotizaciones.get(cotizacionId)
    if (!guardada) return error(404, 'cotizacion_no_encontrada', 'No existe')
    const existente = [...estado.suscripciones.values()].find((s) => s.cotizacion_id === cotizacionId)
    if (existente) return HttpResponse.json(suscripcionConPago(existente.id))
    const cotizacion = conEstadoActual(guardada.cotizacion)
    if (cotizacion.estado === 'vencida') {
      return error(409, 'cotizacion_vencida', 'La oferta venció; hay que recotizar')
    }
    const id = siguienteId('sus')
    estado.suscripciones.set(id, {
      id,
      cotizacion_id: cotizacion.id,
      nombre_producto: cotizacion.nombre_producto,
      prima_mensual: cotizacion.prima_mensual,
      estado: 'aceptada',
      aceptada_en: new Date().toISOString(),
      pagoId: null,
    })
    return HttpResponse.json(suscripcionConPago(id), { status: 201 })
  }),

  http.get(ruta('/suscripciones/:id'), ({ params }) => {
    const suscripcion = suscripcionConPago(String(params.id))
    if (!suscripcion) return error(404, 'suscripcion_no_encontrada', 'No existe')
    return HttpResponse.json(suscripcion)
  }),

  http.post(ruta('/suscripciones/:id/pagos'), async ({ params, request }) => {
    const suscripcionId = String(params.id)
    const suscripcion = suscripcionConPago(suscripcionId)
    if (!suscripcion) return error(404, 'suscripcion_no_encontrada', 'No existe')
    const clave = request.headers.get('Idempotency-Key') ?? ''
    if (!UUID.test(clave)) return error(422, 'clave_idempotencia_invalida', 'Falta la clave del intento')
    const { medio } = (await request.json()) as SolicitudPago
    if (!MEDIOS.includes(medio)) return error(422, 'medio_invalido', 'Medio de pago no admitido')

    // Misma clave: es el mismo intento. Nunca se cobra dos veces.
    const intento = estado.intentos.get(clave)
    if (intento) {
      if (intento.suscripcionId !== suscripcionId) {
        return error(422, 'clave_idempotencia_invalida', 'La clave pertenece a otra suscripción')
      }
      if (intento.pago === 'procesando') return error(409, 'pago_en_curso', 'El pago sigue en curso')
      const repetido: Pago = { ...estado.pagos.get(intento.pago.id)!, reintento: true }
      return HttpResponse.json(repetido)
    }
    // Otra clave: solo si no hay ya un pago aprobado o pendiente.
    if (suscripcion.pago && suscripcion.pago.estado !== 'rechazado') {
      return error(409, 'pago_ya_registrado', 'La suscripción ya tiene un pago')
    }

    estado.intentos.set(clave, { suscripcionId, pago: 'procesando' })
    await delay(LATENCIA_PASARELA_MS)
    const pago: PagoGuardado = {
      id: siguienteId('pag'),
      estado: estado.pasarela[medio],
      medio,
      monto: suscripcion.prima_mensual,
      referencia: `TX-${String(estado.secuencia).padStart(8, '0')}`,
      creado_en: new Date().toISOString(),
    }
    guardarPago(suscripcionId, pago)
    estado.intentos.set(clave, { suscripcionId, pago })
    const nuevo: Pago = { ...pago, reintento: false }
    return HttpResponse.json(nuevo, { status: 201 })
  }),

  http.get(ruta('/suscripciones/:id/pagos/:idPago'), ({ params }) => {
    const suscripcionId = String(params.id)
    const pago = estado.pagos.get(String(params.idPago))
    if (!estado.suscripciones.has(suscripcionId) || !pago) {
      return error(404, 'pago_no_encontrado', 'No existe')
    }
    // Simulación: el banco confirma un pago pendiente en cuanto se consulta.
    if (pago.estado === 'pendiente') guardarPago(suscripcionId, { ...pago, estado: 'aprobado' })
    const actual: Pago = { ...estado.pagos.get(pago.id)!, reintento: false }
    return HttpResponse.json(actual)
  }),

  http.get(ruta('/polizas'), () => HttpResponse.json(estado.polizas)),

  http.get(ruta('/siniestros'), () => HttpResponse.json([...estado.siniestros].reverse())),

  http.post(ruta('/siniestros'), async ({ request }) => {
    const clave = request.headers.get('Idempotency-Key') ?? ''
    if (!UUID.test(clave)) return error(422, 'clave_idempotencia_invalida', 'Falta la clave del reporte')
    const cuerpo = await request.text()
    const solicitud = JSON.parse(cuerpo || '{}') as Partial<SolicitudSiniestro>

    // Misma clave: es el mismo envío. Nunca se crea un segundo siniestro (WEB-F05, criterio 4).
    const previo = estado.reportes.get(clave)
    if (previo) {
      if (previo.solicitud !== cuerpo) {
        return error(409, 'clave_idempotencia_en_uso', 'La clave ya se usó para otro reporte')
      }
      return HttpResponse.json(estado.siniestros.find((s) => s.id === previo.siniestroId), { status: 200 })
    }

    if (!solicitudSiniestroValida(solicitud)) return error(422, 'solicitud_invalida', 'Datos del reporte inválidos')
    const { numero_poliza: numero, ocurrido_el: dia } = solicitud as SolicitudSiniestro
    const poliza = estado.polizas.find((p) => p.numero === numero)
    if (!poliza) return error(404, 'poliza_no_encontrada', 'La póliza no existe')
    if (dia > diaLocal()) return error(422, 'fecha_futura', 'El evento no puede ser futuro')
    if (dia < poliza.inicio || dia > poliza.fin) {
      return HttpResponse.json<ErrorVigencia>(
        {
          codigo: 'evento_fuera_de_vigencia',
          mensaje: 'El evento ocurrió fuera de la vigencia de la póliza',
          vigente_desde: poliza.inicio,
          vigente_hasta: poliza.fin,
        },
        { status: 409 },
      )
    }

    const id = siguienteId('sin')
    const siniestro: Siniestro = {
      id,
      referencia: `SIN-2026-${String(estado.secuencia).padStart(6, '0')}`,
      numero_poliza: numero,
      tipo_evento: solicitud.tipo_evento!,
      ocurrido_el: dia,
      descripcion: solicitud.descripcion!,
      monto_estimado: solicitud.monto_estimado ?? null,
      estado: 'reportado',
      siguiente_paso: 'adjuntar_evidencia',
      canal: 'web',
      reportado_en: new Date().toISOString(),
    }
    estado.siniestros.push(siniestro)
    estado.reportes.set(clave, { siniestroId: id, solicitud: cuerpo })
    // Se registra antes de esperar: un reintento simultáneo con la misma clave recibe este mismo.
    await delay(LATENCIA_REPORTE_MS)
    return HttpResponse.json(siniestro, { status: 201 })
  }),

  http.get(ruta('/siniestros/:id'), ({ params }) => {
    const siniestro = estado.siniestros.find((s) => s.id === String(params.id))
    if (!siniestro) return error(404, 'siniestro_no_encontrado', 'No existe')
    return HttpResponse.json(siniestro)
  }),
]
