import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const ENDPOINT = __ENV.ENDPOINT;
const UBICACION = __ENV.UBICACION || "sin-especificar";
const HORARIO = __ENV.HORARIO || "sin-especificar";

const latenciaCotizacion = new Trend("latencia_cotizacion", true);

export const options = {
  vus: 5,
  duration: __ENV.DURACION || "60s",
  tags: { ubicacion: UBICACION, horario: HORARIO },
};

export default function () {
  if (!ENDPOINT) {
    throw new Error("Definir ENDPOINT, ej: k6 run -e ENDPOINT=http://<ip>:8000 -e UBICACION=bogota -e HORARIO=pico latencia-test.js");
  }
  const res = http.get(`${ENDPOINT}/cotizacion?cliente=vu${__VU}&ramo=hogar`);
  check(res, { "status 200": (r) => r.status === 200 });
  latenciaCotizacion.add(res.timings.duration);
  sleep(1);
}

export function handleSummary(data) {
  const nombreArchivo = `resultado-${UBICACION}-${HORARIO}-${Date.now()}.json`;
  const resumen = {
    ubicacion: UBICACION,
    horario: HORARIO,
    endpoint: ENDPOINT,
    p50_ms: data.metrics.latencia_cotizacion.values["p(50)"],
    p95_ms: data.metrics.latencia_cotizacion.values["p(95)"],
    solicitudes_fallidas: data.metrics.http_req_failed.values.rate,
    total_solicitudes: data.metrics.http_reqs.values.count,
  };
  return {
    stdout: JSON.stringify(resumen, null, 2),
    [nombreArchivo]: JSON.stringify(resumen, null, 2),
  };
}
