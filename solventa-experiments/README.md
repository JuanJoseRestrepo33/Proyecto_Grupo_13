# Solventa · Código de los experimentos de arquitectura

Código ejecutable de los cuatro experimentos que validan los ASR del proyecto.
Incorpora las correcciones D-01 a D-04 verificadas en los prototipos de la semana 6.

## Estructura

```
solventa-experiments/
├── terraform/              # Infraestructura de ambos escenarios de EXP-03
│   ├── main.tf             # GKE, Cloud SQL, Memorystore, Pub/Sub, BigQuery
│   ├── variables.tf        # Parámetros bajo experimentación
│   ├── topologia-a.tfvars  # Multi-zona, una región (~USD 1.331/mes)
│   └── topologia-b.tfvars  # + región de respaldo (~USD 1.910/mes)
├── exp01-resiliencia/      # Circuit Breaker + caché ante Open Finance
├── exp02-eventos/          # Canal de eventos paramétricos con idempotencia
├── exp03-failover/         # Prueba de caos y latencia por región
└── exp04-auditoria/        # Auditoría inmutable y revocación de consentimiento
```

## Requisitos previos

- Terraform ≥ 1.6
- Google Cloud CLI autenticado (`gcloud auth application-default login`)
- Docker y Docker Compose (para el montaje local de EXP-01)
- k6 (generación de carga)
- Python 3.11+
- Un proyecto de GCP con facturación habilitada

## EXP-01 · Resiliencia y degradación ante Finanzas Abiertas

Valida: **RC-01** (latencia) y **RC-03** (disponibilidad del recorrido).
Punto de sensibilidad: umbrales del interruptor de circuito.

```bash
cd exp01-resiliencia
docker compose up -d              # levanta cotización + Redis + WireMock

# Escenario A: proveedor sano
k6 run -e RPM=100  -e BASE_URL=http://localhost:8080 carga.js
k6 run -e RPM=1000 -e BASE_URL=http://localhost:8080 carga.js

# Cambiar a escenario B (degradado) o C (caído):
curl -X POST http://localhost:8081/__admin/mappings/reset
curl -X POST http://localhost:8081/__admin/mappings/import \
     -d @escenarios-wiremock/b-degradado.json
# repetir las corridas de k6
```

Criterio de éxito (definido en el diseño del experimento): p95 ≤ 250 ms y
p99 ≤ 500 ms en el escenario degradado; disponibilidad ≥ 99,9% con el
proveedor caído. Los umbrales están declarados en `carga.js`, de modo que
k6 falla automáticamente si no se cumplen.

## EXP-02 · Escalabilidad del canal de eventos

Valida: **RC-02** (escalabilidad).
Punto de sensibilidad: particionamiento de la suscripción y verificación de idempotencia.

```bash
cd exp02-eventos
# Desplegar consumidores en procesos separados (evita el sesgo del GIL
# que hizo no concluyente la medición local de la semana 6)
kubectl apply -f keda-scaledobject.yaml

# Lanzar la ráfaga
export GCP_PROJECT_ID=tu-proyecto
python generador_rafaga.py --eventos 1000000 --ventana 600 --duplicados 0.05

# Recolectar métricas
./medir_resultados.sh
```

Variables del experimento: `maxReplicaCount` (probar 10, 20, 40) y el valor
de `subscriptionSize` en `keda-scaledobject.yaml`.

## EXP-03 · Latencia y disponibilidad entre zonas y regiones

Valida: **RC-01, RC-03, RC-07**.
Punto de sensibilidad: topología de despliegue y mecanismo de traslado.

```bash
cd terraform
terraform init
terraform apply -var-file=topologia-a.tfvars     # o topologia-b.tfvars

cd ../exp03-failover
# Medir latencia desde cada ubicación, en horario pico y valle
k6 run -e ENDPOINT=https://sa-east.solventa.co -e UBICACION=bogota latencia_por_region.js

# Prueba de caos: derribar la zona activa y medir RTO/RPO reales
export GCP_PROJECT_ID=tu-proyecto
export PG_DSN="postgresql://usuario:clave@host/solventa"
python caos_failover.py --url https://api.solventa.co --zona southamerica-east1-a
```

Criterio de éxito: RTO ≤ 10 min ante caída de zona, RTO ≤ 5 min ante caída
de región, RPO ≤ 30 s sin pérdida de transacciones confirmadas.

**Advertencia de costo:** la topología B factura mientras esté desplegada.
Ejecutar `terraform destroy` al terminar cada corrida.

## EXP-04 · Auditoría inmutable y revocación de consentimiento

Valida: **RC-04, RC-08** (seguridad).
Punto de sensibilidad: costo en latencia del registro de auditoría y alcance
real de la propagación de la revocación sobre datos ya cacheados.

```bash
cd exp04-auditoria
export GCP_PROJECT_ID=tu-proyecto
uvicorn consentimiento_service:app --port 8082 &

# Parte B: reconstrucción e inmutabilidad
python verificar_inmutabilidad.py

# Parte C: propagación real de la revocación
python medir_revocacion.py --revocaciones 20 \
  --consumidores http://localhost:8080 http://localhost:8083
```

## Dependencia entre experimentos

EXP-04 reutiliza la caché de EXP-01 y el bus de eventos de EXP-02, así que debe
ejecutarse después de ambos. Además, si EXP-04 obliga a invalidar la caché de
forma agresiva, cambia la tasa de aciertos asumida en EXP-01 y **hay que
reejecutar EXP-01** para confirmar que el p95 se sostiene.

## Correcciones ya incorporadas (verificadas en la semana 6)

| ID | Corrección | Dónde está en el código |
|----|-----------|--------------------------|
| D-01 | El presupuesto de 120 ms es efectivo en el camino del usuario; los 700 ms quedan para el sondeo | `exp01-resiliencia/cotizacion_service.py`, `obtener_perfil` |
| D-02 | Mínimo de 5 llamadas para evaluar la apertura del circuito | `cotizacion_service.py`, `MIN_CALLS` |
| D-03 | El sondeo de recuperación corre en segundo plano | `cotizacion_service.py`, `maybe_probe` / `_probe` |
| D-04 | Invalidación explícita por evento, no expiración pasiva por TTL | `exp04-auditoria/consentimiento_service.py`, `revocar` |

## Qué no valida este código

Ninguno de estos experimentos comprueba que una herramienta de terceros
funcione (que Pub/Sub entregue mensajes o que Redis almacene datos). Todos
validan decisiones de arquitectura propias bajo las condiciones del caso.
