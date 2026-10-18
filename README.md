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

> Coloca estos archivos en una carpeta `docs/` en la raíz del repo y ajusta los enlaces si usas otra ubicación.

## Documentación

| Documento | Contenido |
|---|---|
| [Guía de usuario](docs/guia-de-usuario/README.md) | Uso de la web (clientes, operaciones y socios) y de la app móvil, con capturas |
| [Patrones de diseño UI/UX](docs/patrones-ui-ux.md) | Catálogo de patrones de interfaz, mapa de flujos, accesibilidad e implementación de los mockups |
| [Design System v2 (PDF)](DesignSystem/Solventa_Design_System_v2.pdf) | Versión vigente: paleta índigo/violeta, botones en píldora y componentes usados por los mockups |
| [Sistema de navegación](DesignSystem/Solventa_Navigation_System.md) ([PDF](DesignSystem/Solventa_Navigation_System.pdf)) | Estructura de navegación web y móvil, flujos y estados |
| [Design System v1.0](DesignSystem/Solventa_Design_System.md) ([PDF](DesignSystem/Solventa_Design_System.pdf)) | Versión anterior en texto: principios, tipografía, espaciado, componentes y guía de implementación |
| [Patrones de arquitectura por vista](docs/Patrones-QR-vistas.md) | Trazabilidad patrón → requisito de calidad |
| [Backlog y plan de sprints](docs/backlog-sprint-plan.md) | Features, estimación y plan |
| [Historias del Sprint 1](docs/HistoriasUsuarioSprint1.md) | Historias detalladas, criterios de aceptación y asignación |
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

<!-- TODO: completar con el estilo arquitectónico elegido por el equipo -->

- **Estilo arquitectónico:** _por definir / describir aquí (monolito modular, microservicios, EDA, etc.) y su justificación_
- **Cliente web:** React + TypeScript ([`apps/web`](apps/web)), que habla solo con su BFF según el contrato [`contratos/bff-web`](contratos/bff-web). Gestión completa: venta asistida, consentimientos, back-office de socios y tableros.
- **Cliente móvil:** _framework y responsabilidades (autoservicio: reporte de siniestros, biometría, modo offline)_
- **Backend / servicios:** _tecnologías y capacidades de negocio implementadas (cotización, suscripción, siniestros, pagos, identidad, perfilamiento)_
- **Integraciones externas (simuladas):** Open Finance / Open Data, KYC/AML, pasarelas de pago, reaseguradoras

Diagramas y vistas de arquitectura: _enlazar aquí (C4, diagramas de despliegue, ADRs, etc.)_

## Estructura del repositorio

```
Proyecto_Grupo_13/
├── .github/                  # CI/CD (workflows), plantillas de issues y PR, Dependabot
│   └── ISSUE_TEMPLATE/       # Plantillas de issues (épicas y features)
├── apps/
│   └── web/                  # Cliente web (React + TypeScript)
├── contratos/
│   ├── bff-web/              # Contrato OpenAPI entre el cliente web y su BFF
│   └── bff-movil/            # Contrato OpenAPI entre la app Android y su BFF
├── docs/                     # Acta, épicas, features, caso de estudio, historias, backlog, patrones, guía de usuario y DevOps
├── DesignSystem/             # Design System, sistema de navegación y diseño del sistema
├── scripts/                  # Herramientas del repo (validadores de commits y de GitFlow)
├── web/                      # Mockups navegables del canal web (HTML), publicados con GitHub Pages
├── mobile/                   # Cliente móvil
│   └── android/              # App nativa Kotlin / Jetpack Compose
├── mobile-docs/              # Mockups navegables del canal móvil (HTML), publicados con GitHub Pages
├── experimentos/             # Experimentos de arquitectura
├── solventa-experiments/     # Experimentos de arquitectura
└── README.md
```

## Cómo ejecutar el proyecto

<!-- TODO: completar con las instrucciones reales una vez definida la arquitectura -->

### Prerrequisitos

- _Node.js / Java / Python (según stack elegido)_
- _Docker (si aplica)_
- _Cuenta de servicios en la nube o emuladores locales_

### Backend

```bash
cd backend
# instrucciones de instalación y ejecución
```

### Cliente web

Requiere Node.js 22. Corre contra un BFF simulado que implementa
[`contratos/bff-web/openapi.yaml`](contratos/bff-web/openapi.yaml); detalle en
[apps/web/README.md](apps/web/README.md).

```bash
cd apps/web
npm install
npm run dev        # http://localhost:5173
```

Mockups navegables: [https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/web/login.html](https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/web/login.html)

### Cliente móvil

Mockups navegables: [https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/mobile-docs/login.html](https://juanjoserestrepo33.github.io/Proyecto_Grupo_13/mobile-docs/login.html)

```bash
cd mobile
# instrucciones de instalación y ejecución
```

## Tablero y flujo de trabajo

El trabajo del equipo se organiza mediante **épicas** y **features (historias de usuario)** registradas como issues de GitHub:

1. Cada **épica** agrupa varias features relacionadas con una capacidad de negocio (ver plantilla [`epica.yml`](.github/ISSUE_TEMPLATE/epica.yml)).
2. Cada **feature** es una historia de usuario con criterios de aceptación verificables (ver plantilla [`feature.yml`](.github/ISSUE_TEMPLATE/feature.yml)).
3. Todos los issues se visualizan en el **tablero del proyecto (GitHub Project)**, filtrando por labels: `epic`, `feature`, `component:web`, `component:mobile`, `priority:*`.

Tablero del proyecto: _enlazar aquí la URL del GitHub Project_

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
