/* Solventa — Internacionalización (i18n) y Localización (l10n)
 *
 * Mecanismo:
 *  · Cada elemento marcado con [data-i18n] guarda su texto original en
 *    español la primera vez que se renderiza (data-i18n-src) y se traduce
 *    consultando EN_DICT por coincidencia exacta de esa cadena origen.
 *  · Cada elemento con [data-money] / [data-date] guarda un valor crudo
 *    (entero en pesos, o fecha ISO) que se formatea con Intl.NumberFormat /
 *    Intl.DateTimeFormat según el locale activo (es-CO por defecto, en-US).
 *  · El locale se persiste en localStorage y se aplica en cada carga de
 *    página, de modo que la preferencia del usuario viaja entre pantallas.
 */
(function () {
  "use strict";

  var STORAGE_KEY = "solventa_locale";
  var LOCALES = { es: "es-CO", en: "en-US" };

  var EN_DICT = {
    /* Flujos complementarios */
    "Preguntas frecuentes": "Frequently asked questions",
    "Buscar en las preguntas": "Search the questions",
    "¿Cuánto tarda en emitirse mi póliza?": "How long does it take to issue my policy?",
    "¿Qué pasa si revoco mi consentimiento de Open Finance?": "What happens if I revoke my Open Finance consent?",
    "¿Qué es un pago paramétrico?": "What is a parametric payout?",
    "¿Me pueden cobrar dos veces si reintento el pago?": "Can I be charged twice if I retry the payment?",
    "¿Cómo reporto un siniestro?": "How do I report a claim?",
    "No encontramos preguntas con ese texto. Prueba el chat en vivo.": "No questions match that text. Try live chat.",
    "No tienes un consentimiento vigente.": "You don't have an active consent.",
    "Cotizaremos con precio estándar.": "We'll quote with the standard price.",
    "Otorgar consentimiento →": "Grant consent →",
    "Demo": "Demo",
    "Simular que Open Finance no responde (timeout 700 ms)": "Simulate Open Finance not responding (700 ms timeout)",
    "Fuente externa degradada.": "External source degraded.",
    "Usaremos tu perfil financiero en caché (hace 2 h) para no demorar la cotización.": "We'll use your cached financial profile (2 h old) so the quote isn't delayed.",
    "Cotización": "Quote",
    "válida por": "valid for",
    "válida": "valid",
    "¿Por qué este precio?": "Why this price?",
    "La cotización venció.": "The quote expired.",
    "Los precios se garantizan por 5 minutos.": "Prices are guaranteed for 5 minutes.",
    "Recotizar": "Quote again",
    "Sin consentimiento de Open Finance vigente.": "No active Open Finance consent.",
    "La oferta usa tarifa estándar. Otorga tu consentimiento para obtener un precio según tu perfil.": "The offer uses the standard rate. Grant consent to get a price based on your profile.",
    "Ingresar como (demo)": "Sign in as (demo)",
    "Operaciones": "Operations",
    "Socio de distribución": "Distribution partner",
    "Ingresa un correo válido y tu contraseña.": "Enter a valid email and your password.",
    "¿Olvidaste tu contraseña?": "Forgot your password?",
    "Periodo": "Period",
    "Hoy": "Today",
    "Últimos 7 días": "Last 7 days",
    "Últimos 30 días": "Last 30 days",
    "No tienes permisos para ver esta sección.": "You don't have permission to view this section.",
    "Tu sesión es de cliente. En la demo puedes cambiar de rol.": "You're signed in as a customer. In the demo you can switch roles.",
    "Cambiar de rol (demo)": "Switch role (demo)",
    "Cotizaciones": "Quotes",
    "Pólizas emitidas": "Policies issued",
    "Siniestros abiertos": "Open claims",
    "Disponibilidad journeys críticos": "Critical journey availability",
    "Pendiente": "Pending",
    "Gestionar": "Manage",
    "Ingresa un correo válido.": "Enter a valid email.",
    "Pago paramétrico.": "Parametric payout.",
    "Se pagó automáticamente al cumplirse la condición de la póliza; no requirió reclamación.": "It was paid automatically when the policy condition was met; no claim was needed.",
    "Buscar póliza por número": "Search policy by number",
    "Buscar": "Search",
    "Filtrar por producto": "Filter by product",
    "Todos": "All",
    "No hay pólizas para este filtro.": "No policies for this filter.",
    "📥 Descargar reporte (CSV)": "📥 Download report (CSV)",
    "Client ID": "Client ID",
    "Secreto del cliente": "Client secret",
    "Versión de API": "API version",
    "Rotar secreto": "Rotate secret",
    "Decisión de suscripción": "Underwriting decision",
    "Simular aprobación del analista": "Simulate analyst approval",
    "Simular caso que requiere revisión asistida": "Simulate a case that needs assisted review",
    "Simular pago rechazado por el banco": "Simulate a payment declined by the bank",
    "Pagar": "Pay",
    "Pago rechazado por el banco emisor.": "Payment declined by the issuing bank.",
    "No se realizó ningún cobro. Verifica los datos o usa otro medio e intenta de nuevo.": "No charge was made. Check the details or use another method and try again.",
    "Pago aprobado.": "Payment approved.",
    "Firma": "Signature",
    "Solventa quiere usar tu ubicación": "Solventa wants to use your location",
    "Solo la usamos mientras pides asistencia, para encontrar el prestador más cercano.": "We only use it while you request assistance, to find the nearest provider.",
    "Ahora no": "Not now",
    "Permitir": "Allow",
    "Escribe tu dirección": "Enter your address",
    "Buscar prestadores": "Find providers",
    "Asistencia en camino": "Assistance on the way",
    "Confirmada": "Confirmed",
    "llega en": "arrives in",
    "Solicitud recibida": "Request received",
    "Prestador en camino": "Provider on the way",
    "Atención en sitio": "On-site service",
    "Cancelar solicitud": "Cancel request",
    "Clínica del Country (urgencias)": "Clínica del Country (emergency)",
    "¿Puedo ver mi póliza sin internet?": "Can I see my policy without internet?",
    "¿Cómo activo la huella?": "How do I enable fingerprint?",
    "Sin conexión · credencial disponible desde la última sincronización": "Offline · card available from the last sync",
    "¿Qué datos usamos?": "What data do we use?",
    "Comportamiento de pago, endeudamiento y estabilidad de ingresos, solo mientras el consentimiento esté vigente.": "Payment behavior, debt and income stability, only while the consent is active.",
    "Usar Open Finance": "Use Open Finance",
    "Precio según tu perfil financiero": "Price based on your financial profile",
    "Sin consentimiento vigente: precio estándar.": "No active consent: standard price.",
    "Otorgar →": "Grant →",
    "Simular pago rechazado": "Simulate declined payment",
    "Pago rechazado.": "Payment declined.",
    "No se hizo ningún cobro. Intenta de nuevo.": "No charge was made. Try again.",
    "Consejo: usa buena luz, evita reflejos y que el documento ocupe todo el recuadro.": "Tip: use good light, avoid glare and fill the frame with the document.",
    "Documentos escaneados": "Scanned documents",
    "Aún no has escaneado documentos.": "You haven't scanned any documents yet.",
    "Crear cuenta": "Create account",
    "Marcar todo leído": "Mark all as read",
    "Todas": "All",
    "🔎 Siniestro en revisión": "🔎 Claim under review",
    "Hace 2 h": "2 h ago",
    "Ver detalle →": "View details →",
    "⚙️ Preferencias de notificación": "⚙️ Notification preferences",
    "Tus datos": "Your details",
    "Cédula de ciudadanía": "National ID number",
    "Celular": "Mobile phone",
    "Acepto los términos y la política de tratamiento de datos (incluye validación de identidad KYC/AML).": "I accept the terms and the data processing policy (includes KYC/AML identity verification).",
    "Selfie con prueba de vida": "Selfie with liveness check",
    "Identidad verificada.": "Identity verified.",
    "Documento vigente, rostro coincide y sin alertas en listas restrictivas.": "Valid document, face matches and no watchlist alerts.",
    "¡Todo listo,": "All set,",
    "+ Agregar nueva póliza": "+ Add new policy",
    "+ Reportar siniestro": "+ Report a claim",
    "/mes": "/mo",
    "1. Revisa las condiciones de tu oferta": "1. Review your offer's terms",
    "1. Selecciona el producto que deseas cotizar": "1. Select the product you want to quote",
    "2. Información del cliente": "2. Customer information",
    "2. Medio de pago": "2. Payment method",
    "3. Firma electrónica": "3. Electronic signature",
    "3. Personaliza tu oferta con Open Finance": "3. Personalize your offer with Open Finance",
    "4. Tu oferta personalizada": "4. Your personalized offer",
    "4. ¡Póliza emitida!": "4. Policy issued!",
    "Accesibilidad": "Accessibility",
    "Acceso restringido · Rol Operaciones": "Restricted access · Operations role",
    "Aceptar oferta": "Accept offer",
    "Aceptar renovación": "Accept renewal",
    "Activa": "Active",
    "Activas": "Active",
    "Alcance": "Scope",
    "Alcance autorizado": "Authorized scope",
    "Alcance del consentimiento": "Consent scope",
    "Acciones": "Actions",
    "Alta": "High",
    "Archivadas": "Archived",
    "Aseguradora digital de finanzas abiertas": "Digital open-finance insurer",
    "Autorizo explícitamente el uso de mis datos financieros para los fines descritos, de acuerdo con la política de tratamiento de datos.":
      "I explicitly authorize the use of my financial data for the purposes described, in accordance with the data processing policy.",
    "Ayuda": "Help",
    "Back-office de socios": "Partner back office",
    "Backoffice": "Back office",
    "Banco Andes S.A. · Socio de distribución": "Banco Andes S.A. · Distribution partner",
    "Bienvenido, Juan": "Welcome, Juan",
    "CVV": "CVV",
    "Cancelación de viaje": "Trip cancellation",
    "Cancelar": "Cancel",
    "Cancelar póliza": "Cancel policy",
    "Casos que requieren intervención": "Cases requiring intervention",
    "Centro de ayuda": "Help center",
    "Cerrar sesión": "Log out",
    "Ciudad": "City",
    "Cliente": "Customer",
    "Cobertura": "Coverage",
    "Coberturas": "Coverages",
    "Coberturas disponibles para este producto": "Coverages available for this product",
    "Comentarios (opcional)": "Comments (optional)",
    "Con perito": "With adjuster",
    "Condiciones del nuevo periodo": "New term conditions",
    "Configuración": "Settings",
    "Confirmación": "Confirmation",
    "Confirmar cancelación": "Confirm cancellation",
    "Confirmar revocación": "Confirm revocation",
    "Consentimiento": "Consent",
    "Consentimientos": "Consents",
    "Consentimientos de Open Finance": "Open Finance consents",
    "Consentimientos otorgados": "Consents granted",
    "Consentimientos vigentes": "Active consents",
    "Contacto": "Contact",
    "Contexto:": "Context:",
    "Continuar": "Continue",
    "Contraseña": "Password",
    "Correo electrónico": "Email address",
    "Cotiza tu primer seguro": "Get your first quote",
    "Cotizaciones por producto": "Quotes by product",
    "Cotización y suscripción": "Quotation & subscription",
    "Cotizar": "Get a Quote",
    "Cotizar un seguro": "Quote an insurance policy",
    "Credenciales y cuotas de la integración": "Integration credentials & quotas",
    "Cuenta": "Account",
    "CONSENT-2026-00417 · vigente": "CONSENT-2026-00417 · active",
    "Cuota (rate limit)": "Quota (rate limit)",
    "Datos del crédito": "Loan details",
    "Datos y consentimiento": "Data & consent",
    "Deducible": "Deductible",
    "Deportes de aventura": "Adventure sports",
    "Descripción de lo ocurrido": "Description of what happened",
    "Dispositivos": "Devices",
    "Documentación": "Documentation",
    "Documento": "ID document",
    "Documento de identidad": "ID document",
    "Embebido en Banco Andes S.A.": "Embedded in Banco Andes S.A.",
    "✉️ Escribir": "✉️ Write to us",
    "En revisión": "Under review",
    "Encontré una mejor oferta": "Found a better offer",
    "Entidad financiera": "Financial institution",
    "Entiendo las consecuencias y confirmo que deseo cancelar esta póliza.":
      "I understand the consequences and confirm I want to cancel this policy.",
    "Enviar reporte": "Submit report",
    "Equipo dañado o perdido": "Damaged or lost equipment",
    "Español (Colombia)": "Spanish (Colombia)",
    "Esta acción finalizará tu cobertura.": "This action will end your coverage.",
    "Esta póliza vence el 27 de septiembre de 2026.": "This policy expires on September 27, 2026.",
    "Estado": "Status",
    "Evidencias (fotos, facturas, documentos)": "Evidence (photos, receipts, documents)",
    "Fecha": "Date",
    "Fecha de nacimiento": "Date of birth",
    "Fecha del evento": "Date of the event",
    "Fecha efectiva": "Effective date",
    "Firmar electrónicamente": "Sign electronically",
    "Fuentes consultadas": "Sources consulted",
    "Fuentes de Open Data incluidas": "Open Data sources included",
    "General": "General",
    "Guardar cambios": "Save changes",
    "Historial de pagos": "Payment history",
    "Hogar": "Home",
    "ID de crédito": "Loan ID",
    "Idioma / Language": "Language / Idioma",
    "Idioma de la interfaz": "Interface language",
    "Información financiera": "Financial information",
    "Información general": "General information",
    "Iniciar chat": "Start chat",
    "Iniciar sesión": "Log in",
    "Inicio": "Home",
    "Insatisfacción con el servicio": "Dissatisfied with the service",
    "Ir a inicio": "Go to home",
    "Ir a mis siniestros": "Go to my claims",
    "Límite asegurado": "Insured limit",
    "Línea de tiempo": "Timeline",
    "Manual": "Manual",
    "Media": "Medium",
    "Medio de pago": "Payment method",
    "Mi Perfil": "My Profile",
    "Mi perfil": "My profile",
    "Mis Pólizas": "My Policies",
    "Mis pólizas": "My policies",
    "Mis siniestros": "My claims",
    "Modificar": "Modify",
    "Modificar cobertura": "Modify coverage",
    "Monto": "Amount",
    "Monto del crédito": "Loan amount",
    "Monto estimado": "Estimated amount",
    "Monto estimado del siniestro": "Estimated claim amount",
    "Motivo": "Reason",
    "Motivo de cancelación": "Cancellation reason",
    "Método de pago": "Payment method",
    "Nombre completo": "Full name",
    "Notificaciones": "Notifications",
    "Notificaciones por correo": "Email notifications",
    "Notificaciones push": "Push notifications",
    "Nueva vigencia": "New term",
    "Nuevo": "New",
    "Nuevo valor de la prima": "New premium amount",
    "Número": "Number",
    "Número de póliza": "Policy number",
    "Número de reclamo": "Claim number",
    "Número de tarjeta": "Card number",
    "Oferta de vida hipotecario": "Mortgage life offer",
    "Oferta vida hipotecario": "Mortgage life offer",
    "Origen": "Source",
    "Otorgado el": "Granted on",
    "Otorgar consentimiento": "Grant consent",
    "Otorgar un nuevo consentimiento": "Grant a new consent",
    "Otro": "Other",
    "POL-2026-001234 · Seguro de Viaje": "POL-2026-001234 · Travel Insurance",
    "POL-2026-001235 · Dispositivos": "POL-2026-001235 · Devices",
    "POL-2026-002891 · Vida Hipotecario": "POL-2026-002891 · Mortgage Life",
    "Pagado": "Paid",
    "Pagados (mes)": "Paid (month)",
    "Panel principal": "Main dashboard",
    "Pendiente revisión": "Pending review",
    "Perfilamiento de cotización y suscripción": "Quotation & subscription profiling",
    "Perfilamiento vida hipotecario": "Mortgage life profiling",
    "Plazo": "Term",
    "Precio": "Price",
    "Precio actual": "Current price",
    "Precio nuevo periodo": "New term price",
    "Prestador asignado": "Assigned provider",
    "Prima": "Premium",
    "Prima mensual total": "Total monthly premium",
    "Prioridad": "Priority",
    "Privacidad": "Privacy",
    "Producto": "Product",
    "Próximo pago": "Next payment",
    "Póliza": "Policy",
    "Póliza afectada": "Affected policy",
    "Pólizas": "Policies",
    "Pólizas activas": "Active policies",
    "Pólizas emitidas a través de su canal": "Policies issued through your channel",
    "Rechazar": "Decline",
    "Reclamo registrado": "Claim registered",
    "Recomendada": "Recommended",
    "Recordatorios de vencimiento": "Expiration reminders",
    "Referencia": "Reference",
    "Renovación": "Renewal",
    "Renovar": "Renew",
    "Renovar ahora": "Renew now",
    "Renovar póliza": "Renew policy",
    "Reportar": "Report",
    "Reportar siniestro": "Report a claim",
    "Reportar un siniestro": "Report a claim",
    "Resumen": "Summary",
    "Resumen de pólizas": "Policy summary",
    "Retraso de vuelo": "Flight delay",
    "Retraso de vuelo (6+ horas)": "Flight delay (6+ hours)",
    "Revocado": "Revoked",
    "Revocado el": "Revoked on",
    "Revocar consentimiento": "Revoke consent",
    "Score de perfil": "Profile score",
    "Scoring individual": "Individual scoring",
    "Seguro de Auto — Plan Full": "Auto Insurance — Full Plan",
    "Seguro de Viaje": "Travel Insurance",
    "Siniestros": "Claims",
    "Siniestros asociados": "Associated claims",
    "Siniestros en curso": "Claims in progress",
    "Siniestros por estado": "Claims by status",
    "Siniestros recientes": "Recent claims",
    "Socios de distribución": "Distribution partners",
    "Solventa · Prototipo de navegación · Basado en el Design System v2.0":
      "Solventa · Navigation prototype · Based on the Design System v2.0",
    "Soporte": "Support",
    "Suscribir en un clic": "Subscribe in one click",
    "Suscripción": "Subscription",
    "Suscripción de tu póliza": "Your policy subscription",
    "Tablero operacional": "Operations dashboard",
    "Tarjeta registrada": "Registered card",
    "Teléfono": "Phone",
    "Tipo": "Type",
    "Tipo de evento": "Event type",
    "Tu póliza de dispositivos vence en 15 días.": "Your devices policy expires in 15 days.",
    "Tu póliza fue emitida correctamente.": "Your policy was issued successfully.",
    "Usar mis datos financieros (Open Finance)": "Use my financial data (Open Finance)",
    "Valor asegurado": "Insured value",
    "Valor asegurado deseado": "Desired insured value",
    "Valor del inmueble": "Property value",
    "Vence": "Expires",
    "Vence pronto": "Expiring soon",
    "Vencida": "Expired",
    "Vencidas": "Expired",
    "Vencidas / por vencer": "Expired / expiring soon",
    "Vencimiento": "Expiration",
    "Ver": "View",
    "Ver 8 pagos": "View 8 payments",
    "Ver FAQs": "View FAQs",
    "Ver condiciones completas": "View full terms",
    "Ver detalle": "View details",
    "Ver historial": "View history",
    "Ver historial de uso": "View usage history",
    "Ver mi póliza": "View my policy",
    "Ver todas": "View all",
    "Ver todas las notificaciones": "View all notifications",
    "Ver todas mis pólizas →": "View all my policies →",
    "Viaje": "Travel",
    "Vida Hipotecario": "Mortgage Life",
    "Vida Hipotecario — cobertura ajustada a tu perfil": "Mortgage Life — coverage tailored to your profile",
    "Vida hipotecario": "Mortgage life",
    "Vigencia": "Term",
    "Vigente": "Active",
    "Vigente hasta": "Valid until",
    "Volver": "Back",
    "Ya no necesito la cobertura": "No longer need the coverage",
    "mes": "mo",
    "¿Nuevo en Solventa?": "New to Solventa?",
    "¿Qué datos solicitamos?": "What data do we request?",
    "¿Revocar este consentimiento?": "Revoke this consent?",
    "← Atrás": "← Back",
    "⚡ Automático": "⚡ Automatic",
    "✈️ Viaje": "✈️ Travel",
    "✓ Activa": "✓ Active",
    "❓ Preguntas frecuentes": "❓ Frequently asked questions",
    "🏠 Hogar": "🏠 Home",
    "💬 Chat en vivo": "💬 Live chat",
    "📎 certificado_aerolinea.pdf": "📎 airline_certificate.pdf",
    "📞 Llamar": "📞 Call",
    "📥 Descargar PDF": "📥 Download PDF",
    "📱 Dispositivos": "📱 Devices",
    "🚗 Auto": "🚗 Car",
    "🧮 Cotizar un seguro": "🧮 Quote an insurance policy",
    "Contrato firmado electrónicamente.": "Contract signed electronically.",
    "Firma electrónica": "Electronic signature",
    "Revisa el resumen del contrato antes de firmar. Esta es una simulación: no se genera ninguna firma real.": "Review the contract summary before signing. This is a simulation: no real signature is generated.",
    "Firmante": "Signer",
    "Acepto los términos del contrato y autorizo mi firma electrónica.": "I accept the contract terms and authorize my electronic signature.",
    "Aceptar y firmar": "Accept and sign",
    "Finalizar": "Finish",
    "1. Información del evento": "1. Event information",
    "2. Evidencias (fotos, facturas, documentos)": "2. Evidence (photos, invoices, documents)",
    "3. Revisa tu reporte": "3. Review your report",
    "Al cancelar, perderás la protección de esta póliza a partir de la fecha efectiva. Podría aplicar un ajuste o devolución proporcional según las condiciones del producto.": "By cancelling, you will lose this policy's protection from the effective date. A proportional adjustment or refund may apply depending on the product terms.",
    "Al enviar, tu reclamo quedará registrado y comenzará su evaluación.": "Once sent, your claim will be registered and its evaluation will begin.",
    "Arrastra tus archivos aquí o haz clic para adjuntar": "Drag your files here or click to attach",
    "Cancelada": "Cancelled",
    "Cotizar un seguro →": "Get an insurance quote →",
    "Cuéntanos qué pasó para iniciar la evaluación de tu caso.": "Tell us what happened so we can start evaluating your case.",
    "Daño accidental": "Accidental damage",
    "Debes mantener al menos una cobertura.": "You must keep at least one coverage.",
    "Débito automático": "Automatic debit",
    "Enfermedades graves": "Critical illness",
    "Esta póliza fue cancelada.": "This policy was cancelled.",
    "Esta póliza vence el": "This policy expires on",
    "Este paso es opcional: puedes enviar el reporte y adjuntar evidencias después.": "This step is optional: you can send the report and attach evidence later.",
    "Evidencia": "Evidence",
    "Evidencias": "Evidence files",
    "Extensión de garantía": "Warranty extension",
    "Fallecimiento": "Death",
    "Firma el contrato de póliza para continuar con la emisión.": "Sign the policy contract to continue with issuance.",
    "Incapacidad total permanente": "Permanent total disability",
    "Información": "Information",
    "La captura con cámara y geoetiquetado está disponible en la app móvil.": "Camera capture and geotagging are available in the mobile app.",
    "Las condiciones anteriores quedan registradas en el historial de la póliza para trazabilidad y auditoría.": "Previous terms are kept in the policy history for traceability and auditing.",
    "Opcional": "Optional",
    "Pérdida": "Loss",
    "Renuévala para mantener tu cobertura activa.": "Renew it to keep your coverage active.",
    "Revisión": "Review",
    "Robo": "Theft",
    "Saldo de la deuda": "Outstanding debt balance",
    "Sin cambios respecto al periodo actual": "No changes from the current term",
    "Sin deducible": "No deductible",
    "Tarjeta •••• 0192": "Card •••• 0192",
    "Ya no tiene cobertura vigente. Puedes cotizar un nuevo seguro cuando quieras.": "It no longer has active coverage. You can get a new quote any time.",
    "cobertura adicional": "additional coverage",
    "10% del daño": "10% of the damage",
    "15 de cada mes": "15th of each month",
    "Automática": "Automatic",
    "Pagos": "Payments",
    "Ver historial de pagos": "View payment history",
    "Ya no tiene cobertura vigente.": "It no longer has active coverage.",
    "$1.000.000 USD": "$1,000,000 USD",
    "$150.000 COP": "$150,000 COP",
    "Auto": "Auto",
  };

  var ANNOUNCE_EN = "Language switched to English";
  var ANNOUNCE_ES = "Idioma cambiado a español";

  function currentLocale() {
    try {
      return localStorage.getItem(STORAGE_KEY) || "es";
    } catch (e) {
      return "es";
    }
  }

  function persistLocale(locale) {
    try { localStorage.setItem(STORAGE_KEY, locale); } catch (e) { /* almacenamiento no disponible */ }
  }

  function translateText(locale, esText) {
    if (locale === "es") return esText;
    return EN_DICT[esText] || esText;
  }

  function applyTextNodes(locale) {
    var nodes = document.querySelectorAll("[data-i18n]");
    nodes.forEach(function (el) {
      if (!el.dataset.i18nSrc) {
        el.dataset.i18nSrc = el.textContent;
      }
      el.textContent = translateText(locale, el.dataset.i18nSrc);
    });
  }

  function applyMoney(locale) {
    var intlLocale = LOCALES[locale] || LOCALES.es;
    document.querySelectorAll("[data-money]").forEach(function (el) {
      var amount = parseFloat(el.getAttribute("data-money"));
      var currency = el.getAttribute("data-currency") || "COP";
      if (isNaN(amount)) return;
      try {
        el.textContent = new Intl.NumberFormat(intlLocale, {
          style: "currency",
          currency: currency,
          maximumFractionDigits: 0,
        }).format(amount);
      } catch (e) { /* Intl no soporta esta moneda, se deja el texto original */ }
    });
  }

  function applyDates(locale) {
    var intlLocale = LOCALES[locale] || LOCALES.es;
    document.querySelectorAll("[data-date]").forEach(function (el) {
      var iso = el.getAttribute("data-date");
      var d = new Date(iso + "T00:00:00");
      if (isNaN(d.getTime())) return;
      try {
        el.textContent = new Intl.DateTimeFormat(intlLocale, {
          day: "2-digit", month: "short", year: "numeric",
        }).format(d);
      } catch (e) { /* fecha inválida, se deja el texto original */ }
    });
  }

  function announce(locale) {
    var el = document.getElementById("a11yAnnouncer");
    if (el) el.textContent = locale === "en" ? ANNOUNCE_EN : ANNOUNCE_ES;
  }

  function applyLocale(locale, opts) {
    opts = opts || {};
    document.documentElement.lang = locale;
    applyTextNodes(locale);
    applyMoney(locale);
    applyDates(locale);

    var cur = document.getElementById("langToggleCur");
    if (cur) cur.textContent = locale === "es" ? "ES" : "EN";
    var sel = document.getElementById("langSelect");
    if (sel) sel.value = locale;

    if (opts.announceChange) announce(locale);
  }

  function setLocale(locale) {
    persistLocale(locale);
    applyLocale(locale, { announceChange: true });
  }

  document.addEventListener("DOMContentLoaded", function () {
    applyLocale(currentLocale());

    var toggle = document.getElementById("langToggle");
    if (toggle) {
      toggle.addEventListener("click", function () {
        setLocale(currentLocale() === "es" ? "en" : "es");
      });
    }
    var select = document.getElementById("langSelect");
    if (select) {
      select.addEventListener("change", function () {
        setLocale(select.value);
      });
    }
  });
})();
