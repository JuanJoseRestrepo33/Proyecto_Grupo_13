# EXP-03: Latencia y disponibilidad entre zonas y regiones

Implementación del experimento descrito en "Experimentos de Arquitectura - Semana 5" (sección 2.3). Valida dos cosas separadas, con dos montajes separados:

1. **El mecanismo de failover** (zona y región): se prueba local, con Podman, porque ahí sí se puede controlar con precisión el apagado de contenedores y medir RTO/RPO reales contra Postgres en replicación.
2. **La latencia real desde Colombia**: se prueba en AWS free tier, porque localhost siempre da ~0ms y no sirve para responder la pregunta real del experimento (qué región ofrece mejor p95 desde Bogotá/Cali).

> Nota: el diseño de producción de Solventa sigue siendo **Google Cloud Platform** (decisión semana 3). Este montaje usa AWS solo porque el equipo no tiene acceso a GCP durante estas semanas — ver limitación explícita más abajo.

> Corrección: el documento original mencionaba "Bogotá y Ciudad de México" como puntos de prueba. Fue un error (contenido de IA sin revisar). Los puntos de prueba reales son **Bogotá y Cali**.

## Estructura

```
servicio-cotizacion/       stub del "Servicio de Cotización" (FastAPI + Postgres, para el montaje local)
  standalone_server.py     versión sin dependencias, para las instancias EC2 (solo mide latencia, no persiste)
local/                     montaje con Podman: 3 zonas + 1 zona de respaldo, Postgres primario + réplica, HAProxy
  chaos/                   scripts que apagan zonas/región y miden tiempo de recuperación
terraform/                 despliegue en AWS free tier de 2 regiones candidatas, para medir latencia real
k6/                        script de carga para medir p50/p95 desde Bogotá y Cali contra los endpoints AWS
```

## Parte 1 — Mecanismo de failover (local, Podman)

Requiere Podman instalado en el host, con soporte para el subcomando `podman compose` (no en este contenedor de trabajo).

```bash
cd local
podman compose up -d --build
curl http://localhost:8080/cotizacion   # debe responder, alternando zona-a/zona-b/zona-c
```

**Prueba de caída de zona** (situación esperada: recuperación en segundos, sin pérdida):

```bash
./chaos/kill-zone.sh zone-a 30
```

**Prueba de caída de región completa** (apaga zonas + Postgres primario, promueve la réplica):

```bash
./chaos/kill-region.sh 60
```

Ambos scripts generan un `.jsonl` en `local/chaos/` con cada solicitud (fase, código HTTP, latencia, zona que respondió) y eventos con timestamp de apagado/promoción. De ahí se calcula:

- **RTO real** = tiempo entre el evento `zona_apagada`/`region_primaria_apagada` y la primera respuesta 200 sostenida.
- **RPO real** = `transacciones_perdidas` que reporta `kill-region.sh` (diferencia de conteo entre la región primaria justo antes de apagar y la réplica justo después de promoverla).

Después de `kill-region.sh` la réplica queda promovida (ya no es standby válido). Para reiniciar el ambiente:

```bash
podman compose down -v && podman compose up -d --build
```

## Parte 2 — Latencia real Bogotá/Cali (AWS free tier)

Despliega el stub `standalone_server.py` (sin dependencias, solo para medir latencia) en dos regiones candidatas.

```bash
cd terraform
terraform init
terraform apply   # revisar el plan; usa el free tier (t3.micro) pero igual crea recursos reales en tu cuenta AWS
```

Por defecto compara `us-east-1` (primaria candidata) vs `sa-east-1` (respaldo candidata). Si la cuenta tiene disponible `mx-central-1` (región de México, geográficamente más cercana a Colombia), vale la pena agregarla como tercera comparación duplicando el bloque de recursos `*_respaldo` en `main.tf` — no se incluyó por defecto porque no se verificó su disponibilidad/paridad de free tier en la cuenta del equipo.

Al terminar, `terraform output` da las dos URLs (`http://<ip>:8000`).

**IMPORTANTE:** al terminar las mediciones, correr `terraform destroy` para no dejar instancias corriendo y generando costo.

## Parte 3 — Medición desde Bogotá y Cali (k6)

Esto lo debe correr **alguien físicamente conectado desde cada ciudad** (oficina/casa en Bogotá, oficina/casa en Cali) — un k6 corrido desde un solo lugar no puede medir "latencia desde Bogotá y desde Cali" a la vez.

```bash
cd k6
k6 run -e ENDPOINT=http://<ip-primaria>:8000 -e UBICACION=bogota -e HORARIO=pico latencia-test.js
k6 run -e ENDPOINT=http://<ip-respaldo>:8000 -e UBICACION=bogota -e HORARIO=pico latencia-test.js
```

Repetir cambiando `UBICACION=cali` desde Cali, y `HORARIO=valle` en horario no pico. El documento pide repetir durante al menos 3 días para capturar variabilidad — cada corrida deja un `resultado-<ubicacion>-<horario>-<timestamp>.json` con p50/p95; hay que juntar esos archivos para el análisis final.

## Cómo interpretar el resultado (resumen de la sección 2.3 del documento)

| Resultado medido | Lectura |
|---|---|
| RTO zona ≤ 10 min, RPO ≤ 30 s, región candidata da mejor p95 | Hipótesis confirmada, se fija esa región como primaria |
| RTO de zona por encima del objetivo | Ajustar frecuencia de health check / umbral de fallos (`inter`, `fall` en `haproxy.cfg`), repetir |
| RPO > 30 s | Replicación asíncrona insuficiente para datos transaccionales → evaluar replicación síncrona para entidades críticas |
| Latencia aceptable pero costo alto | Conservar redundancia multi-zona, degradar región de respaldo a esquema más económico, documentar el RTO resultante como concesión |
| Ninguna topología cumple con costo viable | Hipótesis refutada → renegociar meta de RC-07 con evidencia del experimento |

## Limitaciones a documentar en el reporte final

- La topología real de producción es GCP; estos resultados de AWS sirven para validar el **mecanismo** de failover (que es agnóstico de proveedor) y para elegir la mejor región **dentro de AWS**, no para revalidar la decisión de proveedor de la semana 3.
- El montaje local de Postgres corre en un solo host, no en zonas geográficamente separadas — mide fielmente el mecanismo (RTO/RPO), no la latencia real.
