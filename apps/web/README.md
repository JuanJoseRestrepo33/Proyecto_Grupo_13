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
| `npm run contratos:validar` | Valida el contrato OpenAPI del BFF |
| `npm run contratos:tipos` | Regenera `src/api/esquema.ts` desde el contrato |

Variables de entorno: ver [`.env.example`](.env.example).

## Estructura

```
src/
├── api/
│   ├── esquema.ts        # Tipos GENERADOS desde el contrato. No se edita a mano
│   ├── tipos.ts          # Alias legibles de los esquemas
│   └── cliente.ts        # Cliente tipado del BFF (bff.GET / bff.POST)
├── mocks/                # BFF simulado con MSW, con estado en memoria
├── componentes/          # Componentes reutilizables (Plantilla: encabezado, navegación, idioma)
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
al otorgarlo pasa a personalizada y al revocarlo vuelve a estándar.
