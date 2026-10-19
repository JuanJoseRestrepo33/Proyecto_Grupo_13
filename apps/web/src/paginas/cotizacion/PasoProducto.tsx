import { useTranslation } from 'react-i18next'

import type { CodigoProducto, Producto } from '../../api/tipos'
import { formatearMoneda } from '../../formato'
import type { Idioma } from '../../i18n'
import estilos from './Cotizacion.module.css'

interface Propiedades {
  productos: Producto[]
  seleccionado: CodigoProducto | null
  error: string | null
  onSeleccionar: (producto: CodigoProducto) => void
}

export function PasoProducto({ productos, seleccionado, error, onSeleccionar }: Propiedades) {
  const { t, i18n } = useTranslation()
  return (
    <fieldset className={estilos.opciones} aria-describedby={error ? 'error-producto' : undefined}>
      <legend className="sr-only">{t('cotizacion.producto.titulo')}</legend>
      {productos.map((producto) => (
        <label key={producto.codigo} className={estilos.opcion}>
          <input
            type="radio"
            name="producto"
            value={producto.codigo}
            checked={seleccionado === producto.codigo}
            onChange={() => onSeleccionar(producto.codigo)}
          />
          <strong>{producto.nombre_corto}</strong>
          <span>{producto.descripcion}</span>
          <span>
            {t('cotizacion.producto.desde', {
              prima: formatearMoneda(producto.prima_estandar, i18n.language as Idioma),
            })}
          </span>
        </label>
      ))}
      {error && (
        <p id="error-producto" className={estilos.error} role="alert">
          {t(error)}
        </p>
      )}
    </fieldset>
  )
}
