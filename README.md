# Grupo 13 - Proyecto Integrador I

# Solventa - Aseguradora Digital de Finanzas Abiertas

> Proyecto Final · MISW4501 · Maestría en Ingeniería de Software · Universidad de los Andes

Solventa es una aseguradora digital (*insurtech*) que nace sin sistemas heredados: una "fintech de seguros" construida desde cero sobre la nube y sobre el ecosistema de **Finanzas Abiertas (Open Finance)** y **Datos Abiertos (Open Data)**. Este repositorio contiene el diseño de arquitectura y la implementación (cliente web, cliente móvil y backend) desarrollados por el equipo como parte del curso Proyecto Final.

## Tabla de contenido

- [Descripción del proyecto](#-descripción-del-proyecto)
- [Equipo](#-equipo)
- [Documentación](#-documentación)
- [Atributos de calidad](#-atributos-de-calidad)
- [Arquitectura](#-arquitectura)
- [Estructura del repositorio](#-estructura-del-repositorio)
- [Cómo ejecutar el proyecto](#-cómo-ejecutar-el-proyecto)
- [Tablero y flujo de trabajo](#-tablero-y-flujo-de-trabajo)
- [Cómo contribuir](#-cómo-contribuir)

## Descripción del proyecto

El mercado de seguros en América Latina arrastra baja penetración, procesos manuales, tiempos de emisión de días y una experiencia de siniestros que erosiona la confianza del cliente. **Solventa** apuesta a resolver esto combinando:

- **Seguro embebido**: distribución de seguros vía API, en el punto exacto de necesidad del cliente (comprar un tiquete, alquilar un carro, tomar un crédito, abrir una cuenta).
- **Perfilamiento de riesgo con datos reales**: precios personalizados a partir de la vida financiera real del cliente, con su consentimiento explícito bajo el marco de Finanzas Abiertas (Decreto 1297 de 2022, Circular Externa 004 de 2024 de la SFC).

**Propuesta de valor:** cotizar, suscribir, emitir y pagar siniestros de forma casi instantánea, a través de un cliente web y un cliente móvil, cada uno con capacidades propias de su canal.

> Este es un caso de estudio académico. Solventa es una empresa ficticia creada con fines de enseñanza (MISW4501-2026); cualquier semejanza con entidades reales es coincidencia.

## Equipo

| Nombre | Rol | Código | Correo |
|---|---|---|---|
| Erick Julián Coral Crespo | Gerente del proyecto (Monitor) | - | - |
| Edwin Hernan Hurtado Cruz | Desarrollo | 202326341 | eh.hurtado@uniandes.edu.co |
| Harold Andres Bartolo Moscoso | Desarrollo | 202513889 | h.bartolo@uniandes.edu.co |
| Sergio Fernando Barrera Molano | Desarrollo | 202517034 | sf.barreram1@uniandes.edu.co |
| Juan Jose Restrepo Bonilla | Desarrollo | 202516633 | jj.restrepob1@uniandes.edu.co |

## Documentación

| Documento | Contenido |
|---|---|
| [Guía de usuario](docs/guia-de-usuario/README.md) | Uso de la web (clientes, operaciones y socios) y de la app móvil, con capturas |
| [Decisiones de arquitectura (ADR)](docs/adr/README.md) | BFF único con dos routers, consentimiento vigente antes de la caché y topología en Cloud Run |
| [Informe de calidad de ARQ-05](docs/calidad/informe-arq05.md) | Carga con k6, caos y auditoría en *staging*: corridas, umbrales y hallazgos |
| [Contratos de los BFF](contratos/bff-web/README.md) | OpenAPI de la web ([`bff-web`](contratos/bff-web/openapi.yaml)) y de la app ([`bff-movil`](contratos/bff-movil/openapi.yaml)) |
| [Patrones de diseño UI/UX](docs/patrones-ui-ux.md) | Catálogo de patrones de interfaz, mapa de flujos, accesibilidad e implementación de los mockups |
| [Design System v2 (PDF)](DesignSystem/Solventa_Design_System_v2.pdf) | Versión vigente: paleta índigo/violeta, botones en píldora y componentes usados por los mockups |
| [Sistema de navegación](DesignSystem/Solventa_Navigation_System.md) ([PDF](DesignSystem/Solventa_Navigation_System.pdf)) | Estructura de navegación web y móvil, flujos y estados |
| [Design System v1.0](DesignSystem/Solventa_Design_System.md) ([PDF](DesignSystem/Solventa_Design_System.pdf)) | Versión anterior en texto: principios, tipografía, espaciado, componentes y guía de implementación |
| [Patrones de arquitectura por vista](docs/Patrones-QR-vistas.md) | Trazabilidad patrón → requisito de calidad |
| [Backlog y plan de sprints](docs/backlog-sprint-plan.md) | Features, estimación y plan |
| Historias de usuario | Criterios de aceptación, partición y asignación por sprint: [Sprint 1](docs/HistoriasUsuarioSprint1.md), [Sprint 2](docs/HistoriasUsuarioSprint2.md) y [Sprint 3](docs/HistoriasUsuarioSprint3.md) |
| [Ambiente DevOps](docs/devops.md) | Flujo de trabajo, versionamiento, convención de commits, CI/CD, pruebas y tablero |

### Mockups navegables (publicados con GitHub Pages)

| Canal | Enlace |
|---|---|
| 🖥️ Web | [https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/web/login.html](https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/web/login.html) |
| 📱 Móvil | [https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/mobile-docs/login.html](https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/mobile-docs/login.html) |

Se publican automáticamente desde `main` en cada cambio. Para abrirlos en local: `python3 -m http.server 8765` en la raíz y visitar `/web/login.html` o `/mobile-docs/login.html`.

## Atributos de calidad

La arquitectura debe satisfacer simultáneamente seis atributos de calidad, cada uno con metas medibles y validables mediante experimentos de arquitectura:

| Atributo | Meta representativa |
|---|---|
| **Latencia** | Cotización embebida p95 ≤ 250 ms / p99 ≤ 500 ms |
| **Escalabilidad** | Escalar de 500 a 50.000 cotizaciones/min conservando el p95 |
| **Disponibilidad** | ≥ 99,97% mensual en journeys críticos (venta y siniestros) |
| **Seguridad** | Cifrado en tránsito/reposo, consentimiento auditable, revocación ≤ 5 min |
| **Facilidad de modificación** | Nuevo ramo de seguro en ≤ 2 semanas-equipo, sin tocar el núcleo |
| **Facilidad de integración** | Alta de un nuevo socio embebido en ≤ 1 semana |

Detalle completo de los escenarios de calidad en la Acta de Constitución y en el caso de estudio.

## Arquitectura

```
navegador ──▶ solventa-web (nginx) ──/api/──▶ solventa-bff ──▶ solventa-nucleo ──▶ Cloud SQL (PostgreSQL)
app Android ─────────────/api/v1/movil/──▶ ┘                        └──▶ Cloud Storage (evidencias y pólizas)
```

- **Estilo arquitectónico:** monolito modular en el núcleo, con un *Backend for Frontend* delante.
  Cada capacidad de negocio es un módulo con su propio esquema y usuario en PostgreSQL, y los módulos
  solo se hablan por la fachada `/v1` o por eventos en proceso; `lint-imports` verifica esa frontera en
  el CI. Así un módulo se puede sacar a un servicio aparte sin tocar a los demás, y mientras tanto se
  despliega y se opera una sola pieza.
- **Núcleo** ([`backend`](backend/README.md)): Python 3.12 y FastAPI. Módulos de identidad,
  consentimiento, perfilamiento, cotización, suscripción, pagos, pólizas, siniestros, notificaciones,
  socios y auditoría (log a prueba de alteración).
- **BFF** ([`bff`](bff/README.md)): un solo servicio con dos routers, `/api/v1/web/` y `/api/v1/movil/`,
  cada uno con su contrato OpenAPI ([ADR 0001](docs/adr/0001-bff-unico-dos-routers.md)). Valida las
  sesiones que firma el núcleo (ES256) y nunca importa su código.
- **Cliente web** ([`apps/web`](apps/web/README.md)): React + TypeScript, servido por nginx en el
  mismo origen que el BFF. Cotización, consentimientos, compra y consulta de pólizas, reporte y
  seguimiento de siniestros, operaciones y socios.
- **Cliente móvil** ([`mobile/android`](mobile/android/README.md)): Kotlin y Jetpack Compose, un módulo
  `feature/*` por historia. Registro con KYC, ingreso con biometría, consentimiento, pólizas, reporte de
  siniestros con cámara y sin conexión, seguimiento y notificaciones push.
- **Integraciones externas (simuladas):** Open Finance (con *circuit breaker* y caché que respeta el
  consentimiento vigente, [ADR 0002](docs/adr/0002-consentimiento-vigente-antes-de-la-cache.md)),
  KYC/AML, pasarela de pagos, firma electrónica y proveedor de push.
- **Nube:** Google Cloud, en `us-east1`. Tres servicios de Cloud Run, Cloud SQL y Cloud Storage, con
  *staging* desde `develop` y producción desde `main` en el mismo proyecto (sufijo `-prd`)
  ([ADR 0003](docs/adr/0003-topologia-de-despliegue-cloud-run.md),
  [`docs/devops.md`](docs/devops.md#despliegue-continuo)).

Web en *staging*: [https://solventa-web-6y7n6dblla-ue.a.run.app](https://solventa-web-6y7n6dblla-ue.a.run.app).
Por costo, Cloud SQL se apaga cada noche; si la web no responde, hay que encenderla con el workflow
*Encender o apagar Cloud SQL*.

## Estructura del repositorio

```
Proyecto_Grupo_13/
├── .github/                  # CI/CD (workflows), plantillas de issues y PR, Dependabot
│   └── ISSUE_TEMPLATE/       # Plantillas de issues (épicas y features)
├── apps/
│   └── web/                  # Cliente web (React + TypeScript) y sus E2E de Playwright (e2e/)
├── backend/                  # Núcleo: monolito modular en Python (app/modulos/, una carpeta por capacidad)
├── bff/                      # BFF único con los routers web y móvil
├── contratos/
│   ├── bff-web/              # Contrato OpenAPI entre el cliente web y su BFF
│   └── bff-movil/            # Contrato OpenAPI entre la app Android y su BFF
├── docs/                     # Historias, ADR, informe de calidad, backlog, patrones, guía de usuario y DevOps
├── DesignSystem/             # Design System, sistema de navegación y diseño del sistema
├── scripts/                  # Validadores de commits y de GitFlow, ambiente en GCP, humo y carga (k6, caos)
├── web/                      # Mockups navegables del canal web (HTML), publicados con GitHub Pages
├── mobile/                   # Cliente móvil
│   └── android/              # App nativa Kotlin / Jetpack Compose
├── mobile-docs/              # Mockups navegables del canal móvil (HTML), publicados con GitHub Pages
├── experimentos/             # Experimentos de arquitectura
├── solventa-experiments/     # Experimentos de arquitectura
└── README.md
```

## Cómo ejecutar el proyecto

Cada componente tiene su README con las variables de entorno y los comandos que corre el CI. Aquí
va lo mínimo para levantar todo en local.

### Prerrequisitos

- Python 3.12 para el núcleo y el BFF.
- Node.js 22 para la web.
- JDK 17 y Android SDK 35 para la app (Android Studio, o `gradlew` con `ANDROID_HOME`).
- Opcional: Docker con PostgreSQL. Sin las variables `*_DATABASE_URL`, cada módulo guarda en memoria y
  no hace falta ninguna base.

### Núcleo y BFF

```bash
cd backend
python3.12 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
HUMO_CORREO=prueba@solventa.test HUMO_CLAVE=<clave> uvicorn app.main:app --reload   # http://127.0.0.1:8000/docs

# en otra terminal
cd bff
python3.12 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
uvicorn app.main:app --reload --port 8001                                          # http://127.0.0.1:8001/docs
```

`HUMO_CORREO` y `HUMO_CLAVE` crean al arrancar una cuenta con la que entrar desde la web o la app. Con
PostgreSQL, cada módulo lee su cadena de conexión (`AUDITORIA_DATABASE_URL`, `COTIZACION_DATABASE_URL`,
etc.; ver [`backend/README.md`](backend/README.md)) y crea su esquema al arrancar.

### Cliente web

```bash
cd apps/web
npm install
npm run dev                      # http://localhost:5173, con el BFF simulado
VITE_MOCKS=false npm run dev     # contra el BFF local del puerto 8001
```

Las E2E de Playwright corren contra un ambiente con el BFF real (`npm run e2e`); detalle en
[`apps/web/README.md`](apps/web/README.md).

### Cliente móvil

```bash
cd mobile/android
./gradlew :app:assembleDebug     # con el BFF simulado dentro de la app (lo que corre el CI)
./gradlew :app:installDebug -PBFF_MOCK=false -PBFF_BASE_URL=<url del BFF>/api/v1/movil/
```

En Windows se usa `gradlew.bat`. `installDebug` necesita un emulador o un dispositivo con API 26 o
superior. El modo contra *staging* y las E2E en emulador están en
[`mobile/android/README.md`](mobile/android/README.md).

### Pruebas

| Componente | Comando |
|---|---|
| Núcleo y BFF | `ruff check . && lint-imports && pytest --cov=app` |
| Web | `npm run lint && npm run typecheck && npm run test:cobertura` |
| App | `./gradlew detekt lintDebug :koverVerify` |

El CI ([`ci.yml`](.github/workflows/ci.yml)) corre lo mismo en cada PR y exige al menos 70 % de
cobertura. La carga con k6 y el caos se lanzan a mano contra *staging* (workflow *Carga de cotización
(k6)*); los resultados están en el [informe de ARQ-05](docs/calidad/informe-arq05.md).

### Mockups navegables

[Web](https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/web/login.html) y
[móvil](https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/mobile-docs/login.html), publicados
desde `main` con GitHub Pages.

## Tablero y flujo de trabajo

El trabajo del equipo se organiza mediante **épicas** y **features (historias de usuario)** registradas como issues de GitHub:

1. Cada **épica** agrupa varias features relacionadas con una capacidad de negocio (ver plantilla [`epica.yml`](.github/ISSUE_TEMPLATE/epica.yml)).
2. Cada **feature** es una historia de usuario con criterios de aceptación verificables (ver plantilla [`feature.yml`](.github/ISSUE_TEMPLATE/feature.yml)).
3. Todos los issues se visualizan en el **tablero del proyecto (GitHub Project)**, filtrando por labels: `epic`, `feature`, `component:web`, `component:mobile`, `priority:*`.

Tablero del proyecto: [GitHub Project 4](https://github.com/users/JuanJoseRestrepo33/projects/4).

## Cómo contribuir

El flujo completo está en [docs/devops.md](docs/devops.md). El equipo trabaja con **GitFlow**: `main`
(producción), `develop` (integración, rama por defecto), `feature/*`, `release/*` y `hotfix/*`.

1. Instala los hooks una vez por clon: `pip install pre-commit && pre-commit install --install-hooks`.
2. Crea una rama corta desde `develop`: `git switch -c feature/WEB-F02-consentimiento-web`.
3. Escribe commits con la convención `tipo(alcance): descripción (HISTORIA)`, por ejemplo
   `feat(consentimiento): revocar consentimiento desde la web (WEB-F02)`.
4. Abre un pull request **hacia `develop`** con la plantilla y el mismo formato en el título, y enlaza
   la historia con `Closes #N`.
5. El merge, por *squash*, requiere el check `ci-ok` en verde y la aprobación de otro integrante.
6. Al cierre de cada sprint se crea `release/vX.Y.0` desde `develop` y se fusiona en `main`. El tag y
   el release se generan solos.

---

**Curso:** MISW4501 - Proyecto Final · **Institución:** Universidad de los Andes · **Código del caso:** MISW4501-2026
