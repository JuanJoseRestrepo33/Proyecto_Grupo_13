/** Catálogos de los mocks del BFF. Datos ficticios y deterministas, aptos para aserciones. */
import type { Credenciales, Entidad, FactorPrecio, Finalidad, Producto, Sesion } from '../api/tipos'

export const SESION: Sesion = { cliente: { id: 'cli-001', nombre: 'Ana' } }

/** Cuenta de prueba de los mocks: con estas credenciales se inicia sesión como `SESION`. */
export const CREDENCIALES_PRUEBA: Credenciales = { correo: 'ana@solventa.test', contrasena: 'Solventa-2026' }

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

/** Catálogo de productos cotizables con su prima estándar mensual. */
export const PRODUCTOS: Producto[] = [
  {
    codigo: 'auto',
    nombre: 'Seguro de Auto — Plan Full',
    nombre_corto: 'Auto',
    descripcion: 'Responsabilidad civil, todo riesgo y hurto.',
    prima_estandar: { valor: 148000, moneda: 'COP' },
    valor_asegurado: '$60.000.000 COP',
    deducible: '5% del valor asegurado',
    coberturas: [
      { nombre: 'Responsabilidad civil', limite: '$1.200.000.000 COP' },
      { nombre: 'Todo riesgo (daño y hurto)', limite: 'Deducible 5%' },
      { nombre: 'Asistencia en vía 24/7', limite: 'Incluida' },
    ],
  },
  {
    codigo: 'hogar',
    nombre: 'Seguro de Hogar — Plan Protegido',
    nombre_corto: 'Hogar',
    descripcion: 'Incendio, terremoto y contenidos.',
    prima_estandar: { valor: 62000, moneda: 'COP' },
    valor_asegurado: '$250.000.000 COP',
    deducible: '$500.000 COP',
    coberturas: [
      { nombre: 'Incendio y terremoto', limite: '$250.000.000 COP' },
      { nombre: 'Contenidos', limite: '$40.000.000 COP' },
      { nombre: 'Responsabilidad civil familiar', limite: '$80.000.000 COP' },
    ],
  },
  {
    codigo: 'viaje',
    nombre: 'Seguro de Viaje — Plan Internacional',
    nombre_corto: 'Viaje',
    descripcion: 'Asistencia médica, equipaje y retraso de vuelo.',
    prima_estandar: { valor: 49000, moneda: 'COP' },
    valor_asegurado: '$1.000.000 USD',
    deducible: '$150.000 COP',
    coberturas: [
      { nombre: 'Asistencia médica', limite: '$1.000.000 USD' },
      { nombre: 'Equipaje perdido', limite: '$1.000.000 COP' },
      { nombre: 'Retraso de vuelo ≥3h (paramétrico)', limite: '$350.000 COP' },
    ],
  },
  {
    codigo: 'dispositivos',
    nombre: 'Seguro de Dispositivos — Plan Total',
    nombre_corto: 'Dispositivos',
    descripcion: 'Daño accidental, robo y pérdida.',
    prima_estandar: { valor: 21000, moneda: 'COP' },
    valor_asegurado: '$3.200.000 COP',
    deducible: '10% del daño',
    coberturas: [
      { nombre: 'Daño accidental', limite: '$3.200.000 COP' },
      { nombre: 'Robo', limite: '$3.200.000 COP' },
      { nombre: 'Pérdida', limite: '$1.600.000 COP' },
    ],
  },
]

/** Versión de la regla de tarificación y sus factores. El cálculo es aditivo sobre la prima estándar. */
export const VERSION_REGLA = 'rating-v3.2'

export const FACTORES_OPEN_FINANCE: FactorPrecio[] = [
  { nombre: 'Comportamiento de pago (Open Finance)', efecto: -0.06 },
  { nombre: 'Nivel de endeudamiento', efecto: -0.03 },
  { nombre: 'Siniestralidad de la zona (Open Data)', efecto: 0.01 },
  { nombre: 'Edad y ciudad', efecto: -0.02 },
]

export const FACTORES_ESTANDAR: FactorPrecio[] = [
  { nombre: 'Edad y ciudad (incluidas en la tarifa base)', efecto: 0 },
  { nombre: 'Siniestralidad de la zona (incluida en la tarifa base)', efecto: 0 },
  { nombre: 'Sin datos financieros: no se aplican ajustes de Open Finance', efecto: 0 },
]

/** Vigencia de una oferta (WEB-F01): después hay que recotizar. */
export const VIGENCIA_COTIZACION_MS = 5 * 60 * 1000
