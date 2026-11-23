"""Documento de la póliza en PDF, sin dependencias externas.

Un PDF 1.4 de una página con Helvetica y codificación WinAnsi, que cubre las tildes y la eñe
del español. Basta para que el cliente descargue y guarde su póliza; la maquetación definitiva
es del canal y de la aseguradora, no del núcleo.
"""

from __future__ import annotations

from ..dominio.modelo import Poliza

_MARGEN_X = 56
_ALTO_PAGINA = 842
_ANCHO_PAGINA = 595
_INTERLINEA = 16


def _texto(valor: str) -> bytes:
    """Cadena literal de PDF: escapa los delimitadores y pasa a la codificación WinAnsi."""
    escapado = valor.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    return b"(" + escapado.encode("cp1252", errors="replace") + b")"


def _lineas(poliza: Poliza) -> list[tuple[str, int]]:
    """Las líneas del documento con su tamaño de letra."""
    prima = "-"
    if poliza.prima_mensual is not None:
        prima = f"${poliza.prima_mensual.monto:,}".replace(",", ".")
        prima += f" {poliza.prima_mensual.moneda} al mes"
    lineas: list[tuple[str, int]] = [
        ("Solventa - Póliza de seguro", 18),
        ("", 11),
        (f"Número de póliza: {poliza.numero}", 12),
        (f"Producto: {poliza.nombre_producto or poliza.producto}", 11),
        (f"Tomador: {poliza.id_cliente}", 11),
        (f"Vigencia: del {poliza.inicio.isoformat()} al {poliza.fin.isoformat()}, incluidos", 11),
        (f"Valor asegurado: {poliza.valor_asegurado or '-'}", 11),
        (f"Deducible: {poliza.deducible or '-'}", 11),
        (f"Prima: {prima}", 11),
        ("", 11),
        ("Coberturas", 13),
    ]
    lineas += [(f"- {c.nombre}: {c.limite}", 11) for c in poliza.coberturas]
    lineas += [
        ("", 11),
        (f"Firma electrónica: {poliza.id_firma or '-'}", 9),
        (f"Emitida: {poliza.emitida_en.isoformat() if poliza.emitida_en else '-'}", 9),
        ("Documento de prueba de un prototipo académico (MISW4501). No es un contrato real.", 8),
    ]
    return lineas


def _contenido(poliza: Poliza) -> bytes:
    partes = [b"BT"]
    y = _ALTO_PAGINA - 72
    for texto, tamano in _lineas(poliza):
        if texto:
            partes.append(
                b"/F1 %d Tf 1 0 0 1 %d %d Tm " % (tamano, _MARGEN_X, y) + _texto(texto) + b" Tj"
            )
        y -= _INTERLINEA
    partes.append(b"ET")
    return b"\n".join(partes)


class GeneradorPdf:
    def generar(self, poliza: Poliza) -> bytes:
        flujo = _contenido(poliza)
        objetos = [
            b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %d %d] "
            b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
            % (_ANCHO_PAGINA, _ALTO_PAGINA),
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            b"<< /Length %d >>\nstream\n" % len(flujo) + flujo + b"\nendstream",
            b"<< /Title " + _texto(f"Póliza {poliza.numero}") + b" /Producer (Solventa) >>",
        ]
        salida = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        posiciones = []
        for numero, objeto in enumerate(objetos, start=1):
            posiciones.append(len(salida))
            salida += b"%d 0 obj\n" % numero + objeto + b"\nendobj\n"
        inicio_xref = len(salida)
        salida += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objetos) + 1)
        salida += b"".join(b"%010d 00000 n \n" % p for p in posiciones)
        salida += b"trailer\n<< /Size %d /Root 1 0 R /Info 6 0 R >>\n" % (len(objetos) + 1)
        salida += b"startxref\n%d\n%%%%EOF\n" % inicio_xref
        return bytes(salida)
