/* MacroMod docs — DSL syntax highlighting (CodeMirror simple-mode) +
   static snippet colorizing. The interactive editor (Run + engine) is wired in
   initEditor() below. Loaded on every page; the editor part is a no-op unless the
   page has #macromod-editor. */
(function () {
  "use strict";
  if (typeof CodeMirror === "undefined") return;

  // ---- the MacroMod DSL mode --------------------------------------------
  var KEYWORDS = /\b(?:if|elseif|else|endif|do|loop|while|until|for|foreach|next|break|unsafe|endunsafe|repeat|forever)\b/;
  CodeMirror.defineSimpleMode("macromod", {
    start: [
      { regex: /\/\/.*/, token: "comment" },
      { regex: /"(?:[^\\"]|\\.)*"/, token: "string" },
      { regex: /\$\$\{|\}\$\$/, token: "meta" }, // script-block delimiters
      { regex: /\$\$(?:\[[a-zA-Z0-9 _\-.]*\]|<[^>]+>|i:d|[0-9?idfuwthskmp!])/, token: "atom" }, // $$ params
      { regex: /%[@#&]?[a-zA-Z~][\w\-]*(?:\[\d+\])?%/, token: "variable-2" }, // %var%
      { regex: KEYWORDS, token: "keyword" },
      { regex: /\b(?:true|false)\b/, token: "atom" },
      { regex: /[a-zA-Z_]+(?=\s*\()/, token: "def" }, // action name before "("
      { regex: /[@#&][a-zA-Z~][\w\-]*(?:\[\d*\])?/, token: "variable" }, // sigil variables
      { regex: /\b\d+\b/, token: "number" },
      { regex: /:=|==|!=|<=|>=|&&|\|\||[=<>!+\-*/%&|]/, token: "operator" }
    ],
    meta: { lineComment: "//" }
  });

  // ---- colorize static ```macro code blocks -----------------------------
  function colorizeSnippets() {
    document.querySelectorAll(".language-macro").forEach(function (el) {
      if (el.dataset.mmDone) return;
      var target = el.querySelector("code") || el;
      var text = target.textContent.replace(/\n+$/, "");
      var out = document.createElement("span");
      CodeMirror.runMode(text, "macromod", out);
      target.textContent = "";
      target.appendChild(out);
      target.classList.add("mm-cm");
      el.dataset.mmDone = "1";
    });
  }

  // ---- interactive editor (filled in by the engine deliverable) ---------
  function initEditor() {
    if (typeof window.MacroModInitEditor === "function") {
      window.MacroModInitEditor(CodeMirror);
    }
  }

  function ready() {
    colorizeSnippets();
    initEditor();
  }
  if (document.readyState !== "loading") ready();
  else document.addEventListener("DOMContentLoaded", ready);
})();
