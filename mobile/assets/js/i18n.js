/* Solventa Móvil — Internacionalización (i18n) y Localización (l10n)
 * Mismo mecanismo que el cliente web (ver web/assets/js/i18n.js):
 *  · [data-i18n] guarda el texto original en español y lo traduce por
 *    coincidencia exacta contra EN_DICT.
 *  · [data-money] / [data-date] se reformatean con Intl según el locale.
 */
(function () {
  "use strict";

  var STORAGE_KEY = "solventa_locale";
  var LOCALES = { es: "es-CO", en: "en-US" };

  var EN_DICT = {
    "/mes": "/mo",
    "Accesibilidad": "Accessibility",
    "Acceso con huella digital": "Fingerprint access",
    "Acceso con reconocimiento facial": "Face recognition access",
    "Accesos directos": "Shortcuts",
    "Activas": "Active",
    "Actualización de datos": "Data update",
    "Adjuntar documento": "Attach document",
    "Alinea el documento y toca para escanear": "Align the document and tap to scan",
    "Aseguradora digital de finanzas abiertas, en tu bolsillo.": "Digital open-finance insurer, in your pocket.",
    "Asistencia": "Assistance",
    "Asistencia en sitio": "On-site assistance",
    "Asociar a trámite": "Link to a request",
    "Autorizo el uso de mis datos financieros según la política de tratamiento de datos.":
      "I authorize the use of my financial data under the data processing policy.",
    "Avisos": "Alerts",
    "Ayer": "Yesterday",
    "Ayuda": "Help",
    "Billetera": "Wallet",
    "Billetera de pólizas": "Policy wallet",
    "Cancelar": "Cancel",
    "Cerrar sesión": "Log out",
    "Coberturas": "Coverages",
    "Configuración": "Settings",
    "Confirmar": "Confirm",
    "Consentimiento": "Consent",
    "Consentimiento Open Finance": "Open Finance consent",
    "Consentimientos Open Finance": "Open Finance consents",
    "Continuar": "Continue",
    "Contraseña": "Password",
    "Correo electrónico": "Email address",
    "Cotiza y suscribe en menos de 2 minutos.": "Quote and subscribe in under 2 minutes.",
    "Cotizar": "Get a Quote",
    "Cotizar ahora": "Quote now",
    "Cotizar y suscribir": "Quote & subscribe",
    "Credencial digital": "Digital credential",
    "Cuenta": "Account",
    "Datos y documentos": "Data & documents",
    "Descripción": "Description",
    "Dispositivos": "Devices",
    "Documento": "Document",
    "Documento legible. Encuadre ajustado automáticamente.": "Document is legible. Framing adjusted automatically.",
    "Emisión": "Issuance",
    "En revisión": "Under review",
    "Entidad financiera": "Financial institution",
    "Enviar reporte": "Submit report",
    "Equipo dañado": "Damaged equipment",
    "Escanear": "Scan",
    "Escanear documento": "Scan document",
    "Español (Colombia)": "Spanish (Colombia)",
    "Evidencia fotográfica": "Photo evidence",
    "Foto capturada": "Photo captured",
    "General": "General",
    "Guardar cambios": "Save changes",
    "Hace 3 días": "3 days ago",
    "Hace 5 días": "5 days ago",
    "Hola, Juan 👋": "Hi, Juan 👋",
    "Hoy": "Today",
    "Idioma / Language": "Language / Idioma",
    "Idioma de la interfaz": "Interface language",
    "Iniciar chat": "Start chat",
    "Iniciar sesión": "Log in",
    "Inicio": "Home",
    "Ir a mi inicio": "Go to my home",
    "Línea de tiempo": "Timeline",
    "MOB-F03 · Onboarding y autoservicio": "MOB-F03 · Onboarding & self-service",
    "MOB-F04 · Autoservicio": "MOB-F04 · Self-service",
    "MOB-F05 · Siniestros y asistencia": "MOB-F05 · Claims & assistance",
    "MOB-F06 · Siniestros y asistencia": "MOB-F06 · Claims & assistance",
    "MOB-F07 · Siniestros y asistencia": "MOB-F07 · Claims & assistance",
    "MOB-F09 · Siniestros y asistencia": "MOB-F09 · Claims & assistance",
    "Mi perfil": "My profile",
    "Mi póliza": "My policy",
    "Mis pólizas": "My policies",
    "Mis siniestros": "My claims",
    "Modificar": "Modify",
    "Nombre completo": "Full name",
    "Notificaciones": "Notifications",
    "Notificaciones push": "Push notifications",
    "Número de póliza": "Policy number",
    "Número de tarjeta": "Card number",
    "Otorgar consentimiento": "Grant consent",
    "Otorgar nuevo consentimiento": "Grant new consent",
    "Otro": "Other",
    "POL-2026-001234 · Seguro de Viaje": "POL-2026-001234 · Travel Insurance",
    "POL-2026-001235 · Dispositivos": "POL-2026-001235 · Devices",
    "Pagado": "Paid",
    "Pagar y emitir póliza": "Pay & issue policy",
    "Pago paramétrico procesado": "Parametric payment processed",
    "Pantalla dañada": "Damaged screen",
    "Perfil": "Profile",
    "Perfilamiento y suscripción": "Profiling & subscription",
    "Por vencer": "Expiring soon",
    "Prestadores cercanos": "Nearby providers",
    "Punto de atención Solventa": "Solventa service point",
    "Póliza afectada": "Affected policy",
    "Pólizas": "Policies",
    "Pólizas activas": "Active policies",
    "Recomendada": "Recommended",
    "Recomendado para tu dispositivo": "Recommended for your device",
    "Recordatorios de vencimiento": "Expiration reminders",
    "Renovar ahora →": "Renew now →",
    "Repetir": "Retake",
    "Reportar": "Report",
    "Reportar siniestro": "Report a claim",
    "Retraso de vuelo": "Flight delay",
    "Revocar": "Revoke",
    "Seguro de Viaje": "Travel Insurance",
    "Selfie capturada, prueba de vida validada.": "Selfie captured, liveness check passed.",
    "Siniestro": "Claim",
    "Siniestro CLM-2026-000118": "Claim CLM-2026-000118",
    "Siniestro en curso": "Claim in progress",
    "Siniestros": "Claims",
    "Solicitar asistencia": "Request assistance",
    "Solventa Móvil · Prototipo de navegación": "Solventa Mobile · Navigation prototype",
    "Suscripción POL-2026-005214": "Subscription POL-2026-005214",
    "Taller AutoExpress": "AutoExpress Shop",
    "Tipo de evento": "Event type",
    "Toca para simular la captura": "Tap to simulate the capture",
    "Tu cobertura ya está activa.": "Your coverage is now active.",
    "Tu identidad quedó verificada y registrada para auditoría.": "Your identity was verified and logged for audit.",
    "Tu póliza de dispositivos vence en 15 días.": "Your devices policy expires in 15 days.",
    "Validando tu identidad con el proveedor de KYC/AML…": "Validating your identity with the KYC/AML provider…",
    "Valor del equipo": "Equipment value",
    "Vence": "Expires",
    "Vence pronto": "Expiring soon",
    "Ver FAQs": "View FAQs",
    "Ver condiciones →": "View terms →",
    "Ver credencial": "View credential",
    "Ver detalle": "View details",
    "Ver en mi billetera": "View in my wallet",
    "Ver seguimiento →": "Track status →",
    "Ver toda mi billetera →": "View my full wallet →",
    "Ver uso": "View usage",
    "Verificación": "Verification",
    "Verifiquemos tu identidad": "Let's verify your identity",
    "Vida Hipotecario": "Mortgage Life",
    "Vigencia": "Term",
    "Vigente": "Active",
    "Vigente hasta": "Valid until",
    "mes": "mo",
    "¡Póliza emitida!": "Policy issued!",
    "¡Todo listo!": "All set!",
    "¿Necesitas un seguro?": "Need insurance?",
    "¿Revocar este consentimiento?": "Revoke this consent?",
    "← Atrás": "← Back",
    "⏰ Vencimiento próximo": "⏰ Upcoming expiration",
    "⚡ Automático": "⚡ Automatic",
    "⚡ Pago paramétrico": "⚡ Parametric payment",
    "✅ Cambio confirmado": "✅ Change confirmed",
    "✈️ Viaje": "✈️ Travel",
    "✓ Activa": "✓ Active",
    "❓ Preguntas frecuentes": "❓ Frequently asked questions",
    "🏠 Hogar": "🏠 Home",
    "👆 Ingresar con huella": "👆 Log in with fingerprint",
    "💬 Chat en vivo": "💬 Live chat",
    "📍 Geoetiquetado: Bogotá, D.C. (4.711, -74.072)": "📍 Geotag: Bogotá, D.C. (4.711, -74.072)",
    "📞 Llamar": "📞 Call",
    "📥 Descargar PDF": "📥 Download PDF",
    "📱 Dispositivos": "📱 Devices",
    "📴 Simular modo sin conexión": "📴 Simulate offline mode",
    "🔁 Renovación disponible": "🔁 Renewal available",
    "🔔 Simular notificación push": "🔔 Simulate push notification"
  };

  var ANNOUNCE_EN = "Language switched to English";
  var ANNOUNCE_ES = "Idioma cambiado a español";

  function currentLocale() {
    try { return localStorage.getItem(STORAGE_KEY) || "es"; } catch (e) { return "es"; }
  }

  function persistLocale(locale) {
    try { localStorage.setItem(STORAGE_KEY, locale); } catch (e) { /* almacenamiento no disponible */ }
  }

  function translateText(locale, esText) {
    if (locale === "es") return esText;
    return EN_DICT[esText] || esText;
  }

  function applyTextNodes(locale) {
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      if (!el.dataset.i18nSrc) el.dataset.i18nSrc = el.textContent;
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
          style: "currency", currency: currency, maximumFractionDigits: 0,
        }).format(amount);
      } catch (e) { /* Intl no soporta esta moneda */ }
    });
  }

  function applyDates(locale) {
    var intlLocale = LOCALES[locale] || LOCALES.es;
    document.querySelectorAll("[data-date]").forEach(function (el) {
      var d = new Date(el.getAttribute("data-date") + "T00:00:00");
      if (isNaN(d.getTime())) return;
      try {
        el.textContent = new Intl.DateTimeFormat(intlLocale, {
          day: "2-digit", month: "short", year: "numeric",
        }).format(d);
      } catch (e) { /* fecha inválida */ }
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
    if (toggle) toggle.addEventListener("click", function () {
      setLocale(currentLocale() === "es" ? "en" : "es");
    });
    var select = document.getElementById("langSelect");
    if (select) select.addEventListener("change", function () { setLocale(select.value); });
  });
})();
