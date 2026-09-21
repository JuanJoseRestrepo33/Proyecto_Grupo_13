// EXP-03 | Latencia real vista desde la ubicacion de los usuarios.
// Ejecutar DESDE COLOMBIA (tu computador) contra el balanceador de cada region,
// en horario pico y valle durante al menos 3 dias:
//   k6 run -e ENDPOINT=http://IP_SAO_PAULO -e REGION=sa-east1 -e UBICACION=cali latencia_por_region.js
//   k6 run -e ENDPOINT=http://IP_US_EAST   -e REGION=us-east1 -e UBICACION=cali latencia_por_region.js
import http from 'k6/http';
import { Trend } from 'k6/metrics';

const lat = new Trend('latencia_extremo_a_extremo_ms');
const ENDPOINT = __ENV.ENDPOINT;
const REGION = __ENV.REGION || 'desconocida';
const UBICACION = __ENV.UBICACION || 'desconocida';

export const options = {
  vus: 5,
  duration: __ENV.DURACION || '10m',
  summaryTrendStats: ['count', 'p(50)', 'p(95)', 'p(99)'],
};

export default function () {
  const res = http.post(`${ENDPOINT}/cotizar`,
    JSON.stringify({ cliente: `lat-${__VU}-${__ITER % 200}` }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status === 200) lat.add(res.timings.duration);   // incluye la red real
}

export function handleSummary(data) {
  const m = data.metrics.latencia_extremo_a_extremo_ms;
  const ahora = new Date();
  const out = {
    experimento: 'EXP-03', medicion: 'latencia por region',
    ubicacion: UBICACION, region: REGION, endpoint: ENDPOINT,
    hora_local: ahora.toString(),
    muestras: m ? m.values.count : 0,
    p50_ms: m ? m.values['p(50)'] : null,
    p95_ms: m ? m.values['p(95)'] : null,
    p99_ms: m ? m.values['p(99)'] : null,
  };
  out.valido = out.muestras > 0;
  out.cumple_p95 = out.valido && out.p95_ms < 250;
  return {
    stdout: JSON.stringify(out, null, 2) + '\n',
    [`resultados/exp03-latencia-${REGION}-${UBICACION}-${ahora.getTime()}.json`]: JSON.stringify(out, null, 2),
  };
}
