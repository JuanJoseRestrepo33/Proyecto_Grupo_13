#!/usr/bin/env bash
# Imprime en el log del job el diagnóstico de las pruebas instrumentadas que fallaron: el stack completo de
# cada fallo (del XML de resultados) y lo que dejaron en logcat el runner de pruebas y AndroidRuntime.
#
# Va al log y no a un artefacto: el repositorio es público, el enmascarado de GitHub cubre los logs y no los
# archivos, y los resultados del runner pueden traer los argumentos de instrumentación (entre ellos la clave).
# La app no escribe en logcat; solo se leen las etiquetas TestRunner y AndroidRuntime.
#
# Uso (desde mobile/android, con el emulador aún encendido): scripts/fallos-e2e.sh
# Nunca falla: es diagnóstico y no debe tapar el error de las pruebas.

resultados="app/build/outputs/androidTest-results/connected"

echo "::group::Fallos de las pruebas instrumentadas (stack completo)"
if find "$resultados" -name '*.xml' 2>/dev/null | grep -q .; then
  find "$resultados" -name '*.xml' -print0 2>/dev/null | xargs -0 -r python3 -c '
import sys
import xml.etree.ElementTree as ET

for ruta in sys.argv[1:]:
    try:
        raiz = ET.parse(ruta).getroot()
    except ET.ParseError as error:
        print(f"No se pudo leer {ruta}: {error}")
        continue
    for caso in raiz.iter("testcase"):
        for fallo in list(caso.findall("failure")) + list(caso.findall("error")):
            clase, nombre = caso.get("classname"), caso.get("name")
            print(f"=== {clase}.{nombre}")
            print((fallo.text or fallo.get("message") or "").strip())
            print()
' || true
else
  echo "No hay resultados XML en $resultados."
fi
echo "::endgroup::"

echo "::group::logcat (TestRunner y AndroidRuntime)"
adb logcat -d -v time -s TestRunner:V AndroidRuntime:E 2>/dev/null || echo "Sin emulador: no hay logcat."
echo "::endgroup::"
exit 0
