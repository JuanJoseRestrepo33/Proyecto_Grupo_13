import { Route, Routes } from 'react-router'

import { Plantilla } from './componentes/Plantilla'
import { EnConstruccion } from './paginas/EnConstruccion'
import { Inicio } from './paginas/Inicio'
import { NoEncontrada } from './paginas/NoEncontrada'

export function App() {
  return (
    <Routes>
      <Route element={<Plantilla />}>
        <Route index element={<Inicio />} />
        <Route path="cotizacion" element={<EnConstruccion seccion="cotizacion" />} />
        <Route path="consentimientos" element={<EnConstruccion seccion="consentimientos" />} />
        <Route path="*" element={<NoEncontrada />} />
      </Route>
    </Routes>
  )
}
