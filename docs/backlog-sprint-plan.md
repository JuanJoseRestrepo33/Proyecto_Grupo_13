# Backlog priorizado y plan de 8 semanas

Estimación en story points (planning poker, escala Fibonacci) de las 20 features del repositorio, y su distribución en 4 sprints de 2 semanas, con el backend repartido por dominio de negocio entre Sergio y Juan.

- **Equipo:** 4 personas × 8h/semana × 8 semanas = **256h** de capacidad total
- **Backlog:** 154 story points en 20 features (4 épicas)
- **Cadencia:** 4 sprints de 2 semanas → 16h por persona por sprint

## Cómo queda repartido el equipo

El backend no lo carga una sola persona: se divide por dominio de negocio.

| Persona | Rol | Dedicación (8 sem.) | Qué construye |
|---|---|---|---|
| **Sergio** | Backend | 64h | Dominio **pólizas**: consentimiento, cotización, suscripción/emisión, consulta y ciclo de vida (renovar / cancelar / modificar). |
| **Juan** | DevOps + Backend | 64h | Dominio **siniestros** (reporte, seguimiento y eventos paramétricos a escala) + integraciones móviles (verificación KYC/AML, notificaciones push, geolocalización, validación de documentos) + las APIs de agregación operacional y back-office de socios. |
| **Harold** | Frontend | 64h | Las 11 pantallas del cliente web, sobre las APIs de Sergio y Juan. |
| **Edwin** | Full Stack | 64h | La app móvil completa (las 9 features MOB), reutilizando las mismas APIs. |

## Backlog priorizado

Alcance de prototipo funcional con integraciones simuladas (Open Finance, KYC/AML, pasarela de pago), como indica el propio caso Solventa.

### WEB-EP01 · Adquisición y consulta de seguros (39 pts · 77h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| WEB-F01 | Cotización y oferta personalizada | Alta | Sergio + Harold | 13 | 26h |
| WEB-F02 | Gestión del consentimiento Open Finance | Alta | Sergio + Harold | 8 | 14h |
| WEB-F03 | Suscripción y emisión de póliza | Alta | Sergio + Harold | 13 | 26h |
| WEB-F04 | Consulta de pólizas y coberturas | Media | Sergio + Harold | 5 | 11h |

### WEB-EP02 · Ciclo de vida, siniestros y operación (47 pts · 94h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| WEB-F05 | Reporte de siniestro asistido | Alta | Juan + Harold | 8 | 15h |
| WEB-F06 | Consulta y seguimiento de siniestros | Alta | Juan + Harold | 8 | 21h |
| WEB-F07 | Renovación de póliza | Media | Sergio + Harold | 8 | 9h |
| WEB-F08 | Cancelación de póliza | Media | Sergio + Harold | 5 | 8h |
| WEB-F09 | Modificación de póliza | Media | Sergio + Harold | 5 | 8h |
| WEB-F10 | Tablero operacional | Media | Juan + Harold | 5 | 14h |
| WEB-F11 | Back-office de socios de distribución | Media | Juan + Harold | 8 | 19h |

### MOB-EP01 · Onboarding, identidad y autoservicio (39 pts · 45h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| MOB-F01 | Onboarding y autenticación biométrica | Alta | Juan + Edwin | 13 | 20h |
| MOB-F02 | Consentimiento Open Finance (móvil) | Alta | Edwin *(reusa API de Sergio)* | 5 | 4h |
| MOB-F03 | Billetera de pólizas y modo offline | Alta | Edwin *(reusa API de Sergio)* | 13 | 14h |
| MOB-F04 | Cotización y suscripción de autoservicio | Media | Edwin *(reusa API de Sergio)* | 8 | 7h |

### MOB-EP02 · Siniestros, asistencia y notificaciones (29 pts · 40h)

| Historia | Título | Prioridad | Responsable(s) | Puntos | Horas |
|---|---|---|---|---:|---:|
| MOB-F05 | Reporte de siniestro con evidencia multimedia | Alta | Edwin *(reusa API de Juan)* | 8 | 8h |
| MOB-F06 | Seguimiento de siniestros y notificaciones push | Alta | Juan + Edwin | 8 | 13h |
| MOB-F07 | Geolocalización y asistencia en sitio | Media | Juan + Edwin | 5 | 9h |
| MOB-F08 | Notificaciones de ciclo de vida y vencimientos | Media | Edwin *(reusa API de Juan)* | 3 | 3h |
| MOB-F09 | Escaneo de documentos | Media | Juan + Edwin | 5 | 7h |

**Total backlog: 154 puntos · 256h**

## Plan de 8 semanas · 4 sprints

Cada sprint dura 2 semanas y cada persona tiene 16h de presupuesto. Las horas de cada tarjeta suman exactamente ese presupuesto por persona y por sprint.

### Sprint 1 · Semanas 1–2 · 21 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Servicio de consentimiento (WEB-F02 · MOB-F02): 10h · Motor de cotización, avance (WEB-F01): 6h | 16h |
| Harold | UI consentimiento (WEB-F02): 4h · UI cotizador (WEB-F01): 8h · UI checkout, avance (WEB-F03): 4h | 16h |
| Edwin | Onboarding biométrico (MOB-F01): 14h · Consentimiento móvil, avance (MOB-F02): 2h | 16h |
| Juan | Hook de verificación KYC/AML (MOB-F01): 6h · Servicio de siniestros: reporte (WEB-F05 · MOB-F05): 10h | 16h |

**Cierra:** WEB-F02 (8 pts) · MOB-F01 (13 pts)

### Sprint 2 · Semanas 3–4 · 34 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Motor de cotización, cierre (WEB-F01): 12h · Suscripción y emisión, avance (WEB-F03): 4h | 16h |
| Harold | UI checkout, cierre (WEB-F03): 4h · UI reporte de siniestro (WEB-F05): 5h · UI seguimiento de siniestros (WEB-F06): 5h · UI consulta de pólizas, avance (WEB-F04): 2h | 16h |
| Edwin | Consentimiento móvil, cierre (MOB-F02): 2h · Billetera y modo offline (MOB-F03): 14h | 16h |
| Juan | Servicio de siniestros: seguimiento y eventos paramétricos a escala (WEB-F06 · MOB-F06): 16h | 16h |

**Cierra:** WEB-F01 (13 pts) · MOB-F02 (5 pts) · WEB-F05 (8 pts) · WEB-F06 (8 pts)

### Sprint 3 · Semanas 5–6 · 29 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Suscripción y emisión, cierre (WEB-F03): 14h · Consulta de pólizas, avance (WEB-F04 · MOB-F03): 2h | 16h |
| Harold | UI consulta de pólizas, cierre (WEB-F04): 3h · UI renovación (WEB-F07): 5h · UI cancelación (WEB-F08): 4h · UI modificación (WEB-F09): 4h | 16h |
| Edwin | Reporte de siniestro con evidencia (MOB-F05): 8h · Seguimiento y push (MOB-F06): 7h · Cotización autoservicio, avance (MOB-F04): 1h | 16h |
| Juan | Disparadores de notificaciones push (MOB-F06): 6h · Geolocalización y match de prestadores (MOB-F07): 5h · Validación de documentos escaneados (MOB-F09): 4h · API de agregación, avance (WEB-F10): 1h | 16h |

**Cierra:** WEB-F03 (13 pts) · MOB-F05 (8 pts) · MOB-F06 (8 pts)

### Sprint 4 · Semanas 7–8 · 70 pts entregados

| Persona | Tareas | Horas |
|---|---|---:|
| Sergio | Consulta de pólizas, cierre (WEB-F04 · MOB-F03): 4h · Ciclo de vida: renovación, cancelación, modificación (WEB-F07/08/09): 12h | 16h |
| Harold | UI tablero operacional (WEB-F10): 8h · UI back-office de socios (WEB-F11): 8h | 16h |
| Edwin | Cotización autoservicio, cierre (MOB-F04): 6h · Geolocalización y asistencia (MOB-F07): 4h · Notificaciones de ciclo de vida (MOB-F08): 3h · Escaneo de documentos (MOB-F09): 3h | 16h |
| Juan | API de agregación operacional, cierre (WEB-F10): 5h · API de back-office de socios (WEB-F11): 11h | 16h |

**Cierra:** WEB-F04, WEB-F07, WEB-F08, WEB-F09, WEB-F10, WEB-F11, MOB-F03, MOB-F04, MOB-F07, MOB-F08, MOB-F09 (70 pts)

## Notas de secuenciación

> **Los Sprints 1–3 son enteramente historias de prioridad Alta** (84 pts, 9 de las 10 historias): consentimiento, cotización, suscripción, onboarding biométrico, y el flujo completo de siniestros en ambos canales. La única excepción es **MOB-F03 · Billetera offline** (13 pts), que espera al Sprint 4 porque comparte el servicio de consulta de pólizas con WEB-F04, y Sergio prioriza cerrar primero Suscripción y emisión (la base del recorrido crítico de venta). Si el equipo prefiere adelantarla, basta con invertir ese orden en Sergio; no cambia el total de horas, solo qué llega antes.

> **El Sprint 4 concentra 70 pts:** los 57 pts de la cola de historias Media (ciclo de vida, tablero, back-office, features móviles secundarias) más los 13 pts de Billetera offline que quedaron pendientes. Es el sprint con menos margen: si algo se atrasa en S1–S3, lo primero que se recorta aquí es el back-office de socios (WEB-F11) o el escaneo de documentos (MOB-F09), ninguno de los dos toca un recorrido crítico del caso.

---

*Estimación en planning poker (Fibonacci) sobre las 20 features de este repositorio · Backend repartido por dominio entre Sergio (pólizas) y Juan (siniestros + integraciones móviles).*
