# Solventa · Cliente web

Aplicación web de Solventa: venta asistida, consentimientos de Open Finance y, en sprints
siguientes, back-office y tableros. Habla **solo** con su BFF, según el contrato
[`contratos/bff-web/openapi.yaml`](../../contratos/bff-web/openapi.yaml).

## Stack

| Área | Herramienta | Por qué |
|---|---|---|
| UI | React 19 + TypeScript | Stack definido en el Design System (React 18+ con TypeScript) |
| Build | Vite | Servidor de desarrollo y build de producción |
| Rutas | React Router | Navegación del SPA |
| Estilos | CSS Modules + design tokens | Tokens del Design System v2 en [`src/estilos/tokens.css`](src/estilos/tokens.css) |
| i18n | i18next + react-i18next | Textos externalizados en español e inglés (definición de hecho) |
| Cliente del BFF | openapi-fetch | Tipado con los tipos generados del contrato |
| Mocks del BFF | MSW | El mismo mock sirve al navegador y a las pruebas |
| Pruebas | Vitest + Testing Library + axe-core | API compatible con Jest; axe valida accesibilidad |
| Lint | ESLint + jsx-a11y | Reglas de accesibilidad en modo estricto |

## Cómo ejecutarla

Requiere Node.js 22.

```bash
cd apps/web
npm install
npm run dev          # http://localhost:5173, con el BFF simulado (VITE_MOCKS=true)
```

| Script | Qué hace |
|---|---|
| `npm run dev` | Servidor de desarrollo con mocks del BFF |
| `npm test` | Pruebas en modo observación |
| `npm run test:cobertura` | Pruebas con cobertura (falla por debajo de 70%) |
| `npm run lint` / `npm run typecheck` | ESLint y TypeScript |
| `npm run build` | Build de producción (no incluye los mocks) |
| `npm run e2e` | Pruebas E2E (Playwright) contra un ambiente con el BFF real; ver abajo |
| `npm run contratos:validar` | Valida el contrato OpenAPI del BFF |
| `npm run contratos:tipos` | Regenera `src/api/esquema.ts` desde el contrato |

Variables de entorno: ver [`.env.example`](.env.example).

Contra el BFF real: con el núcleo y el BFF corriendo en local (ver [`bff/README.md`](../../bff/README.md)), `VITE_MOCKS=false npm run dev`. El servidor de desarrollo reenvía `/api` al BFF (`BFF_URL`, por defecto `http://127.0.0.1:8001`), igual que nginx en *staging*. Para tener cuenta con qué entrar, el núcleo crea una al arrancar con `HUMO_CORREO` y `HUMO_CLAVE`. Para ver la cotización con Open Finance caído (WEB-F01, criterio 3), arranca el núcleo con `PERFILAMIENTO_OPEN_FINANCE_MODO=caido`.

## Estructura

```
src/
├── api/
│   ├── esquema.ts        # Tipos GENERADOS desde el contrato. No se edita a mano
│   ├── tipos.ts          # Alias legibles de los esquemas
│   └── cliente.ts        # Cliente tipado del BFF (bff.GET / bff.POST)
├── mocks/                # BFF simulado con MSW, con estado en memoria
├── sesion/               # Sesión del cliente: proveedor, useSesion y rutas protegidas (WEB-F13)
├── componentes/          # Componentes reutilizables (Plantilla: encabezado, navegación, idioma, cuenta)
├── paginas/              # Una por ruta
├── i18n/                 # es.json, en.json y configuración
├── estilos/              # tokens.css y global.css
└── formato.ts            # Montos y fechas según el idioma
```

## Trabajar contra el contrato

1. Si una pantalla necesita algo que el contrato no tiene, **primero se cambia el contrato**
   (`contratos/bff-web/openapi.yaml`) y se acuerda con quien construye el BFF.
2. `npm run contratos:tipos` regenera los tipos. El CI falla si quedan desactualizados.
3. Se ajusta el mock en `src/mocks/manejadores.ts` y se usa `bff.GET(...)` / `bff.POST(...)` en la
   pantalla. TypeScript avisa si la ruta, los parámetros o la respuesta no coinciden.

Los mocks reproducen el guion del Sprint 1: sin consentimiento la cotización es de tarifa estándar,
al otorgarlo pasa a personalizada y al revocarlo vuelve a estándar. El proveedor de Open Finance
responde según `estado.openFinance`, con los mismos modos que el núcleo: `sano`, `degradado` (perfil
en caché) o `caido` (tarifa estándar con motivo `proveedor_no_disponible`). Para reportar siniestros,
el cliente tiene una póliza vigente y una vencida hace 30 días (`polizasDePrueba`), con fechas
relativas a hoy como la semilla de *staging*. Contra el núcleo real, las pólizas se siembran con
`POLIZAS_SEMILLA` (ver [`backend/README.md`](../../backend/README.md)). La evidencia se adjunta en
el reporte o después, en **Evidencias** (`/siniestros/evidencias`); los mocks toman el formato del
tipo del archivo, mientras que el BFF real lo comprueba con su contenido.

## Sesión (WEB-F13)

Todas las pantallas, salvo el inicio de sesión (`/ingresar`) y la de página no encontrada, exigen sesión. Sin ella, la web lleva al inicio de sesión y, al entrar, vuelve a la página pedida. Si cualquier llamada al BFF responde 401 `sesion_no_vigente` (la sesión dura 60 minutos y no se renueva), la web pide iniciar sesión de nuevo. La web nunca ve el token: la cookie `sesion` es `HttpOnly` y la maneja el navegador.

Los mocks arrancan con la sesión abierta. Después de cerrarla se entra con la cuenta de prueba `ana@solventa.test` / `Solventa-2026` (`CREDENCIALES_PRUEBA` en `src/mocks/datos.ts`); cualquier otra combinación responde 401 `credenciales_invalidas`, igual exista o no el correo.

## Pruebas E2E (Playwright)

Las pruebas de [`e2e/`](e2e) recorren la web de punta a punta contra el BFF y el núcleo reales,
nunca contra los mocks. En CD corren después de cada despliegue a *staging* (job `e2e`, ver
[`docs/devops.md`](../../docs/devops.md)). En local, con el núcleo, el BFF y `VITE_MOCKS=false npm run dev`
corriendo:

```bash
npx playwright install chromium   # una vez
E2E_CORREO=<cuenta> E2E_CLAVE=<clave> npm run e2e
```

| Variable | Para qué |
|---|---|
| `E2E_URL_WEB` | Origen de la web. Por defecto `http://localhost:5173` |
| `E2E_CORREO`, `E2E_CLAVE` | Cuenta de prueba del ambiente: en local, la que crea el núcleo con `HUMO_CORREO` y `HUMO_CLAVE` |
| `E2E_OPEN_FINANCE` | `sano` (por defecto) o `caido`, el modo del núcleo (`PERFILAMIENTO_OPEN_FINANCE_MODO`). Cada flujo de cotización corre solo en el modo que le corresponde |

Las pruebas comparten la cuenta de prueba, así que corren una a la vez y cada una deja el estado que
necesita (por ejemplo, el consentimiento) sin depender del orden.
