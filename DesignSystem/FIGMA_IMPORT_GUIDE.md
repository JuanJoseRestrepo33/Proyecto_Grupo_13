# 📦 Guía de Importación - Solventa Design System a Figma

## Archivos Incluidos

```
✓ Solventa_System_Design.pdf (134 KB)
  └─ Arquitectura técnica completa, APIs, escalabilidad

✓ Solventa_Design_System.pdf (79 KB)
  └─ Guía visual, componentes, patrones UI/UX

✓ solventa_design_system.json (7 KB)
  └─ Estructura de tokens para Figma (colores, tipografía, spacing)

✓ solventa_design_system.html (21 KB)
  └─ Prototipo interactivo del design system (abrir en navegador)
```

---

## Opción 1: Importar JSON a Figma (Recomendado)

### Paso 1: Crear un archivo Figma
1. Abre Figma (figma.com)
2. Crea un nuevo proyecto o abre uno existente
3. Crea un nuevo file: "Solventa Design System"

### Paso 2: Importar Design Tokens
1. En el Design System, ve a **Assets** → **Design Tokens**
2. Haz clic en **+** para crear nuevo token set
3. Copia el contenido de `solventa_design_system.json` 
4. Pega en Figma (puede variar según la versión)

### Alternativa (Figma Tokens Plugin):
1. Instala el plugin **"Figma Tokens"** (gratuito)
2. Abre el plugin en tu proyecto
3. Importa directamente `solventa_design_system.json`
4. Los tokens estarán disponibles en tu proyecto

### Paso 3: Crear Componentes Manualmente (basándote en la guía)

Basándote en `Solventa_Design_System.pdf`:

#### **Button Component**
```
1. Crea un componente "Button"
2. Variantes: 
   ├─ Primary (bg: #1E40AF, text: white)
   ├─ Secondary (bg: #F9FAFB, border: 2px blue)
   ├─ Ghost (bg: transparent)
   ├─ Disabled (bg: #D1D5DB, opacity: 0.6)
   └─ Loading (spinner animated)
3. Tamaños: sm, md, lg
4. Estados: default, hover, active, disabled
5. Iconos: left, right, none
```

#### **Input Component**
```
1. Crea un componente "Input"
2. Variantes:
   ├─ Default (border: 1px #D1D5DB)
   ├─ Focus (border: 2px #1E40AF, shadow)
   ├─ Error (border: 2px #EF4444, bg: #FEE2E2)
   ├─ Success (border: 2px #10B981, bg: #F0FDF4)
   └─ Disabled (bg: #F3F4F6, opacity: 0.6)
3. Label arriba (auto-layout horizontal)
4. Hint text debajo
5. Error message (red, conditional)
```

#### **Card Component**
```
1. Crea un componente "Card"
2. Propiedades:
   ├─ bg: white
   ├─ border: 1px #D1D5DB
   ├─ padding: 20px
   ├─ border-radius: 8px
   ├─ shadow: 0 1px 3px rgba(0,0,0,0.1)
   └─ shadow-hover: 0 4px 6px rgba(0,0,0,0.1)
3. Auto-layout: vertical, gap 16px
4. Variantes: Default, Info, Alert, Success, Error
```

---

## Opción 2: Usar el Prototipo HTML Interactivo

### Abrir en navegador
```bash
# Abre solventa_design_system.html en cualquier navegador
open solventa_design_system.html
```

### Copiar colores
1. Haz clic en cualquier color en la paleta
2. Copia el código hex (#1E40AF)
3. Pégalo en Figma (click en color → "Hex")

### Copiar tipografía
- **Font:** Inter (descárgalo de Google Fonts si no lo tienes)
- **Tamaños:** copia del HTML y ajusta en Figma
- **Weights:** 400, 500, 600, 700

### Copiar valores de espaciado
- 4px, 8px, 16px, 24px, 32px, 48px, 64px
- Configura auto-layout gaps con estos valores

---

## Opción 3: Crear Design File desde cero (Paso a Paso)

### FASE 1: Configuración Base (30 min)

**Paso 1: Crear pages**
```
┌─ 📄 1. Colors (paleta completa)
├─ 📄 2. Typography (escala completa)
├─ 📄 3. Spacing & Grid (8px sistema)
├─ 📄 4. Shadows & Elevation
├─ 📄 5. Components
│  ├─ Button
│  ├─ Input
│  ├─ Card
│  ├─ Badge
│  ├─ Modal
│  └─ Toast
├─ 📄 6. Patterns
│  ├─ Wizard (4 pasos)
│  ├─ Notification
│  └─ Form Layout
└─ 📄 7. Screens (wireframes)
   ├─ Desktop
   ├─ Tablet
   └─ Mobile
```

**Paso 2: Create color styles**
```
En Assets → Colors:
├─ Primary/900 (#0F2F6F)
├─ Primary/700 (#1E40AF)
├─ Primary/50 (#F0F6FF)
├─ Success (#10B981)
├─ Warning (#F59E0B)
├─ Error (#EF4444)
├─ Text/Primary (#111827)
├─ Text/Secondary (#374151)
├─ Border (#D1D5DB)
└─ BG (#F9FAFB)
```

**Paso 3: Create typography styles**
```
En Assets → Typography:
├─ H1/48px/700 (Inter Bold)
├─ H2/36px/600 (Inter Semibold)
├─ Body/16px/400 (Inter Regular)
├─ Label/12px/600 (Inter Semibold)
└─ Code/12px/400 (IBM Plex Mono)
```

### FASE 2: Componentes (2-3 horas)

Ver sección anterior "Crear Componentes Manualmente"

### FASE 3: Patterns & Layouts (1-2 horas)

Basándote en `Solventa_Design_System.pdf`:
- Quotation Wizard (4 pasos con progress bar)
- Policy detail view (layout desktop + mobile)
- Claim form (multi-field form)
- Dashboard (cards grid)

### FASE 4: Prototyping (1 hora)

Conecta los frames con prototyping:
```
1. Quotation page → Step 2 (on Next button click)
2. Step 2 → Step 3 (on Next button click)
3. Step 3 → Confirmation (on Confirm button click)
4. Links en componentes → Detail views
```

---

## Estructura Recomendada en Figma

```
📁 Solventa Design System
├─ 🎨 Colors & Tokens
│  ├─ Primary palette
│  ├─ Semantic (Success, Error, Warning)
│  ├─ Neutrals
│  └─ Data visualization
│
├─ 🔤 Typography
│  ├─ Desktop scale (H1-Caption)
│  ├─ Mobile scale
│  └─ Mono (code)
│
├─ 📐 Layout System
│  ├─ 8px spacing scale
│  ├─ 12-column grid
│  ├─ Margins & padding
│  └─ Breakpoints
│
├─ ⚙️ Components
│  ├─ Primitives
│  │  ├─ Button (all variants)
│  │  ├─ Input (all states)
│  │  ├─ Card
│  │  ├─ Badge
│  │  ├─ Icon
│  │  └─ Avatar
│  │
│  └─ Composite
│     ├─ Form group
│     ├─ Navbar
│     ├─ Footer
│     └─ Modal
│
├─ 🎯 Patterns
│  ├─ Quotation wizard
│  ├─ Policy detail
│  ├─ Claim form
│  └─ Notifications
│
└─ 📱 Screens
   ├─ Desktop (1024px)
   ├─ Tablet (768px)
   └─ Mobile (375px)
```

---

## Comandos Útiles en Figma

### Crear Grid de 12 columnas
```
1. Insert → Frame (1024px width)
2. Clic derecho → Layout grid
3. Grid type: Columns
4. Count: 12
5. Gutter: 16px
6. Left/Right: 24px
```

### Crear auto-layout (Button)
```
1. Select button frame
2. Shift + A (o Layout panel)
3. Direction: Horizontal
4. Gap: 8px (entre icon + text)
5. Padding: 12px (vertical), 24px (horizontal)
6. Align: Center
```

### Crear componente
```
1. Select element
2. Ctrl+Alt+K (o Right-click → Create component)
3. Rename: Button (este es el main)
4. Create variants (Ctrl+Alt+V)
```

---

## Checklists para Figma

### ✅ Antes de finalizar
- [ ] Todos los componentes tienen variantes (hover, active, disabled)
- [ ] Color styles aplicados (no colores hardcoded)
- [ ] Typography styles aplicados (no font sizes hardcoded)
- [ ] Auto-layout en todos los componentes
- [ ] Documentación en cada página
- [ ] Grid visible en wireframes (Debug: Cmd+')
- [ ] Contraste de colores verificado
- [ ] Componentes testeados en móvil
- [ ] Design handed off (publicado)

### ✅ Exportación para desarrollo
- [ ] Componentes SVG exportados
- [ ] Colors exported as CSS variables
- [ ] Typography CSS styles generadas
- [ ] Spacing tokens en JSON
- [ ] Specs documentadas (padding, gap, etc.)

---

## Videos/Tutoriales Recomendados

- **Figma Tokens Plugin:** https://www.figma.com/community/plugin/843461159747178978
- **Component Best Practices:** https://help.figma.com/en/articles/5679564
- **Design Systems:** https://help.figma.com/en/articles/5794793

---

## Soporte

Si encuentras problemas importando a Figma:

1. **Tokens no se actualizan:** Reinstala el plugin Figma Tokens
2. **Componentes se pierden:** Asegúrate de tener permisos de edit
3. **Colors no iguales:** Verifica que HEX sea exacto (ej: #1E40AF vs #1e40af)
4. **Tipografía diferente:** Descarga Inter font en tu sistema

---

## Próximos Pasos

1. ✅ Importa los colors y typography tokens
2. ✅ Crea los componentes base (Button, Input, Card)
3. ✅ Documental cada componente con specs
4. ✅ Crea wireframes de las pantallas principales
5. ✅ Haz handoff a development (genera specs)
6. ✅ Mantenimiento: actualiza versiones cuando hay cambios

---

**Versión:** 1.0 | **Última actualización:** 2026-01-15  
**Documentación:** Ver `Solventa_Design_System.pdf`  
**Arquitectura:** Ver `Solventa_System_Design.pdf`
