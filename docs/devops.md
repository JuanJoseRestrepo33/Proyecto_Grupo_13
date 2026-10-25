# Ambiente de trabajo DevOps

> **Historia:** ARQ-04 · Ambiente de trabajo DevOps · Sprint 1
> **Alcance de este documento:** cómo trabaja el equipo en el repositorio (ramas, commits, pull
> requests, versiones), qué valida el pipeline y cómo se despliega. No describe código de negocio.

## Tabla de contenido

- [Resumen: criterio → evidencia](#resumen-criterio--evidencia)
- [Flujo de trabajo](#flujo-de-trabajo)
- [Estrategia de versionamiento](#estrategia-de-versionamiento)
- [Convención de commits](#convención-de-commits)
- [Pull requests y peer review](#pull-requests-y-peer-review)
- [Integración continua](#integración-continua)
- [Despliegue continuo](#despliegue-continuo)
- [Ejecución de pruebas](#ejecución-de-pruebas)
- [Revisión automática de código](#revisión-automática-de-código)
- [Sprint Backlog y asignación por integrante](#sprint-backlog-y-asignación-por-integrante)
- [Configuración manual en GitHub](#configuración-manual-en-github)
- [Configuración manual en GCP](#configuración-manual-en-gcp)

---

## Resumen: criterio → evidencia

| Criterio | Dónde se evidencia |
|---|---|
| Parametrización de CI/CD | [`.github/workflows/ci.yml`](../.github/workflows/ci.yml), [`cd.yml`](../.github/workflows/cd.yml), [`release.yml`](../.github/workflows/release.yml) · pestaña **Actions** |
| Sprint Backlog y asignación | [Sección de este documento](#sprint-backlog-y-asignación-por-integrante) · **GitHub Project** (vista *Sprint Backlog*) |
| Commits / pull requests | Hook [`.pre-commit-config.yaml`](../.pre-commit-config.yaml) + [`scripts/validar_commit.py`](../scripts/validar_commit.py) · plantilla [`pull_request_template.md`](../.github/pull_request_template.md) · historial de PR |
| Flujo de trabajo y versionamiento | GitFlow: [flujo de trabajo](#flujo-de-trabajo) y [versionamiento](#estrategia-de-versionamiento) · validador [`scripts/validar_rama.py`](../scripts/validar_rama.py) · rulesets de `main`, `develop` y `release/*` · tags `vX.Y.Z` y **Releases** automáticos |
| Ejecución de pruebas | Jobs `herramientas` y `backend` del CI · artefactos de reporte y resumen de cobertura en cada ejecución |
| Peer review (opcional) | Aprobación obligatoria de otro integrante en cada PR |
| Revisión automática (opcional) | [`codeql.yml`](../.github/workflows/codeql.yml) · [`dependabot.yml`](../.github/dependabot.yml) · pestaña **Security** |

---

## Flujo de trabajo

El equipo trabaja con **GitFlow**: dos ramas de larga vida y ramas de vida corta para cada tipo de
trabajo. Ningún cambio entra a `main` ni a `develop` sin pull request.

| Rama | Vida | Sale de | Entra a | Propósito |
|---|---|---|---|---|
| `main` | Permanente | - | - | Código en producción. Solo recibe releases y hotfixes. Cada merge lleva un tag `vX.Y.Z` |
| `develop` | Permanente | `main` (una vez) | - | Integración del sprint en curso. Se despliega en *staging* en cada merge. **Rama por defecto** del repositorio |
| `feature/…` (y `fix/`, `docs/`, `ci/`, `test/`, `refactor/`, `chore/`) | Corta | `develop` | `develop` | Una historia o parte de una historia |
| `release/vX.Y.0` | Corta | `develop` | `main` | Estabilización del cierre de sprint. Solo admite correcciones |
| `hotfix/vX.Y.Z` | Corta | `main` | `main` | Corrección urgente sobre producción |

```
main      ──●───────────────────────────────●───────────────●──────▶
            │ v0.0.0                        │ v0.1.0        │ v0.1.1
            │                              ╱ ╲             ╱ ╲
release     │               ●────●────●───╱   ╲   hotfix  ╱   ╲
            │              ╱ release/v0.1.0  ╲    ●──────╱     ╲
            │             ╱                   ╲  ╱              ╲
develop   ──●──●────●────●─────────────────────●────────────────●──▶
               ╲   ╱ ╲  ╱                   back-merge       back-merge
feature         ●─●   ●●
          feature/ARQ-04-…  feature/WEB-F02-…
```

### Día a día: una historia

1. Toma una historia del tablero y muévela a **In progress**.
2. Crea la rama desde `develop` actualizado:

   ```bash
   git switch develop && git pull
   git switch -c feature/WEB-F02-consentimiento-web
   ```

3. Haz commits pequeños con la [convención](#convención-de-commits).
4. Abre el PR **hacia `develop`** temprano, en borrador si hace falta. Al pedir revisión, mueve la
   historia a **In review**.
5. Con el CI en verde y una aprobación, se hace **squash merge**. El título del PR queda como el
   commit en `develop`, la rama se borra y el cambio se despliega en *staging*.

### Cierre de sprint: release

1. El jueves de la semana 2, con las historias del sprint integradas en `develop`, se crea la rama de
   release:

   ```bash
   git switch develop && git pull
   git switch -c release/v0.1.0
   git push -u origin release/v0.1.0
   ```

2. Durante la estabilización solo entran correcciones: ramas `fix/HISTORIA-…` con PR **hacia
   `release/v0.1.0`**. `develop` sigue abierta para el trabajo del sprint siguiente.
3. Se abre el PR `release/v0.1.0` → `main` con el título `chore(release): v0.1.0` y se fusiona con
   **merge commit**, no con squash, para que `main` y `develop` compartan la historia.
4. Al fusionarlo, [`release.yml`](../.github/workflows/release.yml) hace solo lo siguiente:
   - crea el tag `v0.1.0` y el release de GitHub con notas generadas desde los PR;
   - publica las imágenes (núcleo, BFF y web) etiquetadas con la versión;
   - abre el PR de **back-merge** `main` → `develop`.
5. Se aprueba y se fusiona el back-merge, también con **merge commit**. Así `develop` recibe las
   correcciones hechas en la release.

### Urgencias en producción: hotfix

1. Se crea `hotfix/v0.1.1` desde `main`, se corrige y se abre el PR hacia `main` con el título
   `chore(release): v0.1.1`.
2. Al fusionarlo, el flujo es el mismo de la release: tag, release y back-merge a `develop`.

### Nombres de rama

El CI valida el nombre de la rama origen y que el destino sea el permitido. Un PR de una `feature/`
directo a `main`, por ejemplo, se rechaza. Lo hace el script
[`scripts/validar_rama.py`](../scripts/validar_rama.py).

| Patrón | Ejemplo | Destino permitido |
|---|---|---|
| `feature/HISTORIA-descripcion` | `feature/MOB-F01-onboarding-biometrico` | `develop` |
| `fix/HISTORIA-descripcion` | `fix/WEB-F02-revocacion-duplicada` | `develop` o `release/vX.Y.0` |
| `docs/`, `ci/`, `test/`, `refactor/`, `chore/` + `HISTORIA-descripcion` | `ci/ARQ-04-despliegue` | `develop` (`docs/` también a `release/`) |
| `release/vX.Y.0` | `release/v0.1.0` | `main` |
| `hotfix/vX.Y.Z` (Z ≥ 1) | `hotfix/v0.1.1` | `main` |
| `main` | back-merge | `develop` |

`HISTORIA` es el ID del tablero (`ARQ-04`, `WEB-F02`) o el número del issue (`12`). La descripción va
en minúscula y con guiones.

**Ramas de vida corta:** una `feature/` no debería vivir más de 2 días hábiles. Si una historia es más
grande, se parte en PR incrementales hacia `develop`.

---

## Estrategia de versionamiento

**Versionamiento semántico** (`MAYOR.MENOR.PARCHE`). La versión sale del nombre de la rama de release
o de hotfix, y el tag se crea automáticamente en `main` al fusionarla. Nadie crea tags a mano.

| Cambio | Rama | Incrementa | Ejemplo |
|---|---|---|---|
| Cierre de sprint | `release/vX.Y.0` | MENOR | `v0.1.0` → `v0.2.0` |
| Corrección urgente en producción | `hotfix/vX.Y.Z` | PARCHE | `v0.2.0` → `v0.2.1` |
| Primera versión entregable completa | `release/v1.0.0` | MAYOR | `v1.0.0` al final del Proyecto Final II |

| Sprint | Release prevista |
|---|---|
| Sprint 1 | `v0.1.0` |
| Sprint 2 | `v0.2.0` |
| Sprint 3 | `v0.3.0` |
| Sprint 4 (entrega final) | `v1.0.0` |

| Ambiente | Rama | Imágenes (`solventa-nucleo`, `solventa-bff`, `solventa-web`) |
|---|---|---|
| *staging* | `develop` | `:develop` y `:<sha>` |
| producción | `main` | `:main`, `:<sha>` y `:X.Y.Z` |

La API pública se versiona aparte, en la ruta (`/v1`, `/v2`). Esto lo define ARQ-01.

---

## Convención de commits

[Conventional Commits](https://www.conventionalcommits.org/es/) más una **referencia obligatoria a
una historia del tablero**:

```
tipo(alcance): descripción en minúscula (HISTORIA)
```

| Parte | Valores |
|---|---|
| `tipo` | `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert` |
| `alcance` | Opcional, en minúscula: `backend`, `web`, `mobile`, `ci`, `consentimiento`… |
| `HISTORIA` | ID del tablero (`ARQ-04`, `WEB-F02`, `MOB-F01`) o número de issue (`#12`). Puede ir en la cabecera o en el cuerpo (`Refs: ARQ-04`, `Closes #12`). |

Ejemplos válidos:

```
feat(consentimiento): revocar consentimiento desde la web (WEB-F02)
ci: publicar cobertura en el resumen del pipeline (ARQ-04)
fix(mobile)!: cambiar contrato del onboarding

Refs: MOB-F01
```

Los commits de merge (`Merge …`), `Revert "…"`, `fixup!` y `squash!` están exentos. Los PR de
release, hotfix y back-merge usan `chore(release): vX.Y.Z …` y no exigen una historia, porque
agrupan varias.

### Dónde se valida

| Momento | Mecanismo | Efecto |
|---|---|---|
| Al hacer commit | Hook `commit-msg` de [pre-commit](https://pre-commit.com) | Rechaza el commit y muestra el formato esperado |
| Al abrir o actualizar el PR | Job `convenciones` del CI | Valida el **flujo GitFlow** (rama origen → destino), el **título del PR** y **cada commit** del PR. Bloquea el merge si falla |

El hook local es una ayuda; el CI es el que garantiza la regla, porque un hook se puede saltar con
`--no-verify`.

**Instalación del hook (una vez por clon):**

```bash
pip install pre-commit
pre-commit install --install-hooks
```

Además del mensaje, el hook revisa YAML/JSON, marcadores de conflicto, archivos de más de 1 MB, y
formato y lint de Python con ruff.

---

## Pull requests y peer review

- Todo PR usa la [plantilla](../.github/pull_request_template.md): historia que cierra
  (`Closes #N`), qué cambia, cómo probarlo y la checklist de la definición de hecho.
- **Título** con la convención de commits. Es el mensaje que queda en `develop` tras el squash merge.
- **Método de merge:** *squash* para las ramas de trabajo hacia `develop` o `release/*`; *merge
  commit* para release y hotfix hacia `main` y para el back-merge `main` → `develop`.
- **Mínimo una aprobación** de un integrante distinto del autor. Si se agregan commits después de
  aprobar, la aprobación se descarta y hay que pedirla de nuevo.
- Todas las conversaciones de revisión deben quedar resueltas antes del merge.
- El revisor verifica los criterios de aceptación de la historia, no solo el código. Esto es parte de
  la definición de hecho.

**Qué mira el revisor:**

1. ¿Cumple los criterios de aceptación de la historia?
2. ¿Las pruebas cubren los casos de los criterios, incluidos los de error?
3. ¿Respeta las fronteras de módulo y los adaptadores (ARQ-01, ARQ-02)?
4. ¿Hay secretos, datos personales o credenciales en el diff?
5. ¿Es legible sin que el autor lo explique?

---

## Integración continua

Workflow [`ci.yml`](../.github/workflows/ci.yml). Corre en cada PR hacia `develop`, `main` o `release/*`
y en cada push a `develop` o `main`.

| Job | Cuándo | Qué valida |
|---|---|---|
| `convenciones` | Solo en PR | Flujo GitFlow (nombre de la rama y destino permitido), título del PR y todos sus commits contra la convención |
| `backend` | Cuando existe `backend/pyproject.toml` | Lint y formato (ruff), frontera entre módulos (import-linter), pruebas unitarias con cobertura y **cobertura del código nuevo ≥ 70%** (diff-cover) |
| `web` | Cuando existe `apps/web/package.json` | Contrato del BFF válido (Redocly), tipos del contrato al día, lint con reglas de accesibilidad (ESLint + jsx-a11y), tipos (TypeScript), pruebas con cobertura ≥ 70%, **cobertura del código nuevo ≥ 70%** (diff-cover) y build de producción sin los mocks del BFF |
| `herramientas` | Siempre | Lint (ruff) y pruebas de las herramientas del repositorio (`scripts/`), entre ellas la prueba de humo `humo.py` |
| `imagen` | Por cada componente con `Dockerfile` (`backend/`, `bff/`, `apps/web/`) | Que su imagen Docker construya. La de la web, además, arranca y debe servir el SPA y reenviar `/api/` |
| `ci-ok` | Siempre | Agrega los anteriores. **Es el único check requerido** en la protección de ramas |

Los jobs de backend se activan solos cuando ARQ-01 cree `backend/`. Para que funcionen sin tocar el
pipeline, el backend debe cumplir este contrato:

- `backend/pyproject.toml` con el extra `dev`, que incluya `pytest`, `pytest-cov`, `ruff`,
  `import-linter` y `diff-cover`.
- El paquete de la aplicación se llama `app` (la cobertura se mide con `--cov=app`).
- Los contratos de import-linter se declaran en `[tool.importlinter]` del mismo `pyproject.toml`.
- `backend/Dockerfile` que construya la imagen del núcleo.

El job `web` sigue el mismo patrón para [`apps/web`](../apps/web) (scripts `contratos:validar`,
`contratos:tipos`, `lint`, `typecheck`, `test:cobertura` y `build` en su `package.json`). Si el cliente
móvil se implementa con su propio stack, se agrega un job análogo con su propio detector (por ejemplo
`apps/mobile/package.json`).

**Resultados visibles en cada ejecución:**

- Anotaciones de lint directamente en las líneas del PR.
- Resumen de cobertura (total y del código nuevo) en la página de la ejecución.
- Artefactos descargables: `cobertura-backend` y `cobertura-web` (cobertura, reporte JUnit y reporte
  de código nuevo) y `pruebas-herramientas`.

---

## Despliegue continuo

Workflow [`cd.yml`](../.github/workflows/cd.yml). Corre en cada push a `develop` o `main`, es decir, en
cada merge. También se puede lanzar a mano desde **Actions → CD → Run workflow**.

```
merge a develop ──▶ CI ──▶ imágenes :develop ──▶ Artifact Registry ──▶ Cloud Run (staging)    ──▶ prueba de humo
merge a main    ──▶ CI ──▶ imágenes :main    ──▶ Artifact Registry ──▶ Cloud Run (produccion) ──▶ prueba de humo
                     └──▶ release.yml: tag vX.Y.Z, release, imágenes :X.Y.Z, PR de back-merge
```

Se despliegan tres servicios de Cloud Run, uno por componente. Cada uno se llama igual que su imagen:

| Servicio | Componente | Qué es | Conoce a |
|---|---|---|---|
| `solventa-nucleo` | `backend/` | Monolito modular, API `/v1` | Cloud SQL y Secret Manager |
| `solventa-bff` | `bff/` | BFF único con los routers `/api/v1/web/` y `/api/v1/movil/` (WEB-F01) | El núcleo, por `NUCLEO_URL` |
| `solventa-web` | `apps/web/` | nginx que sirve la web estática y reenvía `/api/` al BFF | El BFF, por `BFF_URL` |

```
navegador ──▶ solventa-web ──/api/──▶ solventa-bff ──▶ solventa-nucleo ──▶ Cloud SQL
app móvil ───────────────/api/v1/movil/──▶ ┘
```

La web y el BFF comparten origen gracias al proxy de nginx: el contrato `bff-web` declara rutas
relativas (`/api/v1/web`) y el cliente envía `credentials: 'include'`, así que la cookie de sesión
funciona sin CORS ni cookies de terceros. La app móvil llama directo al BFF.

| Etapa | Qué hace | Requisito para activarse |
|---|---|---|
| **Publicar imágenes** | Por cada componente, construye `<servicio>:<sha>` y `:<rama>` y las publica en el registro de paquetes de GitHub | Que exista el `Dockerfile` del componente. No requiere secretos |
| **Copiar a Artifact Registry** | Copia el manifiesto y las capas de la imagen `:<sha>` al registro del proyecto de Google Cloud, sin reconstruirla: lo que se despliega es exactamente lo que el CI probó | Que el environment (`staging` o `produccion`) tenga las variables de la tabla de abajo **y que el servicio ya exista** en Cloud Run (primer despliegue manual) |
| **Desplegar en Cloud Run** | Publica una revisión nueva del servicio con esa imagen. Las variables de entorno y los secretos ya montados en el servicio se conservan | Igual que la anterior |
| **Prueba de humo** | [`scripts/humo.py`](../scripts/humo.py) pide las rutas del servicio a la URL que devuelve Cloud Run y comprueba el estado HTTP, sin imprimir respuestas | Igual que la anterior |

Los servicios se despliegan **uno a la vez, en orden núcleo → BFF → web**: si uno falla, los
siguientes no se despliegan. La web solo se despliega cuando existe `bff/`, porque sin BFF no tiene a
quién reenviar `/api/`.

Si un servicio no existe todavía en Cloud Run, el pipeline no lo crea: deja un aviso y sigue. Creado
por el pipeline, quedaría con la cuenta de servicio por defecto y sin sus variables ni secretos.

**Prueba de humo.** Cada parte Cloud de una historia agrega sus filas a la tabla `RUTAS` de
`scripts/humo.py` (servicio, método, ruta y estados aceptados); el pipeline no cambia. En las rutas
protegidas, un 401 cuenta como éxito: prueba que la ruta existe y exige sesión. Un 5xx, un 404 o la
falta de respuesta hacen fallar el despliegue. Se reintenta durante un minuto mientras la revisión
arranca.

### Principio de seguridad: el repositorio no guarda credenciales

El pipeline nombra la plataforma (Google Cloud: Artifact Registry y Cloud Run), pero **no guarda
ninguna credencial de infraestructura**:

- La autenticación ante Google Cloud es por **Workload Identity Federation**: GitHub emite un token
  OIDC de corta vida para cada ejecución y Google Cloud lo cambia por un token de la cuenta de servicio
  de despliegue. No existe ninguna llave JSON de cuenta de servicio, ni en GitHub ni en el código.
- Las cadenas de conexión, contraseñas y llaves de las bases de datos y servicios viven en **Secret
  Manager** y se montan como variables de entorno sobre el servicio de Cloud Run. El pipeline no las
  ve: despliega la imagen y la plataforma conserva la configuración del servicio entre revisiones.
- En GitHub solo existen **variables** (no secretos) por environment, con los mismos nombres en
  `staging` y en `produccion`. Son identificadores, no credenciales: saber el nombre del proyecto o de
  la cuenta de servicio no da acceso a nada sin el token OIDC de este repositorio.
- La prueba de humo solo comprueba que el servicio responde. No imprime respuestas, porque podrían
  revelar detalles internos.

| Variable (en cada environment) | Ejemplo | Descripción |
|---|---|---|
| `GCP_PROJECT_ID` | `solventa-staging` | Proyecto de Google Cloud del ambiente |
| `GCP_REGION` | `us-east1` | Región de Artifact Registry y de Cloud Run |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/1234/locations/global/workloadIdentityPools/github/providers/solventa` | Proveedor de identidad que confía en este repositorio |
| `GCP_SERVICE_ACCOUNT` | `despliegue@solventa-staging.iam.gserviceaccount.com` | Cuenta de servicio que el pipeline suplanta para desplegar |
| `GCP_ARTIFACT_REPOSITORY` | `solventa` | Repositorio Docker de Artifact Registry |

Si falta alguna, el job de despliegue termina sin error, deja un aviso con las que faltan y no toca
la plataforma. Los tres servicios se exponen sin autenticación de Cloud Run: la web y el BFF porque
los usan el navegador y la app, y el núcleo porque no autentica clientes (eso es responsabilidad del
BFF). Cuando exista un API Gateway (Sprint 4), el núcleo pasa a aceptar solo tráfico interno y su
prueba de humo se hace con un token de identidad.

### Nombres acordados entre componentes

Los fija la infraestructura y los leen los componentes. Cambiar uno exige cambiarlo en los dos
lados.

| Qué | Nombre | Lo lee | Historia |
|---|---|---|---|
| URL del núcleo | `NUCLEO_URL` | `solventa-bff` | WEB-F01 |
| URL del BFF | `BFF_URL` (origen, sin barra final) | `solventa-web` (nginx) | WEB-F01 |
| Conexión del log de auditoría | `AUDITORIA_DATABASE_URL` ← secreto `auditoria-database-url` | `solventa-nucleo` | ARQ-03 |
| Conexión de cotización | `COTIZACION_DATABASE_URL` ← secreto `cotizacion-database-url` | `solventa-nucleo` | WEB-F01 |

Cada módulo con persistencia tiene **su esquema y su usuario de base de datos** en la misma instancia
y la misma base (`solventa-auditoria` / `solventa`), con permisos solo sobre su esquema: un módulo no
puede leer los datos de otro, igual que en el código (ARQ-01).

### Qué espera el núcleo de la plataforma

- **Puerto.** Cloud Run inyecta `PORT`; el contenedor escucha ahí (sin ella, en 8000).
- **Sondas.** `GET /salud` responde 503 cuando una dependencia está caída; sirve como sonda de
  arranque y de vida.
- **Apagado.** Cloud Run envía `SIGTERM` y da diez segundos. El núcleo aprovecha ese lapso para
  escribir lo que quedó en la cola de auditoría.
- **Secretos.** `AUDITORIA_DATABASE_URL` y `COTIZACION_DATABASE_URL` montadas desde Secret Manager,
  apuntando a Cloud SQL. Sin ellas el log de auditoría y las cotizaciones viven en memoria y se
  pierden con cada revisión: válido para una demo, no para *staging*.
- **Instancias.** Consentimiento y perfilamiento guardan su estado en memoria hasta que tengan
  persistencia propia. Mientras tanto el núcleo corre con **una sola instancia**
  (`--min-instances=1 --max-instances=1`); con más, un consentimiento otorgado en una instancia no
  existe en la otra. El BFF y la web no guardan estado y pueden escalar.

### Qué esperan el BFF y la web de la plataforma

- **BFF.** Escucha en `PORT` (sin ella, 8000), responde `GET /salud` y lee `NUCLEO_URL`.
- **Web.** nginx sin privilegios; escucha en `PORT` (sin ella, 8080). Requiere `BFF_URL` al arrancar:
  sin ella nginx no inicia y Cloud Run no promueve la revisión. Las rutas del SPA que no son archivos
  devuelven `index.html`; `/assets/` se cachea como inmutable. Acepta cuerpos de hasta 12 MB en `/api/`
  (evidencias de 10 MB, MOB-F05).

---

## Ejecución de pruebas

| Nivel | Herramienta | Dónde corre | Umbral |
|---|---|---|---|
| Unitarias del backend | pytest + pytest-cov | Job `backend` | Cobertura del **código nuevo ≥ 70%** (bloquea el merge) |
| Frontera entre módulos | import-linter | Job `backend` | Cero importaciones prohibidas |
| Unitarias y de componentes web | Vitest + Testing Library, contra los mocks del BFF (MSW) | Job `web` | Cobertura total y del **código nuevo ≥ 70%** |
| Accesibilidad web | axe-core en las pruebas + ESLint jsx-a11y | Job `web` | Cero violaciones |
| Contrato del BFF web | Redocly + verificación de tipos generados | Job `web` | Contrato válido y tipos al día |
| Herramientas del repo | pytest | Job `herramientas` | Todas en verde |
| Integración con adaptadores simulados | pytest | Job `backend` (cuando existan, ARQ-02) | Todas en verde |
| Humo en *staging* y producción | Petición a `/v1/` sin imprimir la respuesta | Workflow CD | Respuesta HTTP exitosa |

Para correrlas en local:

```bash
cd scripts && pip install pytest pytest-cov ruff && pytest -q     # herramientas del repo
cd apps/web && npm install && npm run test:cobertura              # app web
```

---

## Revisión automática de código

| Herramienta | Qué hace | Dónde se ve |
|---|---|---|
| **CodeQL** ([`codeql.yml`](../.github/workflows/codeql.yml)) | Análisis estático de seguridad y calidad (`security-and-quality`) para Python, JavaScript/TypeScript y los workflows de GitHub Actions. Corre en cada PR, en cada push a `develop` o `main` y cada lunes. Excluye los archivos generados ([`codeql-config.yml`](../.github/codeql/codeql-config.yml)) | Security → Code scanning, y anotaciones en el PR |
| **Dependabot** ([`dependabot.yml`](../.github/dependabot.yml)) | PR semanales hacia `develop` que actualizan las acciones de GitHub y las dependencias npm de `apps/web` (menores y parches agrupados en un solo PR). Al crear `backend/` se agregan pip y docker | Pestaña Pull requests |
| **ruff** | Lint y formato de Python, en el hook local y en el CI | Anotaciones en el PR |
| **ESLint** | Lint de TypeScript/React con reglas de accesibilidad (jsx-a11y estricto), en el CI | Anotaciones en el PR |

---

## Sprint Backlog y asignación por integrante

Las historias y su detalle están en [`HistoriasUsuarioSprint1.md`](HistoriasUsuarioSprint1.md). En el
tablero, cada historia es un issue creado con la plantilla
[`feature.yml`](../.github/ISSUE_TEMPLATE/feature.yml), asignado a su responsable y con sus campos
completos.

### Sprint 1 (32 puntos, 64 horas)

| ID | Historia | Puntos | Horas | Responsable | Depende de |
|---|---|---:|---:|---|---|
| ARQ-04 | Ambiente de trabajo DevOps | 5 | 8h | Juan | - |
| ARQ-01 | Esqueleto del monolito modular con fachada versionada | 2 | 4h | Sergio | ARQ-04 |
| ARQ-02 | Capa de adaptadores para integraciones externas | 2 | 4h | Juan | ARQ-01 |
| ARQ-03 | Log de auditoría append-only | 2 | 4h | Sergio | ARQ-01 |
| WEB-F02 | Gestión del consentimiento Open Finance | 8 | 12h | Sergio + Harold | ARQ-01, ARQ-02, ARQ-03 |
| MOB-F01 | Onboarding y autenticación biométrica | 13 | 18h | Edwin + Juan | ARQ-01, ARQ-02 |
| WEB-F01 | Cotización *(rebanada: UI del cotizador)* | - | 8h | Harold | - |
| WEB-F03 | Suscripción y emisión *(rebanada: UI de checkout)* | - | 4h | Harold | - |
| MOB-F02 | Consentimiento móvil *(rebanada: pantalla)* | - | 2h | Edwin | - |

### Carga por integrante

| Integrante | Rol en el sprint | Tareas | Horas |
|---|---|---|---:|
| Sergio | Backend | ARQ-01 4h · ARQ-03 4h · WEB-F02 servicio de consentimiento 8h | 16h |
| Juan | DevOps + Backend | ARQ-04 8h · ARQ-02 4h · MOB-F01 hook de KYC/AML 4h | 16h |
| Harold | Frontend web | WEB-F02 UI de consentimiento 4h · WEB-F01 UI del cotizador 8h · WEB-F03 UI de checkout 4h | 16h |
| Edwin | Full stack móvil | MOB-F01 onboarding biométrico 14h · MOB-F02 consentimiento móvil 2h | 16h |
| Erick | Gerente del proyecto | Facilita ceremonias, mantiene el tablero y verifica la definición de hecho | - |

Una historia compartida (p. ej. WEB-F02) se registra como un issue con **ambos** responsables
asignados y una sub-issue por persona, para que el burndown refleje el avance de cada parte.

### Tablero (GitHub Project)

**Columnas (campo *Status*):** `Product Backlog` → `Sprint Backlog` → `In progress` → `In review` → `Done`.

**Campos personalizados**, que son los datos de los que salen los charts del sprint:

| Campo | Tipo | Uso |
|---|---|---|
| `Sprint` | Iteration (2 semanas) | A qué sprint pertenece la historia |
| `Puntos` | Number | Estimación en planning poker. Alimenta el **burndown** y el **velocity** |
| `Horas` | Number | Horas estimadas |
| `Valor` | Number | Valor de negocio acordado con el gerente. Alimenta el **value chart** |
| `Tipo` | Single select | `Arquitectura` / `Funcional` |
| `Componente` | Single select | `Backend` / `Web` / `Móvil` / `Transversal` |

**Vistas sugeridas:** *Sprint Backlog* (tablero filtrado por la iteración actual), *Por integrante*
(tabla agrupada por Assignees) y *Insights* (gráficos de burnup y de puntos por sprint, desde
Project → Insights).

**Automatizaciones del Project** (Project → Workflows): *Item added → Product Backlog*,
*Pull request linked → In progress*, *Item closed → Done*, *Pull request merged → Done*.

---

## Configuración manual en GitHub

Estos ajustes no se pueden versionar en archivos. Los hace un administrador del repositorio, una sola
vez.

### 1. Rama por defecto

Crear `develop` desde `main` (una sola vez) y dejarla como rama por defecto, para que los PR apunten a
ella sin tener que elegirla:

```bash
git switch main && git pull
git switch -c develop && git push -u origin develop
```

Settings → General → Default branch → **`develop`**.

### 2. Protección de ramas (Settings → Rules → Rulesets → New branch ruleset)

Las tres reglas comparten esta base:

- **Enforcement:** Active
- ☑ Restrict deletions · ☑ Block force pushes
- ☑ Require a pull request before merging
  - Required approvals: **1**
  - ☑ Dismiss stale pull request approvals when new commits are pushed
  - ☑ Require conversation resolution before merging
- ☑ Require status checks to pass → agregar **`ci-ok`**

| Ruleset | Target | Allowed merge methods | Además |
|---|---|---|---|
| `proteger-main` | Include by pattern: `main` | **Merge** únicamente | ☑ Require branches to be up to date · ☑ Require code scanning results (CodeQL, recomendado) |
| `proteger-develop` | Include by pattern: `develop` | **Squash** y **Merge** | Squash para las ramas de trabajo; merge commit para el back-merge |
| `proteger-release` | Include by pattern: `release/**` | **Squash** | Solo entran correcciones |

Con esto, un `git push` directo a `main`, `develop` o una `release/*` es rechazado por GitHub.

### 3. Opciones generales (Settings → General → Pull Requests)

- ☑ Allow merge commits (default message: *Pull request title*)
- ☑ Allow squash merging (default message: *Pull request title*)
- ☐ Allow rebase merging
- ☑ Automatically delete head branches

En Settings → Actions → General: ☑ *Allow GitHub Actions to create and approve pull requests*, para
que `release.yml` pueda abrir el back-merge. Se recomienda además crear el secreto de repositorio
`RELEASE_TOKEN`: un token *fine-grained* de un integrante, limitado a este repositorio, con permiso de
*Pull requests* y *Contents*. Sin él, GitHub no dispara el CI sobre el PR de back-merge y hay que
cerrarlo y reabrirlo a mano para que corra.

### 4. Seguridad (Settings → Code security)

- ☑ Dependabot alerts · ☑ Dependabot security updates
- Code scanning: lo maneja el workflow `codeql.yml`. No se debe activar además el *default setup*,
  porque entran en conflicto.

### 5. Packages y environments

- Tras la primera publicación, los paquetes `solventa-nucleo`, `solventa-bff` y `solventa-web`
  (perfil → Packages) se dejan **privados** y se enlazan al repositorio para heredar sus permisos.
- Settings → Environments → **New environment**, uno por ambiente:

  | Environment | Deployment branches | Recomendado |
  |---|---|---|
  | `staging` | Selected branches → solo `develop` | - |
  | `produccion` | Selected branches → solo `main` | *Required reviewers*: el gerente del proyecto aprueba cada despliegue |

  En cada uno se cargan, como **variables** del environment, las cinco de la tabla de
  [despliegue continuo](#despliegue-continuo).
- No se define ningún secreto ni variable de despliegue a nivel de repositorio. Así, cada ambiente
  solo es accesible desde su rama.

---

## Configuración manual en GCP

Una vez por ambiente, con `gcloud` autenticado como administrador del proyecto. Los valores entre
`<>` son los que después se cargan como variables del environment en GitHub.

### 1. APIs, registro y base de datos

```bash
gcloud config set project <GCP_PROJECT_ID>
gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  iamcredentials.googleapis.com sqladmin.googleapis.com secretmanager.googleapis.com

gcloud artifacts repositories create <GCP_ARTIFACT_REPOSITORY> \
  --repository-format=docker --location=<GCP_REGION>

gcloud sql instances create solventa-auditoria --database-version=POSTGRES_15 \
  --tier=db-f1-micro --region=<GCP_REGION>
gcloud sql databases create solventa --instance=solventa-auditoria
gcloud sql users create auditoria --instance=solventa-auditoria --password='<contraseña>'
```

El esquema, el rol `solventa_app` y los triggers los crea el núcleo al arrancar, así que el usuario de
la cadena de conexión necesita permiso para crear esquemas y roles la primera vez.

### 2. Esquemas y secretos de las cadenas de conexión

Cloud Run se conecta a Cloud SQL por un socket Unix bajo `/cloudsql/`. Un secreto por módulo:

```bash
SOCKET="/cloudsql/<GCP_PROJECT_ID>:<GCP_REGION>:solventa-auditoria"
printf "postgresql://auditoria:<contraseña>@/solventa?host=$SOCKET" \
  | gcloud secrets create auditoria-database-url --data-file=-
printf "postgresql://cotizacion:<contraseña de cotizacion>@/solventa?host=$SOCKET" \
  | gcloud secrets create cotizacion-database-url --data-file=-
```

**Usuario de cotización (WEB-F01).** Se crea con SQL y no con `gcloud sql users create`, porque este
último lo agrega a `cloudsqlsuperuser`. Desde **Cloud SQL Studio** (consola de GCP → Cloud SQL →
`solventa-auditoria` → Cloud SQL Studio), conectado a la base `solventa` como `auditoria`:

```sql
CREATE ROLE cotizacion LOGIN PASSWORD '<contraseña de cotizacion>';
GRANT CONNECT ON DATABASE solventa TO cotizacion;
-- Solo para que el núcleo cree su esquema al arrancar (CREATE SCHEMA IF NOT EXISTS cotizacion).
GRANT CREATE ON DATABASE solventa TO cotizacion;
```

Comprobación: conectado como `cotizacion`, `SELECT 1 FROM auditoria.registros LIMIT 1;` debe
responder *permission denied*.

### 3. Cuenta de servicio y Workload Identity Federation

```bash
gcloud iam service-accounts create despliegue --display-name="Despliegue desde GitHub Actions"
CUENTA="despliegue@<GCP_PROJECT_ID>.iam.gserviceaccount.com"
for rol in roles/run.admin roles/artifactregistry.writer; do
  gcloud projects add-iam-policy-binding <GCP_PROJECT_ID> --member="serviceAccount:$CUENTA" --role="$rol"
done

gcloud iam workload-identity-pools create github --location=global
gcloud iam workload-identity-pools providers create-oidc solventa \
  --location=global --workload-identity-pool=github \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository == '<propietario>/<repositorio>'"

NUMERO=$(gcloud projects describe <GCP_PROJECT_ID> --format='value(projectNumber)')
gcloud iam service-accounts add-iam-policy-binding "$CUENTA" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$NUMERO/locations/global/workloadIdentityPools/github/attribute.repository/<propietario>/<repositorio>"
```

`GCP_WORKLOAD_IDENTITY_PROVIDER` es
`projects/$NUMERO/locations/global/workloadIdentityPools/github/providers/solventa` y
`GCP_SERVICE_ACCOUNT` es `$CUENTA`. La condición sobre `assertion.repository` hace que solo este
repositorio pueda suplantar la cuenta.

### 4. Cuentas de servicio de ejecución

Cada servicio corre con su propia cuenta y solo los permisos que usa, nunca con la cuenta por defecto
de Compute:

```bash
for cuenta in nucleo bff web; do
  gcloud iam service-accounts create "run-$cuenta" --display-name="Cloud Run · solventa-$cuenta"
  # La cuenta de despliegue puede publicar revisiones que corren con esta cuenta, y solo con esta.
  gcloud iam service-accounts add-iam-policy-binding "run-$cuenta@<GCP_PROJECT_ID>.iam.gserviceaccount.com" \
    --member="serviceAccount:$CUENTA" --role=roles/iam.serviceAccountUser
done
NUCLEO="run-nucleo@<GCP_PROJECT_ID>.iam.gserviceaccount.com"

gcloud projects add-iam-policy-binding <GCP_PROJECT_ID> \
  --member="serviceAccount:$NUCLEO" --role=roles/cloudsql.client
for secreto in auditoria-database-url cotizacion-database-url; do
  gcloud secrets add-iam-policy-binding "$secreto" \
    --member="serviceAccount:$NUCLEO" --role=roles/secretmanager.secretAccessor
done
```

El acceso a los secretos se da sobre cada secreto, no sobre el proyecto. `run-bff` y `run-web` no
reciben permisos en este sprint: solo hacen llamadas HTTP públicas.

`roles/iam.serviceAccountUser` se da sobre cada cuenta `run-*` y no sobre el proyecto: a nivel de
proyecto, el pipeline podría desplegar revisiones con cualquier cuenta, incluida la de Compute por
defecto, que tiene rol de editor.

### 5. Primer despliegue de cada servicio

El pipeline solo cambia la imagen y **no crea servicios**: la configuración de cada uno se fija una vez
aquí y se conserva entre revisiones. El primer despliegue usa la imagen pública de ejemplo de Cloud
Run; el siguiente merge a `develop` la reemplaza por la real, ya con la configuración en su lugar.

```bash
EJEMPLO=us-docker.pkg.dev/cloudrun/container/hello
```

En orden, porque cada uno necesita la URL del anterior:

```bash
gcloud run deploy solventa-nucleo --region=<GCP_REGION> --image="$EJEMPLO" \
  --service-account="$NUCLEO" \
  --add-cloudsql-instances=<GCP_PROJECT_ID>:<GCP_REGION>:solventa-auditoria \
  --set-secrets=AUDITORIA_DATABASE_URL=auditoria-database-url:latest,COTIZACION_DATABASE_URL=cotizacion-database-url:latest \
  --min-instances=1 --max-instances=1 --allow-unauthenticated
URL_NUCLEO=$(gcloud run services describe solventa-nucleo --region=<GCP_REGION> --format='value(status.url)')

gcloud run deploy solventa-bff --region=<GCP_REGION> --image="$EJEMPLO" \
  --service-account="run-bff@<GCP_PROJECT_ID>.iam.gserviceaccount.com" \
  --set-env-vars=NUCLEO_URL="$URL_NUCLEO" --allow-unauthenticated
URL_BFF=$(gcloud run services describe solventa-bff --region=<GCP_REGION> --format='value(status.url)')

gcloud run deploy solventa-web --region=<GCP_REGION> --image="$EJEMPLO" \
  --service-account="run-web@<GCP_PROJECT_ID>.iam.gserviceaccount.com" \
  --set-env-vars=BFF_URL="$URL_BFF" --allow-unauthenticated
```

Si el núcleo ya existía con la configuración del Sprint 1, basta con agregarle lo nuevo:
`gcloud run services update solventa-nucleo --region=<GCP_REGION> --update-secrets=COTIZACION_DATABASE_URL=cotizacion-database-url:latest`.

Desde entonces cada merge a `develop` publica una revisión nueva de los tres sin intervención manual.
