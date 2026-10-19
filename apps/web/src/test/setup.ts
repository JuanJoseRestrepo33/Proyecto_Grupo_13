import '@testing-library/jest-dom/vitest'
import '../i18n'

import { cleanup } from '@testing-library/react'

import i18n from '../i18n'
import { reiniciarEstado } from '../mocks/estado'
import { servidor } from '../mocks/servidor'

beforeAll(() => servidor.listen({ onUnhandledRequest: 'error' }))

beforeEach(async () => {
  reiniciarEstado()
  await i18n.changeLanguage('es')
})

afterEach(() => {
  cleanup()
  servidor.resetHandlers()
})

afterAll(() => servidor.close())
