# Script Editor

Write a MacroKeybindMod script and **run it live in your browser** — no install needed. The
editor highlights the DSL as you type and executes it with a faithful client-side
interpreter (the same language the [engine](guide/dsl-language.md) runs).

!!! note "What runs here"
    The browser runs the **platform-agnostic** core of the DSL — control flow, variables,
    expressions, `%var%` expansion, and the output/logging actions (`log`, `echo`,
    `sendmessage`, `set`, `inc`, …). Minecraft-bound actions (`key`, `look`, `goto`, …) are
    recognised and shown in the run log as `[mc] key(attack)` rather than actually moving a
    player. It's for writing and checking script logic, exactly like an editor.

<div id="macromod-editor" class="mm-editor" markdown="0">
  <div class="mm-toolbar">
    <label for="mm-sample">Sample:</label>
    <select id="mm-sample" aria-label="Load a sample script"></select>
    <button id="mm-run" type="button">▶ Run</button>
    <button id="mm-clear" type="button">Clear output</button>
  </div>
  <textarea id="mm-source" spellcheck="false" aria-label="MacroKeybindMod script">$${
  // Count to 5, then greet
  for(#i, 1, 5);
    log("tick %#i%");
  next;
  &who := "world";
  log("hello %&who%");
}$$</textarea>
  <div class="mm-output-wrap">
    <div class="mm-output-head">Output</div>
    <pre id="mm-output" class="mm-output" aria-live="polite"></pre>
  </div>
</div>

## Quick reference

| Want to… | Write |
| --- | --- |
| Log a line | `log("text %#var%")` |
| Set a number / string / flag | `#n = 5` · `&s := "hi"` · `set(flag)` |
| Loop a fixed number of times | `for(#i, 1, 10); … ; next` |
| Loop while a condition holds | `do; … ; while(#x < 10)` |
| Branch | `if(#hp < 5); … ; else; … ; endif` |
| Modern braces | `repeat 3 { … }` · `if x > 3 { … }` |

Full language: **[The DSL Language](guide/dsl-language.md)** · every verb: **[Actions](catalog/ACTIONS.md)**.
