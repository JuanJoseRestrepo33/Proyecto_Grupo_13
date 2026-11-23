/// <reference types="node" />
import '@testing-library/jest-dom/vitest'
import '../i18n'

import { File as FileDeNode } from 'node:buffer'

import { cleanup } from '@testing-library/react'

import i18n from '../i18n'
import { reiniciarEstado } from '../mocks/estado'
import { servidor } from '../mocks/servidor'

// jsdom trae su propio File y FormData, pero las solicitudes las arma el fetch de Node (undici),
// que al leer un multipart solo reconoce los suyos: sin esto, los mocks no pueden leer la evidencia
// adjunta. El Blob de jsdom se deja, porque Vitest lo adapta al fetch. En el navegador todo viene
// del mismo lado y no hace falta.
const FormDataDeNode = (await new Response(new URLSearchParams()).formData()).constructor
Object.assign(globalThis, { File: FileDeNode, FormData: FormDataDeNode })

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
