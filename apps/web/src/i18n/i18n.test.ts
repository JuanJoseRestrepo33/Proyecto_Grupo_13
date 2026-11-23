import en from './en.json'
import es from './es.json'
import { idiomaGuardado } from '.'

function claves(objeto: object, prefijo = ''): string[] {
  return Object.entries(objeto).flatMap(([clave, valor]) =>
    typeof valor === 'object' ? claves(valor, `${prefijo}${clave}.`) : [`${prefijo}${clave}`],
  )
}

/** Código fuente de la app (sin pruebas), para buscar las claves que usa. */
const fuentes = import.meta.glob<string>(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!../api/esquema.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
})

describe('i18n', () => {
  it('español e inglés tienen exactamente las mismas claves', () => {
    expect(claves(en).sort()).toEqual(claves(es).sort())
  })

  it('cada clave literal que usa el código existe en los textos', () => {
    const existentes = new Set(claves(es))
    const faltantes = Object.entries(fuentes).flatMap(([archivo, codigo]) =>
      [...codigo.matchAll(/\bt\(\s*['"]([\w.]+)['"]/g)]
        .map(([, clave]) => clave!)
        .filter((clave) => !existentes.has(clave))
        .map((clave) => `${archivo}: ${clave}`),
    )
    expect(faltantes).toEqual([])
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
