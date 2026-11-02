package co.solventa.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Esquemas de contratos/bff-movil/openapi.yaml. Los nombres JSON van en snake_case.

@Serializable
enum class TipoDocumento {
    @SerialName("cedula_ciudadania") CEDULA_CIUDADANIA,
    @SerialName("cedula_extranjeria") CEDULA_EXTRANJERIA,
    @SerialName("pasaporte") PASAPORTE,
}

@Serializable
enum class EstadoIdentidad {
    @SerialName("pendiente_verificacion") PENDIENTE_VERIFICACION,
    @SerialName("en_revision") EN_REVISION,
    @SerialName("verificada") VERIFICADA,
    @SerialName("rechazada") RECHAZADA,
}

@Serializable
enum class EstadoVerificacion {
    @SerialName("en_proceso") EN_PROCESO,
    @SerialName("aprobada") APROBADA,
    @SerialName("rechazada") RECHAZADA,
    @SerialName("en_revision") EN_REVISION,
}

@Serializable
enum class MotivoRechazo {
    @SerialName("datos_no_coinciden") DATOS_NO_COINCIDEN,
    @SerialName("documento_no_valido") DOCUMENTO_NO_VALIDO,
    @SerialName("no_superada") NO_SUPERADA,
}

@Serializable
data class SolicitudRegistro(
    @SerialName("nombre_completo") val nombreCompleto: String,
    @SerialName("tipo_documento") val tipoDocumento: TipoDocumento,
    @SerialName("numero_documento") val numeroDocumento: String,
    val correo: String,
    val celular: String,
    val contrasena: String,
    @SerialName("version_terminos") val versionTerminos: String,
) {
    // La contraseña nunca debe terminar en un log.
    override fun toString() = "SolicitudRegistro(correo=$correo, numeroDocumento=$numeroDocumento)"
}

@Serializable
data class Otp(
    @SerialName("expira_en") val expiraEn: String,
    @SerialName("intentos_restantes") val intentosRestantes: Int,
    @SerialName("reenvio_disponible_en") val reenvioDisponibleEn: String,
)

@Serializable
data class Registro(
    val id: String,
    @SerialName("estado_identidad") val estadoIdentidad: EstadoIdentidad,
    val otp: Otp,
)

@Serializable
data class SolicitudOtp(val codigo: String)

@Serializable
data class Credenciales(val correo: String, val contrasena: String) {
    override fun toString() = "Credenciales(correo=$correo)"
}

@Serializable
data class SolicitudRenovacion(@SerialName("token_refresco") val tokenRefresco: String) {
    override fun toString() = "SolicitudRenovacion(***)"
}

@Serializable
data class Cliente(
    val id: String,
    @SerialName("nombre_completo") val nombreCompleto: String,
    val correo: String,
    @SerialName("estado_identidad") val estadoIdentidad: EstadoIdentidad,
)

@Serializable
data class Sesion(
    @SerialName("token_acceso") val tokenAcceso: String,
    @SerialName("token_refresco") val tokenRefresco: String,
    @SerialName("expira_en") val expiraEn: String,
    val cliente: Cliente,
) {
    override fun toString() = "Sesion(cliente=${cliente.id}, expiraEn=$expiraEn)"
}

@Serializable
data class VerificacionIdentidad(
    val id: String,
    val estado: EstadoVerificacion,
    val motivo: MotivoRechazo? = null,
    @SerialName("reintento_permitido") val reintentoPermitido: Boolean? = null,
    @SerialName("actualizada_en") val actualizadaEn: String,
)

@Serializable
data class ErrorCampo(val campo: String, val codigo: String)

// Cubre Error, ErrorValidacion, ErrorOtp y ErrorVigencia: los campos extra son opcionales.
@Serializable
data class ErrorBff(
    val codigo: String,
    val mensaje: String,
    val campos: List<ErrorCampo> = emptyList(),
    @SerialName("intentos_restantes") val intentosRestantes: Int? = null,
    /** Solo con `evento_fuera_de_vigencia` (ErrorVigencia). */
    @SerialName("vigente_desde") val vigenteDesde: String? = null,
    @SerialName("vigente_hasta") val vigenteHasta: String? = null,
) {
    companion object {
        // null si la respuesta no trae un cuerpo de error del contrato (p. ej. un proxy caído).
        fun desde(error: retrofit2.HttpException): ErrorBff? = runCatching {
            NetworkClient.json.decodeFromString(serializer(), error.response()?.errorBody()?.string().orEmpty())
        }.getOrNull()
    }
}

// ── Modelos de consentimiento (MOB-F02) ───────────────────────────────────────────────────────
// Las rutas provisionales espejean el contrato del BFF web y el backend Python.
// Se ajustarán cuando #160 publique el contrato de bff-movil v0.2.

@Serializable
enum class CodigoFinalidad {
    @SerialName("cotizacion") COTIZACION,
    @SerialName("suscripcion") SUSCRIPCION,
    @SerialName("prevencion_fraude") PREVENCION_FRAUDE,
}

@Serializable
enum class EstadoConsentimiento {
    @SerialName("vigente") VIGENTE,
    @SerialName("revocado") REVOCADO,
    @SerialName("vencido") VENCIDO,
}

@Serializable
enum class TipoEventoConsentimiento {
    @SerialName("otorgado") OTORGADO,
    @SerialName("renovado") RENOVADO,
    @SerialName("revocado") REVOCADO,
    @SerialName("vencido") VENCIDO,
}

@Serializable
data class EntidadConsentimiento(
    val id: String,
    val nombre: String,
)

@Serializable
data class FinalidadConsentimiento(
    val codigo: CodigoFinalidad,
    val nombre: String,
    val descripcion: String,
    val alcance: List<String>,
    @SerialName("vigencia_meses") val vigenciaMeses: Int,
    @SerialName("entidades_origen") val entidadesOrigen: List<EntidadConsentimiento>,
    @SerialName("version_texto") val versionTexto: String,
)

@Serializable
data class Consentimiento(
    val id: String,
    val finalidad: CodigoFinalidad,
    @SerialName("entidades_origen") val entidadesOrigen: List<EntidadConsentimiento>,
    val estado: EstadoConsentimiento,
    val canal: String,
    @SerialName("otorgado_en") val otorgadoEn: String,
    @SerialName("vence_en") val venceEn: String,
    @SerialName("revocado_en") val revocadoEn: String? = null,
    @SerialName("version_texto") val versionTexto: String,
    @SerialName("por_vencer") val porVencer: Boolean,
    @SerialName("dias_para_vencer") val diasParaVencer: Int? = null,
)

@Serializable
data class SolicitudConsentimiento(
    val finalidad: CodigoFinalidad,
    @SerialName("entidades_origen") val entidadesOrigen: List<String>,
    @SerialName("version_texto") val versionTexto: String,
)

@Serializable
data class EventoConsentimiento(
    val tipo: TipoEventoConsentimiento,
    @SerialName("ocurrido_en") val ocurridoEn: String,
    val canal: String,
    @SerialName("version_texto") val versionTexto: String,
)

// ── Modelos de siniestros (WEB-F05) ───────────────────────────────────────────────────────────
// Las fechas sin hora (`format: date`) llegan como texto ISO (2026-10-26).

@Serializable
enum class EstadoPoliza {
    @SerialName("vigente") VIGENTE,
    @SerialName("vencida") VENCIDA,
    @SerialName("por_iniciar") POR_INICIAR,
}

@Serializable
data class PolizaResumen(
    val numero: String,
    val producto: String,
    val inicio: String,
    /** Último día de cobertura, incluido. */
    val fin: String,
    val estado: EstadoPoliza,
)

@Serializable
enum class TipoEventoSiniestro {
    @SerialName("accidente_vehiculo") ACCIDENTE_VEHICULO,
    @SerialName("hurto") HURTO,
    @SerialName("dano_hogar") DANO_HOGAR,
    @SerialName("retraso_vuelo") RETRASO_VUELO,
    @SerialName("equipo_danado_o_perdido") EQUIPO_DANADO_O_PERDIDO,
    @SerialName("cancelacion_viaje") CANCELACION_VIAJE,
    @SerialName("otro") OTRO,
}

@Serializable
enum class EstadoSiniestro {
    @SerialName("reportado") REPORTADO,
    @SerialName("en_evaluacion") EN_EVALUACION,
    @SerialName("requiere_informacion") REQUIERE_INFORMACION,
    @SerialName("aprobado") APROBADO,
    @SerialName("rechazado") RECHAZADO,
    @SerialName("pagado") PAGADO,
}

@Serializable
enum class SiguientePaso {
    @SerialName("adjuntar_evidencia") ADJUNTAR_EVIDENCIA,
    @SerialName("esperar_evaluacion") ESPERAR_EVALUACION,
}

/** Monto entero en la unidad mayor de la moneda (pesos para COP). La moneda no tiene valor por defecto:
 * kotlinx.serialization omitiría el campo y el contrato lo exige. */
@Serializable
data class Dinero(val valor: Long, val moneda: String) {
    companion object {
        fun pesos(valor: Long) = Dinero(valor, "COP")
    }
}

@Serializable
data class SolicitudSiniestro(
    @SerialName("numero_poliza") val numeroPoliza: String,
    @SerialName("tipo_evento") val tipoEvento: TipoEventoSiniestro,
    @SerialName("ocurrido_el") val ocurridoEl: String,
    val descripcion: String,
    @SerialName("monto_estimado") val montoEstimado: Dinero? = null,
)

@Serializable
data class Siniestro(
    val id: String,
    /** SIN-AAAA-NNNNNN: la misma en la web y en la app. */
    val referencia: String,
    @SerialName("numero_poliza") val numeroPoliza: String,
    @SerialName("tipo_evento") val tipoEvento: TipoEventoSiniestro,
    @SerialName("ocurrido_el") val ocurridoEl: String,
    val descripcion: String,
    @SerialName("monto_estimado") val montoEstimado: Dinero? = null,
    val estado: EstadoSiniestro,
    @SerialName("siguiente_paso") val siguientePaso: SiguientePaso,
    val canal: String,
    @SerialName("reportado_en") val reportadoEn: String,
)

// ── Modelos de evidencia (MOB-F05) ────────────────────────────────────────────────────────────

@Serializable
data class Evidencia(
    val id: String,
    @SerialName("tipo_contenido") val tipoContenido: String,
    val tamano: Long,
    /** Nombre único que puso la app: permite saber si una carga sin respuesta sí llegó. */
    @SerialName("nombre_original") val nombreOriginal: String? = null,
    val canal: String,
    @SerialName("cargada_en") val cargadaEn: String,
    @SerialName("tomada_en") val tomadaEn: String? = null,
    val latitud: Double? = null,
    val longitud: Double? = null,
    val lugar: String? = null,
)
