# Plan de ejecución — EXP-03: Latencia y disponibilidad entre zonas y regiones

Checklist de trabajo. Ver `README.md` para los comandos exactos de cada paso.

## 0. Prerrequisitos

- [x] Confirmar que EXP-03 no depende de EXP-01/EXP-02/EXP-04 (puede iniciar en paralelo desde el día 1).
- [x] Corregir puntos de prueba del documento original: **Bogotá y Cali**, no Bogotá y Ciudad de México (error de contenido IA sin revisar).
- [x] Confirmar plataforma de laboratorio: AWS free tier (no hay acceso a GCP por ahora; la decisión de producción de semana 3 sigue siendo GCP).
- [x] Confirmar Podman disponible en el host.
- [ ] Confirmar cuenta AWS con free tier activo disponible para el equipo.

## 1. Montaje — mecanismo de failover (local, Podman)

Responsable sugerido: **Edwin Hurtado** (despliegue idéntico del servicio + balanceo/health check, según reparto original del documento).

- [x] Stub del Servicio de Cotización (`servicio-cotizacion/app.py`, FastAPI + Postgres).
- [x] `compose.yaml`: 3 zonas + 1 zona de respaldo, Postgres primario + réplica en streaming, HAProxy con health check.
- [x] Corregir nombre de archivo compose (`podman-compose.yml` → `compose.yaml`) para que `podman compose` lo detecte sin `-f`.
- [x] Usar el subcomando `podman compose` (no el binario aparte `podman-compose`), según la instalación real del equipo.
- [ ] `podman compose up -d --build` corre sin errores en el host.
- [ ] `curl http://localhost:8080/cotizacion` responde 200 alternando zona-a/zona-b/zona-c.
- [ ] Verificar réplica en standby: `podman compose exec postgres-replica psql -U solventa -d solventa -c "SELECT pg_is_in_recovery();"` → debe dar `t`.

## 2. Prueba de caída de zona

Responsable sugerido: **Juan José Restrepo** (ejecución de la prueba de caos y medición de recuperación).

- [x] Script `local/chaos/kill-zone.sh` listo.
- [ ] Ejecutar `./chaos/kill-zone.sh zone-a 30` desde `local/`.
- [ ] Confirmar en el `.jsonl` resultante que la recuperación (primer 200 sostenido tras el apagado) ocurre en segundos, sin bloqueo prolongado.
- [ ] Repetir apagando `zone-b` y `zone-c` para descartar que el resultado dependa de cuál zona cae.

## 3. Prueba de caída de región completa

Responsable sugerido: **Juan José Restrepo**.

- [x] Script `local/chaos/kill-region.sh` listo (apaga zonas + Postgres primario, promueve la réplica, calcula RPO por diferencia de conteo).
- [ ] Ejecutar `./chaos/kill-region.sh 60` desde `local/`.
- [ ] Registrar RTO real (tiempo entre `region_primaria_apagada` y primera respuesta 200 sostenida tras `replica_promovida`).
- [ ] Registrar RPO real (`transacciones_perdidas` del log) — objetivo ≤ 30 s de transacciones en vuelo.
- [ ] Reiniciar el ambiente (`podman compose down -v && podman compose up -d --build`) antes de repetir cualquier otra prueba, porque la réplica queda promovida (ya no es standby válido).

## 4. Despliegue en AWS free tier (latencia real)

Responsable sugerido: **Sergio Barrera** (infraestructura reproducible en Terraform).

- [x] Módulo Terraform (`terraform/`) validado con `terraform validate`.
- [ ] `terraform init` + revisar `terraform plan` antes de aplicar.
- [ ] `terraform apply` — confirma 2 instancias EC2 (regiones `us-east-1` y `sa-east-1` por defecto).
- [ ] Evaluar si agregar `mx-central-1` como tercera región candidata (verificar disponibilidad/free tier en la cuenta primero).
- [ ] Guardar las dos URLs de `terraform output` para pasárselas a quien corra k6.

## 5. Medición de latencia desde Bogotá y Cali

Responsable sugerido: **Harold Bartolo** (monitoreo sintético de latencia por ubicación/horario, estimación de costos).

- [x] Script `k6/latencia-test.js` listo (parametrizado por `ENDPOINT`, `UBICACION`, `HORARIO`).
- [ ] Correr desde Bogotá contra cada región candidata, horario pico.
- [ ] Correr desde Bogotá contra cada región candidata, horario valle.
- [ ] Correr desde Cali contra cada región candidata, horario pico.
- [ ] Correr desde Cali contra cada región candidata, horario valle.
- [ ] Repetir el ciclo anterior durante al menos 3 días para capturar variabilidad (según el documento original).
- [ ] Estimar costo mensual por topología comparada (calculadora de costos de AWS).
- [ ] **Destruir la infraestructura** (`terraform destroy`) en cuanto termine la última corrida, para no dejar costo corriendo.

## 6. Análisis y reporte

Responsable sugerido: **Juan José Restrepo** (reporte) con insumos de todo el equipo.

- [ ] Consolidar los `resultado-*.json` de k6 en una tabla p50/p95 por región × ciudad × horario.
- [ ] Consolidar RTO/RPO reales de las pruebas de caos (secciones 2 y 3).
- [ ] Aplicar el árbol de decisión de la sección "Interpretación de los resultados" del documento (confirmada / ajuste de parámetros / replicación síncrona / concesión documentada / hipótesis refutada).
- [ ] Redactar la sección de limitaciones: resultados de AWS validan el mecanismo y la elección de región *dentro de AWS*, no revalidan la decisión de proveedor GCP de la semana 3.
- [ ] Actualizar el documento oficial de experimentos con la corrección Bogotá/Cali (el PDF original todavía dice Ciudad de México).

## 7. Cierre

- [ ] Confirmar que no quedan recursos AWS activos (`terraform show` vacío o consola AWS sin instancias `exp03-*`).
- [ ] Commitear el experimento en `Proyecto_Grupo_13` (sin mezclar con los cambios pendientes de otros compañeros en `web/`, `mobile/`, `DesignSystem/`, `README.md`).
