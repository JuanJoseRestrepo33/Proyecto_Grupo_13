# ADR 0003 · Topología de despliegue del PRY2: Cloud Run y Cloud SQL en una región

- **Estado:** aceptada
- **Fecha:** 2026-11-08
- **Historia:** ARQ-01 · Esqueleto del sistema y pipeline (#122) y ARQ-05 · Verificación de calidad
  en staging (#124)
- **Decisores:** equipo del Grupo 13 (Sergio, Harold, Edwin, Juan)

## Contexto

La vista de despliegue de la arquitectura del PRY1
([wiki, Backlog de Arquitectura](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/wiki/Backlog-Arquitectura-ASR))
propone:

- GKE regional con nodos en varias zonas y autoescalado (HPA y KEDA por profundidad de cola);
- Memorystore (Redis) para la caché de perfiles;
- Pub/Sub para los siniestros paramétricos y la revocación de consentimiento;
- auditoría en BigQuery, malla de servicios con mTLS;
- región de respaldo en *warm standby* (`southamerica-west1`).

Esa topología se construyó con Terraform
([`solventa-experiments/terraform`](../../solventa-experiments/terraform/main.tf)) solo para los
experimentos EXP-01 a EXP-04. Se estimó en **unos USD 1.331 al mes (multizona) y USD 1.910 (con
región de respaldo)**, y la prueba de caída de región no se ejecutó porque promover la réplica es
irreversible ([Lecciones aprendidas, 3.5](https://github.com/JuanJoseRestrepo33/Proyecto_Grupo_13/wiki/Lecciones-aprendidas#35-la-nube-cuesta-y-algunas-pruebas-son-irreversibles)).

El tutor pidió para el PRY2 dos cosas que tiran en sentidos opuestos: desplegar en la nube desde el
Sprint 1, sin implementaciones locales, y **decidir y documentar** si se implementa el esquema
multirregión, que queda a discreción del equipo por su costo. ARQ-01 dejó fuera de alcance
Kubernetes, Terraform de producción, la malla de servicios y el autoescalado. Esta ADR registra la
topología que resultó y la tensión con la vista del PRY1.

## Decisión

*Staging* corre en **una región (`us-east1`)** con servicios administrados que escalan a cero:

| Pieza | Qué hay | En lugar de |
|---|---|---|
| Cómputo | Tres servicios de Cloud Run: `solventa-nucleo` (monolito modular), `solventa-bff` (dos routers, [ADR 0001](0001-bff-unico-dos-routers.md)) y `solventa-web` (nginx) | GKE regional con HPA |
| Datos | Una instancia de Cloud SQL para PostgreSQL 15 (`db-f1-micro`, zonal, sin respaldos), con una base y **un esquema y un usuario por módulo** | Cloud SQL HA con réplica de lectura en otra región |
| Caché | En la memoria de cada réplica del núcleo; el consentimiento se verifica en Cloud SQL antes de usarla ([ADR 0002](0002-consentimiento-vigente-antes-de-la-cache.md)) | Memorystore |
| Eventos | Dentro del monolito, por las interfaces públicas de los módulos | Pub/Sub |
| Archivos | Dos buckets privados de Cloud Storage: evidencias (30 días) y documentos de póliza | - |
| Secretos | Secret Manager, con acceso por secreto y por cuenta de servicio | - |
| Infraestructura como código | [`scripts/ambiente.py`](../../scripts/ambiente.py) con los workflows *Aprovisionar*, *Destruir* y *Encender o apagar Cloud SQL*: comandos de `gcloud` idempotentes, con pruebas sobre una nube simulada | Terraform |
| Acceso | Workload Identity Federation desde GitHub, sin llaves | - |

**No se implementa la multirregión en el PRY2.** RC-07 (failover de región con RTO ≤ 5 min) queda
diseñado y medido en el experimento EXP-03, pero no desplegado.

## Alternativas descartadas

| Alternativa | Por qué no |
|---|---|
| **La topología de los experimentos** (GKE multizona, Memorystore, Pub/Sub, región de respaldo) | Cuesta del orden de USD 1.300 a 1.900 al mes encendida. *Staging* paga una instancia `db-f1-micro` y Cloud Run con mínimo de instancias en 0 o 1, una fracción de eso, y se apaga cuando no se usa. Con cuatro personas y tres sprints, operar un clúster le quitaría tiempo al producto, que es lo que se evalúa en el PRY2 |
| **Terraform para *staging*** | Lo descartó ARQ-01. Con pocos recursos, un script idempotente con pruebas dio lo mismo (crear lo que falta, omitir lo que existe, destruir sin tocar el acceso) sin un estado remoto que cuidar. Si el ambiente crece, migrar a Terraform es el siguiente paso |
| **Cloud SQL HA (regional) desde ya** | Duplica el costo de la instancia. Se activa con `--availability-type=regional` cuando el producto tenga un ambiente de producción |
| **Servicios separados por módulo (microservicios)** | RN-03: equipo reducido. El monolito modular conserva las fronteras (import-linter en CI, un esquema por módulo) sin el costo de operar un servicio por módulo |

## Consecuencias

- **Lo que sí se cumple en *staging*:** despliegue continuo con humo y E2E en cada merge a
  `develop`; un esquema y un usuario por módulo; secretos fuera del repositorio; el ambiente
  completo se crea y se destruye desde GitHub.
- **Lo que no se cumple, y se declara:** RC-03 en caída de zona (instancia zonal, sin respaldos) y
  RC-07 (sin región de respaldo). Son la principal diferencia con la vista de despliegue del PRY1, y
  su evidencia sigue siendo el informe de EXP-03.
- **Escala horizontal limitada por el estado en memoria:** mientras un módulo guarde estado en la
  réplica (hoy las cuentas de identidad), el núcleo corre con una sola instancia. ARQ-05 sube a dos
  cuando identidad persista.
- **Costo controlado:** Cloud SQL se puede apagar fuera de horario (`cloud-sql.yml`; el apagado
  nocturno necesita el environment `staging-apagado`) y los servicios escalan a cero. Apagada la base, el CD avisa y las E2E fallan.
- **Revisión:** esta ADR se reemplaza si el equipo decide desplegar multirregión, o si las pruebas
  de carga de ARQ-05 muestran que una sola región con estas piezas no cumple RC-01.
