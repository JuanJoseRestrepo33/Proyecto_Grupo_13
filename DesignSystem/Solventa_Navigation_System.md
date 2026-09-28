# 🧭 SOLVENTA: SISTEMA DE NAVEGACIÓN
## Web + Móvil | UX Flows | Componentes | Accesibilidad

**Proyecto:** MISW4501 Proyecto Final — Solventa  
**Versión:** 1.0  
**Fecha:** 2026-01-15

---

## TABLA DE CONTENIDOS

1. [Filosofía de Navegación](#1-filosofía-de-navegación)
2. [Navegación Web (Desktop)](#2-navegación-web-desktop)
3. [Navegación Web (Tablet)](#3-navegación-web-tablet)
4. [Navegación Móvil](#4-navegación-móvil)
5. [Componentes de Navegación](#5-componentes-de-navegación)
6. [Flujos de Usuario](#6-flujos-de-usuario)
7. [Estados y Transiciones](#7-estados-y-transiciones)
8. [Accesibilidad](#8-accesibilidad)
9. [Especificaciones Técnicas](#9-especificaciones-técnicas)

---

## 1. FILOSOFÍA DE NAVEGACIÓN

### Principios Rectores

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│  Solventa Navigation: Rápida, clara, predecible         │
│                                                          │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ 1. VELOCIDAD                                            │
│    ├─ Max 2 clics para llegar a cualquier pantalla      │
│    ├─ Shortcuts para tareas frecuentes                  │
│    ├─ Acceso rápido a "mis pólizas" (80% de usuarios)   │
│    └─ Bottom nav en móvil (más accesible que top)       │
│                                                          │
│ 2. CLARIDAD                                             │
│    ├─ Labels explícitos (no solo iconos)                │
│    ├─ Indicador visual de ubicación (breadcrumb + icon) │
│    ├─ Estructura mental consistente                     │
│    └─ 5-7 items max por nivel (evitar overload)         │
│                                                          │
│ 3. CONFIANZA                                            │
│    ├─ Patrón reconocible (estándar de industria)        │
│    ├─ Elementos de seguridad visibles (candado, etc)    │
│    ├─ Logout siempre accesible                          │
│    └─ Breadcrumb + page title (sé dónde estoy)          │
│                                                          │
│ 4. ACCESIBILIDAD                                        │
│    ├─ Navegable solo con teclado (Tab, Enter)           │
│    ├─ Screen reader compatible                          │
│    ├─ Focus indicators visibles                         │
│    └─ Min 44×44px touch targets (móvil)                 │
│                                                          │
│ 5. CONTEXTUALIDAD                                       │
│    ├─ Mostrar opciones relevantes al flujo actual       │
│    ├─ CTA flotante cuando es apropiado (cotizar, etc)   │
│    └─ Avisos de estado (renovaciones, siniestros)       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 2. NAVEGACIÓN WEB (DESKTOP)

### Estructura General (1024px+)

```
┌──────────────────────────────────────────────────────────────────┐
│ 🛡️ Solventa  | Inicio | Mis Pólizas | Siniestros | Soporte  | 🔔👤│
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────┐                                            │
│  │   SIDEBAR       │  CONTENIDO PRINCIPAL                       │
│  │   (240px)       │  (760px)                                   │
│  │                 │                                            │
│  │ 📍 Inicio       │  ┌──────────────────────────────────────┐ │
│  │                 │  │  Bienvenido, Juan                    │ │
│  │ 📋 Mis Pólizas  │  ├──────────────────────────────────────┤ │
│  │ • Activas       │  │                                      │ │
│  │ • Vencidas      │  │  Resumen de Pólizas                 │ │
│  │ • Archivadas    │  │  ┌────────────┐  ┌────────────┐    │ │
│  │                 │  │  │ VIAJE      │  │ DISPOSITIV│    │ │
│  │ 🚨 Siniestros   │  │  │ Activa     │  │ Activa     │    │ │
│  │                 │  │  │ $45/mes    │  │ $20/mes    │    │ │
│  │ 💰 Pagos        │  │  │ [Ver]      │  │ [Ver]      │    │ │
│  │                 │  │  └────────────┘  └────────────┘    │ │
│  │ 👤 Mi Perfil    │  │                                      │ │
│  │                 │  │  [+ Agregar nueva póliza]            │ │
│  │ ⚙️ Configuración│  │                                      │ │
│  │                 │  └──────────────────────────────────────┘ │
│  │ ❓ Ayuda        │                                            │
│  │                 │                                            │
│  │ 🚪 Cerrar sesión│                                            │
│  │                 │                                            │
│  └─────────────────┘                                            │
│                                                                   │
├───────────────────────────────────────────────────────────────────┤
│ © 2026 Solventa | Privacidad | Términos | Status | Contacto    │
└───────────────────────────────────────────────────────────────────┘
```

### Header (Topbar)

```
┌─────────────────────────────────────────────────────────────────┐
│  🛡️ Solventa        │ Inicio  Mis Pólizas  Siniestros  Soporte │
│  Logo              │                                    │ 🔔 3 │
│  64px height       │ Navegación Principal              │ 👤 ▼ │
│  bg: white         │ (Horizontal)                      │      │
│  shadow: subtle    │                                    └──────┘
└─────────────────────────────────────────────────────────────────┘

Componentes:
├─ Logo (clickable → home)
├─ Nav items (Inicio, Mis Pólizas, Siniestros, Soporte)
│  └─ Dropdown de contexto en algunos (Soporte → FAQs, Chat, etc)
├─ Notifications badge (🔔 con contador)
│  └─ Dropdown: últimas notificaciones + enlace a todas
├─ Profile menu (👤)
│  └─ Dropdown: Mi perfil, Configuración, Cerrar sesión
└─ Search box (opcional, si hay mucho contenido)

Estados:
├─ Active page: color azul + underline
├─ Hover: background light blue (#F0F6FF)
├─ Focus: border bottom azul (2px)
└─ Mobile (< 768px): transforma a hamburger menu
```

### Sidebar (Navegación Secundaria)

```
┌──────────────────────┐
│                      │
│  📍 INICIO           │ 16px icon, label abajo
│                      │ Activo: bg #F0F6FF, border-left azul
│  📋 MIS PÓLIZAS      │
│  • Activas           │ Sub-items solo visible cuando hovered
│  • Vencidas          │ Padding: 12px 16px
│  • Archivadas        │ Font: 14px / 400 (regular)
│  • Ver todas         │
│                      │
│  🚨 SINIESTROS       │
│  • Nuevos (3)        │ Badge rojo: "3" para siniestros nuevos
│  • Mis Reclamos      │
│  • Ver historial     │
│                      │
│  💰 PAGOS            │
│  • Realizar pago     │ CTA destacada: color verde
│  • Historial         │
│                      │
│  ─────────────────   │ Divider (Gray-200)
│                      │
│  👤 MI PERFIL        │ Secondary nav (menos importante)
│  ⚙️ CONFIGURACIÓN    │
│  ❓ AYUDA            │
│  📞 CONTACTO         │
│  🌐 CAMBIAR IDIOMA   │
│                      │
│  ─────────────────   │
│  🚪 CERRAR SESIÓN    │ Rojo/warning, al pie
│                      │
└──────────────────────┘

Propiedades:
├─ Width: 240px fixed
├─ Height: Full height (fills viewport)
├─ Background: White (#FFFFFF)
├─ Border-right: 1px #D1D5DB
├─ Padding: 16px
├─ Font-size: 14px
├─ Line-height: 1.6
├─ Z-index: 100 (siempre visible)
└─ Overflow: auto (si hay muchos items)
```

### Tipos de Items en Sidebar

```
1. PRIMARY ITEMS (Azul, 16px icon)
   📍 Inicio
   📋 Mis Pólizas
   🚨 Siniestros
   💰 Pagos

2. EXPANDABLE ITEMS (Con sub-items)
   📋 Mis Pólizas
      ├─ • Activas (sub-item)
      ├─ • Vencidas
      ├─ • Archivadas
      └─ • Ver todas

3. SECONDARY ITEMS (Gris, 14px icon)
   👤 Mi Perfil
   ⚙️ Configuración
   ❓ Ayuda
   📞 Contacto

4. DANGER ITEMS (Rojo)
   🚪 Cerrar Sesión
```

### Interacciones en Desktop

```
Click en "Mis Pólizas":
  → Expande sub-items (smooth, 200ms)
  → Página cambia a Pólizas
  → Actualiza el header (activo = "Mis Pólizas")
  → Scroll al item activo en sidebar

Hover en item:
  → Background #F0F6FF
  → Text color más oscuro
  → Duration: 150ms ease

Click en sub-item:
  → Navega a página específica
  → Mantiene sidebar abierto
  → Indica cuál es el sub-item activo

Click en "Mis Pólizas" (si ya está abierto):
  → Cierra los sub-items
  → Navega a /policies (grid de todas)
```

---

## 3. NAVEGACIÓN WEB (TABLET)

### Estructura General (768px - 1023px)

```
┌─────────────────────────────────────────────────────────┐
│ 🛡️ Solventa  | Mis Pólizas | Siniestros | ≡ | 🔔 👤   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Contenido principal (full-width)                       │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Bienvenido, Juan                                │   │
│  │                                                 │   │
│  │ Mis Pólizas (2 columnas en tablet)             │   │
│  │ ┌────────────┐  ┌────────────┐               │   │
│  │ │ VIAJE      │  │ DISPOSITIV │               │   │
│  │ │ Activa     │  │ Activa     │               │   │
│  │ └────────────┘  └────────────┘               │   │
│  │                                                 │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘

Cambios vs Desktop:
├─ Sidebar desaparece (se convierte en hamburger ≡)
├─ Nav principal en header (horizontal)
├─ Más items caben en header sin scrolling
├─ Dropdown menus en header items
├─ Contenido usa 100% width disponible
└─ Hamburger abre sidebar en overlay (modal-like)
```

### Header en Tablet

```
┌──────────────────────────────────────────────────────────┐
│ 🛡️ Solventa | Mis Pólizas | Siniestros | ≡ | 🔔 👤    │
│ Logo        │                      Menu | N | Profile   │
│ 48px        │ Full navigation       Btn | o │           │
│             │ items visible         ▼   | t │           │
└──────────────────────────────────────────────────────────┘

≡ Hamburger menu (cuando hay overflow)
  └─ Click abre sidebar en overlay
  └─ Sidebar cubre 80% de pantalla (240-300px)
  └─ Semi-transparent dark overlay debajo
  └─ Click fuera cierra el sidebar
```

---

## 4. NAVEGACIÓN MÓVIL

### Estructura General (375px - 767px)

```
┌───────────────────────────────────────┐
│ ≡ Solventa        🔔 (3)    👤 ▼     │ HEADER (56px)
├───────────────────────────────────────┤
│                                       │
│  ┌─────────────────────────────────┐ │
│  │  Bienvenido, Juan               │ │
│  │  Bogotá, DC                     │ │
│  └─────────────────────────────────┘ │
│                                       │
│  ┌─────────────────────────────────┐ │ BANNER
│  │ ¿Necesitas un seguro?           │ │ (CTA flotante)
│  │ Cotiza en 30 segundos           │ │
│  │ [COTIZAR AHORA]                 │ │
│  └─────────────────────────────────┘ │
│                                       │
│  Mis Pólizas (Resumen)               │
│  ┌──────────────────────────────┐    │
│  │ VIAJE SEGURO                 │    │ Full-width
│  │ POL-2026-001234              │    │ Cards
│  │ Status: ✓ ACTIVO             │    │
│  │ $45/mes | Vence: 14 ene 2027 │    │
│  │ [Ver detalles]               │    │
│  └──────────────────────────────┘    │
│                                       │
│  ┌──────────────────────────────┐    │
│  │ DISPOSITIVOS                 │    │
│  │ POL-2026-001235              │    │
│  │ Status: ✓ ACTIVO             │    │
│  │ $20/mes | Vence: 31 dic 2026 │    │
│  │ [Ver detalles]               │    │
│  └──────────────────────────────┘    │
│                                       │
│  [+ AGREGAR NUEVA PÓLIZA]            │
│                                       │
├───────────────────────────────────────┤
│ 🏠 Inicio │ 📋 Pólizas │ 🚨 Avisos  │ BOTTOM NAV (56px)
│   Activo  │            │            │
└───────────────────────────────────────┘

Características:
├─ Header compacto (56px)
├─ Full-width cards (16px margin)
├─ Bottom navigation (56px)
├─ No sidebar (espacio limitado)
├─ Hamburger para menú secundario
└─ Floating Action Button opcional (Cotizar)
```

### Header Móvil

```
┌─────────────────────────────────────┐
│ ≡ Solventa        🔔 (3)    👤 ▼   │
│ Menu  Logo      Notif    Profile    │
│ Btn               Btn       Menu    │
└─────────────────────────────────────┘

≡ Menu Button (hamburger)
  ├─ Click abre drawer de navegación (full-screen overlay)
  ├─ Z-index: 1000
  └─ Cierra al navegar o click fuera

🔔 Notifications Badge
  ├─ Número en rojo (#EF4444)
  ├─ Click abre dropdown con últimas notificaciones
  └─ O navega a /notifications (full page)

👤 Profile Menu
  ├─ Avatar del usuario
  ├─ Click abre dropdown:
  │  ├─ Mi Perfil
  │  ├─ Configuración
  │  ├─ Ayuda
  │  └─ Cerrar Sesión
  └─ Scroll down cierra el dropdown
```

### Bottom Navigation

```
┌──────────────────────────────────────────┐
│ 🏠          📋          🚨               │
│ Inicio      Pólizas     Avisos           │
│ (Activo)                                 │
│                                          │
│ bg: white, border-top: 1px #D1D5DB      │
│ height: 56px                            │
│ Touch target: 56×56px (cada item)       │
└──────────────────────────────────────────┘

Items (max 5):
├─ 🏠 Inicio        (dashboard)
├─ 📋 Pólizas       (mis pólizas)
├─ 🚨 Avisos        (notificaciones + siniestros)
├─ 💰 Pagos         (pagos pendientes) - opcional
└─ 👤 Perfil        (mi cuenta) - opcional

Estados:
├─ Active: icon color azul + text blue
├─ Inactive: icon gray + text gray
├─ Label: 12px/400 (siempre visible en móvil)
└─ No hover effects en móvil
```

### Hamburger Menu (Drawer)

```
ANTES:                      DESPUÉS (Tap ≡):
┌──────────────────┐        ┌──────────────────┐
│ ≡ Solventa       │        │ 🛡️ Solventa   ✕  │ DRAWER
│ Content          │        ├──────────────────┤
│                  │        │ 📍 Inicio        │
│                  │        │ 📋 Mis Pólizas   │
│                  │        │ 🚨 Siniestros   │
│                  │        │ 💰 Pagos         │
│                  │        │ ────────────────  │
└──────────────────┘        │ 👤 Mi Perfil     │
                            │ ⚙️ Configuración │
                            │ ❓ Ayuda         │
                            │ 📞 Contacto      │
                            │ 🌐 Idioma        │
                            │ ────────────────  │
                            │ 🚪 Cerrar Sesión │
                            │                  │
                            └──────────────────┘
                            
Propiedades del Drawer:
├─ Width: 280px (75-80% de pantalla)
├─ Position: fixed, left: 0, top: 0
├─ Height: 100vh
├─ Z-index: 999
├─ Animation: slideIn 250ms ease
├─ Overlay dark: rgba(0,0,0,0.5), click cierra
└─ Scroll: auto si hay many items

Click en item:
├─ Navega a página
├─ Cierra drawer (slide out)
└─ Duration: 250ms
```

### Floating Action Button (Opcional)

```
Posición: Bottom-right (56px desde bottom, 16px desde right)

┌─────────────────────────────┐
│                             │
│                             │
│                    ┌──┐     │
│                    │ +│     │ Click → Cotizar
│                    │  │     │
│                    └──┘     │
│                             │
│                             │
└─────────────────────────────┘

Propiedades:
├─ Size: 56×56px (44×44px touch target)
├─ Background: #1E40AF (primary)
├─ Icon: + blanco (plus sign)
├─ Shadow: 0 4px 12px rgba(0,0,0,0.15)
├─ Position: fixed, bottom: 72px (sobre bottom nav), right: 16px
├─ Z-index: 100
├─ Border-radius: 28px (circular)
├─ Hover: shadow-lg, opacity 0.9
└─ Click → Navega a /quote

Nota: Aparece solo si NO estamos ya en cotización
```

---

## 5. COMPONENTES DE NAVEGACIÓN

### NavLink (Item de Navegación)

```
┌──────────────────────────────────────┐
│  📋 Mis Pólizas      (Desktop/Tablet)│
├──────────────────────────────────────┤
│                                      │
│ DEFAULT:                             │
│  icon (16px)   text (14px)           │
│  color: Gray-700                     │
│  padding: 12px 16px                  │
│  cursor: pointer                     │
│                                      │
│ HOVER:                               │
│  bg: #F0F6FF (light blue)            │
│  color: #1E40AF (blue)               │
│  duration: 150ms ease                │
│                                      │
│ ACTIVE (Current Page):               │
│  color: #1E40AF (blue)               │
│  border-left: 4px #1E40AF            │
│  bg: #F0F6FF (light blue)            │
│  font-weight: 600 (semibold)         │
│                                      │
│ FOCUS (Keyboard):                    │
│  outline: 2px #1E40AF                │
│  outline-offset: -2px                │
│                                      │
└──────────────────────────────────────┘

Props:
├─ icon: string (icon name)
├─ label: string
├─ href: string (path)
├─ active: boolean
├─ badge?: number (notification count)
├─ onClick: callback
└─ disabled?: boolean
```

### Badge (Notificación en item)

```
┌─────────────────────────┐
│  🚨 Siniestros       [3]│
│                         │
│  ┌──────┐              │
│  │  3   │  Rojo (#EF4444)
│  └──────┘  12px width x 20px height
│            border-radius: 10px
│            font: 12px / 600 bold
│            color: white
│            Position: absolute (top-right del item)
│                                         │
└─────────────────────────────────────────┘
```

### Dropdown Menu

```
HOVER en item con dropdown:

┌──────────────────┐
│ ≡ Soporte        │ Label + ▼ chevron
└──────────────────┘
    ↓ (150ms)
┌──────────────────┐
│ ≡ Soporte      ▲ │
├──────────────────┤
│ • FAQs           │
│ • Chat vivo      │  Sub-items
│ • Llamar         │
│ • Email          │
└──────────────────┘

Propiedades:
├─ Trigger: hover (desktop), click (móvil)
├─ Animation: slideDown 200ms ease
├─ Position: absolute, top: 100%, left: 0
├─ Min-width: 200px
├─ Shadow: 0 4px 6px rgba(0,0,0,0.1)
├─ Z-index: 1000
├─ Background: white
├─ Border: 1px #D1D5DB
├─ Border-radius: 8px
├─ Overflow: close si click fuera
└─ Sub-item padding: 12px 16px
```

### Profile Dropdown

```
┌─────────────┐
│ 👤 ▼        │ Avatar + dropdown arrow
└─────────────┘
    ↓
┌──────────────────┐
│ 👤 Juan Rodríguez│
│ juan@mail.com    │  User info
├──────────────────┤
│ 👤 Mi Perfil     │
│ ⚙️ Configuración │
│ ❓ Ayuda         │
│ 📞 Contacto      │  Options
│ ────────────────  │
│ 🚪 Cerrar Sesión │  Danger zone
│                  │  (Red text)
└──────────────────┘
```

---

## 6. FLUJOS DE USUARIO

### Flujo: Nuevo Usuario Cotiza un Seguro

```
ENTRADA: Usuario abre app en móvil

Screen 1: Home/Dashboard
├─ Bottom nav: 🏠 (active)
├─ Contenido: Bienvenida + CTA "Cotiza en 30s"
├─ Click en CTA → Flujo Cotización
└─ O click en "📋 Pólizas" → Mis Pólizas vacías

Flujo Cotización (Modal/New Page):
  Step 1: Tipo cobertura
  Step 2: Monto y duración
  Step 3: Revisión y precio
  Step 4: Checkout + confirmación
  
  Navegación dentro del wizard:
  ├─ Back button (← volver)
  ├─ Progress bar (25%, 50%, 75%, 100%)
  ├─ Next button (seguir)
  └─ Close button (✕ cancelar)

Screen 5: Confirmación
├─ "¡Póliza emitida!"
├─ Número de póliza
├─ [Descargar PDF]
├─ [Ver en mis pólizas]
└─ Notificación push enviada

De vuelta a Home:
├─ Bottom nav: 📋 (highlight)
├─ Nueva póliza aparece en el grid
└─ Notificación: "Póliza emitida"
```

### Flujo: Usuario Reporta Siniestro

```
ENTRADA: Usuario ve notificación de siniestro pendiente

Screen 1: Dashboard Home
├─ Badge: 🚨 (3) - 3 siniestros nuevos
├─ Click en badge → Notificaciones
└─ O click en 🚨 (bottom nav) → Avisos/Siniestros

Screen 2: Mis Siniestros (List)
├─ Póliza: Viaje Seguro | Estado: NUEVO
├─ [Reportar Detalle]
├─ [Chat en vivo]
└─ Antiguos siniestros (historial)

Screen 3: Reportar Siniestro (Form)
├─ Póliza (pre-llenadO)
├─ Fecha evento
├─ Descripción (textarea)
├─ Fotos (upload)
├─ Monto estimado
├─ [Enviar]

Screen 4: Confirmación
├─ ✓ Reclamo registrado
├─ Número de reclamo: CLM-2026-001234
├─ Status: "Pendiente Revisión"
├─ [Ir a mis reclamos]
└─ [Ir a inicio]

Navegación de regreso:
├─ Bottom nav actualiza badge: 🚨 (3) → 🚨 (2)
├─ Reclamo aparece en "Estado: En Revisión"
└─ Chat está disponible si se necesita
```

### Flujo: Usuario Administra Póliza

```
ENTRADA: Click en "📋 Mis Pólizas" (bottom nav móvil)

Screen 1: Mis Pólizas (Grid)
├─ Filtros: [Todas] [Activas] [Vencidas] [Archivadas]
├─ Tarjeta 1: Viaje | Activa | $45/mes
├─ Tarjeta 2: Dispositivos | Activa | $20/mes
├─ Tarjeta 3: Vida | Vencida (badge rojo)
└─ [+ Agregar nueva póliza]

Click en Tarjeta 1 (Viaje):
  Screen 2: Detalle de Póliza
  ├─ Header: Viaje Seguro | Status: ✓ ACTIVA
  ├─ Información General
  │  ├─ Número: POL-2026-001234
  │  ├─ Vigencia: 15 ene - 14 ene 2027
  │  ├─ Precio: $45/mes
  │  ├─ Renovación: Automática
  │  └─ Próximo pago: 15 de cada mes
  │
  ├─ Cobertura Activa
  │  ├─ ✓ Retraso de vuelo (6+ horas): $200
  │  ├─ ✓ Equipo dañado/perdido: $1M
  │  └─ ✓ Cancelación de viaje: 30% reembolso
  │
  ├─ Acciones
  │  ├─ [Modificar Cobertura]
  │  ├─ [Renovar Ahora]
  │  ├─ [Cancelar]
  │  ├─ [📥 Descargar PDF]
  │  └─ [❓ Ver FAQs]
  │
  └─ Breadcrumb: Home > Mis Pólizas > Viaje

Click en [Modificar Cobertura]:
  Screen 3: Cambiar Cobertura
  ├─ Coberturas disponibles
  ├─ Checkbox: seleccionar/deseleccionar
  ├─ Precio actualiza en tiempo real
  ├─ [Guardar Cambios]
  └─ Confirmación: "Cambio aplicado"

Back to Screen 2 (o Home si cierra):
├─ Bottom nav te lleva de vuelta a 📋
├─ La póliza se actualiza
└─ Notificación: "Cobertura actualizada"
```

---

## 7. ESTADOS Y TRANSICIONES

### Estados de Items de Navegación

```
1. DEFAULT
   └─ color: gray-700
   └─ bg: transparent
   └─ cursor: pointer

2. HOVER (Desktop only)
   └─ bg: #F0F6FF
   └─ color: #1E40AF
   └─ transition: 150ms ease

3. ACTIVE (Current Page)
   └─ color: #1E40AF
   └─ bg: #F0F6FF
   └─ border-left: 4px #1E40AF (sidebar) | border-bottom: 2px (header)
   └─ font-weight: 600

4. FOCUS (Keyboard Navigation)
   └─ outline: 2px #1E40AF
   └─ outline-offset: -2px
   └─ visible en todos los navitems

5. DISABLED
   └─ color: gray-400
   └─ opacity: 0.5
   └─ cursor: not-allowed
   └─ bg: transparent

6. WITH BADGE
   └─ Badge rojo (#EF4444) con número
   └─ Position: top-right del item
   └─ Visible en hover y active
```

### Transiciones de Navegación

```
DESKTOP:
├─ Page transition: fade (100ms)
├─ Sidebar item hover: background (150ms ease)
├─ Dropdown open: slideDown (200ms ease)
├─ Active indicator: slideIn (200ms ease)
└─ Redirect on navigation: no animation

MOBILE:
├─ Drawer open: slideIn from left (250ms ease)
├─ Drawer close: slideOut to left (250ms ease)
├─ Overlay fade: fadeIn/Out (250ms ease)
├─ Bottom nav highlight: color change (200ms ease)
├─ Page transition: fade (100ms)
└─ Scroll to top on navigation: auto smooth
```

---

## 8. ACCESIBILIDAD

### WCAG 2.1 AA Compliance

```
KEYBOARD NAVIGATION:
├─ Tab: Cicla entre items de navegación
├─ Enter/Space: Activa enlaces y botones
├─ Esc: Cierra dropdowns y drawers
├─ Arrow keys: Navega en dropdowns (Arrow Down/Up)
└─ Focus trap: dentro de modales/dropdowns

FOCUS INDICATORS:
├─ Visible en todos los interactive elements
├─ Min 2px solid border
├─ Color: #1E40AF (azul)
├─ Offset: 2px
├─ Never removed with outline: none
└─ Contrast: ≥ 4.5:1 against background

SCREEN READER SUPPORT:
├─ Semantic HTML: <nav>, <ul>, <li>, <a>
├─ ARIA labels:
│  ├─ aria-label="Main navigation"
│  ├─ aria-current="page" (para active item)
│  ├─ aria-expanded="true/false" (para dropdowns)
│  └─ aria-label="Open menu" (para hamburger)
│
├─ Icons with text labels (nunca solo icono)
├─ Badge aria-label:
│  └─ <span aria-label="3 new notifications">3</span>
│
└─ Hidden content: aria-hidden="true" si es decorativo

COLOR & CONTRAST:
├─ Active item text: ≥ 4.5:1
├─ Icons + text (no solo color para status)
├─ Ejemplo: ✓ (verde) + "Activa" (text)
└─ Never rely on color alone

TOUCH TARGETS (Mobile):
├─ Min size: 44×44px
├─ Bottom nav items: 56×56px (ideal)
├─ Sidebar items: 56px height min
├─ Spacing: ≥ 8px entre targets
└─ Hamburger button: 48×48px
```

### Testing Checklist

```
✓ Keyboard-only navigation (no mouse)
✓ Screen reader (NVDA, JAWS, VoiceOver)
✓ Focus indicators always visible
✓ Color contrast ≥ 4.5:1
✓ Touch targets ≥ 44×44px (mobile)
✓ ARIA labels completes
✓ Zoom test (200% magnification)
✓ Dark mode support (if applicable)
```

---

## 9. ESPECIFICACIONES TÉCNICAS

### Desktop Sizes

```
Header:
├─ Height: 64px
├─ Logo width: 48px
├─ Nav item padding: 16px 20px
├─ Font: 16px / 400 (Inter)
└─ Z-index: 100

Sidebar:
├─ Width: 240px
├─ Padding: 16px
├─ Item height: 40px (default), 36px (sub-item)
├─ Gap between groups: 16px
├─ Divider: 1px #D1D5DB
└─ Z-index: 100

Main Content:
├─ Max-width: 1280px (container)
├─ Padding: 24px
├─ Margin-left: 240px (sidebar width)
└─ Responsive: 100% width on < 768px
```

### Mobile Sizes

```
Header:
├─ Height: 56px
├─ Hamburger: 48×48px (touch), 24×24px (icon)
├─ Notification badge: width auto, min 20px, height 20px
├─ Profile avatar: 40×40px
└─ Z-index: 100

Bottom Navigation:
├─ Height: 56px
├─ Items: 5 max (each 56×56px touch target)
├─ Icon size: 24px
├─ Label font: 12px / 400
├─ Gap between icon and label: 4px
└─ Z-index: 50

Drawer/Hamburger Menu:
├─ Width: 280px (~ 75% of screen)
├─ Height: 100vh
├─ Item height: 52px
├─ Item padding: 12px 16px
├─ Z-index: 999
└─ Overlay z-index: 998

Floating Action Button:
├─ Size: 56×56px
├─ Icon: 24px
├─ Position: fixed, bottom: 72px, right: 16px
├─ Border-radius: 28px (full circle)
└─ Z-index: 100
```

### Breakpoints

```
┌────────────────────────────────────┐
│ MOBILE              TABLET  DESKTOP│
│ < 768px         768-1023px  1024+ │
├────────────────────────────────────┤
│                                    │
│ Bottom Nav      Sidebar/Header Full│
│ Hamburger       Hidden Sidebar    │
│ Full-width      (transitions to   │
│ cards           visible)          │
│                                    │
│ Drawer overlay  More horizontal   │
│                 nav items         │
│                                    │
└────────────────────────────────────┘
```

### CSS Variables (Design Tokens)

```css
/* Navigation Colors */
--nav-link-text-default: #374151;
--nav-link-text-active: #1E40AF;
--nav-link-bg-hover: #F0F6FF;
--nav-link-bg-active: #F0F6FF;
--nav-link-border-active: #1E40AF;

/* Sizing */
--nav-header-height: 64px;
--nav-header-height-mobile: 56px;
--nav-sidebar-width: 240px;
--nav-bottom-nav-height: 56px;
--nav-item-padding: 12px 16px;

/* Z-Index */
--z-nav-header: 100;
--z-nav-sidebar: 100;
--z-nav-drawer-overlay: 998;
--z-nav-drawer: 999;
--z-nav-dropdown: 1000;

/* Transitions */
--nav-transition-fast: 150ms ease;
--nav-transition-base: 200ms ease;
--nav-transition-slow: 250ms ease;
```

---

## RESUMEN: DIFERENCIAS CLAVE

```
                   DESKTOP          TABLET           MÓVIL
────────────────────────────────────────────────────────
Layout          Sidebar           Sidebar (overlay) Bottom nav
                + Header          + Header          + Header

Nav Primary     Horizontal        Horizontal       Bottom nav
                (header)          (header)         (5 items)

Nav Secondary   Vertical          Hamburger        Hamburger
                (sidebar)         drawer           drawer

Header Height   64px              64px             56px
Sidebar Width   240px             280px overlay    N/A (drawer)

Interaction     Hover             Click            Touch/Tap
Focus Style     Border bottom     Border left      Border left

Dropdowns       Hover             Click            Click
Drawer          Persistent        Modal overlay    Modal overlay

Max Nav Items   7 (main)          5 (header)       4-5 (bottom)
                + 4-5 (sub)       + unlimited      + unlimited
                                  (drawer)         (drawer)
```

---

## IMPLEMENTACIÓN (React)

### Componentes Base

```tsx
// Navigation.tsx
export interface NavItem {
  id: string;
  label: string;
  icon: string;
  href: string;
  badge?: number;
  children?: NavItem[];
  isExternal?: boolean;
}

export const Navigation = () => {
  // Desktop: Sidebar + Header
  // Tablet: Header con hamburger
  // Mobile: Header + Bottom nav + Drawer
};

// NavLink.tsx
export const NavLink = ({
  icon,
  label,
  href,
  active,
  badge,
  onClick,
}: NavLinkProps) => {
  return (
    <a
      href={href}
      className={cx('nav-link', { active })}
      aria-current={active ? 'page' : undefined}
    >
      <Icon name={icon} size={16} />
      {label}
      {badge && <Badge>{badge}</Badge>}
    </a>
  );
};

// BottomNav.tsx (Mobile)
export const BottomNav = () => {
  return (
    <nav className="bottom-nav">
      {items.map(item => (
        <NavLink key={item.id} {...item} />
      ))}
    </nav>
  );
};

// Drawer.tsx (Mobile Hamburger)
export const Drawer = ({ open, onClose }) => {
  return (
    <>
      {open && <Overlay onClick={onClose} />}
      <aside className={cx('drawer', { open })}>
        {/* Nav items */}
      </aside>
    </>
  );
};
```

---

## CONCLUSIÓN

Este sistema de navegación de Solventa:

✅ **Rápido:** Max 2 clics para cualquier pantalla  
✅ **Claro:** Labels explícitos, indicadores visuales claros  
✅ **Accesible:** WCAG 2.1 AA compliant  
✅ **Responsive:** Adapta a móvil, tablet, desktop  
✅ **Intuitivo:** Patrones estándar de industria  
✅ **Flexible:** Maneja múltiples niveles de navegación  

---

**Versión:** 1.0 | **Fecha:** 2026-01-15 | **Estado:** Listo para Implementación
