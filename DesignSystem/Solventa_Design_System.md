# SOLVENTA: DESIGN SYSTEM
## Guía Visual, Componentes y Patrones de Interacción

**Proyecto:** MISW4501 Proyecto Final — Solventa  
**Propósito:** Coherencia visual, reutilización, accesibilidad (WCAG 2.1 AA)  
**Plataformas:** Web (desktop/tablet), Móvil (iOS/Android)  
**Versión:** 1.0

---

## TABLA DE CONTENIDOS

1. [Filosofía de Diseño](#1-filosofía-de-diseño)
2. [Paleta de Colores](#2-paleta-de-colores)
3. [Tipografía](#3-tipografía)
4. [Espaciado y Grid](#4-espaciado-y-grid)
5. [Componentes Base](#5-componentes-base)
6. [Patrones de Interacción](#6-patrones-de-interacción)
7. [Iconografía](#7-iconografía)
8. [Wireframes y Layouts](#8-wireframes-y-layouts)
9. [Accesibilidad](#9-accesibilidad)
10. [Guía de Implementación](#10-guía-de-implementación)

---

## 1. FILOSOFÍA DE DISEÑO

### Principios Rectores

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│  SOLVENTA es rápida, confiable, transparente y accesible│
│                                                          │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ 1. VELOCIDAD                                            │
│    ├─ Interfaces limpias, sin ruido                     │
│    ├─ Menos clics para tareas críticas                  │
│    ├─ Feedback inmediato (animaciones < 300ms)          │
│    └─ Mobile-first: optimizado para pantallas pequeñas  │
│                                                          │
│ 2. CONFIANZA                                            │
│    ├─ Transparencia: precios, términos, riesgos claros  │
│    ├─ Explicabilidad: por qué esta cotización           │
│    ├─ Seguridad: indicadores visuales (HTTPS, candado) │
│    └─ Consistencia: patrones predecibles                │
│                                                          │
│ 3. CLARIDAD                                             │
│    ├─ Jerarquía visual clara (tamaño, color, peso)      │
│    ├─ Lenguaje simple, sin jerga técnica                │
│    ├─ Etiquetas explícitas (no iconos vagos)            │
│    └─ Microcopy ayuda a completar tareas                │
│                                                          │
│ 4. ACCESIBILIDAD                                        │
│    ├─ WCAG 2.1 AA como mínimo                           │
│    ├─ Contraste ≥ 4.5:1 (WCAG AA)                       │
│    ├─ Teclado-navegable (Tab, Enter, Escape)            │
│    ├─ Screen reader compatible (alt text, ARIA)         │
│    └─ Colores no son el único código (+ iconos/texto)   │
│                                                          │
│ 5. INCLUSIVIDAD                                         │
│    ├─ Diseño para todos: adultos mayores, usuarios      │
│    │  con discapacidad visual, auditiva, motora         │
│    ├─ Lenguaje inclusivo: no asumir género              │
│    ├─ Idiomas: español (latam), portugués, inglés       │
│    └─ Contextos: web, móvil, offline                    │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 2. PALETA DE COLORES

### Colores Primarios

```
┌─────────────────────────────────────────────────────────┐
│  PRIMARY: AZUL CONFIANZA                                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Solventa Blue (#1E40AF)                              │
│  RGB(30, 64, 175)                                       │
│  HSL(216°, 71%, 40%)                                    │
│                                                         │
│  ██████████ Primario (botones, links, headers)          │
│                                                         │
│  Usos:                                                  │
│  ├─ Primary CTA buttons ("Cotizar", "Comprar")         │
│  ├─ Links y navegación                                │
│  ├─ Selecciones (checkboxes, radio buttons)            │
│  ├─ Headers y títulos principal                        │
│  └─ Indicadores de estado: activo/seleccionado         │
│                                                         │
│  Variantes:                                            │
│  ├─ Dark (#0F2F6F) - Hover, press                      │
│  ├─ Light (#DFE7F1) - Background hover                │
│  └─ Pale (#F0F6FF) - Backgrounds, highlights           │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  SECONDARY: VERDE ÉXITO / SEGURIDAD                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Solventa Green (#10B981)                              │
│  RGB(16, 185, 129)                                      │
│  HSL(160°, 84%, 39%)                                    │
│                                                         │
│  ██████████ Secundario (confirmaciones, completado)    │
│                                                         │
│  Usos:                                                  │
│  ├─ Botones secundarios ("Confirmar", "Aceptar")       │
│  ├─ Estados de éxito (✓ póliza emitida)                │
│  ├─ Indicadores positivos (cobertura activa)           │
│  ├─ Badges: "Protegido", "Completo"                    │
│  └─ Icons de éxito (checkmarks)                        │
│                                                         │
│  Variantes:                                            │
│  ├─ Dark (#059669) - Hover, press                      │
│  ├─ Light (#D1FAE5) - Background hover                │
│  └─ Pale (#F0FDF4) - Backgrounds                       │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  TERTIARY: ÁMBAR INFORMACIÓN                            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Solventa Amber (#F59E0B)                              │
│  RGB(245, 158, 11)                                      │
│  HSL(38°, 92%, 50%)                                     │
│                                                         │
│  ██████████ Terciario (información, advertencias)      │
│                                                         │
│  Usos:                                                  │
│  ├─ Banners informativos (cambios regulatorios)        │
│  ├─ Advertencias (cobertura expirando)                 │
│  ├─ Campos requeridos (*)                              │
│  ├─ Icons: información (i), ayuda (?)                  │
│  └─ Badges: "Atención", "Requiere acción"              │
│                                                         │
│  Variantes:                                            │
│  ├─ Dark (#B45309) - Hover                             │
│  ├─ Light (#FEF3C7) - Background                       │
│  └─ Pale (#FFFBEB) - Backgrounds                       │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Colores Semánticos

```
┌─────────────────────────────────────────────────────────┐
│  ERROR: ROJO ACCIÓN REQUERIDA                           │
├─────────────────────────────────────────────────────────┤
│  Hex: #EF4444 | RGB(239, 68, 68) | HSL(0°, 91%, 60%)  │
│                                                         │
│  Usos: Errores en formularios, denials, fallos         │
│  Variantes: Dark #DC2626, Light #FCA5A5, Pale #FEE2E2 │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  NEUTRALS: GRISES PARA TEXTO Y BACKGROUNDS             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Black        #000000  (text principal, max contrast)  │
│  Gray-900     #111827  (text primario, headings)       │
│  Gray-700     #374151  (text secundario, body)         │
│  Gray-500     #6B7280  (text terciario, disabled)      │
│  Gray-300     #D1D5DB  (dividers, borders)             │
│  Gray-100     #F3F4F6  (backgrounds, inputs)           │
│  Gray-50      #F9FAFB  (page background, overlays)     │
│  White        #FFFFFF  (highest contrast)              │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  DATA VIS: PARA GRÁFICOS Y CHARTS                       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Chart Blue      #3B82F6  (línea 1, barras)            │
│  Chart Green     #10B981  (línea 2, positivo)          │
│  Chart Orange    #F97316  (línea 3, cambio)            │
│  Chart Red       #EF4444  (línea 4, negativo)          │
│  Chart Purple    #8B5CF6  (línea 5)                    │
│                                                         │
│  Nota: Paleta pastel para contexto web, más saturada  │
│        para móvil (legibilidad en pantallas pequeñas)  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Accesibilidad de Color

```
CONTRASTE (WCAG 2.1 AA mínimo):

✓ Combinaciones APROBADAS:
├─ Text Blue (#1E40AF) on White (#FFF)     → 7.8:1 (AAA)
├─ Text Gray-700 on White (#FFF)           → 8.2:1 (AAA)
├─ Text Gray-500 on White (#FFF)           → 4.5:1 (AA) ← mínimo aceptable
├─ Text White on Blue (#1E40AF)            → 7.8:1 (AAA)
├─ Text White on Green (#10B981)           → 5.3:1 (AA)
├─ Text White on Amber (#F59E0B)           → 4.8:1 (AA)
└─ Button Blue hover (Dark #0F2F6F) text   → 8.1:1 (AAA)

✗ Combinaciones EVITAR:
├─ Gray-500 on Gray-100               → 1.5:1 (insuficiente)
├─ Light Blue on White                → 2.1:1 (insuficiente)
└─ Orange on Yellow                   → 1.2:1 (insuficiente)

PRUEBA EN HERRAMIENTAS:
├─ WebAIM Contrast Checker
├─ Stark (plugin Figma)
├─ Axe DevTools (navegador)
└─ Verify en CI/CD: axe-core, pa11y
```

---

## 3. TIPOGRAFÍA

### Fuentes

```
┌─────────────────────────────────────────────────────────┐
│  FAMILIA PRINCIPAL: INTER                               │
│  (Limpia, legible, modern, sin serif)                   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ¿Por qué?                                             │
│  ├─ Diseñada para pantallas (hinting, kerning)         │
│  ├─ Excelente en tamaños pequeños (móvil)              │
│  ├─ Open source, variado peso (100-900)                │
│  ├─ Neutra: no es "juguetona" ni "corporativa"         │
│  └─ Todos los pesos disponibles gratis (Google Fonts)  │
│                                                         │
│  Weights usados:                                       │
│  ├─ Regular (400) - body text, párrafos                │
│  ├─ Medium (500) - labels, pequeños títulos            │
│  ├─ Semibold (600) - botones, destacados               │
│  └─ Bold (700) - headings, títulos grandes             │
│                                                         │
│  Import:                                               │
│  └─ @import url('https://fonts.googleapis.com/css2?   │
│     family=Inter:wght@400;500;600;700&display=swap');  │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  FAMILIA SECUNDARIA: IBM PLEX MONO                      │
│  (Monoespaciada, para código, números, transacciones)  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Usos:                                                  │
│  ├─ ID de póliza, claim ID (máquina-legible)          │
│  ├─ Montos ($, cifras críticas)                        │
│  ├─ Códigos de error (ERR-001)                         │
│  └─ Timestamps, fechas exactas                         │
│                                                         │
│  Peso: 400 regular, 600 semibold (si es crítico)      │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Escala Tipográfica

```
┌──────────────────────────────────────────────────────────┐
│  DESKTOP (SCALE: 1.125 ratio)                           │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  H1: 48px / 1.3x line-height (62px) / bold             │
│      Ejemplo: "Cotización en segundos"                 │
│      Letter-spacing: -0.02em (tighten grandes)         │
│      Uso: Page titles, hero sections                   │
│                                                          │
│  H2: 36px / 1.4x line-height (50px) / semibold        │
│      Ejemplo: "Datos de tu póliza"                    │
│      Uso: Section headings                            │
│                                                          │
│  H3: 28px / 1.5x line-height (42px) / semibold        │
│      Ejemplo: "Cobertura seleccionada"                │
│      Uso: Subsection titles                           │
│                                                          │
│  H4: 20px / 1.6x line-height (32px) / medium          │
│      Ejemplo: "Opciones de pago"                       │
│      Uso: Card titles, form sections                  │
│                                                          │
│  Body Large: 18px / 1.6x line-height (28px) / regular │
│      Uso: Lead paragraphs, introductions               │
│                                                          │
│  Body: 16px / 1.6x line-height (26px) / regular       │
│      Ejemplo: "La póliza entra en vigencia el..."      │
│      Uso: Main body text, default                      │
│                                                          │
│  Body Small: 14px / 1.6x line-height (22px) / regular │
│      Uso: Secondary text, helper text                  │
│                                                          │
│  Label: 12px / 1.5x line-height (18px) / medium       │
│      Ejemplo: "Selecciona cobertura"                   │
│      Uso: Form labels, badges                         │
│                                                          │
│  Caption: 12px / 1.4x line-height (16px) / regular    │
│      Uso: Footnotes, timestamps, fine print           │
│                                                          │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  MÓVIL (SCALE: 1.08 ratio, más compacto)               │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  H1: 32px / 1.3x line-height (41px) / bold            │
│  H2: 28px / 1.4x line-height (39px) / semibold        │
│  H3: 24px / 1.5x line-height (36px) / semibold        │
│  Body: 16px / 1.6x line-height (26px) / regular       │
│  Body Small: 14px / 1.5x line-height (21px) / regular │
│  Label: 12px / 1.4x line-height (16px) / medium       │
│                                                          │
│  Nota: Más espaciado vertical en móvil (readability)  │
│  Line-height ≥ 1.5x para accesibilidad (WCAG)         │
│                                                          │
└──────────────────────────────────────────────────────────┘

LÍNEA DE BASE:
├─ Todos los textos alineados en múltiplos de 2px
├─ Baseline grid: 2px para precisión
└─ Herramienta: Figma plugins (Base Design)
```

---

## 4. ESPACIADO Y GRID

### Sistema de Espaciado (8px base)

```
┌──────────────────────────────────────────────────────────┐
│  SCALE: 8px como unidad base (divisible, DPI-independent)
├──────────────────────────────────────────────────────────┤
│                                                          │
│  2px   (0.25 rem)   - Micro spacing, borders          │
│  4px   (0.5 rem)    - Tight spacing, icon padding      │
│  8px   (1 rem)      - Default padding, gaps            │
│  12px  (1.5 rem)    - Medium spacing                   │
│  16px  (2 rem)      - Comfortable spacing (standard)   │
│  24px  (3 rem)      - Section spacing                  │
│  32px  (4 rem)      - Large sections                   │
│  48px  (6 rem)      - Hero spacing                     │
│  64px  (8 rem)      - Page sections                    │
│                                                          │
│  USOS:                                                  │
│  ├─ Button padding: 12px (vertical) × 16px (horizontal)│
│  ├─ Card padding: 24px                                 │
│  ├─ Input padding: 12px 16px                           │
│  ├─ Header height: 64px (desktop), 56px (mobile)       │
│  ├─ Gap entre elementos: 16px (horizontal), 24px (vert)│
│  └─ Page margin: 24px (desktop), 16px (mobile)         │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### Grid (12 columnas, responsive)

```
DESKTOP (1024px+):
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ Col │ Col │ Col │ Col │ Col │ Col │ Col │ Col │ Col │ Col │ Col │ Col │
│  1  │  2  │  3  │  4  │  5  │  6  │  7  │  8  │  9  │ 10  │ 11  │ 12  │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
 
 Ancho columna: (1024 - 48) / 12 = ~80px
 Gutter (gap):  16px entre columnas
 Margin: 24px a cada lado

 Ejemplo layout:
 ├─ Sidebar izquierda: 3 columnas (240px)
 ├─ Gutter: 16px
 └─ Contenido principal: 9 columnas (720px)

TABLET (768px):
┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
│Col │Col │Col │Col │Col │Col │Col │Col │Col │Col │Col │Col │
│ 1  │ 2  │ 3  │ 4  │ 5  │ 6  │ 7  │ 8  │ 9  │10  │11  │12  │
└────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘

 Ancho columna: (768 - 32) / 12 = ~61px
 Gutter: 16px
 Margin: 16px a cada lado

MÓVIL (375px - iPhone 12):
┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
│Col │Col │Col │Col │Col │Col │Col │Col │Col │Col │Col │Col │
└────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘

 Ancho columna: (375 - 32) / 12 = ~28.6px
 Gutter: 12px
 Margin: 16px a cada lado
```

---

## 5. COMPONENTES BASE

### Button (Componente Principal)

```
ESTADOS:

┌─────────────────────────────────────────────────────────┐
│  PRIMARY BUTTON (Acción principal)                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Default:                                               │
│  ┌──────────────────────────┐                           │
│  │ ⌘ COTIZAR AHORA          │ bg: Solventa Blue        │
│  └──────────────────────────┘ text: White              │
│                                padding: 12px 24px       │
│                                radius: 8px              │
│                                font: 16px/600 semibold  │
│                                shadow: none             │
│                                                         │
│  Hover:                                                 │
│  ┌──────────────────────────┐ bg: Solventa Blue Dark   │
│  │ ⌘ COTIZAR AHORA          │ opacity: 0.9 (animation)│
│  └──────────────────────────┘ duration: 150ms          │
│                                                         │
│  Active (Press):                                        │
│  ┌──────────────────────────┐ bg: even darker          │
│  │ ⌘ COTIZAR AHORA          │ transform: scale(0.98)   │
│  └──────────────────────────┘ shadow: inset            │
│                                                         │
│  Disabled:                                              │
│  ┌──────────────────────────┐ bg: Gray-300             │
│  │ ⌘ COTIZAR AHORA          │ text: Gray-500           │
│  └──────────────────────────┘ cursor: not-allowed      │
│                                opacity: 0.6             │
│                                                         │
│  Loading:                                               │
│  ┌──────────────────────────┐                           │
│  │ ⏳ Procesando...          │ spinner animado          │
│  └──────────────────────────┘ texto cambia             │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  SECONDARY BUTTON (Acción alternativa)                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Default:                                               │
│  ┌──────────────────────────┐                           │
│  │   Más información        │ bg: Gray-100 (outline)   │
│  └──────────────────────────┘ text: Solventa Blue      │
│                                border: 2px Blue         │
│                                padding: 10px 22px       │
│                                                         │
│  Hover:                                                 │
│  ┌──────────────────────────┐ bg: Light Blue (#F0F6FF) │
│  │   Más información        │                           │
│  └──────────────────────────┘                           │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  TERTIARY / GHOST BUTTON (Acción mínima)                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Default:                                               │
│  ┌──────────────────────────┐                           │
│  │ 🔄 Reintentarlo          │ bg: transparent          │
│  └──────────────────────────┘ text: Solventa Blue      │
│                                underline: none          │
│                                                         │
│  Hover:                                                 │
│  ┌──────────────────────────┐ bg: Light Blue           │
│  │ 🔄 Reintentarlo          │ text: Dark Blue          │
│  └──────────────────────────┘ underline: underline     │
│                                                         │
└─────────────────────────────────────────────────────────┘

TAMAÑOS (Width adaptable, altura fija):

Large:
  Desktop:  height 48px, padding 16px 32px, font 16px/600
  Mobile:   height 44px, padding 12px 24px, font 14px/600

Default:
  Desktop:  height 40px, padding 12px 24px, font 16px/600
  Mobile:   height 40px, padding 12px 20px, font 14px/500

Small:
  Desktop:  height 32px, padding 8px 16px, font 14px/500
  Mobile:   height 32px, padding 6px 12px, font 12px/500

VARIANTES CON ICONOS:

Icon Left:
  ┌──────────────────────────┐
  │  📱 Descargar App        │ icon-size: 18px
  └──────────────────────────┘ gap: 8px

Icon Right:
  ┌──────────────────────────┐
  │  Ver detalles →          │ icon-size: 16px
  └──────────────────────────┘

Icon Only:
  ┌────┐
  │ 🔔 │  height = width (square)
  └────┘  perfect para toolbars

CÓDIGO (React):

<Button 
  variant="primary"      // primary | secondary | ghost
  size="default"         // large | default | small
  disabled={loading}
  onClick={handleClick}
  icon="check"           // icon left
  iconPosition="right"   // left | right
  aria-label="Confirmar póliza"
  fullWidth={false}      // 100% width on mobile
>
  Confirmar Póliza
</Button>

ACCESIBILIDAD:
├─ focus: visible border (2px) offset 2px
├─ aria-label: descriptivo si no hay texto
├─ :disabled → cursor not-allowed
├─ Mínimo 44×44px en mobile (touch target)
└─ Color no es único indicador (use text + icon)
```

### Input Field (Formularios)

```
┌─────────────────────────────────────────────────────────┐
│  TEXT INPUT                                             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Default (empty):                                       │
│  ┌─────────────────────────┐                            │
│  │ Email                   │ bg: Gray-50              │
│  │ ejemplo@mail.com        │ border: 1px Gray-300     │
│  │                         │ radius: 6px              │
│  └─────────────────────────┘ padding: 12px 16px       │
│   Hint: Usaremos tu email... │ height: 40px            │
│                               │ font: 16px/400 regular  │
│                                                         │
│  Focus (active):                                        │
│  ┌─────────────────────────┐ border: 2px Solventa Blue│
│  │ Email                   │ bg: White                │
│  │ ejemplo@mail.com        │ shadow: 0 0 0 3px        │
│  │█                        │        rgba(30,64,175,0.1)│
│  └─────────────────────────┘ outline: none (remove    │
│                               browser default)         │
│                                                         │
│  Error:                                                 │
│  ┌─────────────────────────┐ border: 2px Red          │
│  │ Email                   │ bg: #FEE2E2 (red bg pale)│
│  │ usuario@mail            │                          │
│  └─────────────────────────┘                          │
│   ❌ Email no válido       │ text: #EF4444 (red)      │
│                               icon: ❌ rojo            │
│                                                         │
│  Success:                                               │
│  ┌─────────────────────────┐ border: 2px Green        │
│  │ Email                   │ bg: #F0FDF4 (green pale) │
│  │ usuario@mail.com        │                          │
│  └─────────────────────────┘ ✓                        │
│                               icon: ✓ verde            │
│                                                         │
│  Disabled:                                              │
│  ┌─────────────────────────┐ bg: Gray-100             │
│  │ Email                   │ border: 1px Gray-300     │
│  │                         │ text: Gray-400           │
│  └─────────────────────────┘ cursor: not-allowed      │
│                               opacity: 0.6             │
│                                                         │
└─────────────────────────────────────────────────────────┘

SELECT / DROPDOWN:

┌──────────────────────────────────┐
│ Tipo de cobertura          ▼     │ bg: Gray-50
│ ▼ Selecciona una opción          │ border: 1px Gray-300
│                                  │ padding: 12px 16px
├──────────────────────────────────┤
│ • Viaje                          │ Dropdown open:
│ • Dispositivos                   │ ├─ max-height: 280px (scroll if longer)
│ • Vida                           │ ├─ z-index: 1000
│ • Paramétrico                    │ ├─ shadow: 0 10px 15px rgba(0,0,0,0.1)
│ • Hipotecario                    │ └─ animation: slideDown 150ms
└──────────────────────────────────┘

CHECKBOX:

☑ Soy cliente del banco XYZ        bg: Blue
   Permite verificar cuenta        outline: 1px transparent
   más rápido                      checked: Blue checkmark
                                   padding: 4px (no extra space)
                                   
RADIO BUTTON:

⦿ Cobertura básica ($25/mes)
  Incluye: viaje, equipo, demoras
  
○ Cobertura premium ($45/mes)
  Incluye: todo básico + más

TEXTAREA (Multi-line):

┌─────────────────────────────────┐
│ Describe el siniestro:          │ min-height: 120px
│ Incluye: dónde, cuándo, qué     │ max-height: 300px (scroll)
│                                 │ resize: vertical only
│ El producto se dañó al caer...  │ font: 14px/1.6
│                                 │ padding: 12px 16px
└─────────────────────────────────┘

PASSWORD INPUT:

┌─────────────────────────────────┐
│ Contraseña:                     │
│ ••••••••••••••••••••  👁         │ Eye icon toggle show/hide
│                                 │ on:hover { opacity: 0.7 }
└─────────────────────────────────┘

NÚMERO (Currency):

┌─────────────────────────────────┐
│ Cobertura:    COP $             │ prefix: "COP $"
│               1,000,000         │ separators: auto (1,000,000)
│                                 │ decimals: 0 (no cents)
└─────────────────────────────────┘

FECHA:

┌─────────────────────────────────┐
│ Fecha de nacimiento:            │
│ 15 / 03 / 1985                  │ format: DD / MM / YYYY
│                                 │ calendar icon optional
└─────────────────────────────────┘

LABEL + HINT + ERROR (Anatomy):

Tipo de cobertura *                   [label] (asterisco si required)
                                      
┌─────────────────────────────────┐
│ Elige una opción...             │
└─────────────────────────────────┘

Viaje, dispositivos, vida, etc.       [hint] bajo input (Gray-500)

❌ Campo requerido, elige una opción  [error message] (Red)
```

### Card (Contenedor)

```
┌───────────────────────────────────────────────────────┐
│  BASIC CARD                                           │
├───────────────────────────────────────────────────────┤
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │  VIAJE SEGURO                          [$25/mes]│ │
│  │  ─────────────────────────────────────────────  │ │
│  │                                                 │ │
│  │  ✓ Cobertura por retraso de vuelo (hasta 6h)  │ │
│  │  ✓ Equipo dañado o perdido (hasta $1M)        │ │
│  │  ✓ Cancelación con 30% de reembolso           │ │
│  │  ✓ Asistencia médica 24/7                      │ │
│  │                                                 │ │
│  │  Opciones: [Cambiar cobertura] [Más info] ←   │ │
│  │                                                 │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  bg: White                                          │
│  border: 1px Gray-200                              │
│  border-radius: 8px                                │
│  padding: 20px                                      │
│  shadow: 0 1px 3px rgba(0,0,0,0.1)                 │
│  hover: shadow-md (0 4px 6px rgba(0,0,0,0.1))      │
│  hover-duration: 200ms ease                         │
│                                                       │
│  INTERNAL LAYOUT:                                   │
│  ├─ Header: flex between (title + price)           │
│  ├─ Divider: HR (Gray-200)                         │
│  ├─ Body: list of benefits (bullets)               │
│  └─ Footer: action buttons (flex right)             │
│                                                       │
└───────────────────────────────────────────────────────┘

CARD VARIANTS:

1. Info Card (Light background):
   ┌─────────────────────────────┐
   │ ℹ️ Tu póliza vence en 30 días│ bg: #F0F6FF (Light Blue)
   │ Renuévala ahora para no      │ border: 1px Blue-200
   │ perder cobertura            │ text: Blue-900
   └─────────────────────────────┘

2. Alert Card (Warning):
   ┌─────────────────────────────┐
   │ ⚠️ Acción requerida          │ bg: #FFFBEB (Light Amber)
   │ Tu documento expiró          │ border-left: 4px Amber
   │ Sube uno nuevo              │ text: Gray-900
   └─────────────────────────────┘

3. Success Card (Confirmation):
   ┌─────────────────────────────┐
   │ ✓ Póliza emitida            │ bg: #F0FDF4 (Light Green)
   │ Número: POL-2026-001234     │ border-left: 4px Green
   │ PDF descargado              │ text: Gray-900
   └─────────────────────────────┘

4. Error Card (Problem):
   ┌─────────────────────────────┐
   │ ❌ Pago rechazado            │ bg: #FEE2E2 (Light Red)
   │ Verifica los datos de tu     │ border-left: 4px Red
   │ tarjeta o prueba otro método │ text: Gray-900
   └─────────────────────────────┘
```

### Badge (Pequeño indicador)

```
TIPOS:

Filled (colored background):
┌──────────────────┐
│ ACTIVO           │ bg: Green, text: White
└──────────────────┘ font: 12px/600 semibold
                    padding: 4px 8px, radius: 16px

Outlined (border only):
┌──────────────────┐
│ PENDIENTE        │ border: 1px Gray-300
└──────────────────┘ bg: transparent, text: Gray-700
                    font: 12px/600

Soft (light background):
┌──────────────────┐
│ NUEVO            │ bg: Blue-50 (#F0F6FF)
└──────────────────┘ text: Blue-900
                    font: 12px/600

Status badges:
├─ ✓ VIGENTE (Green)
├─ ⏰ RENOVACIÓN (Amber)
├─ ✓ COMPLETO (Green)
├─ ⚠️ ACCIÓN REQUERIDA (Red)
├─ ⏳ PROCESANDO (Gray)
└─ 🔒 PROTEGIDO (Blue)

USOS:
├─ Policy status: "ACTIVO", "CANCELADO", "VENCIDO"
├─ Priority: "URGENTE", "NORMAL", "BAJA"
├─ Feature flags: "NUEVO", "PROMOCIÓN", "EXCLUSIVO"
└─ Data verification: "VERIFICADO", "PENDIENTE", "RECHAZADO"
```

---

## 6. PATRONES DE INTERACCIÓN

### Patrón: Cotización Step-by-step

```
WIZARD PATTERN (4 pasos):

Paso 1: Tipo de cobertura
────────────────────────────────────────
Progress: [███░░░░░░░░░░░░░░░░] 25%

┌──────────────────────────────────────┐
│  ¿Qué quieres asegurar?              │
├──────────────────────────────────────┤
│                                      │
│  ○ Viaje                             │
│  ○ Dispositivos                      │
│  ⦿ Vida (hipoteca)  ← selected       │
│  ○ Paramétrico                       │
│  ○ Otro                              │
│                                      │
│  [← Anterior]          [Siguiente →] │
└──────────────────────────────────────┘

UX Patterns:
├─ Progress bar visual (25% → 50% → 75% → 100%)
├─ Current step highlighted (left sidebar or tabs)
├─ Next/Back buttons (Previous disabled on step 1)
├─ Save draft auto (localStorage on mobile)
└─ Show summary on review screen

Paso 2: Monto y duración
────────────────────────────────────────
Progress: [██████░░░░░░░░░░░░░] 50%

Monto a cubrir:     [_____________]  (currency input)
Duración:           [1 año ▼]         (dropdown)
                    [6 meses ▼]
                    [1 mes ▼]

Real-time pricing: 
  Monthly: $45/mes (actualiza mientras escribes)
  Total: $540 (12 months)

Paso 3: Confirmación
────────────────────────────────────────
Progress: [████████████░░░░░░░] 75%

Resumen de tu cotización:
├─ Cobertura: Vida hipotecario
├─ Monto: $500,000,000 COP
├─ Duración: 12 meses
├─ Precio: $45/mes ($540 total)
├─ Vigencia: Hoy mismo
├─ Póliza: Emitida al confirmar

Factores de precio:
  Base:           $50 (edad 35, sin datos)
  -$5 (Open Finance): Buen historial de pago
  +$2 (Location): Bogotá, zona media-alta
  = $47 → $45/mes (promoción: -$2)
  
  ⓘ ¿Cómo se calculó?  [Ver detalles]

☑ Acepto términos y condiciones

[← Anterior]          [Confirmar póliza]

Paso 4: Éxito (Confirmation)
────────────────────────────────────────

┌────────────────────────────────────┐
│                                    │
│           ✓                        │
│                                    │
│  ¡Póliza emitida exitosamente!    │
│                                    │
│  Número de póliza: POL-2026-001234 │
│  Vigencia: 15 ene 2026 - 14 ene 2027
│                                    │
│  📧 Confirmación enviada a:       │
│     juan@mail.com                  │
│                                    │
│  Próximos pasos:                   │
│  ✓ Realiza el primer pago         │
│  ✓ Carga tu documento (si es nuev│
│                                    │
│  [📥 Descargar póliza (PDF)]       │
│  [📱 Ver en la app]                │
│  [🏠 Ir al inicio]                 │
│                                    │
└────────────────────────────────────┘

RESPONSIVENESS (Mobile):

Desktop (1024px+):
├─ Wizard en modal (centered, 600px wide)
└─ Step indicators horizontal (top)

Tablet (768px):
├─ Wizard full-width
├─ Step indicators horizontal
└─ Scroll vertically

Mobile (375px):
├─ Wizard full-screen
├─ Step indicators vertical (left sidebar or top accordion)
├─ One field per view if needed
└─ Larger touch targets (44×44px min)
```

### Patrón: Notificación (Toast)

```
TOAST NOTIFICATIONS:

Success Toast (bottom-right):
┌──────────────────────────────┐
│ ✓ Pago procesado             │ auto-dismiss: 4s
│ Transacción ID: TXN-123456   │ position: fixed bottom-right
└──────────────────────────────┘ margin: 16px from edges

Error Toast (stays until close):
┌──────────────────────────────┐
│ ❌ Error procesando pago       │ auto-dismiss: NEVER
│ Código: ERR-402               │ action: [Reintentarlo]
│ Intenta con otro método       │ close button: [X]
└──────────────────────────────┘

Warning Toast:
┌──────────────────────────────┐
│ ⚠️ Tu documento expirará en 5  │ auto-dismiss: 6s
│ días. Renuévalo ahora.       │ action: [Renovar ahora]
└──────────────────────────────┘

Info Toast:
┌──────────────────────────────┐
│ ℹ️ Te hemos enviado un email  │ auto-dismiss: 4s
│ de confirmación              │ no actions needed
└──────────────────────────────┘

STACK BEHAVIOR (Multiple):
┌──────────────────────────────┐
│ ✓ Pago procesado             │  Toast 1 (appears first)
│ Transacción: TXN-123         │  
└──────────────────────────────┘
                ↓
┌──────────────────────────────┐
│ ℹ️ Email enviado a juan@...  │  Toast 2 (stacks below)
└──────────────────────────────┘
                ↓
┌──────────────────────────────┐
│ ❌ Error en servidor          │  Toast 3 (stays until fixed)
│ [Reintentarlo]               │
└──────────────────────────────┘

Max toasts: 3 on screen (older ones auto-dismiss)
```

### Patrón: Modal Dialog

```
MODAL FOR CONFIRMATIONS:

Background: Semi-transparent dark overlay (rgba(0,0,0,0.5))
Z-index: 2000 (above all content)
Animation: Fade in 150ms, scale 0.95 → 1

┌────────────────────────────────────────────────────┐
│  ✓ ¿Cancelas la póliza?           [X close]       │
├────────────────────────────────────────────────────┤
│                                                    │
│  Esta acción es irreversible.                     │
│  Perderás cobertura de inmediato.                 │
│                                                    │
│  ¿Deseas continuar?                              │
│                                                    │
│  [No, mantener póliza] [Sí, cancelar]             │
│                                                    │
└────────────────────────────────────────────────────┘

MODAL SIZES:
├─ Small (400px): simple confirmations
├─ Default (600px): forms, claims
└─ Large (900px): detailed reviews, dashboards

KEYBOARD SUPPORT:
├─ Esc: close modal (if allow)
├─ Tab: cycle focus inside modal
├─ Enter: confirm/submit (if button focused)
└─ Shift+Tab: cycle backward
```

---

## 7. ICONOGRAFÍA

### Icon Set (Línea 24px)

```
STYLE: Line icons, 24px grid, 2px stroke, rounded corners

Icons primarios (más usados):

🏠 Home               (for dashboard)
📋 Document          (for policies)
📱 Mobile            (for app)
🔔 Bell              (for notifications)
⚙️ Settings          (for preferences)
👤 User              (for profile)
🚪 Logout            (for exit)
🔍 Search            (for find)
📱 Phone             (for contact)
📧 Email             (for message)

Action icons:

✓ Check              (success, confirmation)
✕ Close / Dismiss    (close, cancel)
+ Add / Plus         (new, create)
- Minus / Remove     (delete, subtract)
← Back / Arrow Left  (previous, go back)
→ Arrow Right        (next, continue)
⬆ Upload             (file upload)
⬇ Download           (file download)
♻️ Refresh / Reload   (retry, refresh)
🔗 Link / Export     (share, external)

Status icons:

⏳ Loading / Clock   (processing)
✓ Done / Check       (complete)
❌ Error / X         (failed)
⚠️ Warning           (alert)
ℹ️ Info              (information)
🔒 Locked            (secure, private)
🔓 Unlocked          (open, public)

Insurance-specific icons:

🛡️ Shield            (insurance, protection)
🏥 Hospital          (medical, health)
✈️ Plane             (travel)
🚗 Car               (auto, vehicle)
🏠 House             (home, property)
💰 Money             (payment, billing)
📊 Chart             (statistics, analytics)
🎯 Target            (goal, objective)
💎 Diamond           (premium, value)
⚡ Lightning          (fast, quick)

USAGE GUIDELINES:

1. Size consistency:
   ├─ Large: 32px (hero, page headers)
   ├─ Medium: 24px (buttons, cards, navigation) ← DEFAULT
   ├─ Small: 16px (inline, inline with text)
   └─ Tiny: 12px (badges, labels)

2. Color:
   ├─ Primary action: Solventa Blue
   ├─ Positive: Green (success)
   ├─ Negative: Red (error)
   ├─ Neutral: Gray-700 (default)
   └─ Muted: Gray-400 (disabled, secondary)

3. Pairing with text:
   Icon spacing (gap): 8px
   
   ✈️ Viaje              Viaje ✈️        [icon left]    [icon right]

4. Icon + Button:
   ┌──────────────────────────────┐
   │  ⬇️  Descargar póliza         │ icon-text gap: 8px
   └──────────────────────────────┘

5. Icon alone (hover shows tooltip):
   
   🔔                            Tooltip: "Notificaciones"
   on:hover {
     bg: Light Blue (subtle highlight)
     tooltip: appears 200ms delay
   }

IMPLEMENTATION (React):

<Icon name="check" size="24" color="green" />
<Icon name="error" size="16" color="red" />
<Icon name="shield" size="32" aria-label="Protegido" />

ACCESSIBLE ICONS:

✓ Icon-only buttons: add aria-label
  <button aria-label="Cerrar"><Icon name="close" /></button>

✓ Icons with text: text conveys meaning
  <span>✓ Completado</span>

✓ Decorative icons: aria-hidden="true"
  <Icon name="arrow" aria-hidden="true" />

✓ Color-coded icons: add text/pattern
  ✓ Success (green) + "Completado"
  ❌ Error (red) + "Rechazado"
```

---

## 8. WIREFRAMES Y LAYOUTS

### Desktop Web (Desktop-first)

```
LAYOUT GENERAL (1024px+):

┌───────────────────────────────────────────────────────────┐
│                      HEADER (64px)                        │
│  Logo          | Navegación | Perfil | Notificaciones     │
├─────────┬─────────────────────────────────────────────────┤
│         │                                                 │
│ SIDEBAR │              CONTENIDO PRINCIPAL                │
│  (240)  │          (Centro: 760px)                       │
│         │                                                 │
│ • Inicio│                                                 │
│ • Mis   │  ┌─────────────────────────────────────────┐  │
│   pólizas│  │  Mis Pólizas                           │  │
│ • Nuevo │  ├─────────────────────────────────────────┤  │
│   siniestro  │  Filter: [Todas ▼] [Vigentes] [Vencidas]  │
│ • Perfil│  │                                         │  │
│ • Ayuda │  │  ┌──────────────────────────────────┐   │  │
│ • Cerrar│  │  │ VIAJE SEGURO        Activa    $45│   │  │
│   sesión│  │  │ POL-2026-001234    Expires: 14e │   │  │
│         │  │  │ [Ver detalles]  [Renovar]       │   │  │
│         │  │  └──────────────────────────────────┘   │  │
│         │  │                                         │  │
│         │  │  ┌──────────────────────────────────┐   │  │
│         │  │  │ DISPOSITIVOS        Activa    $20│   │  │
│         │  │  │ POL-2026-001235                 │   │  │
│         │  │  │ [Ver detalles]  [Cambiar]       │   │  │
│         │  │  └──────────────────────────────────┘   │  │
│         │  │                                         │  │
│         │  │  [Cargar más...]                       │  │
│         │  │                                         │  │
│         │  └─────────────────────────────────────────┘  │
│         │                                                 │
└─────────┴─────────────────────────────────────────────────┘
│                      FOOTER (48px)                        │
│  © 2026 | Privacidad | Términos | Status | Soporte       │
└───────────────────────────────────────────────────────────┘

SCREEN: Policy Detail

┌───────────────────────────────────────────────────────────┐
│                      HEADER                               │
├─────────┬─────────────────────────────────────────────────┤
│         │                                                 │
│ SIDEBAR │  ← Viaje Seguro                               │
│         │  POL-2026-001234                               │
│         │  Estado: ✓ ACTIVO                              │
│         │                                                 │
│         │  ┌─────────────────────────────────────────┐  │
│         │  │  INFORMACIÓN GENERAL                    │  │
│         │  ├─────────────────────────────────────────┤  │
│         │  │ Asegurado: Juan Rodríguez             │  │
│         │  │ Vigencia: 15 ene 2026 - 14 ene 2027   │  │
│         │  │ Precio: $45/mes ($540 anual)           │  │
│         │  │ Renovación: Automática                 │  │
│         │  └─────────────────────────────────────────┘  │
│         │                                                 │
│         │  ┌─────────────────────────────────────────┐  │
│         │  │  COBERTURA                             │  │
│         │  ├─────────────────────────────────────────┤  │
│         │  │ ✓ Retraso de vuelo (6+ horas)         │  │
│         │  │   Monto: $200                          │  │
│         │  │                                         │  │
│         │  │ ✓ Equipo dañado/perdido                │  │
│         │  │   Monto: $1,000,000                    │  │
│         │  │                                         │  │
│         │  │ ✓ Cancelación de viaje                 │  │
│         │  │   Monto: 30% reembolso                 │  │
│         │  └─────────────────────────────────────────┘  │
│         │                                                 │
│         │  [Modificar] [Renovar] [Cancelar]              │
│         │                                                 │
└─────────┴─────────────────────────────────────────────────┘
```

### Mobile (375px iPhone 12)

```
LAYOUT GENERAL (Mobile):

Full-screen, no sidebar (side-menu alternative)

┌──────────────────────────────────┐
│ ☰ Solventa          🔔  👤        │ Header (56px)
├──────────────────────────────────┤
│                                  │
│ ┌──────────────────────────────┐ │
│ │  📍 Ubicación actual          │ │ Location context (optional)
│ │  Bogotá, DC                   │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │ Quick action (featured)
│ │                              │ │
│ │  ¿Necesitas un seguro?       │ │ Call-to-action banner
│ │  Cotiza en 30 segundos       │ │
│ │                              │ │ bg: Light Blue, border-left: 4px
│ │  [Cotizar ahora]             │ │
│ │                              │ │
│ └──────────────────────────────┘ │
│                                  │
│  Mis pólizas                      │ Section header
│  ─────────────────────────────    │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ VIAJE SEGURO                 │ │ Card (full-width)
│ │ POL-2026-001234              │ │ padding: 16px
│ │                              │ │
│ │ Status: ✓ ACTIVO             │ │ tap for detail
│ │ Vence: 14 ene 2027           │ │
│ │ $45/mes                      │ │
│ │                              │ │
│ │ [Ver detalles]               │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ DISPOSITIVOS                 │ │
│ │ POL-2026-001235              │ │
│ │                              │ │
│ │ Status: ✓ ACTIVO             │ │
│ │ Vence: 31 dic 2026           │ │
│ │ $20/mes                      │ │
│ │                              │ │
│ │ [Ver detalles]               │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ [+ Agregar nueva póliza]     │ │ Add new (large touch target)
│ └──────────────────────────────┘ │
│                                  │
├──────────────────────────────────┤ Bottom nav (56px)
│ 🏠 Inicio | 📋 Pólizas | 🔔 Ayuda│
└──────────────────────────────────┘

SCREEN: New Quotation (Mobile)

┌──────────────────────────────────┐
│ ← Cotización              ✓ 25%   │ Header (56px)
├──────────────────────────────────┤
│                                  │
│ ¿Qué quieres asegurar?           │ Step title
│                                  │
│ ┌──────────────────────────────┐ │
│ │ ○ Viaje                      │ │ Radio button option
│ │                              │ │ padding: 12px 16px
│ └──────────────────────────────┘ │ height: 48px (min touch)
│                                  │
│ ┌──────────────────────────────┐ │
│ │ ○ Dispositivos               │ │
│ │                              │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ ⦿ Vida (hipoteca)            │ │ Selected (filled radio)
│ │                              │ │
│ │ Protege tu crédito si algo   │ │
│ │ te pasa. Desde $45/mes       │ │
│ │                              │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ ○ Paramétrico                │ │
│ │                              │ │
│ └──────────────────────────────┘ │
│                                  │
│ ┌──────────────────────────────┐ │
│ │ ○ Otro                       │ │
│ │                              │ │
│ └──────────────────────────────┘ │
│                                  │
├──────────────────────────────────┤
│ [← Anterior]    [Siguiente →]    │ Fixed action bar (56px)
└──────────────────────────────────┘

RESPONSIVE BREAKPOINTS:

Mobile: 375px - 767px
├─ Single column layout
├─ Full-width cards (16px margin)
├─ Bottom sheet for menus
├─ Large touch targets (44×44px)
└─ Tab navigation (bottom)

Tablet: 768px - 1023px
├─ Sidebar navigation (240px)
├─ 2-column card layout possible
├─ Modal dialogs (600px centered)
└─ Top navigation

Desktop: 1024px+
├─ Sidebar + main (240 + 760)
├─ Cards in grid (3 columns)
├─ Modals, popovers
└─ Full UI rich
```

---

## 9. ACCESIBILIDAD

### WCAG 2.1 Level AA Compliance

```
CONTRAST RATIOS:
├─ Normal text: 4.5:1 minimum
├─ Large text (18px+): 3:1 minimum
├─ UI components & borders: 3:1 minimum
└─ Test with: WebAIM Contrast Checker, Axe DevTools

KEYBOARD NAVIGATION:
├─ Tab order: logical, left-to-right, top-to-bottom
├─ Focus indicator: visible (2px border, 2px offset)
├─ Focus color: blue (Solventa Blue)
├─ Trap prevention: no content hidden from keyboard
├─ Skip links: [Skip to main content]
└─ Form shortcuts:
   ├─ Enter: submit form
   ├─ Esc: close modal/dialog
   └─ Arrow keys: select radio/select options

SCREEN READER SUPPORT:
├─ Semantic HTML:
│  ├─ <header>, <nav>, <main>, <footer>
│  ├─ <button> for buttons (not <div onclick>)
│  ├─ <input> with <label>
│  ├─ <h1>-<h6> for headings (no skipping levels)
│  └─ <table> for tabular data, not layout
│
├─ ARIA labels:
│  ├─ aria-label="Close" (icon-only buttons)
│  ├─ aria-labelledby="title" (section headings)
│  ├─ aria-hidden="true" (decorative icons)
│  ├─ aria-live="polite" (status messages)
│  ├─ aria-required="true" (form fields)
│  └─ role="alert" (error messages)
│
├─ Alt text for images:
│  ├─ "Policy document icon" (descriptive)
│  ├─ "" (empty if purely decorative)
│  └─ max 150 chars

TEXT & CONTENT:
├─ Font size: ≥ 14px (min 12px for caption)
├─ Line height: ≥ 1.5x (readability)
├─ Letter spacing: ≥ 0.12em (for body)
├─ Color not only indicator:
│  ├─ Red error + ❌ icon + text "Error"
│  ├─ Green success + ✓ icon + text "Success"
│  └─ Blue link + underline (not color alone)
│
├─ Language:
│  ├─ Plain language (no jargon)
│  ├─ Short paragraphs
│  ├─ Bullet lists for clarity
│  ├─ Definitions for technical terms
│  └─ Abbreviations expanded first time: "Open Finance (OFin)"

FORMS:
├─ Labels explicit:
│  └─ <label for="email">Email</label>
│     <input id="email" type="email" />
│
├─ Error handling:
│  ├─ Error message linked to field (aria-describedby)
│  ├─ Error focus: move to first error field
│  ├─ In-line validation (as typing or on blur)
│  └─ Color + icon + text (not color alone)
│
├─ Required fields:
│  ├─ Visual indicator (*)
│  ├─ aria-required="true"
│  └─ In label text: "Email (required)"

TESTING CHECKLIST:

Manual testing:
├─ [ ] Keyboard-only navigation (Tab all interactive elements)
├─ [ ] Screen reader (NVDA, JAWS, VoiceOver)
├─ [ ] Color contrast check (all text, buttons, borders)
├─ [ ] Zoom test (200% magnification)
├─ [ ] Focus indicators (always visible)
├─ [ ] Mobile touch targets (44×44px minimum)
└─ [ ] Alt text for all images

Automated testing:
├─ axe DevTools (browser plugin)
├─ Lighthouse (Chrome DevTools)
├─ pa11y (CLI tool)
├─ Jest-axe (unit testing)
└─ CI/CD integration (fail build on violations)

Continuous:
├─ Include accessibility in design review
├─ Train team on WCAG 2.1
├─ Include diverse testers (with disabilities)
└─ Test with real assistive tech (not simulators)
```

---

## 10. GUÍA DE IMPLEMENTACIÓN

### Design Token System (CSS)

```css
/* Color Tokens */

:root {
  /* Primary */
  --color-primary-900: #0F2F6F;
  --color-primary-700: #1E40AF;
  --color-primary-500: #3B82F6;
  --color-primary-100: #DFE7F1;
  --color-primary-50: #F0F6FF;

  /* Semantic */
  --color-success: #10B981;
  --color-warning: #F59E0B;
  --color-error: #EF4444;
  --color-info: #3B82F6;

  /* Neutral */
  --color-text-primary: #111827;
  --color-text-secondary: #374151;
  --color-text-tertiary: #6B7280;
  --color-border: #D1D5DB;
  --color-bg-primary: #FFFFFF;
  --color-bg-secondary: #F9FAFB;
  --color-bg-tertiary: #F3F4F6;

  /* Typography */
  --font-family-primary: 'Inter', -apple-system, BlinkMacSystemFont;
  --font-family-mono: 'IBM Plex Mono', monospace;

  --font-size-h1: 48px;
  --font-size-h2: 36px;
  --font-size-h3: 28px;
  --font-size-h4: 20px;
  --font-size-body-lg: 18px;
  --font-size-body: 16px;
  --font-size-body-sm: 14px;
  --font-size-label: 12px;

  --font-weight-regular: 400;
  --font-weight-medium: 500;
  --font-weight-semibold: 600;
  --font-weight-bold: 700;

  --line-height-tight: 1.3;
  --line-height-normal: 1.5;
  --line-height-relaxed: 1.6;

  /* Spacing */
  --space-xs: 4px;
  --space-sm: 8px;
  --space-md: 16px;
  --space-lg: 24px;
  --space-xl: 32px;
  --space-2xl: 48px;

  /* Shadows */
  --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 6px rgba(0, 0, 0, 0.1);
  --shadow-lg: 0 10px 15px rgba(0, 0, 0, 0.1);

  /* Border Radius */
  --radius-sm: 4px;
  --radius-md: 6px;
  --radius-lg: 8px;
  --radius-full: 9999px;

  /* Transitions */
  --transition-fast: 150ms ease;
  --transition-base: 200ms ease;
  --transition-slow: 300ms ease;
}

/* Component Styles */

.btn-primary {
  background-color: var(--color-primary-700);
  color: white;
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-lg);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-body);
  transition: background-color var(--transition-fast);
  cursor: pointer;
}

.btn-primary:hover {
  background-color: var(--color-primary-900);
}

.btn-primary:focus {
  outline: 2px solid var(--color-primary-700);
  outline-offset: 2px;
}

.btn-primary:disabled {
  background-color: var(--color-border);
  color: var(--color-text-tertiary);
  cursor: not-allowed;
  opacity: 0.6;
}

/* Input */

.input {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-sm) var(--space-md);
  font-size: var(--font-size-body);
  font-family: var(--font-family-primary);
  line-height: var(--line-height-normal);
  transition: all var(--transition-fast);
}

.input:focus {
  outline: none;
  border-color: var(--color-primary-700);
  box-shadow: 0 0 0 3px rgba(30, 64, 175, 0.1);
}

.input:invalid {
  border-color: var(--color-error);
  background-color: #FEE2E2;
}

/* Card */

.card {
  background-color: var(--color-bg-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  box-shadow: var(--shadow-sm);
  transition: all var(--transition-base);
}

.card:hover {
  box-shadow: var(--shadow-md);
}

/* Text */

.text-h1 {
  font-size: var(--font-size-h1);
  font-weight: var(--font-weight-bold);
  line-height: var(--line-height-tight);
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.text-body {
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-regular);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
}

.text-body-secondary {
  color: var(--color-text-secondary);
}

/* Responsive */

@media (max-width: 768px) {
  :root {
    --font-size-h1: 32px;
    --font-size-h2: 28px;
    --space-lg: 16px;
  }
}
```

### Component Library (React)

```typescript
// Button.tsx

import React from 'react';
import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost';
export type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  disabled?: boolean;
  loading?: boolean;
  fullWidth?: boolean;
  icon?: string; // icon name
  iconPosition?: 'left' | 'right';
  onClick?: () => void;
  type?: 'button' | 'submit' | 'reset';
  children: React.ReactNode;
  ariaLabel?: string;
  testId?: string;
}

export const Button: React.FC<ButtonProps> = ({
  variant = 'primary',
  size = 'md',
  disabled = false,
  loading = false,
  fullWidth = false,
  icon,
  iconPosition = 'left',
  onClick,
  type = 'button',
  children,
  ariaLabel,
  testId,
}) => {
  const className = [
    styles.button,
    styles[`button--${variant}`],
    styles[`button--${size}`],
    fullWidth && styles['button--fullWidth'],
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      className={className}
      type={type}
      disabled={disabled || loading}
      onClick={onClick}
      aria-label={ariaLabel}
      data-testid={testId}
    >
      {loading ? (
        <>
          <span className={styles.spinner} />
          {typeof children === 'string' ? 'Procesando...' : children}
        </>
      ) : (
        <>
          {icon && iconPosition === 'left' && (
            <Icon name={icon} className={styles.icon} />
          )}
          {children}
          {icon && iconPosition === 'right' && (
            <Icon name={icon} className={styles.icon} />
          )}
        </>
      )}
    </button>
  );
};

// Input.tsx

interface InputProps {
  label?: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'email' | 'password' | 'number';
  error?: string;
  hint?: string;
  required?: boolean;
  disabled?: boolean;
  placeholder?: string;
  ariaLabel?: string;
}

export const Input: React.FC<InputProps> = ({
  label,
  value,
  onChange,
  error,
  hint,
  required,
  disabled,
  placeholder,
  ariaLabel,
}) => {
  const inputId = `input-${Math.random()}`;

  return (
    <div className={styles.inputGroup}>
      {label && (
        <label htmlFor={inputId} className={styles.label}>
          {label}
          {required && <span className={styles.required}>*</span>}
        </label>
      )}
      <input
        id={inputId}
        className={[
          styles.input,
          error && styles['input--error'],
        ].join(' ')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        placeholder={placeholder}
        aria-label={ariaLabel}
        aria-required={required}
        aria-invalid={!!error}
        aria-describedby={error ? `${inputId}-error` : undefined}
      />
      {error && (
        <span id={`${inputId}-error`} className={styles.error}>
          ❌ {error}
        </span>
      )}
      {hint && <span className={styles.hint}>{hint}</span>}
    </div>
  );
};
```

### Storybook Examples

```
storybook/
├─ Button.stories.tsx
├─ Input.stories.tsx
├─ Card.stories.tsx
├─ Modal.stories.tsx
└─ Toast.stories.tsx

// Button.stories.tsx

import { Button } from './Button';

export default {
  title: 'Components/Button',
  component: Button,
};

export const Primary = () => (
  <Button variant="primary">Cotizar Ahora</Button>
);

export const PrimaryLoading = () => (
  <Button variant="primary" loading>
    Cotizar Ahora
  </Button>
);

export const Disabled = () => (
  <Button variant="primary" disabled>
    Cotizar Ahora
  </Button>
);

export const WithIcon = () => (
  <Button variant="primary" icon="download" iconPosition="right">
    Descargar
  </Button>
);

export const Secondary = () => (
  <Button variant="secondary">Más Información</Button>
);

export const Ghost = () => (
  <Button variant="ghost">Reintentarlo</Button>
);

// Run: npm run storybook
// Then: http://localhost:6006
```

---

## CHECKLIST DE IMPLEMENTACIÓN

```
FASE 1: Setup (Semana 1)
├─ [ ] Crear proyecto React + TypeScript
├─ [ ] Instalar Tailwind CSS o CSS Modules
├─ [ ] Configurar Storybook
├─ [ ] Crear design token system (CSS variables)
├─ [ ] Establecer Git workflow
└─ [ ] Documentar en Figma

FASE 2: Componentes Base (Semanas 2-3)
├─ [ ] Button (todas las variantes)
├─ [ ] Input, Select, Checkbox, Radio
├─ [ ] Card, Modal, Toast, Badge
├─ [ ] Icons (24px set)
├─ [ ] Typography (H1-H6, Body, etc.)
└─ [ ] Todos los componentes en Storybook

FASE 3: Layouts y Patrones (Semanas 4-5)
├─ [ ] Header (desktop + mobile)
├─ [ ] Sidebar Navigation
├─ [ ] Quotation Wizard (4 pasos)
├─ [ ] Policy Detail view
├─ [ ] Claim Form
└─ [ ] Responsive grids (mobile, tablet, desktop)

FASE 4: Accesibilidad y Testing (Semana 6)
├─ [ ] WCAG 2.1 AA audit (axe DevTools)
├─ [ ] Screen reader testing (NVDA/JAWS)
├─ [ ] Keyboard navigation test
├─ [ ] Color contrast verification
├─ [ ] Unit tests (Jest + React Testing Library)
├─ [ ] E2E tests (Cypress)
└─ [ ] Accessibility audit report

FASE 5: Documentation (Week 7)
├─ [ ] Design System Figma (all components)
├─ [ ] Component API documentation
├─ [ ] Usage guidelines per component
├─ [ ] Accessibility notes
├─ [ ] Do's and Don'ts
└─ [ ] Migration guide (if redesigning existing)

HERRAMIENTAS:
├─ Design: Figma
├─ Dev: React, TypeScript, Tailwind/CSS Modules
├─ Component Library: Storybook
├─ Testing: Jest, React Testing Library, Cypress
├─ Accessibility: axe DevTools, WAVE, Contrast Checker
├─ Documentation: Storybook Docs, Notion
└─ CI/CD: GitHub Actions (test + a11y checks)
```

---

## CONCLUSIÓN

Este **Design System de Solventa** proporciona:

✓ **Coherencia visual** en web y móvil  
✓ **Componentes reutilizables** (Button, Input, Card, etc.)  
✓ **Accesibilidad WCAG 2.1 AA** desde el inicio  
✓ **Responsive design** (mobile-first)  
✓ **Documentación completa** (Figma + Storybook)  
✓ **Padrón profesional** para fintech/insurtech  

Todas las decisiones prioritzan **claridad, confianza, velocidad y accesibilidad**.

---

**Versión:** 1.0 | **Fecha:** 2026-01-15 | **Estado:** Listo para Implementación
