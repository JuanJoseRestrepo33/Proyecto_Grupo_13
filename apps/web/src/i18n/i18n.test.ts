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

describe('idioma al cargar la aplicación', () => {
  // Cada caso vuelve a cargar el módulo para ejecutar su inicialización desde cero.
  beforeEach(() => vi.resetModules())
  afterEach(() => localStorage.removeItem('solventa_locale'))

  it.each([
    ['en', 'en'],
    [null, 'es'],
  ])('con preferencia guardada %s, <html lang> queda en %s y la interfaz en ese idioma', async (guardado, esperado) => {
    if (guardado) localStorage.setItem('solventa_locale', guardado)
    else localStorage.removeItem('solventa_locale')
    document.documentElement.lang = ''

    const { default: instancia } = await import('.')

    expect(document.documentElement.lang).toBe(esperado)
    expect(instancia.language).toBe(esperado)
  })
})
