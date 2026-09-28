/* Solventa — flujos funcionales complementarios (prototipo de navegación)
 * Cotización por producto con Open Finance y degradación, suscripción con
 * decisión/pago/emisión, consentimiento, tablero operacional, back-office de
 * socios, onboarding biométrico, asistencia en sitio, escaneo, notificaciones,
 * modo sin conexión y pantallas de cuenta. Todo es simulado (sessionStorage).
 * Se comparte sin cambios entre web/ y mobile/. Debe cargarse DESPUÉS de flow.js
 * y ANTES de i18n.js y app.js.
 */
(function () {
  "use strict";
  var S = window.Solv;
  if (!S) return;
  var t = S.t, money = S.money, dfmt = S.dfmt, rd = S.rd, wr = S.wr, qa = S.qa, params = S.params;
  var MOBILE = !!document.querySelector(".device-frame");
  var $ = function (id) { return document.getElementById(id); };
  var today = S.iso(new Date());
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function on(el, ev, fn) { if (el) el.addEventListener(ev, fn); }
  function fire(el) { el.dispatchEvent(new Event("change", { bubbles: true })); }
  function after(ms, fn) { return setTimeout(fn, ms); }
  // Botón que “trabaja” un momento antes de resolver (simula la llamada al backend)
  function busy(btn, label, ms, fn) {
    var txt = btn.textContent; btn.disabled = true; btn.setAttribute("aria-busy", "true"); btn.textContent = label;
    after(ms, function () { btn.disabled = false; btn.removeAttribute("aria-busy"); btn.textContent = txt; fn(); });
  }

  /* ======================================================================
     Catálogo de productos y cotización
     ====================================================================== */
  var PROD = {
    auto:  { name: "Seguro de Auto — Plan Full", short: "Auto", std: 148000, of: 132900, value: "$60.000.000 COP", deductible: "5% del valor asegurado",
             cov: [["Responsabilidad civil", "$1.200.000.000 COP"], ["Todo riesgo (daño y hurto)", "Deducible 5%"], ["Asistencia en vía 24/7", "Incluida"]],
             extra: [["Vehículo de reemplazo", "hasta 10 días", 9000]] },
    hogar: { name: "Seguro de Hogar — Plan Protegido", short: "Hogar", std: 62000, of: 54900, value: "$250.000.000 COP", deductible: "$500.000 COP",
             cov: [["Incendio y terremoto", "$250.000.000 COP"], ["Contenidos", "$40.000.000 COP"], ["Responsabilidad civil familiar", "$80.000.000 COP"]],
             extra: [["Asistencia domiciliaria", "plomería y cerrajería", 5000]] },
    viaje: { name: "Seguro de Viaje — Plan Internacional", short: "Viaje", std: 49000, of: 45000, value: "$1.000.000 USD", deductible: "$150.000 COP",
             cov: [["Asistencia médica", "$1.000.000 USD"], ["Equipaje perdido", "$1.000.000 COP"], ["Retraso de vuelo ≥3h (paramétrico)", "$350.000 COP"]],
             extra: [["Deportes de aventura", "cobertura adicional", 7000]] },
    disp:  { name: "Seguro de Dispositivos — Plan Total", short: "Dispositivos", std: 21000, of: 18500, value: "$3.200.000 COP", deductible: "10% del daño",
             cov: [["Daño accidental", "$3.200.000 COP"], ["Robo", "$3.200.000 COP"], ["Pérdida", "$1.600.000 COP"]],
             extra: [["Extensión de garantía", "2 años adicionales", 4000]] },
    vida:  { name: "Vida Hipotecario — cobertura ajustada a tu perfil", short: "Vida Hipotecario", std: 24800, of: 20400, value: "Saldo de la deuda", deductible: "Sin deducible",
             cov: [["Fallecimiento", "100% del saldo insoluto"], ["Incapacidad total permanente", "100% del saldo insoluto"]],
             extra: [["Enfermedades graves", "cobertura adicional", 6000]] }
  };
  function quote() { return rd("solv_quote", null); }
  function makeQuote(prod, useOF, degraded) {
    var p = PROD[prod], n = rd("solv_qseq", 0) + 1; wr("solv_qseq", n);
    var q = { prod: prod, name: p.name, price: useOF ? p.of : p.std, std: p.std, of: !!useOF, degraded: !!degraded,
              qt: "QT-2026-" + (8830 + n), created: Date.now() };
    wr("solv_quote", q); return q;
  }

  /* ======================================================================
     Consentimientos Open Finance (compartidos con cotización e hipotecario)
     ====================================================================== */
  var CONSENT0 = [
    { id: "CONSENT-2026-00417", title: "Perfilamiento de cotización y suscripción", scope: "Cotización, suscripción y perfilamiento de riesgo",
      bank: "Banco Andes S.A.", granted: "2026-01-10", until: "2027-01-10", status: "vigente" },
    { id: "CONSENT-2026-00512", title: "Perfilamiento vida hipotecario", scope: "Perfilamiento de oferta de vida hipotecario",
      bank: "Banco Andes S.A.", granted: "2026-03-02", until: "2027-03-02", status: "revocado", revoked: "2026-08-18" }
  ];
  function consents() { return rd("solv_consents", CONSENT0); }
  function hasConsent() { return consents().some(function (c) { return c.status === "vigente"; }); }
  function activeConsent() { return consents().filter(function (c) { return c.status === "vigente"; })[0]; }

  function consentCard(c) {
    var live = c.status === "vigente";
    var dates = live
      ? "<div><label>" + t("Otorgado el", "Granted on") + "</label><p class='text-sm'>" + dfmt(c.granted) + "</p></div><div><label>" + t("Vigente hasta", "Valid until") + "</label><p class='text-sm'>" + dfmt(c.until) + "</p></div>"
      : "<div><label>" + t("Otorgado el", "Granted on") + "</label><p class='text-sm'>" + dfmt(c.granted) + "</p></div><div><label>" + t("Revocado el", "Revoked on") + "</label><p class='text-sm'>" + dfmt(c.revoked) + "</p></div>";
    var body = MOBILE
      ? "<p class='text-sm'>" + esc(c.bank) + " · " + (live ? t("vigente hasta ", "valid until ") + dfmt(c.until) : t("revocado el ", "revoked on ") + dfmt(c.revoked)) + "</p>"
      : "<div class='form-grid' style='margin-bottom:0'><div><label>" + t("Alcance", "Scope") + "</label><p class='text-sm'>" + esc(c.scope) + "</p></div><div><label>" + t("Entidad financiera", "Financial institution") + "</label><p class='text-sm'>" + esc(c.bank) + "</p></div>" + dates + "</div>";
    return "<div class='card' data-consent='" + c.id + "'><div class='card-header'><div><h2>" + esc(c.title) + "</h2><p class='text-sm text-muted mono'>" + c.id + "</p></div>" +
      "<span class='badge " + (live ? "success" : "neutral") + "'>" + (live ? t("Vigente", "Active") : t("Revocado", "Revoked")) + "</span></div>" + body +
      "<div class='" + (MOBILE ? "btn-row" : "page-actions") + "' style='margin-top:8px'><button type='button' class='btn btn-ghost btn-sm' data-consent-log='" + c.id + "'>" + t("Ver historial de uso", "View usage history") + "</button>" +
      (live ? "<button type='button' class='btn btn-danger-outline btn-sm' data-consent-revoke='" + c.id + "'>" + t("Revocar consentimiento", "Revoke consent") + "</button>" : "") + "</div></div>";
  }
  function renderConsents() {
    var box = $("consentList"); if (!box) return;
    box.innerHTML = consents().map(consentCard).join("");
    qa("[data-consent-count]").forEach(function (el) { el.textContent = consents().filter(function (c) { return c.status === "vigente"; }).length; });
  }
  function consentInit() {
    qa("[data-consent-count]").forEach(function (el) { el.textContent = consents().filter(function (c) { return c.status === "vigente"; }).length; });
    var box = $("consentList"); if (!box) return;
    renderConsents();
    box.addEventListener("click", function (e) {
      var rv = e.target.closest("[data-consent-revoke]"), lg = e.target.closest("[data-consent-log]");
      if (rv) {
        var id = rv.getAttribute("data-consent-revoke");
        S.modal({
          title: t("¿Revocar este consentimiento?", "Revoke this consent?"),
          html: "<p class='text-sm'>" + t("Dejaremos de usar tus datos financieros de Open Finance en nuevas cotizaciones. Las pólizas ya emitidas seguirán siendo consultables.", "We will stop using your Open Finance data in new quotes. Policies already issued remain available.") +
                "</p><p class='text-sm'><strong>" + t("La revocación se hace efectiva en menos de 5 minutos.", "Revocation takes effect in under 5 minutes.") + "</strong></p>",
          buttons: [{ label: t("Cancelar", "Cancel"), cls: "btn-ghost", value: false }, { label: t("Confirmar revocación", "Confirm revocation"), cls: "btn-error", value: true }]
        }).then(function (yes) {
          if (!yes) return;
          var all = consents().map(function (c) { return c.id === id ? Object.assign({}, c, { status: "revocado", revoked: today }) : c; });
          wr("solv_consents", all); renderConsents();
          S.toast(t("Consentimiento revocado", "Consent revoked"), id + " · " + t("efectivo en ≤ 5 min; queda registrado en auditoría.", "effective in ≤ 5 min; recorded in the audit log."));
        });
      }
      if (lg) {
        var cid = lg.getAttribute("data-consent-log"), c = consents().filter(function (x) { return x.id === cid; })[0];
        var rows = [[dfmt(c.granted), t("Otorgamiento", "Granted"), t("Canal web · firma del titular", "Web channel · holder signature")],
                    [dfmt(S.addDays(c.granted, 3)), t("Consulta de perfil", "Profile lookup"), t("Cotización QT-2026-08790", "Quote QT-2026-08790")],
                    [dfmt(S.addDays(c.granted, 50)), t("Consulta de perfil", "Profile lookup"), t("Oferta vida hipotecario", "Mortgage life offer")]];
        if (c.status === "revocado") rows.push([dfmt(c.revoked), t("Revocación", "Revocation"), t("Solicitada por el titular", "Requested by the holder")]);
        S.modal({
          title: t("Historial de uso", "Usage history"),
          html: "<p class='text-sm text-muted mono'>" + cid + "</p><div class='table-wrap'><table style='min-width:0'><thead><tr><th scope='col'>" + t("Fecha", "Date") + "</th><th scope='col'>" + t("Evento", "Event") + "</th><th scope='col'>" + t("Detalle", "Detail") + "</th></tr></thead><tbody>" +
                rows.map(function (r) { return "<tr><td>" + r[0] + "</td><td>" + r[1] + "</td><td>" + r[2] + "</td></tr>"; }).join("") +
                "</tbody></table></div><p class='text-xs text-muted'>" + t("Registro de auditoría inmutable (append-only). Datos usados: comportamiento de pago, endeudamiento y estabilidad de ingresos.", "Immutable (append-only) audit log. Data used: payment behavior, debt level and income stability.") + "</p>",
          buttons: [{ label: t("Cerrar", "Close"), cls: "btn-primary", value: true }]
        });
      }
    });
    var chk = $("chkTerm"), grant = $("grantConsent");
    if (chk && grant) {
      var gate = function () { grant.disabled = !chk.checked; }; on(chk, "change", gate); gate();
      on(grant, "click", function () {
        busy(grant, t("Registrando…", "Recording…"), 700, function () {
          var all = consents(), n = all.length + 1, scopeSel = $("cAlcance"), bankSel = $("cEntidad");
          var scope = scopeSel ? scopeSel.options[scopeSel.selectedIndex].text : "Cotización y suscripción";
          var c = { id: "CONSENT-2026-00" + (600 + n), title: t("Perfilamiento: ", "Profiling: ") + scope, scope: scope,
                    bank: bankSel ? bankSel.options[bankSel.selectedIndex].text : "Banco Andes S.A.", granted: today, until: S.addYears(today, 1), status: "vigente" };
          all.unshift(c); wr("solv_consents", all); renderConsents();
          chk.checked = false; gate();
          S.toast(t("Consentimiento otorgado", "Consent granted"), c.id + " · " + t("vigente por 12 meses.", "valid for 12 months."));
          var back = params.get("back"); if (back) after(900, function () { location.href = back + ".html"; });
        });
      });
    }
  }

  /* ======================================================================
     Cotización (web: 4 pasos con Open Finance · móvil: autoservicio)
     ====================================================================== */
  function selectedProduct(root) {
    var r = (root || document).querySelector("input[name='producto']:checked, input[name='p']:checked");
    return r ? r.value : "auto";
  }
  function renderOffer(q) {
    var p = PROD[q.prod];
    qa("[data-offer='name']").forEach(function (el) { el.textContent = p.name; });
    qa("[data-offer='qt']").forEach(function (el) { el.textContent = q.qt; });
    qa("[data-offer='price']").forEach(function (el) { el.setAttribute("data-money", q.price); el.textContent = money(q.price); });
    qa("[data-offer='cov']").forEach(function (ul) { ul.innerHTML = p.cov.map(function (c) { return "<li>✓ " + c[0] + " — " + c[1] + "</li>"; }).join(""); });
    qa("[data-offer='basis']").forEach(function (el) {
      el.textContent = q.of
        ? (q.degraded ? t("Perfilamiento con Open Finance desde caché (fuente externa degradada) · resultado explicable y auditable.", "Open Finance profiling from cache (external source degraded) · explainable and auditable result.")
                      : t("Perfilamiento con Open Finance aplicado · resultado explicable y auditable.", "Open Finance profiling applied · explainable and auditable result."))
        : t("Precio estándar sin datos financieros (no se usó Open Finance).", "Standard price without financial data (Open Finance not used).");
    });
    qa("[data-offer='saving']").forEach(function (el) {
      el.hidden = !q.of;
      el.textContent = t("Ahorras ", "You save ") + money(q.std - q.price) + t("/mes frente al precio estándar", "/mo versus the standard price");
    });
  }
  function explain(q) {
    var f = q.of
      ? [[t("Comportamiento de pago (Open Finance)", "Payment behavior (Open Finance)"), "−6%"], [t("Nivel de endeudamiento", "Debt level"), "−3%"], [t("Siniestralidad de la zona (Open Data)", "Area loss ratio (Open Data)"), "+1%"], [t("Edad y ciudad", "Age and city"), "−2%"]]
      : [[t("Edad y ciudad", "Age and city"), "0%"], [t("Siniestralidad de la zona (Open Data)", "Area loss ratio (Open Data)"), "+1%"], [t("Sin datos financieros", "No financial data"), t("tarifa base", "base rate")]];
    S.modal({
      title: t("¿Por qué este precio?", "Why this price?"),
      html: "<p class='text-sm text-muted'>" + t("Factores que explican la prima de ", "Factors behind the premium of ") + q.qt + "</p><div class='table-wrap'><table style='min-width:0'><thead><tr><th scope='col'>" + t("Factor", "Factor") + "</th><th scope='col'>" + t("Efecto", "Effect") + "</th></tr></thead><tbody>" +
            f.map(function (r) { return "<tr><td>" + r[0] + "</td><td class='mono'>" + r[1] + "</td></tr>"; }).join("") + "</tbody></table></div>" +
            "<p class='text-xs text-muted'>" + t("Versión del modelo de tarificación: rating-v3.2 · la decisión queda registrada en la bitácora de auditoría.", "Rating model version: rating-v3.2 · the decision is stored in the audit log.") + "</p>",
      buttons: [{ label: t("Entendido", "Got it"), cls: "btn-primary", value: true }]
    });
  }
  function offerTimer(wizard, offerStep) {
    var left = 300, el = $("offerTimer"), expired = $("offerExpired"), accept = $("acceptOffer");
    if (!el) return;
    setInterval(function () {
      if (!offerStep.classList.contains("active") || left <= 0) return;
      left--;
      el.textContent = Math.floor(left / 60) + ":" + ("0" + (left % 60)).slice(-2);
      if (left === 0) { expired.hidden = false; if (accept) { accept.classList.add("btn-disabled"); accept.setAttribute("aria-disabled", "true"); } }
    }, 1000);
    on($("requote"), "click", function () {
      left = 300; expired.hidden = true; el.textContent = "5:00";
      if (accept) { accept.classList.remove("btn-disabled"); accept.removeAttribute("aria-disabled"); }
      renderOffer(makeQuote(selectedProduct(wizard), quote() && quote().of, false));
    });
    on(accept, "click", function (e) { if (accept.getAttribute("aria-disabled") === "true") e.preventDefault(); });
  }
  function quoteInit() {
    var wizard = document.querySelector("[data-quote-wizard]"); if (!wizard) return;
    var steps = qa(".wizard-step", wizard), offerStep = steps[steps.length - (MOBILE ? 2 : 1)];
    var ofSwitch = $("useOF"), degr = $("simDegraded"), noConsent = $("ofNoConsent"), degrMsg = $("ofDegraded");
    // Sin consentimiento vigente no se puede usar Open Finance
    if (ofSwitch && !hasConsent()) { ofSwitch.checked = false; ofSwitch.disabled = true; if (noConsent) noConsent.hidden = false; }
    on(degr, "change", function () { if (degrMsg) degrMsg.hidden = !degr.checked; });
    new MutationObserver(function () {
      if (!offerStep.classList.contains("active")) return;
      var useOF = ofSwitch ? ofSwitch.checked : hasConsent();
      renderOffer(makeQuote(selectedProduct(wizard), useOF, useOF && degr && degr.checked));
    }).observe(offerStep, { attributes: true, attributeFilter: ["class"] });
    on($("whyPrice"), "click", function () { explain(quote()); });
    offerTimer(wizard, offerStep);
    // Móvil: el paso de pago emite desde la pantalla de suscripción
    on($("payIssue"), "click", function (e) {
      var q = quote(); if (!q) return;
      if ($("mSimFail") && $("mSimFail").checked) {
        e.preventDefault();
        var err = $("mPayErr"); err.hidden = false; $("mSimFail").checked = false;
        return;
      }
    });
  }

  /* ======================================================================
     Suscripción y emisión
     ====================================================================== */
  function issue(q) {
    var done = rd("solv_issued", []), dup = done.filter(function (p) { return p.qt === q.qt; })[0];
    if (dup) return dup;  // idempotencia: la misma cotización no emite dos pólizas
    var p = PROD[q.prod], n = 5130 + done.length, day = new Date().getDate();
    var pol = { id: String(n), qt: q.qt, name: p.short, num: "POL-2026-00" + n, price: q.price, start: today, end: S.addDays(S.addYears(today, 1), -1),
                status: "activa", renewal: "Automática", value: p.value, deductible: p.deductible, pay: "Tarjeta •••• 0192", payDay: day,
                cov: p.cov, extra: p.extra };
    done.push(pol); wr("solv_issued", done); return pol;
  }
  function fillIssued(pol) {
    qa("[data-issued='num']").forEach(function (el) { el.textContent = pol.num; });
    qa("[data-issued='name']").forEach(function (el) { el.textContent = pol.name; });
    qa("[data-issued='start']").forEach(function (el) { el.setAttribute("data-date", pol.start); el.textContent = dfmt(pol.start); });
    qa("[data-issued='end']").forEach(function (el) { el.setAttribute("data-date", pol.end); el.textContent = dfmt(pol.end); });
    qa("[data-issued='link']").forEach(function (a) { a.setAttribute("href", (MOBILE ? "billetera-detalle.html" : "poliza-detalle.html") + "?p=" + pol.id); });
    qa("[data-issued='pdf']").forEach(function (el) { el.setAttribute("data-mock-download", pol.num + ".pdf"); });
  }
  function subscribeInit() {
    var wizard = document.querySelector("[data-subscribe-wizard]");
    var mobileResult = MOBILE && document.body.getAttribute("data-page") === "suscripcion";
    if (!wizard && !mobileResult) return;
    var q = quote() || makeQuote(MOBILE ? "disp" : "auto", hasConsent(), false);
    renderOffer(q);
    // Móvil: la pantalla de suscripción es el resultado del pago
    if (mobileResult) { fillIssued(issue(q)); return; }
    var steps = qa(".wizard-step", wizard);

    // Paso 1 · decisión de suscripción (automática o asistida)
    var manual = $("simManual"), decision = $("decisionState"), approve = $("analystApprove"), decided = $("decisionOk");
    var setDecision = function (pending) {
      decision.innerHTML = pending
        ? "<span class='badge warning'>" + t("En revisión asistida", "Under assisted review") + "</span> <span class='text-sm text-muted'>" + t("Un analista revisará tu caso (SLA 4 h). Te avisaremos por correo y push.", "An analyst will review your case (4 h SLA). We'll notify you by email and push.") + "</span>"
        : "<span class='badge success'>" + t("✓ Aprobada automáticamente", "✓ Automatically approved") + "</span> <span class='text-sm text-muted mono'>DEC-" + q.qt.slice(3) + "</span>";
      approve.hidden = !pending; decided.value = pending ? "" : "1"; fire(decided);
    };
    on(manual, "change", function () { setDecision(manual.checked); });
    on(approve, "click", function () {
      busy(approve, t("Resolviendo…", "Resolving…"), 900, function () {
        manual.checked = false; setDecision(false);
        S.toast(t("Suscripción aprobada", "Underwriting approved"), t("El analista aprobó tu solicitud.", "The analyst approved your application."));
      });
    });
    setDecision(false);

    // Paso 2 · pago idempotente con posible rechazo
    var pay = $("payNow"), payOk = $("payOk"), fail = $("simPayFail"), err = $("payErr"), ok = $("payDone");
    on(pay, "click", function () {
      err.hidden = true;
      busy(pay, t("Procesando pago…", "Processing payment…"), 1100, function () {
        if (fail && fail.checked) { err.hidden = false; fail.checked = false; return; }
        ok.hidden = false; pay.hidden = true; payOk.value = "1"; fire(payOk);
      });
    });

    // Paso 4 · emisión (idempotente por cotización)
    var last = steps[steps.length - 1];
    new MutationObserver(function () { if (last.classList.contains("active")) fillIssued(issue(q)); })
      .observe(last, { attributes: true, attributeFilter: ["class"] });
  }

  /* ======================================================================
     Vida hipotecario embebido
     ====================================================================== */
  function mortgageInit() {
    var box = $("mortgageOffer"); if (!box) return;
    var withOF = hasConsent(), c = activeConsent();
    renderOffer({ prod: "vida", price: withOF ? PROD.vida.of : PROD.vida.std, std: PROD.vida.std, of: withOF, qt: "OFFER-2026-33021" });
    $("mortgageNoConsent").hidden = withOF;
    qa("[data-mortgage-consent]").forEach(function (el) { el.textContent = c ? c.id + " · " + t("vigente", "active") : t("Sin consentimiento vigente", "No active consent"); });
    qa("[data-mortgage-score]").forEach(function (el) { el.textContent = withOF ? "742 / 850 (" + t("riesgo bajo", "low risk") + ")" : t("No disponible (sin Open Finance)", "Not available (no Open Finance)"); });
    on($("mortgageTerms"), "click", function () {
      S.modal({
        title: t("Condiciones de Vida Hipotecario", "Mortgage Life terms"),
        html: "<ul class='text-sm' style='padding-left:18px'><li>" + t("Beneficiario oneroso: Banco Andes S.A. hasta por el saldo insoluto.", "Loss payee: Banco Andes S.A. up to the outstanding balance.") + "</li><li>" +
              t("Vigencia: igual al plazo del crédito (180 meses), renovación anual automática.", "Term: same as the loan (180 months), automatic annual renewal.") + "</li><li>" +
              t("Exclusiones: preexistencias no declaradas y suicidio el primer año.", "Exclusions: undeclared pre-existing conditions and suicide in the first year.") + "</li><li>" +
              t("Puedes endosar una póliza propia en lugar de esta oferta.", "You may assign your own policy instead of this offer.") + "</li></ul>",
        buttons: [{ label: t("Cerrar", "Close"), cls: "btn-primary", value: true }]
      });
    });
    on($("mortgageSubscribe"), "click", function () { makeQuote("vida", withOF, false); });
  }

  /* ======================================================================
     Tablero operacional (rol Operaciones)
     ====================================================================== */
  var PERIOD = {
    hoy: ["1.284", "312", "47", "99,97%", "▲ 6,2% vs. ayer", "▲ 3,1%", "▼ 4 vs. ayer"],
    "7d": ["8.906", "1.047", "53", "99,98%", "▲ 2,4% vs. semana anterior", "▲ 1,8%", "▲ 6 vs. semana anterior"],
    "30d": ["37.412", "4.388", "61", "99,96%", "▲ 11,0% vs. mes anterior", "▲ 7,5%", "▲ 9 vs. mes anterior"]
  };
  var CASES = {
    "CLM-2026-000118": { kind: t("Siniestro", "Claim"), text: t("Retraso de vuelo — la evidencia de la aerolínea requiere validación de un perito.", "Flight delay — airline evidence needs an adjuster's validation."),
      actions: [[t("Asignar perito", "Assign adjuster"), "btn-primary", "Perito asignado"], [t("Aprobar pago", "Approve payment"), "btn-success", "Aprobado"]] },
    "QT-2026-08790": { kind: t("Suscripción", "Underwriting"), text: t("Score 588/850 con endeudamiento alto; el motor no fue concluyente. Cliente: M. Torres · Hogar $250M.", "Score 588/850 with high debt; the engine was inconclusive. Customer: M. Torres · Home $250M."),
      actions: [[t("Rechazar", "Decline"), "btn-error", "Rechazada"], [t("Aprobar con recargo 8%", "Approve with 8% loading"), "btn-success", "Aprobada"]] },
    "EVT-2026-441209": { kind: t("Pago paramétrico", "Parametric payout"), text: t("Lluvia > 80 mm en Cali (IDEAM). La segunda fuente climática no respondió; 212 pólizas afectadas.", "Rain > 80 mm in Cali (IDEAM). The second weather source didn't respond; 212 policies affected."),
      actions: [[t("Retener pagos", "Hold payouts"), "btn-ghost", "Retenido"], [t("Verificar y liberar pagos", "Verify and release payouts"), "btn-success", "Liberado"]] }
  };
  function roleGate(need, label) {
    var gate = $("roleGate"); if (!gate) return;
    var role = rd("solv_role", null);
    if (role && role !== need) {
      gate.hidden = false;
      qa("[data-role-content]").forEach(function (el) { el.hidden = true; });
      on($("switchRole"), "click", function () { wr("solv_role", need); location.reload(); });
    }
  }
  function opsInit() {
    if (document.body.getAttribute("data-page") !== "operaciones") return;
    roleGate("operaciones");
    var sel = $("opsPeriod");
    on(sel, "change", function () {
      var v = PERIOD[sel.value];
      qa("[data-ops]").forEach(function (el) { el.textContent = v[+el.getAttribute("data-ops")]; });
      S.toast(t("Tablero actualizado", "Dashboard updated"), sel.options[sel.selectedIndex].text);
    });
    var done = rd("solv_cases", {});
    qa("[data-case]").forEach(function (tr) {
      var id = tr.getAttribute("data-case"), badge = tr.querySelector("[data-case-status]");
      var paint = function () { if (done[id]) { badge.className = "badge success"; badge.textContent = done[id]; } };
      paint();
      on(tr.querySelector("[data-case-open]"), "click", function () {
        var c = CASES[id];
        S.modal({
          title: c.kind + " · " + id,
          html: "<p class='text-sm'>" + c.text + "</p>" + (done[id] ? "<p class='text-sm'><span class='badge success'>" + done[id] + "</span></p>" :
                "<div class='form-group'><label for='caseNote'>" + t("Nota para la auditoría", "Audit note") + "</label><textarea id='caseNote' rows='2' placeholder='" + t("Motivo de la decisión…", "Reason for the decision…") + "'></textarea></div>"),
          buttons: done[id] ? [{ label: t("Cerrar", "Close"), cls: "btn-primary", value: null }]
                            : [{ label: t("Cerrar", "Close"), cls: "btn-ghost", value: null }].concat(c.actions.map(function (a) { return { label: a[0], cls: a[1], value: a[2] }; }))
        }).then(function (v) {
          if (!v) return;
          done[id] = v; wr("solv_cases", done); paint();
          S.toast(t("Caso resuelto", "Case resolved"), id + " · " + v + " · " + t("registrado en auditoría", "recorded in the audit log"));
        });
      });
    });
  }

  /* ======================================================================
     Back-office de socios (aislamiento por socio)
     ====================================================================== */
  function partnerInit() {
    if (document.body.getAttribute("data-page") !== "socios") return;
    roleGate("socio");
    var form = $("partnerSearch"), out = $("partnerResult"), prod = $("partnerProduct");
    on(form, "submit", function (e) {
      e.preventDefault();
      var q = $("partnerQuery").value.trim().toUpperCase(), row = document.querySelector("[data-partner-pol='" + q + "']");
      qa("[data-partner-pol]").forEach(function (r) { r.style.background = ""; });
      if (!q) { out.hidden = true; return; }
      out.hidden = false;
      if (row) {
        row.style.background = "var(--primary-50)"; row.scrollIntoView({ block: "center", behavior: "smooth" });
        out.className = "alert success"; out.innerHTML = "<span class='ic' aria-hidden='true'>✅</span><div>" + t("Póliza encontrada en tu organización: ", "Policy found in your organization: ") + "<strong class='mono'>" + esc(q) + "</strong></div>";
      } else if (/^POL-\d{4}-\d{6}$/.test(q)) {
        out.className = "alert error"; out.innerHTML = "<span class='ic' aria-hidden='true'>🔒</span><div><strong>403 · " + t("Acceso denegado.", "Access denied.") + "</strong> " + t("La póliza no fue emitida por los canales de Banco Andes S.A. Cada socio solo ve su propia información.", "The policy was not issued through Banco Andes S.A. channels. Each partner only sees its own data.") + "</div>";
      } else {
        out.className = "alert warning"; out.innerHTML = "<span class='ic' aria-hidden='true'>⚠️</span><div>" + t("Formato esperado: POL-AAAA-NNNNNN.", "Expected format: POL-YYYY-NNNNNN.") + "</div>";
      }
    });
    on(prod, "change", function () {
      var v = prod.value, n = 0;
      qa("[data-partner-pol]").forEach(function (r) { var show = !v || r.getAttribute("data-prod") === v; r.hidden = !show; if (show) n++; });
      $("partnerEmpty").hidden = n > 0;
    });
    on($("rotateSecret"), "click", function () {
      S.modal({
        title: t("¿Rotar el secreto del cliente?", "Rotate the client secret?"),
        html: "<p class='text-sm'>" + t("El secreto actual seguirá funcionando 24 horas para que actualices tus integraciones sin interrupción.", "The current secret keeps working for 24 hours so you can update your integrations without downtime.") + "</p>",
        buttons: [{ label: t("Cancelar", "Cancel"), cls: "btn-ghost", value: false }, { label: t("Rotar secreto", "Rotate secret"), cls: "btn-primary", value: true }]
      }).then(function (yes) {
        if (!yes) return;
        var s = "sk_live_" + Math.random().toString(36).slice(2, 6) + "••••••••" + Math.random().toString(36).slice(2, 6);
        $("secretMask").textContent = s;
        S.toast(t("Secreto rotado", "Secret rotated"), t("Cópialo ahora: no se volverá a mostrar completo.", "Copy it now: it won't be shown in full again."));
      });
    });
  }

  /* ======================================================================
     Autenticación: login web por rol, login móvil biométrico, registro
     ====================================================================== */
  function loginInit() {
    var form = $("loginForm"); if (!form) return;
    on(form, "submit", function (e) {
      e.preventDefault();
      var email = $("email"), err = $("loginErr");
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.value) || !$("pass").value) { err.hidden = false; email.setAttribute("aria-invalid", "true"); email.focus(); return; }
      var role = $("loginRole") ? $("loginRole").value : "cliente";
      wr("solv_role", role);
      location.href = { cliente: "index.html", operaciones: "operaciones.html", socio: "socios.html" }[role];
    });
    on($("forgotPass"), "click", function (e) {
      e.preventDefault();
      S.modal({
        title: t("Recuperar contraseña", "Reset password"),
        html: "<div class='form-group'><label for='fpEmail'>" + t("Correo electrónico", "Email") + "</label><input type='email' id='fpEmail' placeholder='nombre@correo.com'></div>",
        buttons: [{ label: t("Cancelar", "Cancel"), cls: "btn-ghost", value: false }, { label: t("Enviar enlace", "Send link"), cls: "btn-primary", value: true }]
      }).then(function (yes) { if (yes) S.toast(t("Revisa tu correo", "Check your email"), t("Si la cuenta existe, recibirás un enlace válido por 15 minutos.", "If the account exists, you'll get a link valid for 15 minutes.")); });
    });
    on($("bioLogin"), "click", function () {
      S.modal({
        title: t("Ingresar con huella", "Sign in with fingerprint"),
        html: "<p style='font-size:48px;text-align:center;margin:8px 0' aria-hidden='true'>👆</p><p class='text-sm' style='text-align:center'>" + t("Apoya tu dedo en el sensor del dispositivo.", "Place your finger on the device sensor.") + "</p>",
        buttons: [{ label: t("Usar PIN", "Use PIN"), cls: "btn-ghost", value: "pin" }, { label: t("Simular lectura", "Simulate scan"), cls: "btn-primary", value: "ok" }]
      }).then(function (v) {
        if (v === "ok") { wr("solv_role", "cliente"); location.href = "index.html"; }
        if (v === "pin") S.modal({
          title: t("Ingresa tu PIN", "Enter your PIN"),
          html: "<div class='form-group'><label for='pinIn'>" + t("PIN de 4 dígitos", "4-digit PIN") + "</label><input id='pinIn' type='password' inputmode='numeric' maxlength='4' placeholder='••••'></div>",
          buttons: [{ label: t("Cancelar", "Cancel"), cls: "btn-ghost", value: false }, { label: t("Ingresar", "Sign in"), cls: "btn-primary", value: true }]
        }).then(function (yes) { if (yes) location.href = "index.html"; });
      });
    });
  }
  function onboardingInit() {
    var wizard = document.querySelector("[data-onboarding]"); if (!wizard) return;
    var selfie = $("selfieDone"), zone = document.querySelector("[data-camera-mock='selfieOk']");
    on(zone, "click", function () { selfie.value = "1"; fire(selfie); });
    var verify = $("kycStep"), spin = $("kycSpin"), res = $("kycOk"), kyc = $("kycDone");
    new MutationObserver(function () {
      if (!verify.classList.contains("active") || kyc.value) return;
      spin.hidden = false; res.hidden = true;
      after(1600, function () { spin.hidden = true; res.hidden = false; kyc.value = "1"; fire(kyc); });
    }).observe(verify, { attributes: true, attributeFilter: ["class"] });
    on($("regName"), "input", function () { qa("[data-reg-name]").forEach(function (el) { el.textContent = $("regName").value.split(" ")[0] || "Juan"; }); });
  }

  /* ======================================================================
     Móvil: asistencia en sitio, escaneo, notificaciones, modo sin conexión
     ====================================================================== */
  function assistInit() {
    var perm = $("geoPerm"); if (!perm) return;
    var content = $("geoContent"), track = $("assistTrack"), st = rd("solv_assist", null);
    var show = function () { perm.hidden = true; content.hidden = false; };
    if (rd("solv_geo", false)) show();
    on($("geoAllow"), "click", function () { wr("solv_geo", true); show(); S.toast(t("Ubicación activada", "Location enabled"), t("Calle 72 # 10-34, Bogotá (±15 m)", "Calle 72 # 10-34, Bogotá (±15 m)")); });
    on($("geoDeny"), "click", function () { $("geoManual").hidden = false; });
    on($("geoManualGo"), "click", function () { wr("solv_geo", true); show(); });
    var paint = function () {
      st = rd("solv_assist", null); track.hidden = !st;
      qa("[data-assist-req]").forEach(function (b) { b.disabled = !!st; });
      if (st) { $("assistWho").textContent = st.who; $("assistEta").textContent = st.eta; }
    };
    paint();
    qa("[data-assist-req]").forEach(function (b) {
      on(b, "click", function () {
        var who = b.getAttribute("data-assist-req"), eta = b.getAttribute("data-eta");
        S.modal({
          title: t("Solicitar asistencia", "Request assistance"),
          html: "<p class='text-sm'><strong>" + esc(who) + "</strong></p><div class='form-group'><label for='asPol'>" + t("Póliza", "Policy") + "</label><select id='asPol'><option>POL-2026-001234 · Viaje</option><option>POL-2026-001235 · Dispositivos</option></select></div>" +
                "<div class='form-group'><label for='asType'>" + t("¿Qué necesitas?", "What do you need?") + "</label><select id='asType'><option>" + t("Revisión del equipo", "Device check") + "</option><option>" + t("Grúa / traslado", "Tow / transfer") + "</option><option>" + t("Atención médica", "Medical care") + "</option></select></div>",
          buttons: [{ label: t("Cancelar", "Cancel"), cls: "btn-ghost", value: false }, { label: t("Confirmar solicitud", "Confirm request"), cls: "btn-primary", value: true }]
        }).then(function (yes) {
          if (!yes) return;
          wr("solv_assist", { who: who, eta: eta }); paint();
          S.toast(t("Asistencia solicitada", "Assistance requested"), who + " · " + t("llega en ", "arrives in ") + eta);
          track.scrollIntoView({ behavior: "smooth", block: "center" });
        });
      });
    });
    on($("assistCancel"), "click", function () {
      S.modal({ title: t("¿Cancelar la solicitud?", "Cancel the request?"), html: "<p class='text-sm'>" + t("El prestador será notificado.", "The provider will be notified.") + "</p>",
        buttons: [{ label: t("Volver", "Go back"), cls: "btn-ghost", value: false }, { label: t("Sí, cancelar", "Yes, cancel"), cls: "btn-error", value: true }]
      }).then(function (yes) { if (yes) { wr("solv_assist", null); paint(); S.toast(t("Solicitud cancelada", "Request cancelled"), ""); } });
    });
    on($("assistCall"), "click", function () { S.toast(t("Llamada simulada", "Simulated call"), t("Conectando con el prestador…", "Connecting to the provider…")); });
  }
  function scanInit() {
    var attach = $("scanAttach"); if (!attach) return;
    var list = $("scanList"), ok = $("scanOk");
    var paint = function () {
      var docs = rd("solv_scans", []);
      $("scanEmpty").hidden = docs.length > 0;
      list.innerHTML = docs.map(function (d) { return "<div class='summary-row'><span>📄 " + esc(d.file) + "</span><span class='badge success'>" + esc(d.to) + "</span></div>"; }).join("");
    };
    paint();
    on(attach, "click", function () {
      busy(attach, t("Validando…", "Validating…"), 800, function () {
        var docs = rd("solv_scans", []), sel = $("scTramite"), to = sel.options[sel.selectedIndex].text;
        docs.unshift({ file: "documento_" + (docs.length + 1) + ".pdf", to: to }); wr("solv_scans", docs); paint();
        ok.hidden = true;
        S.toast(t("Documento adjuntado", "Document attached"), to);
      });
    });
    on($("scanRetry"), "click", function () { ok.hidden = true; });
  }
  function notifInit() {
    var box = $("notifList"); if (!box) return;
    var read = rd("solv_read", {});
    var paint = function () {
      var n = 0;
      qa("[data-notif]", box).forEach(function (c) {
        var id = c.getAttribute("data-notif"), unread = !read[id];
        c.classList.toggle("unread", unread); if (unread) n++;
        var dot = c.querySelector(".unread-dot"); if (dot) dot.hidden = !unread;
      });
      $("notifCount").textContent = n ? n + " " + t("sin leer", "unread") : t("Todo al día", "All caught up");
    };
    qa("[data-notif]", box).forEach(function (c) { on(c, "click", function () { read[c.getAttribute("data-notif")] = 1; wr("solv_read", read); paint(); }); });
    on($("markAll"), "click", function () { qa("[data-notif]", box).forEach(function (c) { read[c.getAttribute("data-notif")] = 1; }); wr("solv_read", read); paint(); });
    var tabs = qa("[data-notif-filter]");
    tabs.forEach(function (b) {
      on(b, "click", function () {
        var f = b.getAttribute("data-notif-filter");
        tabs.forEach(function (x) { x.classList.toggle("active", x === b); x.setAttribute("aria-pressed", String(x === b)); });
        qa("[data-notif]", box).forEach(function (c) { c.hidden = f !== "all" && c.getAttribute("data-kind") !== f; });
      });
    });
    paint();
  }
  function offlineInit() {
    var btn = $("offlineToggle"), banner = $("offlineBanner"); if (!banner) return;
    var off = rd("solv_offline", false);
    var paint = function () {
      banner.hidden = !off;
      if (btn) btn.textContent = off ? t("📶 Volver a conectarse", "📶 Go back online") : t("📴 Simular modo sin conexión", "📴 Simulate offline mode");
      qa("[data-online-only]").forEach(function (a) {
        a.classList.toggle("btn-disabled", off);
        if (off) a.setAttribute("aria-disabled", "true"); else a.removeAttribute("aria-disabled");
      });
    };
    on(btn, "click", function () {
      off = !off; wr("solv_offline", off); paint();
      S.toast(off ? t("Sin conexión", "Offline") : t("Conectado", "Online"), off ? t("Mostrando la última sincronización guardada.", "Showing the last saved sync.") : t("Datos sincronizados.", "Data synced."));
    });
    document.addEventListener("click", function (e) {
      var a = e.target.closest("[data-online-only]");
      if (a && off) { e.preventDefault(); S.toast(t("Requiere conexión", "Connection required"), t("Esta acción estará disponible cuando vuelvas a estar en línea.", "This action will be available when you're back online.")); }
    });
    paint();
  }

  /* ======================================================================
     Cuenta: perfil, configuración, ayuda
     ====================================================================== */
  function accountInit() {
    var save = $("saveProfile");
    on(save, "click", function () {
      var email = $("pEmail"), err = $("pEmailErr");
      var bad = !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.value);
      err.hidden = !bad; email.setAttribute("aria-invalid", String(bad));
      if (bad) { email.focus(); return; }
      busy(save, t("Guardando…", "Saving…"), 600, function () { S.toast(t("Perfil actualizado", "Profile updated"), t("Tus datos quedaron guardados.", "Your details were saved.")); });
    });
    qa("[data-pref]").forEach(function (sw) {
      var prefs = rd("solv_prefs", {}), k = sw.id;
      if (k in prefs) sw.checked = prefs[k];
      on(sw, "change", function () {
        var p = rd("solv_prefs", {}); p[k] = sw.checked; wr("solv_prefs", p);
        S.toast(t("Preferencia guardada", "Preference saved"), sw.getAttribute("data-pref") + ": " + (sw.checked ? t("activada", "on") : t("desactivada", "off")));
      });
    });
    var faq = $("faqSearch");
    on(faq, "input", function () {
      var q = faq.value.toLowerCase(), n = 0;
      qa("#faqList details").forEach(function (d) { var hit = d.textContent.toLowerCase().indexOf(q) >= 0; d.hidden = !hit; if (hit) n++; });
      $("faqEmpty").hidden = n > 0;
    });
  }

  consentInit(); quoteInit(); subscribeInit(); mortgageInit(); opsInit(); partnerInit();
  loginInit(); onboardingInit(); assistInit(); scanInit(); notifInit(); offlineInit(); accountInit();
})();
