// EXP-01 | Llena la cache con los 500 clientes que usa carga.js.
// Reemplaza el bucle de 500 procesos curl, que en Windows era lentisimo
// y podia quedarse colgado. k6 lo hace en un solo proceso en segundos.
import http from 'k6/http';
import exec from 'k6/execution';

export const options = {
  scenarios: {
    precalentar: { executor: 'shared-iterations', vus: 20, iterations: 500, maxDuration: '2m' },
  },
  summaryTrendStats: [],
};

export default function () {
  const i = exec.scenario.iterationInTest;
  http.post(`${__ENV.BASE_URL || 'http://localhost:8080'}/cotizar`,
    JSON.stringify({ cliente: `c${i}` }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '5s' });
}

export function handleSummary() { return { stdout: '' }; }
