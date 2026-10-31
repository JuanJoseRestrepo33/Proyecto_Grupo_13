import { formatearTamano, revisarArchivo, TAMANO_MAXIMO } from './evidencias'

/** Un archivo del tamaño dado, sin reservar esa memoria. */
function archivo(nombre: string, tipo: string, tamano = 1024): File {
  const resultado = new File(['x'], nombre, { type: tipo })
  Object.defineProperty(resultado, 'size', { value: tamano })
  return resultado
}

describe('revisarArchivo', () => {
  it.each([
    ['foto.jpg', 'image/jpeg'],
    ['foto.JPEG', 'image/jpeg'],
    ['captura.png', 'image/png'],
    ['video.mp4', 'video/mp4'],
    ['factura.pdf', 'application/pdf'],
    // El navegador suele no conocer el tipo de HEIC: basta con la extensión.
    ['foto.heic', ''],
    // O con el tipo, aunque la extensión no lo diga.
    ['documento', 'application/pdf'],
  ])('admite %s (%s)', (nombre, tipo) => {
    expect(revisarArchivo(archivo(nombre, tipo))).toBeNull()
  })

  it.each([
    ['animacion.gif', 'image/gif'],
    ['notas.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['sin-extension', ''],
  ])('rechaza el formato de %s', (nombre, tipo) => {
    expect(revisarArchivo(archivo(nombre, tipo))).toBe('formato')
  })

  it('admite hasta 10 MB exactos y rechaza un byte más', () => {
    expect(revisarArchivo(archivo('video.mp4', 'video/mp4', TAMANO_MAXIMO))).toBeNull()
    expect(revisarArchivo(archivo('video.mp4', 'video/mp4', TAMANO_MAXIMO + 1))).toBe('tamano')
  })

  it('rechaza un archivo vacío', () => {
    expect(revisarArchivo(archivo('foto.jpg', 'image/jpeg', 0))).toBe('vacio')
  })
})

describe('formatearTamano', () => {
  it('usa bytes por debajo de 1 kB, kB por debajo de 1 MB y MB desde ahí, según el idioma', () => {
    expect(formatearTamano(45, 'es')).toBe('45 B')
    expect(formatearTamano(830 * 1024, 'es')).toMatch(/^830\s?kB$/i)
    expect(formatearTamano(2.4 * 1024 * 1024, 'es')).toMatch(/^2,4\s?MB$/)
    expect(formatearTamano(TAMANO_MAXIMO, 'en')).toMatch(/^10\s?MB$/)
  })
})
