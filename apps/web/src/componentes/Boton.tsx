import type { ButtonHTMLAttributes, Ref } from 'react'

import estilos from './Boton.module.css'

export type VarianteBoton = 'primario' | 'secundario' | 'fantasma'

/** Clases del botón, también para enlaces que deben verse como botón. */
function clasesBoton(variante: VarianteBoton = 'primario'): string {
  return `${estilos.boton} ${estilos[variante]}`
}

interface PropiedadesBoton extends ButtonHTMLAttributes<HTMLButtonElement> {
  variante?: VarianteBoton
  ref?: Ref<HTMLButtonElement>
}

export function Boton({ variante = 'primario', className, type = 'button', ...resto }: PropiedadesBoton) {
  return <button type={type} className={`${clasesBoton(variante)} ${className ?? ''}`.trim()} {...resto} />
}
