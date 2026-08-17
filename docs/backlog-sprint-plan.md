# Backlog priorizado y plan de 8 semanas

Estimación en story points (planning poker, escala Fibonacci) de las 20 features del repositorio, y su distribución en 4 sprints de 2 semanas, con el backend repartido por dominio de negocio entre Sergio y Juan.

- **Equipo:** 4 personas × 8h/semana × 8 semanas = **256h** de capacidad total
- **Backlog:** 154 story points en 20 features (4 épicas)
- **Cadencia:** 4 sprints de 2 semanas → 16h por persona por sprint

## Cómo queda repartido el equipo

Con Juan como DevOps que también sabe backend, el backend no lo carga una sola persona: se divide por dominio de negocio, no por "features vs. infraestructura".

| Persona | Rol | Dedicación (8 sem.) | Qué construye |
|---|---|---|---|
| **Sergio** | Backend | 64h | Dominio **pólizas**: consentimiento, cotización, suscripción/emisión, consulta y ciclo de vida (renovar / cancelar / modificar). |
| **Juan** | DevOps + Backend | 64h | Dominio **siniestros** (reporte, seguimiento, eventos paramétricos) + integraciones móviles (KYC, push, geolocalización, escaneo) + 24h de plataforma mínima (ver [Plataforma](#plataforma-juan)). |
| **Harold** | Frontend | 64h | Las 11 pantallas del cliente web, sobre las APIs de Sergio y Juan. |
| **Edwin** | Full Stack | 64h | La app móvil completa (las 9 features MOB), reutilizando las mismas APIs. |

## Backlog priorizado

Alcance de prototipo funcional con integraciones simuladas (Open Finance, KYC/AML, pasarela de pago), como indica el propio caso Solventa.

### WEB-EP01 — Adquisición y consulta de seguros (39 pts · 77h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| WEB-F01 | Cotización y oferta personalizada | Alta | Sergio + Harold | 13 | 26h |
| WEB-F02 | Gestión del consentimiento Open Finance | Alta | Sergio + Harold | 8 | 14h |
| WEB-F03 | Suscripción y emisión de póliza | Alta | Sergio + Harold | 13 | 26h |
| WEB-F04 | Consulta de pólizas y coberturas | Media | Sergio + Harold | 5 | 11h |

### WEB-EP02 — Ciclo de vida, siniestros y operación (47 pts · 82h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| WEB-F05 | Reporte de siniestro asistido | Alta | Juan + Harold | 8 | 13h |
| WEB-F06 | Consulta y seguimiento de siniestros | Alta | Juan + Harold | 8 | 15h |
| WEB-F07 | Renovación de póliza | Media | Sergio + Harold | 8 | 9h |
| WEB-F08 | Cancelación de póliza | Media | Sergio + Harold | 5 | 8h |
| WEB-F09 | Modificación de póliza | Media | Sergio + Harold | 5 | 8h |
| WEB-F10 | Tablero operacional | Media | Juan + Harold | 5 | 13h |
| WEB-F11 | Back-office de socios de distribución | Media | Juan + Harold | 8 | 16h |

### MOB-EP01 — Onboarding, identidad y autoservicio (39 pts · 42h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| MOB-F01 | Onboarding y autenticación biométrica | Alta | Juan + Edwin | 13 | 17h |
| MOB-F02 | Consentimiento Open Finance (móvil) | Alta | Edwin *(reusa API de Sergio)* | 5 | 4h |
| MOB-F03 | Billetera de pólizas y modo offline | Alta | Edwin *(reusa API de Sergio)* | 13 | 14h |
| MOB-F04 | Cotización y suscripción de autoservicio | Media | Edwin *(reusa API de Sergio)* | 8 | 7h |

### MOB-EP02 — Siniestros, asistencia y notificaciones (29 pts · 31h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| MOB-F05 | Reporte de siniestro con evidencia multimedia | Alta | Edwin *(reusa API de Juan)* | 8 | 8h |
| MOB-F06 | Seguimiento de siniestros y notificaciones push | Alta | Juan + Edwin | 8 | 9h |
| MOB-F07 | Geolocalización y asistencia en sitio | Media | Juan + Edwin | 5 | 6h |
| MOB-F08 | Notificaciones de ciclo de vida y vencimientos | Media | Edwin *(reusa API de Sergio)* | 3 | 3h |
| MOB-F09 | Escaneo de documentos | Media | Juan + Edwin | 5 | 5h |

**Total backlog: 154 puntos · 232h**

## Plan de 8 semanas — 4 sprints

Cada sprint dura 2 semanas y cada persona tiene 16h de presupuesto. Las horas de cada tarjeta suman exactamente ese presupuesto por persona y por sprint.

### Sprint 1 — Semanas 1–2 · 21 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Servicio de consentimiento (WEB-F02 · MOB-F02) — 10h · Motor de cotización, avance (WEB-F01) — 6h | 16h |
| Harold | UI consentimiento (WEB-F02) — 4h · UI cotizador (WEB-F01) — 8h · UI checkout, avance (WEB-F03) — 4h | 16h |
| Edwin | Onboarding biométrico (MOB-F01) — 14h · Consentimiento móvil, avance (MOB-F02) — 2h | 16h |
| Juan | CI + entorno de staging — 12h · Hook de verificación KYC/AML (MOB-F01) — 3h · Mocks de integraciones, avance — 1h | 16h |

**Cierra:** WEB-F02 (8 pts) · MOB-F01 (13 pts)

### Sprint 2 — Semanas 3–4 · 18 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Motor de cotización, cierre (WEB-F01) — 12h · Suscripción y emisión, avance (WEB-F03) — 4h | 16h |
| Harold | UI checkout, cierre (WEB-F03) — 4h · UI reporte de siniestro (WEB-F05) — 5h · UI seguimiento de siniestros (WEB-F06) — 5h · UI consulta de pólizas, avance (WEB-F04) — 2h | 16h |
| Edwin | Consentimiento móvil, cierre (MOB-F02) — 2h · Billetera y modo offline (MOB-F03) — 14h | 16h |
| Juan | Mocks de integraciones, cierre + despliegue continuo — 11h · Servicio de siniestros: reporte, avance (WEB-F05 · MOB-F05) — 5h | 16h |

**Cierra:** WEB-F01 (13 pts) · MOB-F02 (5 pts)

### Sprint 3 — Semanas 5–6 · 45 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Suscripción y emisión, cierre (WEB-F03) — 14h · Consulta de pólizas, avance (WEB-F04 · MOB-F03) — 2h | 16h |
| Harold | UI consulta de pólizas, cierre (WEB-F04) — 3h · UI renovación (WEB-F07) — 5h · UI cancelación (WEB-F08) — 4h · UI modificación (WEB-F09) — 4h | 16h |
| Edwin | Reporte de siniestro con evidencia (MOB-F05) — 8h · Seguimiento y push (MOB-F06) — 7h · Cotización autoservicio, avance (MOB-F04) — 1h | 16h |
| Juan | Servicio de siniestros: reporte y seguimiento, cierre (WEB-F05/06 · MOB-F05/06) — 13h · Disparadores de push (MOB-F06) — 2h · API de agregación, avance (WEB-F10) — 1h | 16h |

**Cierra:** WEB-F03 (13 pts) · WEB-F05 (8 pts) · WEB-F06 (8 pts) · MOB-F05 (8 pts) · MOB-F06 (8 pts)

### Sprint 4 — Semanas 7–8 · 70 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Consulta de pólizas, cierre (WEB-F04 · MOB-F03) — 4h · Ciclo de vida: renovación, cancelación, modificación (WEB-F07/08/09) — 12h | 16h |
| Harold | UI tablero operacional (WEB-F10) — 8h · UI back-office de socios (WEB-F11) — 8h | 16h |
| Edwin | Cotización autoservicio, cierre (MOB-F04) — 6h · Geolocalización y asistencia (MOB-F07) — 4h · Notificaciones de ciclo de vida (MOB-F08) — 3h · Escaneo de documentos (MOB-F09) — 3h | 16h |
| Juan | API de agregación, cierre (WEB-F10) — 4h · API de back-office de socios (WEB-F11) — 8h · Geolocalización backend + validación de escaneo (MOB-F07 · MOB-F09) — 4h | 16h |

**Cierra:** WEB-F04, WEB-F07, WEB-F08, WEB-F09, WEB-F10, WEB-F11, MOB-F03, MOB-F04, MOB-F07, MOB-F08, MOB-F09 (70 pts)

## Notas de secuenciación

> **8 de las 10 historias Alta cierran en las primeras 6 semanas** (S1–S3: 84 pts). La única excepción es **MOB-F03 · Billetera offline** (13 pts): comparte el servicio de consulta de pólizas con WEB-F04, y Sergio prioriza cerrar primero Suscripción y emisión — la base del recorrido crítico de venta. Si el equipo prefiere adelantar Billetera, basta con invertir ese orden en el Sprint 3–4 de Sergio; no cambia el total de horas, solo qué llega antes.

> **El Sprint 4 concentra 70 pts** porque ahí cae toda la cola de historias Media (ciclo de vida, tablero, back-office, y la mayoría de features móviles secundarias) más la Billetera. Es el sprint con menos margen: si algo se atrasa en S1–S3, lo primero que se recorta aquí es el back-office de socios (WEB-F11) o el escaneo de documentos (MOB-F09) — ninguno de los dos toca un recorrido crítico del caso.

## Plataforma (Juan)

24 de las 64h de Juan, integradas en el plan de arriba: solo lo que el equipo necesita para poder construir y demostrar el prototipo en 8 semanas. El resto de sus horas son backend de siniestros y las integraciones móviles.

| Tarea | Detalle | Horas |
|---|---|---:|
| CI y entorno de staging | Pipeline de build/test + un ambiente donde correr la demo | 12h |
| Mocks de integraciones externas | Open Finance, KYC/AML, pasarela de pago, push — las que el caso llama "simuladas" | 8h |
| Despliegue continuo | De los tres componentes (web, móvil, backend) a staging | 4h |

---

*Estimación en planning poker (Fibonacci) sobre las 20 features de este repositorio · Backend repartido por dominio entre Sergio (pólizas) y Juan (siniestros + integraciones móviles).*
