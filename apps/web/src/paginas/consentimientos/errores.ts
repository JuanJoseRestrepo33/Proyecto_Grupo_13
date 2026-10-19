/** Códigos de error del contrato que la pantalla sabe explicar; el resto usa un mensaje general. */
const CONOCIDOS = new Set([
  'consentimiento_ya_vigente',
  'consentimiento_no_vigente',
  'consentimiento_revocado',
  'version_texto_desactualizada',
  'entidades_invalidas',
])

export function claveDeError(codigo: string | undefined): string {
  return `consentimientos.errores.${codigo && CONOCIDOS.has(codigo) ? codigo : 'general'}`
}
