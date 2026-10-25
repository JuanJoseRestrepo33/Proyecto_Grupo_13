# Registros de decisiones de arquitectura (ADR)

Cada ADR registra una decisión de arquitectura: el contexto, lo que se decidió, las alternativas
descartadas y las consecuencias. Una ADR aceptada no se edita para cambiar la decisión; si la
decisión cambia, se escribe una ADR nueva que la reemplaza y se marca la anterior como
*reemplazada*.

| # | Decisión | Estado | Historia |
|---|---|---|---|
| [0001](0001-bff-unico-dos-routers.md) | Un solo servicio BFF con dos routers y dos contratos | Aceptada | WEB-F01 |

## Candidatas

Decisiones ya tomadas en el código o en la infraestructura que conviene registrar:

- Base de datos compartida por instancia, con un esquema y un usuario por módulo ([`docs/devops.md`](../devops.md)).
- nginx en Cloud Run como proxy de la web hacia el BFF, en el mismo origen ([`docs/devops.md`](../devops.md)).
- Firma asimétrica de los tokens de sesión: el núcleo firma y el BFF solo valida (WEB-F13, #155).

## Cómo escribir una

1. Copia la estructura de la última ADR y numérala con el siguiente número (`0002-...`).
2. Llena contexto, decisión, alternativas y consecuencias. Cada alternativa explica por qué no.
3. Agrega la fila a la tabla de arriba y ábrela en un PR `docs/HISTORIA-adr-...`.
