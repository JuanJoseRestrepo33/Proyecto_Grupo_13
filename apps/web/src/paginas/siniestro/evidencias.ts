/**
 * Evidencia de un siniestro (MOB-F05): revisión de cada archivo antes de enviarlo (criterio 4) y
 * carga al BFF. El BFF vuelve a validar, y comprueba el formato con el contenido del archivo.
 */
import { bff } from '../../api/cliente'
import type { Evidencia, SolicitudEvidencia } from '../../api/tipos'
import { IDIOMAS, type Idioma } from '../../i18n'

/** 10 MB, el mismo límite del BFF. */
export const TAMANO_MAXIMO = 10 * 1024 * 1024

/** Formatos admitidos por el contrato, con sus extensiones. */
const FORMATOS: Record<Evidencia['tipo_contenido'], string[]> = {
  'image/jpeg': ['jpg', 'jpeg'],
  'image/png': ['png'],
  'image/heic': ['heic'],
  'video/mp4': ['mp4'],
  'application/pdf': ['pdf'],
}

/** Valor del atributo `accept` del selector de archivos. */
export const ACEPTA = Object.entries(FORMATOS)
  .flatMap(([tipo, extensiones]) => [tipo, ...extensiones.map((e) => `.${e}`)])
  .join(',')

export type Rechazo = 'formato' | 'tamano' | 'vacio'

/**
 * Por qué no se puede enviar el archivo, o `null` si se puede. El navegador no siempre conoce el
 * tipo (HEIC suele llegar sin él), así que basta con el tipo o con la extensión.
 */
export function revisarArchivo(archivo: File): Rechazo | null {
  if (archivo.size === 0) return 'vacio'
  if (archivo.size > TAMANO_MAXIMO) return 'tamano'
  const extension = archivo.name.split('.').pop()?.toLowerCase() ?? ''
  const admitido = Object.entries(FORMATOS).some(
    ([tipo, extensiones]) => archivo.type === tipo || extensiones.includes(extension),
  )
  return admitido ? null : 'formato'
}

/** Tamaño legible: "2,4 MB", "830 kB", "45 B". */
export function formatearTamano(bytes: number, idioma: Idioma): string {
  // Intl escribe "45 byte" en español; el símbolo B se lee igual en los dos idiomas.
  if (bytes < 1024) return `${new Intl.NumberFormat(IDIOMAS[idioma]).format(bytes)} B`
  const [valor, unidad] = bytes >= 1024 * 1024 ? [bytes / (1024 * 1024), 'megabyte'] : [bytes / 1024, 'kilobyte']
  return new Intl.NumberFormat(IDIOMAS[idioma], {
    style: 'unit',
    unit: unidad,
    maximumFractionDigits: 1,
  }).format(valor)
}

/** Por qué no se adjuntó: los rechazos del BFF, o `error` si no hubo respuesta o fue otro fallo. */
export type FalloCarga = Rechazo | 'cerrado' | 'almacenamiento' | 'error'

export type ResultadoCarga = { ok: true; evidencia: Evidencia } | { ok: false; fallo: FalloCarga }

const FALLOS: Record<string, FalloCarga> = {
  archivo_demasiado_grande: 'tamano',
  formato_no_admitido: 'formato',
  archivo_vacio: 'vacio',
  siniestro_cerrado: 'cerrado',
  almacenamiento_no_disponible: 'almacenamiento',
}

/**
 * El archivo con un tipo admitido. El BFF exige que el tipo declarado sea uno de los admitidos y que
 * coincida con el contenido; si el navegador no lo conoce (HEIC suele llegar sin él), se toma de la
 * extensión que ya validó `revisarArchivo`. El BFF sigue comprobando el contenido real.
 */
function conTipoAdmitido(archivo: File): File {
  if (archivo.type in FORMATOS) return archivo
  const extension = archivo.name.split('.').pop()?.toLowerCase() ?? ''
  const tipo = Object.entries(FORMATOS).find(([, extensiones]) => extensiones.includes(extension))?.[0]
  return tipo ? new File([archivo], archivo.name, { type: tipo, lastModified: archivo.lastModified }) : archivo
}

export async function subirEvidencia(idSiniestro: string, archivo: File): Promise<ResultadoCarga> {
  const formulario = new FormData()
  formulario.append('archivo', conTipoAdmitido(archivo), archivo.name)
  try {
    const { data, error } = await bff.POST('/siniestros/{id}/evidencias', {
      params: { path: { id: idSiniestro } },
      // openapi-fetch envía un FormData tal cual y deja que el navegador ponga el multipart.
      body: formulario as unknown as SolicitudEvidencia,
    })
    if (data) return { ok: true, evidencia: data }
    return { ok: false, fallo: FALLOS[error?.codigo ?? ''] ?? 'error' }
  } catch {
    return { ok: false, fallo: 'error' }
  }
}
