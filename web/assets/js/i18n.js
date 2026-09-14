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
    "Solventa · Prototipo de navegación · Basado en el Design System v1.0":
      "Solventa · Navigation prototype · Based on the Design System v1.0",
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
    "WEB-F01 · Adquisición": "WEB-F01 · Acquisition",
    "WEB-F02 · Adquisición": "WEB-F02 · Acquisition",
    "WEB-F03 · Adquisición": "WEB-F03 · Acquisition",
    "WEB-F04 · Ciclo de vida": "WEB-F04 · Lifecycle",
    "WEB-F05 · Ciclo de vida y siniestros": "WEB-F05 · Lifecycle & claims",
    "WEB-F06 · Ciclo de vida y siniestros": "WEB-F06 · Lifecycle & claims",
    "WEB-F07 · Ciclo de vida": "WEB-F07 · Lifecycle",
    "WEB-F08 · Ciclo de vida": "WEB-F08 · Lifecycle",
    "WEB-F09 · Ciclo de vida": "WEB-F09 · Lifecycle",
    "WEB-F10 · Ciclo de vida y operación": "WEB-F10 · Lifecycle & operations",
    "WEB-F11 · Ciclo de vida y operación": "WEB-F11 · Lifecycle & operations",
    "WEB-F12 · Vida hipotecario": "WEB-F12 · Mortgage life",
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
    "🧮 Cotizar un seguro": "🧮 Quote an insurance policy"
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
