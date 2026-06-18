/* MacroKeybindMod docs — DSL syntax highlighting + interactive in-browser editor.
   - Defines a CodeMirror "macromod" mode.
   - Colorizes static ```macro snippets.
   - On the editor page (#macromod-editor) builds a live editor + a faithful
     client-side interpreter for the platform-agnostic core of the DSL. */
(function () {
  "use strict";
  if (typeof CodeMirror === "undefined") return;

  // ======================================================================
  // 1. CodeMirror mode
  // ======================================================================
  var KEYWORDS = /\b(?:if|elseif|else|endif|do|loop|while|until|for|foreach|next|break|unsafe|endunsafe|repeat|forever)\b/;
  CodeMirror.defineSimpleMode("macromod", {
    start: [
      { regex: /\/\/.*/, token: "comment" },
      { regex: /"(?:[^\\"]|\\.)*"/, token: "string" },
      { regex: /\$\$\{|\}\$\$/, token: "meta" },
      { regex: /\$\$(?:\[[a-zA-Z0-9 _\-.]*\]|<[^>]+>|i:d|[0-9?idfuwthskmp!])/, token: "atom" },
      { regex: /%[@#&]?[a-zA-Z~][\w\-]*(?:\[\d+\])?%/, token: "variable-2" },
      { regex: KEYWORDS, token: "keyword" },
      { regex: /\b(?:true|false)\b/, token: "atom" },
      { regex: /[a-zA-Z_]+(?=\s*\()/, token: "def" },
      { regex: /[@#&][a-zA-Z~][\w\-]*(?:\[\d*\])?/, token: "variable" },
      { regex: /\b\d+\b/, token: "number" },
      { regex: /:=|==|!=|<=|>=|&&|\|\||[=<>!+\-*/%&|]/, token: "operator" }
    ],
    meta: { lineComment: "//" }
  });

  // ======================================================================
  // 2. Static snippet colorizing
  // ======================================================================
  function colorizeSnippets() {
    document.querySelectorAll(".language-macro").forEach(function (el) {
      if (el.dataset.mmDone) return;
      var target = el.querySelector("code") || el;
      var out = document.createElement("span");
      CodeMirror.runMode(target.textContent.replace(/\n+$/, ""), "macromod", out);
      target.textContent = "";
      target.appendChild(out);
      target.classList.add("mm-cm");
      el.dataset.mmDone = "1";
    });
  }

  // ======================================================================
  // 3. The client-side interpreter (platform-agnostic core)
  // ======================================================================
  var MC_ACTIONS = {
    key: 1, keydown: 1, keyup: 1, press: 1, look: 1, turn: 1, goto: 1, stopnav: 1,
    craft: 1, craftandwait: 1, pick: 1, sneak: 1, sprint: 1, use: 1, attack: 1,
    drop: 1, respawn: 1, disconnect: 1, playsound: 1, gui: 1, bind: 1
  };
  var LOOP_OPEN = { do: 1, for: 1, foreach: 1 };
  var LOOP_CLOSE = { loop: 1, while: 1, until: 1, next: 1 };
  var CONDS = { if: 1, elseif: 1, iif: 1, while: 1, until: 1 };

  function splitTop(text, delim) {
    var out = [], buf = "", q = false, i = 0;
    while (i < text.length) {
      var c = text[i];
      if (c === "\\" && i + 1 < text.length) { buf += c + text[i + 1]; i += 2; continue; }
      if (c === '"') { q = !q; buf += c; i++; continue; }
      if (c === delim && !q) { out.push(buf); buf = ""; i++; continue; }
      buf += c; i++;
    }
    out.push(buf);
    return out;
  }
  function dequote(s) {
    s = s.trim();
    if (s.length >= 2 && s[0] === '"' && s[s.length - 1] === '"')
      return s.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, "\\");
    return s;
  }
  function isVar(s) { return /^@?[#&]?[a-zA-Z~][\w\-]*(?:\[\d*\])?$/.test(s.trim()); }

  // modern brace syntax -> legacy statements (mirrors the Kotlin transpiler)
  function transpileModern(src) {
    var out = [], closers = [], n = 0;
    src.split("\n").forEach(function (raw) {
      var line = raw.trim().replace(/;$/, "").trim();
      if (!line || line.indexOf("//") === 0) return;
      if (line === "}") { out.push(closers.pop() || ""); return; }
      var m;
      if (/^}\s*else\s*\{$/.test(line)) { out.push("else;"); return; }
      if ((m = /^}\s*else\s*if\s+(.+?)\s*\{$/.exec(line))) { out.push("elseif(" + m[1].trim() + ");"); return; }
      if (/\{$/.test(line)) {
        var h = line.replace(/\{$/, "").trim();
        if (h.indexOf("if ") === 0) { out.push("if(" + h.slice(3).trim() + ");"); closers.push("endif;"); }
        else if (h.indexOf("while ") === 0) { out.push("do;if(!(" + h.slice(6).trim() + "));break;endif;"); closers.push("loop;"); }
        else if (h.indexOf("repeat ") === 0) { out.push("for(#__loop" + (n++) + ", 1, " + h.slice(7).trim() + ");"); closers.push("next;"); }
        else if (h === "forever") { out.push("do;"); closers.push("loop;"); }
        else if ((m = /^foreach\s+(\S+)\s+in\s+(\S+)$/.exec(h))) { out.push("foreach(" + m[1] + ", " + m[2] + ");"); closers.push("next;"); }
        else { out.push(h + ";"); closers.push(""); }
        return;
      }
      out.push(line + ";");
    });
    return out.join("");
  }

  function compile(source) {
    var prog = [];
    if (source.indexOf("$${") >= 0) {
      var re = /\$\$\{([\s\S]*?)\}\$\$/g, last = 0, m;
      while ((m = re.exec(source))) {
        if (m.index > last) emitChat(source.slice(last, m.index), prog);
        parseStatements(m[1], prog);
        last = re.lastIndex;
      }
      if (last < source.length) emitChat(source.slice(last), prog);
    } else {
      var body = /\{\s*$/m.test(source) ? transpileModern(source) : source;
      parseStatements(body, prog);
    }
    return prog;
  }
  function emitChat(text, prog) {
    text.split("\n").forEach(function (l) { if (l.trim() !== "") prog.push({ k: "chat", text: l }); });
  }
  function parseStatements(body, prog) {
    splitTop(body.replace(/\r/g, "\n").replace(/\n/g, ";"), ";").forEach(function (s) {
      var st = compileStatement(s);
      if (st) prog.push(st);
    });
  }
  function compileStatement(raw) {
    var s = raw.trim();
    if (!s || s.indexOf("//") === 0) return null;
    var eq = findAssign(s);
    if (eq > 0) {
      var isSet = s[eq - 1] === ":";
      var lhs = s.slice(0, isSet ? eq - 1 : eq).trim();
      if (isVar(lhs)) {
        var rhs = s.slice(eq + 1).trim();
        var call = /^([a-zA-Z_]+)\s*\(([\s\S]*)\)$/.exec(rhs);
        if (!isSet && call) return invoke(call[1], call[2], lhs);
        return { k: "inv", name: isSet ? "set" : "assign", args: [lhs, rhs], out: null };
      }
    }
    var c = /^([a-zA-Z_]+)\s*\(([\s\S]*)\)$/.exec(s);
    if (c) return invoke(c[1], c[2], null);
    var d = /^([a-zA-Z_]+)$/.exec(s);
    if (d) return { k: "inv", name: d[1], args: [], out: null };
    return { k: "inv", name: s.split("(")[0].trim(), args: [], out: null };
  }
  function invoke(name, argText, out) {
    var firstRaw = CONDS[name.toLowerCase()];
    var args = argText.trim() === "" ? [] : splitTop(argText, ",").map(function (a, i) {
      return (i === 0 && firstRaw) ? a.trim() : dequote(a);
    });
    return { k: "inv", name: name, args: args, out: out };
  }
  function findAssign(s) {
    var q = false;
    for (var i = 0; i < s.length; i++) {
      var c = s[i];
      if (c === '"') q = !q;
      else if (!q && c === "=") {
        var p = s[i - 1] || " ", nx = s[i + 1] || " ";
        if (p === "!" || p === "<" || p === ">" || p === "=") continue;
        if (nx === "=") { i++; continue; }
        return i;
      }
    }
    return -1;
  }

  // ---- variables --------------------------------------------------------
  function Vars() { this.local = {}; this.shared = {}; this.arr = {}; }
  function parseName(name) {
    var m = /^(@?)([#&]?)([a-zA-Z~][\w\-]*)(\[(\d*)\])?$/.exec(name.trim());
    if (!m) return null;
    return { shared: m[1] === "@", type: m[2] === "#" ? "n" : m[2] === "&" ? "s" : "b",
      name: m[3].toLowerCase(), idx: m[4] ? (m[5] === "" ? -1 : +m[5]) : null,
      key: (m[2] || "") + m[3].toLowerCase() };
  }
  Vars.prototype.store = function (v) { return v.shared ? this.shared : this.local; };
  Vars.prototype.get = function (name) {
    var v = parseName(name); if (!v) return null;
    if (v.idx != null && v.idx >= 0) { var a = this.arr[(v.shared ? "@" : "") + v.key]; return a ? a[v.idx] : undefined; }
    return this.store(v)[v.key];
  };
  Vars.prototype.set = function (name, val) {
    var v = parseName(name); if (!v) return;
    var coerced = v.type === "n" ? toInt(val) : v.type === "b" ? toBool(val) : String(val);
    if (v.idx != null && v.idx >= 0) {
      var ak = (v.shared ? "@" : "") + v.key; (this.arr[ak] = this.arr[ak] || [])[v.idx] = coerced;
    } else this.store(v)[v.key] = coerced;
  };
  Vars.prototype.array = function (name) { var v = parseName(name); if (!v) return []; return (this.arr[(v.shared ? "@" : "") + v.key] || []).filter(function (x) { return x !== undefined; }); };
  Vars.prototype.push = function (name, val) { var v = parseName(name); if (!v) return; var ak = (v.shared ? "@" : "") + v.key; (this.arr[ak] = this.arr[ak] || []).push(v.type === "n" ? toInt(val) : String(val)); };
  Vars.prototype.pop = function (name) { var v = parseName(name); if (!v) return undefined; var a = this.arr[(v.shared ? "@" : "") + v.key]; return a && a.length ? a.pop() : undefined; };

  function toInt(x) { if (typeof x === "number") return x | 0; if (typeof x === "boolean") return x ? 1 : 0; var n = parseInt(x, 10); return isNaN(n) ? 0 : n; }
  function toBool(x) { if (typeof x === "boolean") return x; if (typeof x === "number") return x !== 0; var s = String(x).toLowerCase(); return s === "true" || (parseInt(s, 10) || 0) !== 0; }

  // ---- %var% expansion --------------------------------------------------
  function expand(text, vars) {
    if (text.indexOf("%") < 0) return text;
    var re = /%(@?[#&]?[a-zA-Z~][\w\-]*(?:\[\d+\])?)%/, out = text, i = 0;
    while (i++ < 256) {
      var m = re.exec(out); if (!m) break;
      var val = vars.get(m[1]);
      var rep = val === undefined || val === null ? defaultFor(m[1]) : String(val);
      out = out.slice(0, m.index) + rep + out.slice(m.index + m[0].length);
    }
    return out;
  }
  function defaultFor(name) { var v = parseName(name); return !v ? "" : v.type === "n" ? "0" : v.type === "b" ? "False" : ""; }

  // ---- expression evaluator (precedence climbing) -----------------------
  function evaluate(expr, vars) {
    var src = expand(expr, vars), toks = lex(src), pos = 0;
    function peek() { return toks[pos]; }
    function next() { return toks[pos++]; }
    function prim() {
      var t = next();
      if (!t) return 0;
      if (t.t === "num") return t.v;
      if (t.t === "str") return t.v;
      if (t.t === "(") { var e = expr2(0); if (peek() && peek().t === ")") next(); return e; }
      if (t.t === "op" && (t.v === "!" || t.v === "-")) { var o = prim(); return t.v === "!" ? !truthy(o) : -toInt(o); }
      if (t.t === "id") { var lo = t.v.toLowerCase(); if (lo === "true") return true; if (lo === "false") return false; var gv = vars.get(t.v); return gv === undefined ? 0 : gv; }
      return 0;
    }
    var BP = { "||": 1, "|": 1, "&&": 2, "&": 2, "==": 3, "=": 3, "!=": 3, "<": 3, "<=": 3, ">": 3, ">=": 3, "+": 4, "-": 4, "*": 5, "/": 5, "%": 5 };
    function expr2(min) {
      var left = prim();
      while (peek() && peek().t === "op" && BP[peek().v] >= min && min > 0 === min > 0) {
        var op = peek().v, bp = BP[op]; if (bp == null || bp < min) break; next();
        var right = expr2(bp + 1); left = apply(op, left, right);
      }
      return left;
    }
    return expr2(1);
  }
  function lex(s) {
    var t = [], i = 0;
    while (i < s.length) {
      var c = s[i];
      if (/\s/.test(c)) { i++; continue; }
      if (c === "(") { t.push({ t: "(" }); i++; continue; }
      if (c === ")") { t.push({ t: ")" }); i++; continue; }
      if (c === '"') { var j = i + 1, b = ""; while (j < s.length && s[j] !== '"') { b += s[j]; j++; } t.push({ t: "str", v: b }); i = j + 1; continue; }
      if (/\d/.test(c)) { var k = i; while (k < s.length && /\d/.test(s[k])) k++; t.push({ t: "num", v: +s.slice(i, k) }); i = k; continue; }
      if (c === "&" && s[i + 1] === "&") { t.push({ t: "op", v: "&&" }); i += 2; continue; }
      if (c === "|") { if (s[i + 1] === "|") { t.push({ t: "op", v: "||" }); i += 2; } else { t.push({ t: "op", v: "|" }); i++; } continue; }
      if (/[a-zA-Z_#&@~]/.test(c)) { var p = i; if (s[p] === "@") p++; if (s[p] === "#" || s[p] === "&") p++; while (p < s.length && /[\w~\-\[\]]/.test(s[p])) p++; t.push({ t: "id", v: s.slice(i, p) }); i = p; continue; }
      var two = s.substr(i, 2);
      if (["==", "!=", "<=", ">="].indexOf(two) >= 0) { t.push({ t: "op", v: two }); i += 2; continue; }
      if ("+-*/%<>=!".indexOf(c) >= 0) { t.push({ t: "op", v: c }); i++; continue; }
      i++;
    }
    return t;
  }
  function truthy(v) { if (typeof v === "boolean") return v; if (typeof v === "number") return v !== 0; var s = String(v).toLowerCase(); return s === "true" || (parseInt(s, 10) || 0) !== 0; }
  function num(v) { return typeof v === "number" ? v : toInt(v); }
  function apply(op, l, r) {
    switch (op) {
      case "+": return num(l) + num(r); case "-": return num(l) - num(r);
      case "*": return num(l) * num(r); case "/": return num(r) === 0 ? 0 : (num(l) / num(r)) | 0;
      case "%": return num(r) === 0 ? 0 : num(l) % num(r);
      case "==": case "=": return eq(l, r); case "!=": return !eq(l, r);
      case "<": return num(l) < num(r); case "<=": return num(l) <= num(r);
      case ">": return num(l) > num(r); case ">=": return num(l) >= num(r);
      case "&&": case "&": return truthy(l) && truthy(r); case "||": case "|": return truthy(l) || truthy(r);
    }
    return 0;
  }
  function eq(l, r) { if (typeof l === "string" || typeof r === "string") return String(l) === String(r); return num(l) === num(r); }

  // ---- interpreter ------------------------------------------------------
  function run(source, emit) {
    var vars = new Vars(), prog = compile(source), stack = [], ptr = 0, steps = 0, MAX = 200000;
    function live() { return stack.every(function (f) { return f.cf; }); }
    function parentLive() { return stack.slice(1).every(function (f) { return f.cf; }); }
    var ctx = { vars: vars, emit: emit, expand: function (t) { return expand(t, vars); }, eval: function (e) { return evaluate(e, vars); } };
    while (ptr < prog.size || ptr < prog.length) {
      if (++steps > MAX) { emit("error", "stopped: step limit (possible infinite loop)"); return; }
      var ins = prog[ptr];
      if (!ins) break;
      if (ins.k === "chat") { if (live()) ins.text.split("|").forEach(function (l) { if (l) emit("chat", expand(l, vars)); }); ptr++; continue; }
      var op = operator(ins.name);
      if (op === "STOP") { return; }
      if (op === "ENDIF" || op === "ENDUNSAFE") { stack.pop(); ptr++; continue; }
      if (op === "ELSE") { var f = stack[stack.length - 1]; if (f) { f.cf = parentLive() && !f.ifFlag; f.elseFlag = true; f.ifFlag = true; } ptr++; continue; }
      if (op === "ELSEIF") { var fe = stack[stack.length - 1]; if (fe) { if (fe.elseFlag || fe.ifFlag) fe.cf = false; else { var c = parentLive() && (ins.args.length === 0 ? true : truthy(evaluate(ins.args[0], vars))); fe.cf = c; if (c) fe.ifFlag = true; } } ptr++; continue; }
      if (op === "IF" || op === "UNSAFE") { var c2 = op === "UNSAFE" ? live() : (live() && truthy(evaluate(ins.args[0] || "1", vars))); stack.push({ cf: c2, ifFlag: c2, elseFlag: false, name: ins.name, args: ins.args }); ptr++; continue; }
      if (op === "LOOP_OPEN") { var fl = { cf: live(), body: ptr + 1, name: ins.name, args: ins.args, st: null }; if (fl.cf) fl.cf = enterLoop(ins, fl, ctx); stack.push(fl); ptr++; continue; }
      if (op === "LOOP_CLOSE") { var top = stack[stack.length - 1]; if (!top) { ptr++; continue; } var cont = top.cf && loopBack(ins, top, ctx); if (cont) ptr = top.body; else { stack.pop(); ptr++; } continue; }
      if (op === "BREAK") { if (live()) for (var b = stack.length - 1; b >= 0; b--) if (LOOP_OPEN[stack[b].name]) { stack[b].cf = false; break; } ptr++; continue; }
      if (live()) execAction(ins, ctx);
      ptr++;
    }
    if (stack.length) emit("error", "unterminated block (missing closer)");
  }
  function operator(name) {
    var n = name.toLowerCase();
    if (n === "if") return "IF"; if (n === "elseif") return "ELSEIF"; if (n === "else") return "ELSE"; if (n === "endif") return "ENDIF";
    if (n === "unsafe") return "UNSAFE"; if (n === "endunsafe") return "ENDUNSAFE";
    if (LOOP_OPEN[n]) return "LOOP_OPEN"; if (LOOP_CLOSE[n]) return "LOOP_CLOSE";
    if (n === "break") return "BREAK"; if (n === "stop") return "STOP";
    return "NORMAL";
  }
  function enterLoop(ins, f, ctx) {
    var n = ins.name.toLowerCase();
    if (n === "do") return true;
    if (n === "for") { var v = ins.args[0].trim(), a = ctx.eval(ins.args[1]), b = ctx.eval(ins.args[2]), s = ins.args.length > 3 ? (ctx.eval(ins.args[3]) || 1) : 1; ctx.vars.set(v, a); f.st = { v: v, cur: num(a), end: num(b), step: num(s) }; return f.st.step > 0 ? f.st.cur <= f.st.end : f.st.cur >= f.st.end; }
    if (n === "foreach") { var lv = ins.args[0].trim(), vals = ctx.vars.array(ins.args[1].trim()); f.st = { v: lv, vals: vals, i: 1 }; if (!vals.length) return false; ctx.vars.set(lv, vals[0]); return true; }
    return true;
  }
  function loopBack(ins, f, ctx) {
    var n = ins.name.toLowerCase();
    if (n === "loop") return true;
    if (n === "while") return truthy(ctx.eval(ins.args[0]));
    if (n === "until") return !truthy(ctx.eval(ins.args[0]));
    if (n === "next") { var st = f.st; if (!st) return false; if (st.vals) { if (st.i >= st.vals.length) return false; ctx.vars.set(st.v, st.vals[st.i++]); return true; } st.cur += st.step; ctx.vars.set(st.v, st.cur); return st.step > 0 ? st.cur <= st.end : st.cur >= st.end; }
    return false;
  }
  function execAction(ins, ctx) {
    var n = ins.name.toLowerCase(), a = ins.args, v = ctx.vars, val;
    function A(i) { return ctx.expand(a[i] != null ? a[i] : ""); }
    if (MC_ACTIONS[n]) { ctx.emit("mc", n + "(" + a.map(function (x) { return ctx.expand(x); }).join(", ") + ")"); return; }
    switch (n) {
      case "log": case "echo": ctx.emit("log", A(0)); return;
      case "sendmessage": ctx.emit("chat", A(0)); return;
      case "set": v.set(a[0].trim(), dequote(A(1))); return;
      case "assign": v.set(a[0].trim(), ctx.eval(a[1])); return;
      case "inc": v.set(a[0].trim(), num(v.get(a[0].trim()) || 0) + (a.length > 1 ? num(ctx.eval(a[1])) : 1)); return;
      case "dec": v.set(a[0].trim(), num(v.get(a[0].trim()) || 0) - (a.length > 1 ? num(ctx.eval(a[1])) : 1)); return;
      case "unset": { var p = parseName(a[0].trim()); if (p) delete v.store(p)[p.key]; return; }
      case "toggle": v.set(a[0].trim(), !truthy(v.get(a[0].trim()))); return;
      case "push": v.push(a[0].trim(), A(1)); val = null; break;
      case "pop": val = v.pop(a[0].trim()); break;
      case "arraysize": val = v.array(a[0].trim()).length; break;
      case "calc": val = ctx.eval(a[0]); break;
      case "iif": val = truthy(ctx.eval(a[0])) ? A(1) : A(2); break;
      case "lcase": val = A(0).toLowerCase(); break;
      case "ucase": val = A(0).toUpperCase(); break;
      case "length": val = A(0).length; break;
      case "trim": val = A(0).trim(); break;
      case "substr": { var s0 = A(0), st0 = num(ctx.eval(a[1])); val = a.length > 2 ? s0.substr(st0, num(ctx.eval(a[2]))) : s0.substr(st0); break; }
      case "replace": val = A(0).split(A(1)).join(A(2)); break;
      case "indexof": val = A(0).indexOf(A(1)); break;
      case "random": { var lo = a.length >= 2 ? num(ctx.eval(a[0])) : 0, hi = a.length >= 2 ? num(ctx.eval(a[1])) : (a.length === 1 ? num(ctx.eval(a[0])) - 1 : 99); val = lo + Math.floor(Math.random() * (hi - lo + 1)); break; }
      case "abs": val = Math.abs(num(ctx.eval(a[0]))); break;
      case "min": val = Math.min(num(ctx.eval(a[0])), num(ctx.eval(a[1]))); break;
      case "max": val = Math.max(num(ctx.eval(a[0])), num(ctx.eval(a[1]))); break;
      case "pass": return;
      default: ctx.emit("mc", n + "(" + a.join(", ") + ")"); return;
    }
    if (ins.out != null && val !== undefined && val !== null) v.set(ins.out.trim(), val);
  }

  // ======================================================================
  // 4. Editor page
  // ======================================================================
  var SAMPLES = [
    { name: "Counter (for / %var%)", code: '$${\n  for(#i, 1, 5);\n    log("tick %#i%");\n  next;\n}$$' },
    { name: "Conditional (if / else)", code: '$${\n  #hp := 4;\n  if(#hp < 5);\n    log("DANGER hp=%#hp%");\n  else;\n    log("fine");\n  endif;\n}$$' },
    { name: "Strings + capture", code: '$${\n  &name := "steve";\n  &up = ucase("%&name%");\n  log("hello %&up%");\n}$$' },
    { name: "do / while loop", code: '$${\n  #n := 0;\n  do;\n    inc(#n);\n    log("n=%#n%");\n  while(#n < 3);\n}$$' },
    { name: "Modern braces", code: '#x = 7\nif #x > 5 {\n  log("big")\n} else {\n  log("small")\n}\nrepeat 3 {\n  log("hi")\n}' },
    { name: "MC actions (echoed)", code: '$${\n  log("attacking + aiming");\n  key(attack);\n  look(0, 0);\n  goto(10, 64, 12);\n}$$' }
  ];

  function initEditor() {
    var root = document.getElementById("macromod-editor");
    if (!root) return;
    var ta = document.getElementById("mm-source");
    var cm = CodeMirror.fromTextArea(ta, {
      mode: "macromod", lineNumbers: true, lineWrapping: true, tabSize: 2, indentUnit: 2, viewportMargin: Infinity
    });
    var sel = document.getElementById("mm-sample");
    SAMPLES.forEach(function (s, i) { var o = document.createElement("option"); o.value = i; o.textContent = s.name; sel.appendChild(o); });
    sel.addEventListener("change", function () { var s = SAMPLES[+sel.value]; if (s) { cm.setValue(s.code); doRun(); } });

    var output = document.getElementById("mm-output");
    function doRun() {
      var lines = [];
      try {
        run(cm.getValue(), function (kind, text) {
          var prefix = kind === "chat" ? "[chat] " : kind === "mc" ? "[mc]   " : kind === "error" ? "[!] " : "";
          lines.push(prefix + text);
        });
      } catch (e) { lines.push("[!] " + e.message); }
      output.textContent = lines.length ? lines.join("\n") : "(no output)";
    }
    document.getElementById("mm-run").addEventListener("click", doRun);
    document.getElementById("mm-clear").addEventListener("click", function () { output.textContent = ""; });
    cm.addKeyMap({ "Ctrl-Enter": doRun, "Cmd-Enter": doRun });
    doRun(); // run the default sample on load
  }

  function ready() { colorizeSnippets(); initEditor(); }
  if (document.readyState !== "loading") ready();
  else document.addEventListener("DOMContentLoaded", ready);
})();
