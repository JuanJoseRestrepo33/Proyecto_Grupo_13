import type { ButtonHTMLAttributes, Ref } from 'react'

import { clasesBoton, type VarianteBoton } from './clasesBoton'

interface PropiedadesBoton extends ButtonHTMLAttributes<HTMLButtonElement> {
  variante?: VarianteBoton
  ref?: Ref<HTMLButtonElement>
}

export function Boton({ variante = 'primario', className, type = 'button', ...resto }: PropiedadesBoton) {
  return <button type={type} className={`${clasesBoton(variante)} ${className ?? ''}`.trim()} {...resto} />
}
