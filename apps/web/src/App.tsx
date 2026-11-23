import { Route, Routes } from 'react-router'

import { Plantilla } from './componentes/Plantilla'
import { Consentimientos } from './paginas/consentimientos/Consentimientos'
import { Cotizacion } from './paginas/cotizacion/Cotizacion'
import { Inicio } from './paginas/Inicio'
import { NoEncontrada } from './paginas/NoEncontrada'
import { DetallePoliza } from './paginas/polizas/DetallePoliza'
import { MisPolizas } from './paginas/polizas/MisPolizas'
import { InicioSesion } from './paginas/sesion/InicioSesion'
import { DetalleSiniestro } from './paginas/siniestro/DetalleSiniestro'
import { EvidenciasSiniestro } from './paginas/siniestro/EvidenciasSiniestro'
import { MisSiniestros } from './paginas/siniestro/MisSiniestros'
import { ReporteSiniestro } from './paginas/siniestro/ReporteSiniestro'
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
            <Route path="polizas" element={<MisPolizas />} />
            <Route path="polizas/:numero" element={<DetallePoliza />} />
            <Route path="siniestros" element={<MisSiniestros />} />
            <Route path="siniestros/reportar" element={<ReporteSiniestro />} />
            <Route path="siniestros/evidencias" element={<EvidenciasSiniestro />} />
            <Route path="siniestros/:id" element={<DetalleSiniestro />} />
          </Route>
          <Route path="*" element={<NoEncontrada />} />
        </Route>
      </Routes>
    </ProveedorSesion>
  )
}
