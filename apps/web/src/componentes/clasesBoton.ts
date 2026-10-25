import estilos from './Boton.module.css'

export type VarianteBoton = 'primario' | 'secundario' | 'fantasma'

/** Clases del botón, también para enlaces que deben verse como botón. */
export function clasesBoton(variante: VarianteBoton = 'primario'): string {
  return `${estilos.boton} ${estilos[variante]}`
}
