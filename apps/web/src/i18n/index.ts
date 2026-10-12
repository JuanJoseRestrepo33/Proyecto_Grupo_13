import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

import en from './en.json'
import es from './es.json'

/** Idiomas soportados y su locale para formatear montos y fechas. */
export const IDIOMAS = { es: 'es-CO', en: 'en-US' } as const
export type Idioma = keyof typeof IDIOMAS

/** Misma clave que usan los mockups, para que la preferencia se conserve. */
const CLAVE_IDIOMA = 'solventa_locale'

export function idiomaGuardado(): Idioma {
  try {
    return localStorage.getItem(CLAVE_IDIOMA) === 'en' ? 'en' : 'es'
  } catch {
    return 'es'
  }
}

export async function cambiarIdioma(idioma: Idioma): Promise<void> {
  try {
    localStorage.setItem(CLAVE_IDIOMA, idioma)
  } catch {
    // Sin almacenamiento disponible: el idioma aplica solo a esta visita.
  }
  document.documentElement.lang = idioma
  await i18n.changeLanguage(idioma)
}

const idiomaInicial = idiomaGuardado()
// Los lectores de pantalla pronuncian según <html lang>: debe coincidir con el idioma restaurado.
document.documentElement.lang = idiomaInicial

void i18n.use(initReactI18next).init({
  resources: { es: { translation: es }, en: { translation: en } },
  lng: idiomaInicial,
  fallbackLng: 'es',
  interpolation: { escapeValue: false },
})

export default i18n
