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
- **Cliente web:** _framework y responsabilidades (gestión completa: venta asistida, back-office de socios, tableros)_
- **Cliente móvil:** _framework y responsabilidades (autoservicio: reporte de siniestros, biometría, modo offline)_
- **Backend / servicios:** _tecnologías y capacidades de negocio implementadas (cotización, suscripción, siniestros, pagos, identidad, perfilamiento)_
- **Integraciones externas (simuladas):** Open Finance / Open Data, KYC/AML, pasarelas de pago, reaseguradoras

Diagramas y vistas de arquitectura: _enlazar aquí (C4, diagramas de despliegue, ADRs, etc.)_

## Estructura del repositorio

```
Proyecto_Grupo_13/
├── .github/
│   └── ISSUE_TEMPLATE/       # Plantillas de issues (épicas y features)
├── docs/                     # Acta de constitución, épicas y features, caso de estudio
├── web/                      # Cliente web
├── mobile/                   # Cliente móvil
├── backend/                  # Servicios / API / capacidades de negocio
└── README.md
```

> Ajusta esta estructura a como esté organizado realmente el repositorio.

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

```bash
cd web
# instrucciones de instalación y ejecución
```

### Cliente móvil

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

1. Crea una rama a partir de `main`: `git checkout -b feature/F-WEB-01-01-cotizacion-asistida`
2. Referencia el issue correspondiente en tus commits (`#<número-de-issue>`).
3. Abre un Pull Request describiendo los cambios y enlazando la feature/issue que resuelve.
4. Solicita revisión de al menos un integrante del equipo antes de hacer merge.

---

**Curso:** MISW4501 - Proyecto Final · **Institución:** Universidad de los Andes · **Código del caso:** MISW4501-2026
