import { claveDeError } from './errores'

describe('claveDeError', () => {
  it('traduce los códigos conocidos y usa un mensaje general para el resto', () => {
    expect(claveDeError('consentimiento_revocado')).toBe('consentimientos.errores.consentimiento_revocado')
    expect(claveDeError('otro_codigo')).toBe('consentimientos.errores.general')
    expect(claveDeError(undefined)).toBe('consentimientos.errores.general')
  })
})
