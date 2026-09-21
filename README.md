# Solventa · Experimentos de arquitectura (versión 2, probada)

Código para ejecutar los cuatro experimentos que validan los ASR del proyecto.
Esta versión **fue ejecutada y probada** contra servicios reales, Redis, Postgres y k6
antes de entregarse (ver la sección *Qué se probó*).

---

## 1. Cómo está organizado

```
solventa-experiments/
├── docker-compose.yml       # entorno local completo, costo $0 (Fase 1)
├── Dockerfile               # una imagen para todos los servicios y scripts
├── servicios/               # los "servicios mínimos" que ejercitan cada decisión
│   ├── cotizacion_service.py      # Circuit Breaker + caché + auditoría (EXP-01, EXP-04)
│   ├── simulador_openfinance.py   # proveedor simulado con escenarios en caliente
│   ├── perfilamiento_service.py   # consumidor con copia propia del dato (EXP-04)
│   ├── consentimiento_service.py  # revocación y reconstrucción (EXP-04)
│   ├── consumidor_parametricos.py # canal de eventos con idempotencia (EXP-02)
│   └── bus.py                     # Pub/Sub real, emulador o Redis
├── db/init.sql              # auditoría append-only con 3 capas de protección
├── exp01/ … exp04/          # un ejecutor run.sh y sus scripts por experimento
├── terraform/               # infraestructura de las topologías A y B (Fase 2)
├── k8s/servicios.yaml       # despliegue en GKE
└── scripts/                 # despliegue, arranque sin Docker y regresión
```

**No son los microservicios reales de Solventa.** Un experimento de arquitectura valida
una decisión, no el producto: cada servicio implementa solo lo necesario para ejercitar
su punto de sensibilidad.

---

## 2. Cómo leer los resultados

Cada corrida deja un JSON en `expXX/resultados/` con dos campos que importan:

| Campo | Significado |
|---|---|
| `valido` | La corrida produjo datos suficientes para concluir. **Si es `false`, el resultado no vale**, sin importar el resto. |
| `cumple_*` | Si se cumplió la meta del ASR. Solo es `true` cuando `valido` también lo es. |

Esta separación existe porque la primera versión reportaba "cumple la meta" en corridas
donde todas las solicitudes habían fallado (con cero datos, `null < 250` es verdadero).

---

## 3. Preparación del entorno

### 3.1 Windows (Git Bash)

Los scripts funcionan en Git Bash, pero requieren cuatro ajustes:

**a) Desactivar la conversión automática de rutas de MSYS.** Sin esto, Git Bash
convierte rutas como `/app/exp02` en `C:/Program Files/Git/app/exp02` antes de
pasarlas a Docker o kubectl, y las corridas fallan con
`the working directory ... is invalid, it needs to be an absolute path`.

```bash
grep -q MSYS_NO_PATHCONV ~/.bashrc || echo 'export MSYS_NO_PATHCONV=1' >> ~/.bashrc
source ~/.bashrc
```

**b) Tener un `python3` real.** En Windows, `python3` suele apuntar al alias de la
Microsoft Store. Verificar con `which -a python python3`. Si `python3` solo aparece en
`WindowsApps`, crear un acceso directo a la instalación real (ajustar la ruta si es otra):

```bash
mkdir -p ~/bin
printf '#!/bin/sh\nexec /c/Python314/python "$@"\n' > ~/bin/python3
chmod +x ~/bin/python3
```

Y desactivar `python.exe` y `python3.exe` en
Configuración > Aplicaciones > Configuración avanzada de aplicaciones > Alias de ejecución de aplicaciones.

**c) Dependencias de Python locales** (necesarias para EXP-04):

```bash
python3 -m pip install psycopg2-binary requests
```

**d) k6.** Instalarlo con winget y agregarlo al PATH de Git Bash:

```bash
winget install -e --id k6.k6
grep -q 'Program Files/k6' ~/.bashrc || echo 'export PATH="$PATH:/c/Program Files/k6"' >> ~/.bashrc
source ~/.bashrc
k6 version
```

> Si editas un `.sh` en el Bloc de notas, guárdalo con finales de línea LF (no CRLF),
> o bash fallará con `$'\r': command not found`.

### 3.2 Contraseñas (Fase 2)

Las contraseñas se generan localmente y se guardan en `.env.secrets`, que **nunca** se
sube al repositorio. Solo letras y números, para que no rompan DSNs del tipo
`postgresql://usuario:clave@host`.

```bash
gen_pass() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32; }

cat > .env.secrets <<EOF
export MSYS_NO_PATHCONV=1
export TF_VAR_db_password_admin='$(gen_pass)'
export DB_ADMIN_PASSWORD="\$TF_VAR_db_password_admin"
export DB_APP_PASSWORD='$(gen_pass)'
EOF
chmod 600 .env.secrets

for p in .env.secrets '*.tfstate' '*.tfstate.*' '.terraform/'; do
  grep -qxF "$p" .gitignore 2>/dev/null || echo "$p" >> .gitignore
done
```

En cada terminal nueva:

```bash
source .env.secrets
echo "MSYS=$MSYS_NO_PATHCONV admin=${#TF_VAR_db_password_admin} app=${#DB_APP_PASSWORD}"
# esperado: MSYS=1 admin=32 app=32
```

Notas:

- 32 caracteres alfanuméricos equivalen a unos 190 bits de entropía.
- Terraform guarda la clave en texto plano en `terraform.tfstate`; por eso se excluye del repositorio.
- Si se cambia `TF_VAR_db_password_admin` después de un `apply`, el siguiente `apply` actualiza el usuario en Cloud SQL.
- La clave `admin_local` del `docker-compose.yml` es solo para el Postgres local y no requiere cambio.

---

## 4. Fase 1 · Local con Docker (costo $0)

Cubre EXP-01, EXP-04 y la corrección de EXP-02. Requiere Docker y [k6](https://k6.io/docs/get-started/installation/).

```bash
docker compose up -d --build
docker compose ps                 # esperar a que redis, postgres y pubsub queden healthy
```

### EXP-01 · Resiliencia ante Finanzas Abiertas (RC-01, RC-03)

```bash
./exp01/run.sh                    # 3 escenarios x 2 cargas, 5 min cada una
DURACION=1m ./exp01/run.sh        # versión rápida para verificar
```

El script cambia el escenario del simulador y reinicia el circuito entre corridas.
Al terminar deja el simulador en estado sano.

### EXP-04 · Auditoría y revocación (RC-04, RC-08)

```bash
./exp04/run.sh                    # partes A (latencia), B (inmutabilidad) y C (revocación)

# Opcional: demostrar que la cadena de hashes detecta a un administrador que
# desactiva las protecciones. Todo ocurre en una transacción que se revierte.
docker compose exec postgres psql -U postgres -d solventa -c "select 1"   # verificar acceso
PG_ADMIN_DSN=postgresql://postgres:admin_local@localhost:5432/solventa \
  python3 exp04/demostrar_deteccion.py
```

### EXP-02 · Corrección del canal de eventos (RC-02)

```bash
MODO=local ./exp02/run.sh         # varía las réplicas del consumidor: 2, 4, 8, 12
```

En local se valida la **corrección** (cero pérdida, cero doble pago). El emulador de
Pub/Sub no está hecho para rendimiento, así que el **escalamiento** solo es concluyente en GKE.

### Sin Docker (alternativa)

Si tienen Redis y Postgres instalados localmente:

```bash
psql -d solventa -f db/init.sql
./scripts/levantar_sin_docker.sh          # usa el bus en modo Redis
./scripts/prueba_regresion_local.sh       # corre todo y resume resultados
```

---

## 5. Fase 2 · Google Cloud

Solo EXP-03 y el escalamiento de EXP-02 requieren la nube.

### 5.1 Preparar

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project MI_PROYECTO

# editar project_id en terraform/topologia-a.tfvars y topologia-b.tfvars
source .env.secrets               # ver sección 3.2
```

### 5.2 Desplegar

```bash
cd terraform
terraform init
terraform apply -var-file=topologia-b.tfvars     # tarda 15-25 min (Cloud SQL es lo lento)
cd ..

export PROJECT=MI_PROYECTO
./scripts/desplegar_gke.sh                        # imprime la IP del balanceador al final
```

`DB_ADMIN_PASSWORD` y `DB_APP_PASSWORD` ya quedan definidas al hacer `source .env.secrets`.

### 5.3 EXP-02 · Escalamiento real

```bash
MODO=gke ./exp02/run.sh                           # 1.000.000 de eventos por corrida
```

### 5.4 EXP-03 · Caída de zona (RC-03, RC-07)

```bash
python3 exp03/caos_failover.py --url http://IP_BALANCEADOR \
  --zona southamerica-east1-a --region southamerica-east1 --project $PROJECT --restaurar

# el script imprime ts_falla; con él se mide el RPO dentro del clúster:
kubectl exec -n solventa deploy/herramientas -- \
  python /app/exp03/verificar_rpo.py --ts-falla TS_FALLA
```

Agregar `--falla-bd` fuerza también el traslado de Cloud SQL entre zonas.
Con tres réplicas repartidas en tres zonas, lo esperado es que la caída de una zona
**no interrumpa** el servicio.

> En Git Bash, el `kubectl exec` anterior requiere `MSYS_NO_PATHCONV=1` (sección 3.1);
> de lo contrario `/app/exp03/...` se convierte en una ruta de Windows.

### 5.5 EXP-03 · Latencia por región

Ejecutar **desde Colombia** (su propio computador es el punto de prueba real),
en horario pico y valle durante al menos tres días:

```bash
cd exp03
k6 run -e ENDPOINT=http://IP_SAO_PAULO -e REGION=sa-east1 -e UBICACION=cali latencia_por_region.js
```

Para comparar con us-east1 hay que desplegar también en el clúster de respaldo:
`CLUSTER_DESTINO=respaldo SKIP_BUILD=1 ./scripts/desplegar_gke.sh`

### 5.6 EXP-03 · Caída de región (al final, es irreversible)

```bash
PROJECT=$PROJECT ./exp03/failover_region.sh
```

Cronometra cada paso del procedimiento. **Promover la réplica es irreversible**:
ejecutarlo al final y luego destruir todo.

### 5.7 Destruir (no olvidar)

```bash
cd terraform && terraform destroy -var-file=topologia-b.tfvars
```

Dejar la topología B desplegada por olvido durante dos semanas cuesta cerca de USD 900,
unas diecisiete veces el costo de las corridas.

---

## 6. Qué se probó y qué no

**Ejecutado y probado** contra servicios reales, Redis 7, Postgres 16 y k6 0.54:

| Prueba | Resultado |
|---|---|
| EXP-01, 6 corridas | Todas válidas. Sano: p95 53 ms, circuito cerrado. Degradado y caído: circuito abierto, 100% desde caché, p95 ≤ 125 ms |
| EXP-04 parte A | Válida en los tres modos (ver nota abajo) |
| EXP-04 parte B | Reconstrucción 100%, UPDATE/DELETE/TRUNCATE bloqueados, cadena íntegra |
| EXP-04 parte C | 20/20 revocaciones propagadas a ambos consumidores, p95 ≈ 11 ms |
| Detección de manipulación | La cadena señaló exactamente la fila alterada por un administrador |
| EXP-02 corrección | Cero pagos dobles y cero pérdidas con 1 y 4 procesos |
| `medir_exp02.py` | 5 de 5 casos correctos, incluida la reentrega de Pub/Sub |
| RPO | Detecta 0 pérdidas cuando no las hay y exactamente las simuladas cuando sí |
| Esquema de base de datos | Se instala limpio y es idempotente |

**Validado solo en sintaxis, no ejecutado:** Terraform, manifiestos de Kubernetes,
docker-compose, construcción de la imagen, emulador de Pub/Sub y todo lo que corre en GCP.
Las versiones de dependencias sí se verificaron contra PyPI.

**No concluyente en local:** el escalamiento de EXP-02. La máquina de prueba tenía un solo
núcleo, así que ningún resultado de escalamiento obtenido allí es válido.

**Nota sobre EXP-04 parte A:** en local los tres modos dan ~52 ms porque la latencia la
domina el proveedor simulado y escribir en un Postgres local cuesta menos de 1 ms.
Contra Cloud SQL, a través de la red, la diferencia entre síncrono y asíncrono debería
aparecer: esta parte hay que repetirla en la nube.

---

## 7. Hallazgo de diseño pendiente (candidato a corrección D-05)

El registro de qué clientes revocaron su consentimiento vive en Redis, y Memorystore
existe solo en la región primaria. Si esa región cae, **las revocaciones se pierden** y
el sistema volvería a usar datos de clientes que retiraron su consentimiento, lo que
incumple RC-08.

El Terraform agrega una caché en la región de respaldo para que el experimento funcione,
pero eso no resuelve el problema de fondo. La corrección sugerida es persistir la
revocación en Postgres (que sí se replica) y usar Redis solo como caché de lectura.

---

## 8. Cambios respecto a la versión anterior

| Antes | Ahora | Motivo |
|---|---|---|
| WireMock | Simulador en Python | WireMock no permite fijar un 15% de error exacto; el filtro anterior producía 100% de errores |
| BigQuery para auditoría | Postgres append-only | Se ejecuta igual en local y en la nube, y la inmutabilidad se puede probar |
| Revocación = borrar la caché | Revocación = dejar de usar el dato | La cotización siguiente volvía a consultar al proveedor y re-cacheaba |
| Pérdida = publicados − procesados − duplicados | Pérdida = IDs únicos − pagos únicos | Con reentregas de Pub/Sub la fórmula anterior daba negativa |
| Terraform con errores de sintaxis | Sintaxis validada | Además: acceso privado para Cloud SQL, permisos de la cola de fallidos, un grupo de nodos por zona |
| Verificación de salud suelta | Sonda en Kubernetes | La anterior no estaba conectada a nada |
| Contraseñas exportadas a mano | `.env.secrets` generado con `openssl`, excluido del repositorio | Evita claves débiles y fugas en git |
| Sin guía para Git Bash | Sección 3.1 (MSYS, `python3`, dependencias) | Las corridas fallaban en Windows por conversión de rutas y el alias de la Store |

---

## 9. Problemas comunes

- **`the working directory 'C:/Program Files/Git/app/...' is invalid`:** falta
  `export MSYS_NO_PATHCONV=1` (sección 3.1).
- **`no se encontró Python; ejecutar sin argumentos para instalar desde el Microsoft Store`:**
  `python3` apunta al alias de Windows (sección 3.1, punto b).
- **`ModuleNotFoundError: No module named 'requests'` o `psycopg2`:**
  `python3 -m pip install psycopg2-binary requests`.
- **`k6: command not found`:** falta el PATH de k6 (sección 3.1, punto d).
- **Una parte de un experimento no imprime nada:** revisar `logs/` y confirmar que
  `MSYS_NO_PATHCONV=1` está activo en esa terminal.
- **Cuota de CPU excedida:** la configuración por defecto usa ~8 vCPU. Si falla, bajar
  `nodos_por_zona` o pedir aumento de cuota en la consola.
- **`terraform apply` pide `db_password_admin`:** falta `source .env.secrets`.
- **Zonas inexistentes:** verificar con `gcloud compute zones list --filter=region:southamerica-east1`
  y ajustar `sufijos_zona`.
- **Los pods no acceden a Pub/Sub:** usan la cuenta de servicio de Compute por defecto;
  verificar que tenga permisos de Pub/Sub en el proyecto.
- **Una corrida sale con `valido: false`:** revisar el campo `advertencia` y los registros en `logs/`.