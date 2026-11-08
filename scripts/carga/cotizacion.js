// ARQ-05 (#136, #216) | Carga de cotización contra staging, perfil de EXP-01.
//
//   k6 run -e BASE_URL=https://solventa-bff-…run.app -e RPM=1000 -e DURACION=5m \
//          -e ESCENARIO=sano scripts/carga/cotizacion.js
//
// Credenciales de la cuenta de prueba en HUMO_CORREO y HUMO_CLAVE (nunca se imprimen). Inicia
// sesión una vez en `setup` por el router web y cada iteración cotiza con esa cookie: el recorrido
// es el del cliente real (BFF → núcleo → perfilamiento → Cloud SQL), no el del prototipo de EXP-01.
//
// El estado del proveedor de Open Finance (sano, degradado o caído) no se cambia aquí: lo pone el
// workflow `carga.yml` en el núcleo (PERFILAMIENTO_OPEN_FINANCE_MODO) antes de correr. ESCENARIO
// solo etiqueta el resumen.
//
// Umbrales: RC-01 (p95 ≤ 250 ms, p99 ≤ 500 ms de extremo a extremo) y RC-03 (menos de 0,1 % de
// respuestas que no sean una oferta). La latencia incluye la red entre el runner y us-east1.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const RPM = parseInt(__ENV.RPM || '1000', 10);
const DURACION = __ENV.DURACION || '5m';
const ESCENARIO = __ENV.ESCENARIO || 'sano';
const WEB = `${BASE_URL}/api/v1/web`;
// Puntos de prueba de ARQ-05: la ciudad del asegurado alterna entre Bogotá y Cali.
const CIUDADES = ['Bogotá', 'Cali'];

const latencia = new Trend('cotizacion_ms', true);
const sinOferta = new Rate('cotizacion_sin_oferta');
const errorServidor = new Rate('cotizacion_5xx');
const enCache = new Rate('cotizacion_perfil_en_cache');
const estandar = new Rate('cotizacion_tarifa_estandar');

export const options = {
  scenarios: {
    cotizaciones: {
      executor: 'constant-arrival-rate',
      rate: RPM,
      timeUnit: '1m',
      duration: DURACION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    cotizacion_ms: ['p(95)<250', 'p(99)<500'],
    cotizacion_sin_oferta: ['rate<0.001'],
    cotizacion_5xx: ['rate<0.001'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!BASE_URL) fail('Falta BASE_URL');
  if (!__ENV.HUMO_CORREO || !__ENV.HUMO_CLAVE) fail('Faltan HUMO_CORREO y HUMO_CLAVE');
  const res = http.post(
    `${WEB}/sesion`,
    JSON.stringify({ correo: __ENV.HUMO_CORREO, contrasena: __ENV.HUMO_CLAVE }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'sesion' } },
  );
  const cookie = res.cookies.sesion && res.cookies.sesion[0];
  if (res.status !== 200 || !cookie) fail(`No se pudo iniciar sesión: ${res.status}`);
  return { sesion: cookie.value };
}

export default function (datos) {
  const ciudad = CIUDADES[__ITER % CIUDADES.length];
  const res = http.post(
    `${WEB}/cotizaciones`,
    JSON.stringify({
      producto: 'auto',
      asegurado: { edad: 35, ciudad, valor_asegurado: 60000000 },
      usar_open_finance: true,
    }),
    {
      headers: { 'Content-Type': 'application/json', Cookie: `sesion=${datos.sesion}` },
      tags: { name: 'cotizar', ciudad },
    },
  );
  latencia.add(res.timings.duration, { ciudad });
  errorServidor.add(res.status >= 500);
  const ok = check(res, { 'oferta emitida (201)': (r) => r.status === 201 });
  sinOferta.add(!ok);
  if (ok) {
    const oferta = res.json();
    enCache.add(oferta.base_perfilamiento === 'open_finance_en_cache');
    estandar.add(oferta.tipo_tarifa === 'estandar');
  }
}

function porcentaje(data, metrica) {
  const v = valor(data, metrica, 'rate');
  return v === null ? null : v * 100;
}

function valor(data, metrica, campo) {
  const m = data.metrics[metrica];
  return m && m.values[campo] !== undefined ? m.values[campo] : null;
}

export function handleSummary(data) {
  const resumen = {
    escenario: ESCENARIO,
    rpm: RPM,
    duracion: DURACION,
    solicitudes: valor(data, 'iterations', 'count'),
    p50_ms: valor(data, 'cotizacion_ms', 'med'),
    p95_ms: valor(data, 'cotizacion_ms', 'p(95)'),
    p99_ms: valor(data, 'cotizacion_ms', 'p(99)'),
    max_ms: valor(data, 'cotizacion_ms', 'max'),
    pct_sin_oferta: porcentaje(data, 'cotizacion_sin_oferta'),
    pct_5xx: porcentaje(data, 'cotizacion_5xx'),
    pct_perfil_en_cache: porcentaje(data, 'cotizacion_perfil_en_cache'),
    pct_tarifa_estandar: porcentaje(data, 'cotizacion_tarifa_estandar'),
    umbrales_cumplidos: Object.values(data.metrics).every(
      (m) => !m.thresholds || Object.values(m.thresholds).every((t) => t.ok),
    ),
  };
  const fila = (k, v) => `| ${k} | ${v === null ? '-' : typeof v === 'number' ? v.toFixed(2) : v} |`;
  const markdown = [
    `### Carga de cotización · escenario \`${ESCENARIO}\``,
    '',
    '| Métrica | Valor |',
    '|---|---|',
    ...Object.entries(resumen).map(([k, v]) => fila(k, v)),
    '',
  ].join('\n');
  return {
    stdout: markdown + '\n',
    [`carga-${ESCENARIO}.json`]: JSON.stringify(resumen, null, 2),
    [`carga-${ESCENARIO}.md`]: markdown,
  };
}
