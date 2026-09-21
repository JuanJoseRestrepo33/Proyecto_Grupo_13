// EXP-01 | Carga sobre el recorrido de cotizacion.
// No se ejecuta suelto: lo invoca run.sh, que cambia el escenario del
// simulador y reinicia el circuito entre corridas.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const latencia   = new Trend('cotizacion_latencia_ms');
const desdeCache = new Rate('desde_cache');
const sinPerfil  = new Rate('sin_perfil');
const errores    = new Counter('errores_http');

const RPM       = parseInt(__ENV.RPM || '1000');
const BASE_URL  = __ENV.BASE_URL || 'http://localhost:8080';
const ESCENARIO = __ENV.ESCENARIO || 'a_sano';

export const options = {
  scenarios: {
    cotizaciones: {
      executor: 'constant-arrival-rate',
      rate: RPM, timeUnit: '1m',
      duration: __ENV.DURACION || '5m',
      preAllocatedVUs: 50, maxVUs: 400,
    },
  },
  thresholds: {
    cotizacion_latencia_ms: ['p(95)<250', 'p(99)<500'],   // RC-01
    sin_perfil: ['rate<0.001'],                           // RC-03
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.post(`${BASE_URL}/cotizar`,
    JSON.stringify({ cliente: `c${Math.floor(Math.random() * 500)}`, monto: 120000000 }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '5s' });
  if (!check(res, { 'status 200': (r) => r.status === 200 })) { errores.add(1); return; }
  const b = res.json();
  latencia.add(b.latencia_ms);
  desdeCache.add(b.origen_perfil === 'cache');
  sinPerfil.add(b.origen_perfil === 'defecto');
}

export function handleSummary(data) {
  const v = (m, k) => (data.metrics[m] ? data.metrics[m].values[k] : null);
  const out = {
    experimento: 'EXP-01', escenario: ESCENARIO, rpm: RPM,
    solicitudes: v('http_reqs', 'count'),
    p50_ms: v('cotizacion_latencia_ms', 'p(50)'),
    p95_ms: v('cotizacion_latencia_ms', 'p(95)'),
    p99_ms: v('cotizacion_latencia_ms', 'p(99)'),
    max_ms: v('cotizacion_latencia_ms', 'max'),
    pct_cache: (v('desde_cache', 'rate') || 0) * 100,
    pct_sin_perfil: (v('sin_perfil', 'rate') || 0) * 100,
    errores_http: v('errores_http', 'count') || 0,
  };
  // Sin datos validos NO hay cumplimiento: evita el falso positivo null < 250.
  const exitosas = (out.solicitudes || 0) - (out.errores_http || 0);
  out.solicitudes_exitosas = exitosas;
  out.valido = exitosas > 0 && out.p95_ms !== null && out.errores_http / (out.solicitudes || 1) < 0.01;
  out.cumple_p95 = out.valido && out.p95_ms < 250;
  out.cumple_p99 = out.valido && out.p99_ms < 500;
  if (!out.valido) out.advertencia = 'CORRIDA INVALIDA: demasiados errores HTTP o sin datos';
  return {
    stdout: JSON.stringify(out, null, 2) + '\n',
    [`resultados/exp01-${ESCENARIO}-${RPM}rpm.json`]: JSON.stringify(out, null, 2),
  };
}
