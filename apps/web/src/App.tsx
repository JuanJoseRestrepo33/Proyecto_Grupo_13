import { Route, Routes } from 'react-router'

import { Plantilla } from './componentes/Plantilla'
import { Consentimientos } from './paginas/consentimientos/Consentimientos'
import { Cotizacion } from './paginas/cotizacion/Cotizacion'
import { Inicio } from './paginas/Inicio'
import { NoEncontrada } from './paginas/NoEncontrada'
import { Suscripcion } from './paginas/suscripcion/Suscripcion'

export function App() {
  return (
    <Routes>
      <Route element={<Plantilla />}>
        <Route index element={<Inicio />} />
        <Route path="cotizacion" element={<Cotizacion />} />
        <Route path="consentimientos" element={<Consentimientos />} />
        <Route path="suscripcion/:cotizacion" element={<Suscripcion />} />
        <Route path="*" element={<NoEncontrada />} />
      </Route>
    </Routes>
  )
}
