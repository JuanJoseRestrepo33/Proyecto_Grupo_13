import { Route, Routes } from 'react-router'

import { Plantilla } from './componentes/Plantilla'
import { Consentimientos } from './paginas/consentimientos/Consentimientos'
import { Cotizacion } from './paginas/cotizacion/Cotizacion'
import { Inicio } from './paginas/Inicio'
import { NoEncontrada } from './paginas/NoEncontrada'
import { InicioSesion } from './paginas/sesion/InicioSesion'
import { Suscripcion } from './paginas/suscripcion/Suscripcion'
import { ProveedorSesion } from './sesion/ProveedorSesion'
import { RutaProtegida } from './sesion/RutaProtegida'

export function App() {
  return (
    <ProveedorSesion>
      <Routes>
        <Route element={<Plantilla />}>
          <Route path="ingresar" element={<InicioSesion />} />
          <Route element={<RutaProtegida />}>
            <Route index element={<Inicio />} />
            <Route path="cotizacion" element={<Cotizacion />} />
            <Route path="consentimientos" element={<Consentimientos />} />
            <Route path="suscripcion/:cotizacion" element={<Suscripcion />} />
          </Route>
          <Route path="*" element={<NoEncontrada />} />
        </Route>
      </Routes>
    </ProveedorSesion>
  )
}
