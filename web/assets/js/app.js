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
})();
