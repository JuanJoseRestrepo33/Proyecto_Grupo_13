/* Solventa — interacciones de navegación (sin lógica de negocio) */
(function () {
  "use strict";

  function closeAllDropdowns(except) {
    document.querySelectorAll(".dropdown-panel.open").forEach(function (p) {
      if (p !== except) p.classList.remove("open");
    });
  }

  document.addEventListener("click", function (e) {
    var trigger = e.target.closest("[data-dropdown-trigger]");
    if (trigger) {
      var panel = document.getElementById(trigger.getAttribute("data-dropdown-trigger"));
      var isOpen = panel.classList.contains("open");
      closeAllDropdowns();
      panel.classList.toggle("open", !isOpen);
      e.stopPropagation();
      return;
    }
    if (!e.target.closest(".dropdown-panel")) closeAllDropdowns();
  });

  // Mobile drawer
  var sidebar = document.getElementById("sidebar");
  var overlay = document.getElementById("drawerOverlay");
  function openDrawer() {
    if (!sidebar) return;
    sidebar.classList.add("open");
    overlay.classList.add("open");
  }
  function closeDrawer() {
    if (!sidebar) return;
    sidebar.classList.remove("open");
    overlay.classList.remove("open");
  }
  document.querySelectorAll("[data-drawer-open]").forEach(function (b) { b.addEventListener("click", openDrawer); });
  document.querySelectorAll("[data-drawer-close]").forEach(function (b) { b.addEventListener("click", closeDrawer); });
  if (overlay) overlay.addEventListener("click", closeDrawer);
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") { closeDrawer(); closeAllDropdowns(); } });

  // Sidebar submenu expand/collapse
  document.querySelectorAll("[data-submenu-toggle]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var sub = document.getElementById(btn.getAttribute("data-submenu-toggle"));
      var expanded = btn.getAttribute("aria-expanded") === "true";
      btn.setAttribute("aria-expanded", String(!expanded));
      sub.style.display = expanded ? "none" : "block";
    });
  });

  // Tabs
  document.querySelectorAll("[data-tabs]").forEach(function (group) {
    var buttons = group.querySelectorAll("[data-tab]");
    buttons.forEach(function (btn) {
      btn.addEventListener("click", function () {
        var target = btn.getAttribute("data-tab");
        buttons.forEach(function (b) { b.classList.remove("active"); });
        btn.classList.add("active");
        group.querySelectorAll("[data-tab-panel]").forEach(function (panel) {
          panel.classList.toggle("active", panel.getAttribute("data-tab-panel") === target);
        });
      });
    });
  });

  // Wizard stepper (navegación entre pasos, sin validar ni enviar datos)
  document.querySelectorAll("[data-wizard]").forEach(function (wizard) {
    var steps = Array.prototype.slice.call(wizard.querySelectorAll(".wizard-step"));
    var dots = Array.prototype.slice.call(wizard.querySelectorAll(".stepper .step"));
    var current = 0;

    function render() {
      // Sin callejones: no hay "Atrás" en el primer paso ni "Continuar" en el último
      wizard.querySelectorAll("[data-wizard-prev]").forEach(function (b) { b.style.visibility = current === 0 ? "hidden" : "visible"; });
      if (current === steps.length - 1) wizard.querySelectorAll("[data-wizard-next]").forEach(function (b) { b.style.display = "none"; });
      else wizard.querySelectorAll("[data-wizard-next]").forEach(function (b) { b.style.display = ""; });
      wizard.querySelectorAll("[data-wizard-last]").forEach(function (el) { el.hidden = current !== steps.length - 1; });
      steps.forEach(function (s, i) { s.classList.toggle("active", i === current); });
      dots.forEach(function (d, i) {
        d.classList.toggle("active", i === current);
        d.classList.toggle("done", i < current);
      });
      wizard.querySelectorAll("[data-wizard-progress]").forEach(function (el) {
        el.textContent = (current + 1) + " de " + steps.length;
      });
      var top = wizard.closest(".main-inner") || wizard;
      top.scrollIntoView({ behavior: "smooth", block: "start" });
    }

    wizard.addEventListener("click", function (e) {
      var next = e.target.closest("[data-wizard-next]");
      var prev = e.target.closest("[data-wizard-prev]");
      var goto = e.target.closest("[data-wizard-goto]");
      if (next && current < steps.length - 1) { current++; render(); }
      if (prev && current > 0) { current--; render(); }
      if (goto) { current = parseInt(goto.getAttribute("data-wizard-goto"), 10); render(); }
    });

    render();
  });

  // Option cards (selección visual simple, sin cálculo real)
  document.querySelectorAll("[data-option-group]").forEach(function (group) {
    group.querySelectorAll(".option-card").forEach(function (card) {
      card.addEventListener("click", function () {
        var input = card.querySelector("input");
        if (input) input.checked = true;
        group.querySelectorAll(".option-card").forEach(function (c) { c.classList.remove("selected"); });
        card.classList.add("selected");
      });
    });
  });

  // Dropzone de evidencias (solo feedback visual, no sube archivos)
  document.querySelectorAll("[data-dropzone]").forEach(function (zone) {
    var input = zone.querySelector("input[type=file]");
    var list = document.getElementById(zone.getAttribute("data-dropzone-list") || "");
    zone.addEventListener("click", function () { if (input) input.click(); });
    if (input) {
      input.addEventListener("change", function () {
        if (!list) return;
        list.innerHTML = "";
        Array.prototype.forEach.call(input.files, function (f) {
          var chip = document.createElement("span");
          chip.className = "file-chip";
          chip.textContent = "📎 " + f.name;
          list.appendChild(chip);
        });
      });
    }
  });
  // Aviso de resultado al volver de un flujo (?ok=clave). Solo feedback de navegación.
  (function () {
    var MSG = {
      modificada: ["Cobertura actualizada", "El cambio fue aplicado a tu póliza.", "Coverage updated", "The change was applied to your policy."],
      renovada:   ["Renovación aceptada", "Tu póliza seguirá vigente un año más.", "Renewal accepted", "Your policy stays active for another year."],
      cancelada:  ["Cancelación solicitada", "Recibirás la confirmación por correo.", "Cancellation requested", "You will receive a confirmation by email."],
      reclamo:    ["Reclamo registrado", "Estado: Pendiente de revisión.", "Claim registered", "Status: Pending review."]
    };
    var key = new URLSearchParams(location.search).get("ok");
    if (!key || !MSG[key]) return;
    var en = false;
    try { en = localStorage.getItem("solventa_locale") === "en"; } catch (e) {}
    var m = MSG[key], i = en ? 2 : 0;
    var host = document.querySelector(".device-frame") || document.body;
    var t = document.createElement("div");
    t.className = "flow-toast"; t.setAttribute("role", "status");
    t.innerHTML = "<span aria-hidden='true'>✅</span><div><strong></strong><span></span></div><button type='button' aria-label='Cerrar'>✕</button>";
    t.querySelector("strong").textContent = m[i];
    t.querySelector("div span").textContent = m[i + 1];
    t.querySelector("button").addEventListener("click", function () { t.remove(); });
    host.appendChild(t);
    setTimeout(function () { t.remove(); }, 5000);
    if (history.replaceState) history.replaceState(null, "", location.pathname + location.hash);
  })();
})();

/* Solventa — mejoras de accesibilidad (WCAG 2.2 AA): foco, ARIA y teclado */
(function () {
  "use strict";
  var es = function () { return (document.documentElement.lang || "es") !== "en"; };
  function announce(msg) {
    var a = document.getElementById("a11yAnnouncer");
    if (!a) return;
    a.textContent = "";
    setTimeout(function () { a.textContent = msg; }, 50);
  }
  var FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select,textarea,[tabindex]:not([tabindex="-1"])';

  // 1) Menú lateral / drawer: rol de diálogo, foco inicial, trampa de foco y retorno de foco
  var menu = document.getElementById("sidebar") || document.getElementById("drawer");
  var opener = null;
  if (menu) {
    var openers = document.querySelectorAll("[data-drawer-open]");
    var isDrawer = function () { return getComputedStyle(menu).position === "fixed" || getComputedStyle(menu).position === "absolute"; };
    new MutationObserver(function () {
      var open = menu.classList.contains("open");
      openers.forEach(function (b) { b.setAttribute("aria-expanded", String(open)); });
      if (!isDrawer()) return;
      if (open) {
        opener = document.activeElement;
        menu.setAttribute("role", "dialog"); menu.setAttribute("aria-modal", "true");
        var first = menu.querySelector(FOCUSABLE);
        if (first) first.focus();
      } else {
        menu.removeAttribute("role"); menu.removeAttribute("aria-modal");
        if (opener && opener.focus) opener.focus();
        opener = null;
      }
    }).observe(menu, { attributes: true, attributeFilter: ["class"] });
    menu.addEventListener("keydown", function (e) {
      if (e.key !== "Tab" || !menu.classList.contains("open") || !isDrawer()) return;
      var items = Array.prototype.filter.call(menu.querySelectorAll(FOCUSABLE), function (n) { return n.offsetParent !== null; });
      if (!items.length) return;
      var first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
  }

  // 2) Dropdowns (notificaciones / perfil): aria-expanded sincronizado, Esc devuelve el foco, flechas navegan
  document.querySelectorAll("[data-dropdown-trigger]").forEach(function (trigger) {
    var panel = document.getElementById(trigger.getAttribute("data-dropdown-trigger"));
    if (!panel) return;
    trigger.setAttribute("aria-controls", panel.id);
    panel.setAttribute("role", "region");
    new MutationObserver(function () {
      var open = panel.classList.contains("open");
      trigger.setAttribute("aria-expanded", String(open));
      if (open) { var f = panel.querySelector("a[href]"); if (f) f.focus(); }
    }).observe(panel, { attributes: true, attributeFilter: ["class"] });
    panel.addEventListener("keydown", function (e) {
      var links = Array.prototype.slice.call(panel.querySelectorAll("a[href]"));
      var i = links.indexOf(document.activeElement);
      if (e.key === "Escape") { panel.classList.remove("open"); trigger.focus(); }
      else if (e.key === "ArrowDown" && links.length) { e.preventDefault(); links[(i + 1) % links.length].focus(); }
      else if (e.key === "ArrowUp" && links.length) { e.preventDefault(); links[(i - 1 + links.length) % links.length].focus(); }
    });
  });

  // 3) Tabs: patrón WAI-ARIA (tablist/tab/tabpanel) con flechas, Inicio y Fin
  document.querySelectorAll("[data-tabs]").forEach(function (group, gi) {
    var tabs = Array.prototype.slice.call(group.querySelectorAll("[data-tab]"));
    if (!tabs.length) return;
    tabs[0].parentNode.setAttribute("role", "tablist");
    var sync = function () {
      tabs.forEach(function (t) {
        var on = t.classList.contains("active");
        t.setAttribute("aria-selected", String(on)); t.tabIndex = on ? 0 : -1;
      });
    };
    tabs.forEach(function (t) {
      var key = t.getAttribute("data-tab"), id = "tab" + gi + "-" + key;
      var panel = group.querySelector('[data-tab-panel="' + key + '"]');
      t.id = id; t.setAttribute("role", "tab");
      if (panel) { panel.setAttribute("role", "tabpanel"); panel.setAttribute("aria-labelledby", id); t.setAttribute("aria-controls", panel.id || (panel.id = "panel" + gi + "-" + key)); }
      t.addEventListener("click", sync);
      t.addEventListener("keydown", function (e) {
        var i = tabs.indexOf(t), n = null;
        if (e.key === "ArrowRight") n = tabs[(i + 1) % tabs.length];
        else if (e.key === "ArrowLeft") n = tabs[(i - 1 + tabs.length) % tabs.length];
        else if (e.key === "Home") n = tabs[0];
        else if (e.key === "End") n = tabs[tabs.length - 1];
        if (n) { e.preventDefault(); n.click(); n.focus(); }
      });
    });
    sync();
  });

  // 4) Asistentes (wizard): aria-current en el paso, anuncio "Paso X de N" y foco al encabezado del paso
  document.querySelectorAll("[data-wizard]").forEach(function (wizard) {
    var dots = Array.prototype.slice.call(wizard.querySelectorAll(".stepper .step"));
    var steps = wizard.querySelectorAll(".wizard-step");
    var stepper = wizard.querySelector(".stepper");
    if (stepper) stepper.setAttribute("role", "list");
    dots.forEach(function (d) { d.setAttribute("role", "listitem"); });
    var apply = function (user) {
      dots.forEach(function (d) { if (d.classList.contains("active")) d.setAttribute("aria-current", "step"); else d.removeAttribute("aria-current"); });
      var idx = dots.findIndex(function (d) { return d.classList.contains("active"); });
      if (idx < 0) return;
      if (user) {
        var label = dots[idx].querySelector(".label");
        announce((es() ? "Paso " : "Step ") + (idx + 1) + (es() ? " de " : " of ") + dots.length + (label ? ": " + label.textContent : ""));
        var target = steps[idx] && steps[idx].querySelector("h1,h2,h3");
        if (target) { target.setAttribute("tabindex", "-1"); target.focus({ preventScroll: true }); }
      }
    };
    apply(false);
    var ready = false; setTimeout(function () { ready = true; }, 0);
    new MutationObserver(function () { apply(ready); }).observe(stepper || wizard, { attributes: true, subtree: true, attributeFilter: ["class"] });
  });

  // 5) Zonas de carga / cámara simulada: operables con teclado
  document.querySelectorAll("[data-dropzone],[data-camera-mock]").forEach(function (z) {
    z.setAttribute("role", "button"); z.tabIndex = 0;
    if (!z.getAttribute("aria-label")) z.setAttribute("aria-label", z.hasAttribute("data-camera-mock") ? (es() ? "Tomar foto (simulado)" : "Take photo (simulated)") : (es() ? "Adjuntar archivos" : "Attach files"));
    z.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); z.click(); } });
  });
  document.querySelectorAll("#fotoOk").forEach(function (n) { n.setAttribute("role", "status"); });

  // 6) Propósito del enlace: "Ver" / "Ver detalle" / "Ver seguimiento" se completan con el contexto de la fila o tarjeta
  document.querySelectorAll("tbody tr .link-btn[href], .card > .link-btn[href], .card > a.btn[href]").forEach(function (a) {
    var ctx = a.closest("tr");
    var text = ctx ? (ctx.querySelector("td") || {}).textContent : (a.closest(".card").querySelector("h2") || {}).textContent;
    if (!text || a.querySelector(".sr-only")) return;
    var s = document.createElement("span"); s.className = "sr-only"; s.textContent = " — " + text.trim();
    a.appendChild(s);
  });

  // 7) Tablas: encabezados de fila/columna explícitos
  document.querySelectorAll("thead th:not([scope])").forEach(function (th) { th.setAttribute("scope", "col"); });
  // Emojis decorativos junto a texto: ocultar del lector de pantalla
  document.querySelectorAll(".ic:not([aria-hidden]), .qi:not([aria-hidden])").forEach(function (n) { n.setAttribute("aria-hidden", "true"); });
})();
