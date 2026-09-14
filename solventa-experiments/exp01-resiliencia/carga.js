// EXP-01 | Guion de carga k6.
//
// Ejecutar los tres escenarios del experimento:
//   k6 run -e RPM=100  -e BASE_URL=http://localhost:8080 carga.js
//   k6 run -e RPM=1000 -e BASE_URL=http://localhost:8080 carga.js
//
// El estado del proveedor (sano / degradado / caido) se cambia en WireMock,
// no en este guion: ver escenarios-wiremock/ en esta misma carpeta.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const latencia = new Trend('cotizacion_latencia_ms');
const degradadas = new Rate('cotizacion_degradada');
const sinDato = new Rate('cotizacion_sin_perfil');

const RPM = __ENV.RPM ? parseInt(__ENV.RPM) : 1000;
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    cotizaciones: {
      executor: 'constant-arrival-rate',
      rate: RPM,
      timeUnit: '1m',
      duration: __ENV.DURACION || '5m',
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    // Metas de RC-01, verificadas en el prototipo de la semana 6
    cotizacion_latencia_ms: ['p(95)<250', 'p(99)<500'],
    // Meta de RC-03: el recorrido sigue disponible aunque degrade
    cotizacion_sin_perfil: ['rate<0.001'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const cliente = `c${Math.floor(Math.random() * 5000)}`;
  const res = http.post(
    `${BASE_URL}/cotizar`,
    JSON.stringify({ cliente, producto: 'vida-hipotecario', monto: 120000000 }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(res, { 'status 200': (r) => r.status === 200 });

  if (res.status === 200) {
    const b = res.json();
    latencia.add(b.latencia_ms);
    degradadas.add(b.degradado === true);
    sinDato.add(b.origen_perfil === 'defecto');
  }
}

export function handleSummary(data) {
  const m = data.metrics.cotizacion_latencia_ms ? data.metrics.cotizacion_latencia_ms.values : {};
  const resumen = {
    rpm: RPM,
    p50_ms: m['p(50)'],
    p95_ms: m['p(95)'],
    p99_ms: m['p(99)'],
    pct_degradadas: data.metrics.cotizacion_degradada
      ? data.metrics.cotizacion_degradada.values.rate * 100 : null,
    pct_sin_perfil: data.metrics.cotizacion_sin_perfil
      ? data.metrics.cotizacion_sin_perfil.values.rate * 100 : null,
  };
  return {
    stdout: JSON.stringify(resumen, null, 2) + '\n',
    [`resultados-exp01-${RPM}rpm.json`]: JSON.stringify(resumen, null, 2),
  };
}
