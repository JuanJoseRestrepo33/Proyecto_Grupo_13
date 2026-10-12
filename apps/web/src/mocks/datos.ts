/** Catálogos de los mocks del BFF. Datos ficticios y deterministas, aptos para aserciones. */
import type { CodigoProducto, Entidad, Finalidad, Producto, Sesion } from '../api/tipos'

export const SESION: Sesion = { cliente: { id: 'cli-001', nombre: 'Ana' } }

export const ENTIDADES: Entidad[] = [
  { id: 'ent-andes', nombre: 'Banco Andes S.A.' },
  { id: 'ent-sur', nombre: 'Cooperativa Financiera del Sur' },
]

export const FINALIDADES: Finalidad[] = [
  {
    codigo: 'cotizacion',
    nombre: 'Cotización',
    descripcion: 'Calcular una prima personalizada con tu comportamiento financiero real.',
    alcance: ['Saldos promedio', 'Historial de pagos', 'Ingresos recurrentes'],
    vigencia_meses: 12,
    entidades_origen: ENTIDADES,
    version_texto: 'cotizacion-2026.1',
  },
  {
    codigo: 'suscripcion',
    nombre: 'Suscripción',
    descripcion: 'Evaluar tu solicitud de póliza sin pedirte documentos adicionales.',
    alcance: ['Ingresos recurrentes', 'Obligaciones vigentes'],
    vigencia_meses: 12,
    entidades_origen: ENTIDADES,
    version_texto: 'suscripcion-2026.1',
  },
  {
    codigo: 'prevencion_fraude',
    nombre: 'Prevención de fraude',
    descripcion: 'Verificar que las transacciones de tu póliza sean legítimas.',
    alcance: ['Titularidad de cuentas', 'Movimientos atípicos'],
    vigencia_meses: 6,
    entidades_origen: ENTIDADES,
    version_texto: 'prevencion-fraude-2026.1',
  },
]

export const PRODUCTOS: Producto[] = [
  { codigo: 'auto', nombre: 'Auto', descripcion: 'Daños, robo y responsabilidad civil.' },
  { codigo: 'hogar', nombre: 'Hogar', descripcion: 'Estructura, contenidos y asistencia.' },
  { codigo: 'viaje', nombre: 'Viaje', descripcion: 'Retrasos, equipaje y asistencia médica.' },
  { codigo: 'dispositivos', nombre: 'Dispositivos', descripcion: 'Daño accidental y hurto.' },
]

/** Prima mensual estándar por producto, en pesos colombianos. */
export const PRIMA_ESTANDAR: Record<CodigoProducto, number> = {
  auto: 132900,
  hogar: 48500,
  viaje: 23900,
  dispositivos: 15900,
}

/** Descuento sobre la prima estándar cuando hay perfil financiero (tarifa personalizada). */
export const DESCUENTO_PERSONALIZADA = 0.15

export const VIGENCIA_COTIZACION_DIAS = 7
