/* Solventa Móvil — interacciones de navegación (sin lógica de negocio) */
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

  var drawer = document.getElementById("drawer");
  var overlay = document.getElementById("drawerOverlay");
  function openDrawer() { if (drawer) { drawer.classList.add("open"); overlay.classList.add("open"); } }
  function closeDrawer() { if (drawer) { drawer.classList.remove("open"); overlay.classList.remove("open"); } }
  document.querySelectorAll("[data-drawer-open]").forEach(function (b) { b.addEventListener("click", openDrawer); });
  document.querySelectorAll("[data-drawer-close]").forEach(function (b) { b.addEventListener("click", closeDrawer); });
  if (overlay) overlay.addEventListener("click", closeDrawer);
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") { closeDrawer(); closeAllDropdowns(); } });

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
      var screen = wizard.closest(".app-screen");
      if (screen) screen.scrollTo({ top: 0, behavior: "smooth" });
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

  // Cámara / escáner (mock visual): un tap simula una captura exitosa
  document.querySelectorAll("[data-camera-mock]").forEach(function (zone) {
    zone.addEventListener("click", function () {
      var out = document.getElementById(zone.getAttribute("data-camera-mock"));
      if (out) out.hidden = false;
    });
  });

  // Toast de notificación push (mock): aparece y se autodescarta
  document.querySelectorAll("[data-push-demo]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var toast = document.getElementById(btn.getAttribute("data-push-demo"));
      if (!toast) return;
      toast.style.display = "flex";
      clearTimeout(toast._t);
      toast._t = setTimeout(function () { toast.style.display = "none"; }, 3500);
    });
  });

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
