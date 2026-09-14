// EXP-03 | Mide latencia desde distintas ubicaciones y horarios.
// Ejecutar desde una VM en Bogota y otra en Ciudad de Mexico:
//   k6 run -e ENDPOINT=https://sa-east.solventa.co -e UBICACION=bogota latencia_por_region.js
import http from 'k6/http';
import { Trend } from 'k6/metrics';

const lat = new Trend('latencia_region_ms');
const ENDPOINT = __ENV.ENDPOINT;
const UBICACION = __ENV.UBICACION || 'desconocida';

export const options = {
  vus: 5,
  duration: __ENV.DURACION || '10m',
  thresholds: { 'latencia_region_ms': ['p(95)<250'] },  // meta de RC-01
};

export default function () {
  const res = http.post(`${ENDPOINT}/cotizar`,
    JSON.stringify({ cliente: `c${__VU}-${__ITER}`, monto: 120000000 }),
    { headers: { 'Content-Type': 'application/json' } });
  lat.add(res.timings.duration);
}

export function handleSummary(data) {
  const m = data.metrics.latencia_region_ms.values;
  const out = {
    ubicacion: UBICACION, endpoint: ENDPOINT,
    hora_utc: new Date().toISOString(),
    p50_ms: m['p(50)'], p95_ms: m['p(95)'], p99_ms: m['p(99)'],
  };
  return {
    stdout: JSON.stringify(out, null, 2) + '\n',
    [`latencia-${UBICACION}-${Date.now()}.json`]: JSON.stringify(out, null, 2),
  };
}
