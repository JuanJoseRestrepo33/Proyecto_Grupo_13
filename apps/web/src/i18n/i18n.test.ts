import en from './en.json'
import es from './es.json'
import { idiomaGuardado } from '.'

function claves(objeto: object, prefijo = ''): string[] {
  return Object.entries(objeto).flatMap(([clave, valor]) =>
    typeof valor === 'object' ? claves(valor, `${prefijo}${clave}.`) : [`${prefijo}${clave}`],
  )
}

describe('i18n', () => {
  it('español e inglés tienen exactamente las mismas claves', () => {
    expect(claves(en).sort()).toEqual(claves(es).sort())
  })

  it('usa español si no hay preferencia o si el almacenamiento falla', () => {
    localStorage.removeItem('solventa_locale')
    expect(idiomaGuardado()).toBe('es')

    const original = Storage.prototype.getItem
    Storage.prototype.getItem = () => {
      throw new Error('bloqueado')
    }
    try {
      expect(idiomaGuardado()).toBe('es')
    } finally {
      Storage.prototype.getItem = original
    }
  })
})
