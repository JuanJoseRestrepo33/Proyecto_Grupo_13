# Solventa · Patrones de diseño → Requisito de calidad favorecido

Trazabilidad entre los patrones aplicados en la arquitectura, el requisito de calidad (RC) que cada uno favorece de forma principal, la vista donde puede observarse y el razonamiento.

| QR favorecido | Patrón usado | En qué vista se puede ver | Cómo lo favorece |
|---|---|---|---|
| **RC-01 Latencia** | Cache-aside (Caché de Perfil, Redis) | Modelo de Componentes 1/3 · Despliegue 2/3 · Información (2/2) | Sirve el Perfil de Riesgo desde Redis y evita la llamada síncrona a Open Finance dentro del presupuesto p95 ≤ 250 ms |
| **RC-01 Latencia** | Backend for Frontend (BFF Web / Móvil) | Modelo de Componentes 1/3 · Despliegue 1/3 | Agrega varias llamadas del núcleo en una y recorta el payload por canal (móvil), reduciendo viajes y bytes |
| **RC-01 Latencia** | Horizontal Pod Autoscaler (HPA) | Despliegue 1/3 y 2/3 | Añade réplicas del `core-monolith` al subir la demanda, sosteniendo el p95 bajo carga |
| **RC-02 Escalabilidad de eventos** | Competing Consumers + auto-escalado por cola (KEDA) | Modelo de Componentes 3/3 · Despliegue 2/3 | Varias instancias consumen en paralelo por partición y escalan según la profundidad de la cola → 1 000 000 eventos / 10 min |
| **RC-02 Escalabilidad de eventos** | Particionamiento / sharding de datos (por fecha, país/región, tipo de evento) | Información (2/2) | Deja Cotización y la cola listas para crecer horizontalmente sin rediseño |
| **RC-03 Disponibilidad** | Circuit Breaker + timeout 700 ms + fallback a caché | Modelo de Componentes 2/3 · Despliegue 3/3 | Corta la llamada a un tercero lento/caído y responde con caché, manteniendo ≥ 99,9 % en modo degradado |
| **RC-03 Disponibilidad** | Redundancia activa multi-zona + Load Balancer con health checks | Despliegue 1/3 y 3/3 | N réplicas por zona; una zona caída se retira automáticamente → ≥ 99,97 % mensual |
| **RC-03 Disponibilidad** | Warm standby (región de respaldo us-east1) | Despliegue 3/3 · Información (2/2) | Failover de región en ≤ 5 min sin reconstruir infraestructura desde cero |
| **RC-03 Disponibilidad** | Replicación síncrona multi-zona + asíncrona cross-region (réplica promotable) | Despliegue 3/3 · Información (2/2) | Acota RTO/RPO ante pérdida de zona o región (RPO ≤ 30 s) |
| **RC-03 Disponibilidad** | Dead-Letter Queue + contrapresión (back-pressure) | Modelo de Componentes 3/3 | Aparta eventos inválidos/duplicados para reproceso y frena las ráfagas de oráculos sin desbordar el bus |
| **RC-03 Disponibilidad** | Idempotent Receiver / `dedup_key` | Modelo de Componentes 3/3 · Información (entidades Evento Paramétrico y Transacción de Pago) | Entrega al-menos-una-vez segura: no se duplican liquidaciones ni pagos ante reintentos ("cero pérdida" en recaudo) |
| **RC-04 Seguridad / privacidad** | Tokenización + cifrado a nivel de columna (Secret Manager + KMS) | Información (2/2) · Despliegue 2/3 | PII (Cliente, Consentimiento, Perfil) se tokeniza antes de persistir; claves gestionadas y aisladas |
| **RC-04 Seguridad / privacidad** | Minimización / anonimización de datos (BigQuery sin PII) | Información (2/2) | La analítica solo opera sobre copias anonimizadas y agregadas |
| **RC-04 Seguridad / privacidad** | Service mesh con mTLS | Despliegue (1/3, 2/3, 3/3) | Autenticación mutua entre pods y cifrado del tráfico intra-clúster |
| **RC-05 Auditabilidad** | Audit Log / almacén append-only (event sourcing parcial) | Modelo de Componentes 1/3 · Información (Log de Decisión) | Registro inmutable de decisiones de pricing/suscripción con `version_regla`, `variables_entrada` y `timestamp` |
| **RC-05 Modificabilidad** | Fachada (interfaz estable del núcleo) | Vista Funcional (C&C) · Modelo de Componentes 1/3 | Contrato versionado que oculta la reestructuración interna de los 9 módulos; los clientes no se rompen al refactorizar |
| **RC-05 Modificabilidad** | Monolito modular (una frontera por capacidad de negocio) | Vista Funcional (C&C) | Cambios localizados por módulo sin la complejidad operativa de microservicios |
| **RC-05 Modificabilidad** | Database per bounded context (una BD por dominio) | Información (2/2) | La evolución de esquema de un dominio no impacta a los demás |
| **RC-05 Modificabilidad** | Adapter (capa de adaptadores de integración) | Modelo de Componentes 2/3 · Despliegue 3/3 | Un cambio en el contrato de una API externa (Open Finance, KYC, pagos, ACORD, firma) no toca los módulos del núcleo |
| **RC-05 Modificabilidad** | Publish/Subscribe (Event Bus) | Modelo de Componentes 3/3 · Información (2/2) | Añadir un consumidor (analítica, fraude, notificaciones) no modifica al productor |
| **RC-06 Integración** | API Gateway + versionado v1/v2 | Vista Funcional (C&C) · Despliegue 1/3 | Punto único de autenticación OAuth2, límites de cuota y migración de versión sin ruptura para socios embebidos |
| **RC-06 Integración** | Modelo de "Socio de Distribución" con credenciales y cuota | Información (modelo de datos) | Aislamiento y control de consumo por socio a nivel de datos |

## Nomenclatura

- **RC-01 Latencia**, **RC-02 Escalabilidad de eventos**, **RC-03 Disponibilidad**, **RC-04 Seguridad**, **RC-05 Auditabilidad / Modificabilidad**, **RC-06 Integración** son los requisitos de calidad de la Hoja de Trabajo.
- Los identificadores `ASR-07.x` (disponibilidad / recuperación) y `ASR-08.x` (seguridad / auditoría) que aparecen en los diagramas son escenarios de calidad derivados de esos RC y de las cifras de la Hoja; no son códigos canónicos del catálogo del proyecto.
