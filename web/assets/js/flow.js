/* Solventa — estado de los flujos (prototipo de navegación)
 * Comparte pólizas y reclamos entre pantallas usando sessionStorage, para que
 * los cambios de un flujo se reflejen en el resto de la app. No hay lógica de
 * negocio real: todo son datos de demostración.
 * Debe cargarse ANTES de i18n.js y app.js.
 */
(function () {
  "use strict";

  /* ---------- utilidades ---------- */
  function rd(k, d) { try { var v = sessionStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
  function wr(k, v) { try { sessionStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* sin almacenamiento */ } }
  function qa(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function lang() {
    try { if (localStorage.getItem("solventa_locale") === "en") return "en"; } catch (e) { /* noop */ }
    return document.documentElement.lang === "en" ? "en" : "es";
  }
  function t(es, en) { return lang() === "en" ? en : es; }
  function loc() { return lang() === "en" ? "en-US" : "es-CO"; }
  function money(n) { return new Intl.NumberFormat(loc(), { style: "currency", currency: "COP", maximumFractionDigits: 0 }).format(n); }
  function dfmt(iso) { return new Intl.DateTimeFormat(loc(), { day: "2-digit", month: "short", year: "numeric" }).format(new Date(iso + "T00:00:00")); }
  function iso(d) { return d.getFullYear() + "-" + ("0" + (d.getMonth() + 1)).slice(-2) + "-" + ("0" + d.getDate()).slice(-2); }
  function addYears(s, y) { var d = new Date(s + "T00:00:00"); d.setFullYear(d.getFullYear() + y); return iso(d); }
  function addDays(s, n) { var d = new Date(s + "T00:00:00"); d.setDate(d.getDate() + n); return iso(d); }
  var host = function () { return document.querySelector(".device-frame") || document.body; };
  var FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select,textarea';
  var params = new URLSearchParams(location.search);

  /* ---------- toast y modal reutilizables ---------- */
  function toast(title, text) {
    var el = document.createElement("div");
    el.className = "flow-toast"; el.setAttribute("role", "status");
    el.innerHTML = "<span aria-hidden='true'>✅</span><div><strong></strong><span></span></div><button type='button'></button>";
    el.querySelector("strong").textContent = title;
    el.querySelector("div span").textContent = text || "";
    var b = el.querySelector("button"); b.textContent = "✕"; b.setAttribute("aria-label", t("Cerrar", "Close"));
    b.addEventListener("click", function () { el.remove(); });
    host().appendChild(el);
    setTimeout(function () { el.remove(); }, 4500);
  }

  // opts: { title, html, buttons:[{label, cls, value}], onOpen(root) } -> Promise(value|null)
  function modal(opts) {
    return new Promise(function (resolve) {
      var back = document.createElement("div");
      back.className = "modal-backdrop";
      var id = "dlg" + Date.now();
      back.innerHTML = "<div class='modal' role='dialog' aria-modal='true' aria-labelledby='" + id + "t'><h2 id='" + id + "t'></h2><div class='modal-body'></div><div class='modal-actions'></div></div>";
      var box = back.firstChild;
      box.querySelector("h2").textContent = opts.title;
      box.querySelector(".modal-body").innerHTML = opts.html || "";
      var actions = box.querySelector(".modal-actions");
      var prev = document.activeElement;
      function close(v) { back.remove(); document.body.style.overflow = ""; if (prev && prev.focus) prev.focus(); resolve(v); }
      (opts.buttons || []).forEach(function (b) {
        var btn = document.createElement("button");
        btn.type = "button"; btn.className = "btn " + (b.cls || "btn-ghost"); btn.textContent = b.label;
        if (b.disabled) btn.disabled = true;
        if (b.id) btn.id = b.id;
        btn.addEventListener("click", function () { close(b.value); });
        actions.appendChild(btn);
      });
      back.addEventListener("click", function (e) { if (e.target === back) close(null); });
      back.addEventListener("keydown", function (e) {
        if (e.key === "Escape") { e.stopPropagation(); close(null); return; }
        if (e.key !== "Tab") return;
        var f = qa(FOCUSABLE, box).filter(function (n) { return !n.disabled; });
        if (!f.length) return;
        if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
        else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
      });
      host().appendChild(back);
      document.body.style.overflow = "hidden";
      if (opts.onOpen) opts.onOpen(box);
      var first = box.querySelector("input,textarea") || actions.querySelector("button:not([disabled])") || box.querySelector("button");
      if (first) first.focus();
    });
  }

  /* ---------- pólizas ---------- */
  var POL = {
    "1234": { name: "Seguro de Viaje", num: "POL-2026-001234", price: 45000, start: "2026-01-15", end: "2027-01-14", status: "activa",
      renewal: "Automática", value: "$1.000.000 USD", deductible: "$150.000 COP", pay: "Tarjeta •••• 0192", payDay: 15,
      cov: [["Retraso de vuelo (6+ horas)", "$200 USD"], ["Equipo dañado o perdido", "$1.000.000 COP"], ["Cancelación de viaje", "30% de reembolso"]],
      extra: [["Deportes de aventura", "cobertura adicional", 7000]] },
    "1235": { name: "Dispositivos", num: "POL-2026-001235", price: 20000, start: "2025-09-28", end: "2026-09-27", status: "porvencer",
      renewal: "Manual", value: "$3.200.000 COP", deductible: "10% del daño", pay: "Tarjeta •••• 0192", payDay: 28,
      cov: [["Daño accidental", "$3.200.000 COP"], ["Robo", "$3.200.000 COP"], ["Pérdida", "$1.600.000 COP"]],
      extra: [["Extensión de garantía", "2 años adicionales", 4000]] },
    "2891": { name: "Vida Hipotecario", num: "POL-2026-002891", price: 20000, start: "2026-03-02", end: "2031-03-02", status: "activa",
      renewal: "Automática", value: "Saldo de la deuda", deductible: "Sin deducible", pay: "Débito automático", payDay: 2,
      cov: [["Fallecimiento", "Saldo de la deuda"], ["Incapacidad total permanente", "Saldo de la deuda"]],
      extra: [["Enfermedades graves", "cobertura adicional", 6000]] }
  };
  var STATUS = {
    activa: ["success", "✓ Activa", "Activa"],
    porvencer: ["warning", "Vence pronto", "Vence pronto"],
    cancelada: ["error", "Cancelada", "Cancelada"]
  };

  function pstate(id) { return rd("solv_pol", {})[id] || {}; }
  function psave(id, patch) { var all = rd("solv_pol", {}); all[id] = Object.assign(all[id] || {}, patch); wr("solv_pol", all); }
  function view(id) {
    var b = POL[id], s = pstate(id);
    var sel = s.sel || { base: b.cov.map(function () { return 1; }), extra: b.extra.map(function () { return 0; }) };
    return { id: id, b: b, sel: sel, status: s.status || b.status, price: s.price || b.price, start: s.start || b.start, end: s.end || b.end };
  }
  function priceFor(b, sel) {
    var on = sel.base.reduce(function (a, x) { return a + x; }, 0);
    var p = b.price * on / b.cov.length;
    b.extra.forEach(function (e, i) { if (sel.extra[i]) p += e[2]; });
    return Math.round(p / 100) * 100;
  }
  function renewPrice(v) { return Math.round(v.price * 1.075 / 100) * 100; }
  function currentId() { var p = params.get("p"); return POL[p] ? p : "1234"; }

  function fill(root, id) {
    var v = view(id);
    qa("[data-pol-text]", root).forEach(function (el) {
      var k = el.getAttribute("data-pol-text");
      var val = { name: v.b.name, num: v.b.num, renewal: v.b.renewal, value: v.b.value, deductible: v.b.deductible, pay: v.b.pay,
                  payday: v.b.payDay + " de cada mes" }[k];
      if (val != null) el.textContent = val;
    });
    qa("[data-pol-date]", root).forEach(function (el) {
      var k = el.getAttribute("data-pol-date");
      var d = { start: v.start, end: v.end, newstart: addDays(v.end, 1), newend: addYears(v.end, 1) }[k];
      if (d) { el.setAttribute("data-date", d); el.textContent = dfmt(d); }
    });
    qa("[data-pol-price]", root).forEach(function (el) {
      var k = el.getAttribute("data-pol-price");
      var n = k === "newprice" ? renewPrice(v) : v.price;
      el.setAttribute("data-money", n); el.setAttribute("data-currency", "COP"); el.textContent = money(n);
    });
    qa("[data-pol-badge]", root).forEach(function (el) {
      var st = STATUS[v.status], plain = el.getAttribute("data-pol-badge") === "plain";
      var txt = plain ? st[2] : st[1];
      el.className = "badge " + st[0] + (el.getAttribute("data-badge-extra") ? " " + el.getAttribute("data-badge-extra") : "");
      el.setAttribute("data-i18n", ""); el.textContent = txt;
    });
    qa("[data-pol-href]", root).forEach(function (a) {
      var ok = a.getAttribute("data-pol-ok");
      a.setAttribute("href", a.getAttribute("data-pol-href") + "?p=" + id + (ok ? "&ok=" + ok : ""));
    });
    qa("[data-pol-pdf]", root).forEach(function (el) { el.setAttribute("data-mock-download", v.b.num + ".pdf"); });
    qa("[data-pol-only]", root).forEach(function (el) { el.hidden = el.getAttribute("data-pol-only") !== id; });
    qa("[data-pol-show]", root).forEach(function (el) {
      var want = el.getAttribute("data-pol-show");
      el.hidden = !(want === v.status || (want === "notcancelled" && v.status !== "cancelada"));
    });
  }

  function coverageTable(v) {
    var tb = document.getElementById("polCov"), ul = document.getElementById("polCovM");
    if (!tb && !ul) return;
    var rows = [];
    v.b.cov.forEach(function (c, i) { if (v.sel.base[i]) rows.push(c); });
    v.b.extra.forEach(function (c, i) { if (v.sel.extra[i]) rows.push([c[0], c[1]]); });
    if (ul) { ul.innerHTML = rows.map(function (c) { return "<li><span data-i18n>" + c[0] + "</span> — <span data-i18n>" + c[1] + "</span></li>"; }).join(""); return; }
    var st = STATUS[v.status === "cancelada" ? "cancelada" : "activa"];
    tb.innerHTML = rows.map(function (c) {
      return "<tr><td data-i18n>" + c[0] + "</td><td data-i18n>" + c[1] + "</td><td><span class='badge " + st[0] + "' data-i18n>" + st[1] + "</span></td></tr>";
    }).join("");
  }

  function pageInit() {
    var id = currentId(), v = view(id);
    // Sin acceso a acciones de administración si la póliza está cancelada
    if (document.body.hasAttribute("data-pol-guard") && v.status === "cancelada") { location.replace("poliza-detalle.html?p=" + id); return; }
    var isPolicyPage = /^poliza-/.test(location.pathname.split("/").pop()) || /billetera-detalle/.test(location.pathname);
    if (isPolicyPage) { fill(document, id); coverageTable(v); }
    qa("[data-pol-row]").forEach(function (r) { fill(r, r.getAttribute("data-pol-row")); });

    // Alertas de vencimiento: solo mientras la póliza siga "por vencer"
    qa("[data-pol-alert]").forEach(function (a) { a.hidden = view(a.getAttribute("data-pol-alert")).status !== "porvencer"; });
    // Totales del inicio
    var live = Object.keys(POL).map(view).filter(function (x) { return x.status !== "cancelada"; });
    qa("[data-pol-count]").forEach(function (el) { el.textContent = live.length; });
    qa("[data-pol-total]").forEach(function (el) {
      var s = live.reduce(function (a, x) { return a + x.price; }, 0);
      el.setAttribute("data-money", s); el.textContent = money(s);
    });

    // ----- Modificar cobertura: precio en tiempo real -----
    var list = document.getElementById("polCovList");
    if (list) {
      var html = "";
      v.b.cov.forEach(function (c, i) {
        html += "<div class='checkbox-row'><input type='checkbox' id='cb" + i + "' data-kind='base' data-i='" + i + "'" + (v.sel.base[i] ? " checked" : "") + "><label for='cb" + i + "'><strong data-i18n>" + c[0] + "</strong> — <span data-i18n>" + c[1] + "</span></label></div>";
      });
      v.b.extra.forEach(function (c, i) {
        html += "<div class='checkbox-row'><input type='checkbox' id='ce" + i + "' data-kind='extra' data-i='" + i + "'" + (v.sel.extra[i] ? " checked" : "") + "><label for='ce" + i + "'><strong data-i18n>" + c[0] + "</strong> — <span data-i18n>" + c[1] + "</span> (+<span data-money='" + c[2] + "' data-currency='COP'>" + money(c[2]) + "</span>/<span data-i18n>mes</span>)</label></div>";
      });
      list.innerHTML = html;
      var save = document.getElementById("saveCov"), out = document.getElementById("newPrice"), warn = document.getElementById("covWarn");
      var recalc = function () {
        var sel = { base: v.b.cov.map(function () { return 0; }), extra: v.b.extra.map(function () { return 0; }) };
        qa("input[data-kind]", list).forEach(function (c) { sel[c.getAttribute("data-kind")][+c.getAttribute("data-i")] = c.checked ? 1 : 0; });
        var any = sel.base.concat(sel.extra).some(Boolean), p = priceFor(v.b, sel);
        out.setAttribute("data-money", p); out.textContent = money(p);
        warn.hidden = any; save.classList.toggle("btn-disabled", !any); save.setAttribute("aria-disabled", String(!any));
        save._sel = sel; save._price = p; save._ok = any;
      };
      list.addEventListener("change", recalc); recalc();
      save.addEventListener("click", function (e) {
        if (!save._ok) { e.preventDefault(); return; }
        psave(id, { sel: save._sel, price: save._price });
      });
    }

    // ----- Renovar -----
    var ren = document.getElementById("renewAccept");
    if (ren) ren.addEventListener("click", function () {
      psave(id, { status: "activa", end: addYears(v.end, 1), price: renewPrice(v) });
    });

    // ----- Cancelar: confirmación en ventana modal -----
    var can = document.getElementById("cancelConfirm"), chk = document.getElementById("chkCancel");
    if (can && chk) {
      var gate = function () { can.disabled = !chk.checked; };
      chk.addEventListener("change", gate); gate();
      can.addEventListener("click", function () {
        modal({
          title: t("¿Cancelar esta póliza?", "Cancel this policy?"),
          html: "<p class='text-sm'>" + t("Perderás la cobertura de ", "You will lose the coverage of ") + "<strong>" + v.b.name + "</strong> (" + v.b.num + "). " + t("Esta acción no se puede deshacer.", "This action cannot be undone.") + "</p>",
          buttons: [{ label: t("Volver", "Go back"), cls: "btn-ghost", value: false }, { label: t("Sí, cancelar póliza", "Yes, cancel policy"), cls: "btn-error", value: true }]
        }).then(function (yes) {
          if (!yes) return;
          psave(id, { status: "cancelada" });
          location.href = "poliza-detalle.html?p=" + id + "&ok=cancelada";
        });
      });
    }

    // ----- Historial de pagos -----
    qa("[data-pol-payments]").forEach(function (a) {
      a.addEventListener("click", function (e) {
        e.preventDefault();
        var vv = view(id), rows = "", start = new Date(vv.start + "T00:00:00");
        for (var i = 0; i < 8; i++) {
          var d = new Date(start.getFullYear(), start.getMonth() + i, vv.b.payDay);
          rows += "<tr><td>" + dfmt(iso(d)) + "</td><td>" + money(vv.b.price) + "</td><td><span class='badge success'>" + t("Pagado", "Paid") + "</span></td></tr>";
        }
        modal({
          title: t("Historial de pagos", "Payment history"),
          html: "<p class='text-sm text-muted'>" + vv.b.name + " · " + vv.b.num + "</p><div class='table-wrap'><table style='min-width:0'><thead><tr><th scope='col'>" + t("Fecha", "Date") + "</th><th scope='col'>" + t("Valor", "Amount") + "</th><th scope='col'>" + t("Estado", "Status") + "</th></tr></thead><tbody>" + rows + "</tbody></table></div>",
          buttons: [{ label: t("Cerrar", "Close"), cls: "btn-primary", value: true }]
        });
      });
    });
  }

  /* ---------- reclamos ---------- */
  function claims() { return rd("solv_claims", []); }
  function claimId(n) { return "CLM-2026-" + ("000000" + (142 + n)).slice(-6); }
  function fieldVal(name) {
    var el = document.querySelector("[data-claim-field='" + name + "']");
    if (!el) return "";
    return el.tagName === "SELECT" ? (el.options[el.selectedIndex] || {}).text || "" : el.value;
  }
  function filesCount() {
    var n = qa("#fileList .file-chip").length, ok = document.getElementById("fotoOk");
    if (ok && !ok.hidden) n += 1;
    return n;
  }

  function claimsInit() {
    var list = claims();
    var last = list[0];

    // Resumen de revisión antes de enviar
    var refresh = function () {
      qa("[data-claim-review]").forEach(function (el) {
        var k = el.getAttribute("data-claim-review"), v;
        if (k === "files") { var n = filesCount(); v = n ? n + " 📎" : t("Sin archivos", "No files"); }
        else if (k === "fecha") { v = fieldVal("fecha") ? dfmt(fieldVal("fecha")) : "—"; }
        else if (k === "monto") { v = fieldVal("monto") || "—"; }
        else v = fieldVal(k) || "—";
        el.textContent = v;
      });
    };
    var stepper = document.querySelector("[data-wizard] .stepper");
    if (stepper && document.querySelector("[data-claim-review]")) {
      new MutationObserver(refresh).observe(stepper, { attributes: true, subtree: true, attributeFilter: ["class"] });
      refresh();
    }
    // Enviar: guarda el reclamo para verlo luego en la lista, el detalle y la confirmación
    qa("[data-claim-submit]").forEach(function (a) {
      a.addEventListener("click", function () {
        var all = claims(), id = claimId(all.length);
        all.unshift({ id: id, poliza: fieldVal("poliza"), tipo: fieldVal("tipo"), fecha: fieldVal("fecha"), monto: fieldVal("monto"), files: filesCount(), created: iso(new Date()) });
        wr("solv_claims", all);
      });
    });

    // Confirmación
    qa("[data-claim='id']").forEach(function (el) { el.textContent = last ? last.id : claimId(0); });
    qa("[data-claim='poliza']").forEach(function (el) { if (last && last.poliza) el.textContent = last.poliza.split(" · ")[0]; });

    // Contadores y avisos
    qa("[data-claims-count]").forEach(function (el) { el.textContent = (+el.getAttribute("data-claims-count") || 0) + list.length; });
    qa("a[href='siniestros.html'] .nav-badge").forEach(function (b) { b.textContent = 2 + list.length; b.setAttribute("aria-label", (2 + list.length) + " " + t("notificaciones", "notifications")); });

    // Listas
    var body = document.getElementById("claimsBody"), cards = document.getElementById("claimsList");
    list.slice().reverse().forEach(function (c) {
      var monto = c.monto || "—", href = "siniestro-detalle.html?id=" + c.id;
      if (body) {
        var tr = document.createElement("tr");
        tr.innerHTML = "<td class='mono'>" + c.id + "</td><td>" + (c.poliza.split(" · ")[1] || c.poliza) + "</td><td>" + c.tipo + "</td><td><span class='badge neutral' data-i18n>Manual</span></td><td><span class='badge warning' data-i18n>Pendiente revisión</span></td><td>" + monto + "</td><td><a class='link-btn' href='" + href + "' data-i18n>Ver</a></td>";
        body.insertBefore(tr, body.firstChild);
      }
      if (cards) {
        var d = document.createElement("div"); d.className = "card";
        d.innerHTML = "<div class='card-header'><h2>" + c.tipo + "</h2><span class='badge warning' data-i18n>Pendiente revisión</span></div><p class='text-sm text-muted mono'>" + c.id + "</p><a class='link-btn' href='" + href + "' data-i18n>Ver seguimiento →</a>";
        cards.insertBefore(d, cards.firstChild);
      }
    });

    // Detalle de un reclamo recién creado
    var wanted = params.get("id"), c = list.filter(function (x) { return x.id === wanted; })[0];
    if (c && document.getElementById("claimTimeline")) {
      qa("[data-claim-detail='tipo']").forEach(function (el) { el.textContent = c.tipo; });
      qa("[data-claim-detail='id']").forEach(function (el) { el.textContent = c.id; });
      qa("[data-claim-detail='poliza']").forEach(function (el) { el.textContent = c.poliza.split(" · ")[0]; });
      qa("[data-claim-detail='monto']").forEach(function (el) { el.textContent = c.monto || "—"; });
      qa("[data-claim-detail='badge']").forEach(function (el) { el.className = "badge warning"; el.setAttribute("data-i18n", ""); el.textContent = "Pendiente revisión"; });
      document.getElementById("claimTimeline").innerHTML =
        "<div class='tl-item done'><div class='tl-date'>" + dfmt(c.created) + "</div><div class='tl-title' data-i18n>Reporte recibido</div></div>" +
        "<div class='tl-item current'><div class='tl-date'>" + dfmt(c.created) + "</div><div class='tl-title' data-i18n>Pendiente revisión</div></div>" +
        "<div class='tl-item'><div class='tl-date' data-i18n>Pendiente</div><div class='tl-title' data-i18n>Decisión y pago</div></div>";
    }
  }

  /* ---------- chat en vivo (simulado) ---------- */
  function chat() {
    var replies = lang() === "en"
      ? ["Hi! I'm Laura from Solventa. How can I help?", "Thanks for the details. I'm checking your case now.", "Your file is with an analyst. You'll get a notification when there is an update.", "Is there anything else I can help you with?"]
      : ["¡Hola! Soy Laura, de Solventa. ¿En qué te puedo ayudar?", "Gracias por la información. Estoy revisando tu caso.", "Tu expediente está con un analista. Recibirás una notificación cuando haya novedades.", "¿Puedo ayudarte con algo más?"];
    var n = 0;
    modal({
      title: t("Chat en vivo", "Live chat"),
      html: "<p class='text-xs text-muted'>" + t("Simulación: las respuestas son automáticas.", "Simulation: replies are automatic.") + "</p><div class='chat-log' role='log' aria-live='polite' aria-label='" + t("Conversación", "Conversation") + "'></div><form class='chat-form'><label for='chatMsg' class='sr-only'>" + t("Mensaje", "Message") + "</label><input type='text' id='chatMsg' autocomplete='off' placeholder='" + t("Escribe un mensaje…", "Type a message…") + "'><button type='submit' class='btn btn-primary btn-auto'>" + t("Enviar", "Send") + "</button></form>",
      buttons: [{ label: t("Cerrar chat", "Close chat"), cls: "btn-ghost", value: true }],
      onOpen: function (box) {
        var log = box.querySelector(".chat-log"), form = box.querySelector("form"), input = box.querySelector("#chatMsg");
        function add(txt, who) {
          var m = document.createElement("div"); m.className = "msg " + who; m.textContent = txt; log.appendChild(m); log.scrollTop = log.scrollHeight;
        }
        function agent() { add(replies[Math.min(n, replies.length - 1)], "agent"); n++; }
        setTimeout(agent, 400);
        form.addEventListener("submit", function (e) {
          e.preventDefault();
          var v = input.value.trim(); if (!v) return;
          add(v, "me"); input.value = ""; setTimeout(agent, 900);
        });
      }
    });
  }

  /* ---------- puertas de validación en asistentes ---------- */
  function gates() {
    qa("[data-wizard]").forEach(function (wz) {
      var steps = qa(".wizard-step", wz), next = wz.querySelector("[data-wizard-next]");
      if (!next || !steps.some(function (s) { return s.hasAttribute("data-required"); })) return;
      var hint = document.createElement("p");
      hint.className = "hint"; hint.id = "gateHint"; hint.hidden = true; hint.style.textAlign = "right";
      hint.textContent = t("Completa los campos obligatorios para continuar.", "Fill in the required fields to continue.");
      wz.querySelector(".wizard-nav").insertAdjacentElement("afterend", hint);
      var mine = false;
      var check = function () {
        var s = steps.filter(function (x) { return x.classList.contains("active"); })[0];
        var need = s && s.getAttribute("data-required");
        if (!need) { if (mine) { next.disabled = false; mine = false; } hint.hidden = true; return; }
        var ok = need.split(",").every(function (id) { var el = document.getElementById(id); return el && el.value.trim() !== ""; });
        next.disabled = !ok; mine = !ok; hint.hidden = ok;
        if (!ok) next.setAttribute("aria-describedby", "gateHint"); else next.removeAttribute("aria-describedby");
      };
      wz.addEventListener("input", check); wz.addEventListener("change", check);
      new MutationObserver(check).observe(wz.querySelector(".stepper"), { attributes: true, subtree: true, attributeFilter: ["class"] });
      check();
    });
  }

  /* ---------- arranque ---------- */
  pageInit();
  claimsInit();
  gates();
  document.addEventListener("click", function (e) {
    var c = e.target.closest("[data-chat]");
    if (c) { e.preventDefault(); chat(); return; }
    var d = e.target.closest("[data-mock-download]");
    if (d) { e.preventDefault(); toast(t("Descarga simulada", "Simulated download"), d.getAttribute("data-mock-download")); }
  });
  window.Solv = { modal: modal, toast: toast };
})();
