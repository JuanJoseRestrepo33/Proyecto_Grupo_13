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
    "🔔 Simular notificación push": "🔔 Simulate push notification",
    "Volver": "Back",
    "Acciones": "Actions",
    "Renovar": "Renew",
    "Rechazar": "Decline",
    "Administrar póliza": "Manage policy",
    "Modificar cobertura": "Modify coverage",
    "Renovar póliza": "Renew policy",
    "Cancelar póliza": "Cancel policy",
    "Retraso de vuelo (6+ horas)": "Flight delay (6+ hours)",
    "Equipo dañado o perdido": "Damaged or lost equipment",
    "Cancelación de viaje": "Trip cancellation",
    "Asistencia médica internacional": "International medical assistance",
    "Nueva prima estimada": "New estimated premium",
    "Tu póliza vence en 15 días.": "Your policy expires in 15 days.",
    "Renuévala para no perder cobertura.": "Renew it to keep your coverage.",
    "Vigencia actual": "Current term",
    "Nueva vigencia": "New term",
    "Prima": "Premium",
    "Aceptar renovación": "Accept renewal",
    "Perderás tu cobertura.": "You will lose your coverage.",
    "La cancelación es efectiva al finalizar el periodo pagado.": "Cancellation takes effect at the end of the paid period.",
    "Motivo de cancelación": "Cancellation reason",
    "Ya no necesito el seguro": "I no longer need the insurance",
    "Encontré una mejor opción": "I found a better option",
    "Precio": "Price",
    "Entiendo que perderé la cobertura de esta póliza.": "I understand I will lose this policy's coverage.",
    "Confirmar cancelación": "Confirm cancellation",
    "Reclamo registrado": "Claim registered",
    "Hemos recibido tu reporte y comenzará su proceso de evaluación.": "We received your report and its evaluation will begin.",
    "Número de reclamo": "Claim number",
    "Estado": "Status",
    "Pendiente revisión": "Pending review",
    "Póliza": "Policy",
    "Ir a mis siniestros": "Go to my claims",
    "Ir a inicio": "Go to home",
    "Información": "Information",
    "Evidencia": "Evidence",
    "Revisión": "Review",
    "Fecha del evento": "Event date",
    "Toma fotos o sube archivos": "Take photos or upload files",
    "Agrega fotos, videos o documentos que soporten tu siniestro.": "Add photos, videos or documents that support your claim.",
    "Formatos: JPG, PNG, PDF, MP4 · Tamaño máximo: 10 MB por archivo": "Formats: JPG, PNG, PDF, MP4 · Max size: 10 MB per file",
    "Revisa tu reporte": "Review your report",
    "Evidencias": "Evidence files",
    "¡Qué bueno tenerte aquí!": "Great to have you here!",
    "Accesos rápidos": "Quick access",
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
    "Extensión de garantía": "Warranty extension",
    "Fallecimiento": "Death",
    "Firma el contrato de póliza para continuar con la emisión.": "Sign the policy contract to continue with issuance.",
    "Incapacidad total permanente": "Permanent total disability",
    "La captura con cámara y geoetiquetado está disponible en la app móvil.": "Camera capture and geotagging are available in the mobile app.",
    "Las condiciones anteriores quedan registradas en el historial de la póliza para trazabilidad y auditoría.": "Previous terms are kept in the policy history for traceability and auditing.",
    "Opcional": "Optional",
    "Pérdida": "Loss",
    "Renuévala para mantener tu cobertura activa.": "Renew it to keep your coverage active.",
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
    "Manual": "Manual",
    "Deportes de aventura": "Adventure sports",
    "Activa": "Active",
    "Pagos": "Payments",
    "Precio actual": "Current price",
    "Precio nuevo periodo": "New term price",
    "Ver historial de pagos": "View payment history",
    "Ya no tiene cobertura vigente.": "It no longer has active coverage.",
    "$1.000.000 USD": "$1,000,000 USD",
    "$150.000 COP": "$150,000 COP",
    "Auto": "Auto",
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
