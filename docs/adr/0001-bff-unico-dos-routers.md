# ADR 0001 · Un solo servicio BFF con dos routers y dos contratos

- **Estado:** aceptada
- **Fecha:** 2026-10-25
- **Historia:** WEB-F01 · Cotización y oferta personalizada (#123), Sprint 2
- **Decisores:** equipo del Grupo 13 (Sergio, Harold, Edwin, Juan)

## Contexto

El modelo de componentes de Solventa define un *Backend for Frontend* por canal: uno para la web y
otro para la app móvil. Cada canal ya tiene su contrato, escrito antes que el BFF y usado por los
mocks de cada cliente:

- [`contratos/bff-web/openapi.yaml`](../../contratos/bff-web/openapi.yaml), bajo `/api/v1/web`.
- [`contratos/bff-movil/openapi.yaml`](../../contratos/bff-movil/openapi.yaml), bajo
  `/api/v1/movil` (la app ya lo espera en `build.gradle.kts`).

En el Sprint 2 el BFF tiene que existir de verdad: es la pieza que une las historias de punta a
punta (WEB-F01, WEB-F13, MOB-F02, WEB-F05, MOB-F05). Para el MVP:

- Hay **un solo responsable de integración** (Sergio) para los dos canales.
- Los dos canales consumen **casi las mismas operaciones** del núcleo: sesión, consentimiento,
  cotización, siniestros y evidencias.
- Cada servicio desplegado suma un pipeline, una sonda, una cuenta de servicio y un costo fijo en
  Cloud Run.

## Decisión

Se construye **un solo servicio BFF** en `bff/`, con dos routers, cada uno fiel a su contrato:

- `/api/v1/web/` cumple `contratos/bff-web`.
- `/api/v1/movil/` cumple `contratos/bff-movil`.

Se despliega como un servicio de Cloud Run, `solventa-bff` ([`docs/devops.md`](../devops.md),
"Despliegue continuo"). Lo usan dos clientes:

- **La web** lo llama a través del proxy de nginx de `solventa-web`, en el mismo origen.
- **La app** lo llama directo.

Vive en un directorio hermano de `backend/` y **no dentro de él**, para que no quede bajo las
reglas del monolito modular que verifica `test_esqueleto_modular.py`. El BFF orquesta el núcleo
por HTTP (`NUCLEO_URL`) y nunca importa su código.

Las diferencias entre un contrato de canal y la API `/v1` del núcleo se resuelven en el BFF, no en
los clientes.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| **Dos servicios BFF desde el inicio** (`bff-web` y `bff-movil`) | Duplica pipeline, despliegue, sondas y la mayor parte del código de orquestación, con una sola persona de integración y un sprint de dos semanas. No da beneficios hasta que los canales diverjan. |
| **Los clientes llaman directo a `/v1` del núcleo** | Saca del núcleo la responsabilidad de autenticar al cliente y de adaptar respuestas a cada canal, y ata los clientes a la API interna. Contradice el modelo de componentes y obliga a cambiar los dos clientes ante cualquier cambio de `/v1`. |
| **Un solo contrato compartido por los dos canales** | Los canales ya tienen necesidades distintas: cookie de sesión en la web y token con renovación biométrica en la app; textos y formatos por canal. Unificar los contratos rompería a los clientes existentes y haría más difícil separar el servicio después. |

## Consecuencias

**Positivas**

- Un despliegue, una sonda y un costo fijo menos en Cloud Run.
- La lógica común de orquestación (llamadas al núcleo, traducción de errores, sesión) se escribe una
  vez.
- **Los contratos siguen separados**: partir el servicio en dos más adelante no cambia ningún
  cliente, solo dónde se despliega cada router.

**Negativas y cómo se mitigan**

| Riesgo | Mitigación |
|---|---|
| Un router termina dependiendo de código del otro y el servicio ya no se puede partir | Sin imports entre los routers web y móvil; lo común vive en un módulo compartido. Se verifica con una prueba de frontera o `import-linter` en `bff/` |
| Un fallo o un pico de un canal afecta al otro | Cloud Run escala el servicio sin estado; si un canal necesita aislarse, se parte el servicio (los contratos ya lo permiten) |
| Un cambio en un contrato se cuela en el otro | Cada contrato se valida por separado con Redocly en su propio job del CI y tiene su propio `CHANGELOG.md` |

**Cuándo revisar esta decisión**

- Si los dos canales necesitan escalar, desplegarse o versionarse a ritmos distintos.
- Si un equipo distinto se hace cargo de uno de los canales.
- Cuando entre el API Gateway (Sprint 4), que puede asumir parte de lo que hoy hace el BFF
  (autenticación y cuotas).
