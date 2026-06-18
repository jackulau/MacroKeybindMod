# The DSL Language

MacroKeybindMod scripts are written in a small but capable language. This page is the
complete guide to it: how source becomes instructions, the variable and expression
systems, every control-flow construct, and the modern brace syntax that sits on top.

For the byte-level specification (regexes, the original engine's exact behaviour),
see the [DSL Reference](../DSL-REFERENCE.md). This page is the *practical* guide.

---

## The big picture

A **macro** is a single string bound to a trigger. It is a mix of **literal chat
text** and embedded **script blocks**:

```macro
hello $${ log("hi") }$$ world
```

Compiling that produces three instructions: send `hello `, run `log("hi")`, send
` world`. There is no top-level grammar beyond "chat text, with `$${ … }$$` islands of
script."

Compilation happens in two phases:

1. **Parameter substitution** (compile time) — `$$` codes like `$$?` (prompt) and
   `$$<file>` (include) are resolved *before* anything runs. See
   [Parameters & Variables](parameters.md).
2. **Compilation** — the text is split into chat parts and script parts; script parts
   are parsed into a flat list of instructions.

`%var%` references, by contrast, are expanded at **run time**, each time an
instruction executes.

---

## Statements

Inside a `$${ … }$$` block, statements are separated by `;`:

```macro
$${ log("one"); log("two"); log("three") }$$
```

In a standalone `.txt` script file, newlines separate statements too, so you can write
one per line without semicolons.

A statement is one of:

| Form | Example | Meaning |
| --- | --- | --- |
| Action call | `log("hi")` | invoke an action with arguments |
| Directive | `break` | invoke an action with no arguments |
| Assignment | `#x = 5` | evaluate and store into a variable |
| Capture | `&n = lcase("AB")` | run an action, store its result |

Comments use `//` and run to the end of the statement:

```macro
$${ // this whole line is ignored
   log("real") }$$
```

---

## Variables

Variables are **typed by a sigil** on their name:

| Sigil | Type | Default | Example |
| :---: | --- | --- | --- |
| `#` | counter (integer) | `0` | `#count`, `#scores[3]` |
| `&` | string | `""` | `&name`, `&items[0]` |
| *(none)* | flag (boolean) | `false` | `ready`, `automine` |
| `@` *(prefix)* | shared / global modifier | — | `@#gold`, `@&motd` |

A name is `[@][#&]name[index]`: an optional `@` for shared scope, an optional type
sigil, a name starting with a letter (or `~`), and an optional `[n]` array index.

### Scopes

- **Local** (no `@`) — lives only for this macro's execution. The default.
- **Shared** (`@` prefix) — global, persists across macros (and, in the full mod, to
  disk). `@#gold` and `#gold` are *different* variables.

```macro
$${ #x := 1; @#x := 99;
   log("local %#x%, shared %@#x%");   // local 1, shared 99
}$$
```

### Arrays

Any variable can be an array. `name[3]` is element 3; `name[]` is the whole-array
specifier used by `foreach` and the array actions:

```macro
$${
  push(&list[], "a");
  push(&list[], "b");
  log("size %arraysize(&list[])%");   // wait — see "capture" below
}$$
```

Array operations: `push` (append), `pop` (remove last), `put` (first free slot),
`arraysize`, plus indexing with `&list[0]`.

### Assignment: `=` vs `:=`

- **`:=` stores a literal string.** The right-hand side is taken verbatim (quotes
  stripped). `%var%` references inside it are expanded *lazily*, when the value is later
  used.
- **`=` evaluates an expression** and stores the typed result.

```macro
$${
  #total = 2 + 3 * 4;        // expression  → 14
  &greeting := "hi %&name%"; // literal      → expanded later
}$$
```

The stored value is **coerced to the variable's type**: assigning `42` to `&s` stores
`"42"`; assigning `"5"` to `#n` stores `5`.

### Capture (out-variables)

If the right-hand side of `=` is an *action call*, the action runs and its return value
is captured:

```macro
$${
  &upper = ucase("hello");    // &upper = "HELLO"
  #where = indexof("hello", "ll");  // #where = 2
  &last  = pop(&list[]);      // removes + captures the last element
}$$
```

---

## Expressions

Expressions appear in `if`/`while`/`until` conditions, in `=` assignments, and in
`calc(...)`. They produce a typed value (integer, string or boolean).

### Operators

| Group | Operators |
| --- | --- |
| Arithmetic | `+` `-` `*` `/` `%` |
| Comparison | `==` (`=`) `!=` `<` `<=` `>` `>=` |
| Logical | `&&` `||` `!` |
| Grouping | `( … )` |

!!! info "Real operator precedence"
    The original engine evaluated left-to-right with **no** arithmetic precedence, so
    `2+3*4` was `(2+3)*4`. MacroKeybindMod uses a proper precedence-climbing parser, so
    `2 + 3 * 4 == 14` as you'd expect. Parenthesise if you ever want the old grouping.

Precedence, lowest to highest: `||` → `&&` → comparisons → `+ -` → `* / %` → unary
`! -` → `( )`.

### Truthiness

A condition is true when: a boolean is `true`, an integer is non-zero, or a string is
`"true"` / parses to a non-zero number / is non-empty.

### Strings

String literals use double quotes. Equality compares by value:

```macro
$${ if(&name == "bob"); log("hi bob"); endif }$$
```

Variables can be referenced **bare** in expressions (`#count > 5`) or via `%count%`
expansion — both resolve to the current value.

---

## Control flow

Control flow is reconstructed at run time from a single instruction pointer and an
operator stack (max nesting depth 32). You don't need to know the mechanism — see
[Architecture](architecture.md#the-runtime-vm) if you're curious — but the constructs
behave exactly as you'd expect.

### Conditionals — `if` / `elseif` / `else` / `endif`

```macro
$${
  if(#hp < 5);
    log("danger");
  elseif(#hp < 10);
    log("careful");
  else;
    log("fine");
  endif;
}$$
```

The first matching branch runs; the rest are skipped. `elseif` with no argument acts
like `else`.

### `do … loop` — infinite loop

Runs forever until a `break`:

```macro
$${ do; key(attack); if(stop); break; endif; loop }$$
```

### `do … while` / `do … until` — post-check loop

The body runs at least once; the condition is checked at the bottom:

```macro
$${ #i := 0; do; inc(#i); log("%#i%"); while(#i < 3) }$$   // 1, 2, 3
```

`until(cond)` is the negation — loop until the condition becomes true.

### `for … next` — counted loop

`for(var, start, end [, step])` is inclusive and may run zero times:

```macro
$${ for(#i, 1, 5); log("%#i%"); next }$$        // 1 2 3 4 5
$${ for(#i, 10, 1, -1); log("%#i%"); next }$$    // 10 9 … 1
```

### `foreach … next` — iterate an array

```macro
$${
  push(&friends[], "alice"); push(&friends[], "bob");
  foreach(&f, &friends[]); log("hi %&f%"); next;
}$$
```

### `break`

Exits the innermost loop. A `break` inside a false branch does nothing (it is gated by
the conditional state).

### `unsafe … endunsafe`

Lifts the per-tick instruction throttle for a tight section. Use sparingly.

---

## Two syntaxes, one runtime

Everything above is the **legacy MKB syntax**. MacroKeybindMod also ships a **modern
brace syntax** that *transpiles* to the legacy form, so the two are fully
interoperable and run on the same VM.

| Modern | Transpiles to |
| --- | --- |
| `if cond { … }` | `if(cond); … ; endif` |
| `} elseif cond {` / `} else if cond {` | `elseif(cond);` |
| `} else {` | `else;` |
| `while cond { … }` | pre-check loop (`do; if(!(cond)); break; endif; …; loop`) |
| `repeat N { … }` | `for(#__loop, 1, N); … ; next` |
| `forever { … }` | `do; … ; loop` |
| `foreach v in arr { … }` | `foreach(v, arr); … ; next` |
| `name(args)` / `var = expr` | unchanged — same statements |

!!! note "`while` semantics differ by syntax"
    Legacy `do … while(c)` is **post-check** (runs once, then tests). Modern
    `while c { … }` is **pre-check** (tests first, may run zero times). Pick the one
    that matches your intent.

A complete modern example:

```macro
#i = 0
while #i < 3 {
  #i = #i + 1
  log("tick %#i%")
}

foreach &f in &friends[] {
  if &f == "bob" {
    log("found bob")
  }
}
```

---

## Worked example: an auto-fisher sketch

```macro
$${
  set(fishing);
  #casts := 0;
  do;
    key(use);              // cast
    // (real version waits for a bite event here)
    inc(#casts);
    log("cast #%#casts%");
    if(#casts >= 64); unset(fishing); endif;
  while(fishing);
  log("done after %#casts% casts");
}$$
```

Read on: [Parameters & Variables](parameters.md) covers the `$$`/`%var%` systems in
full, and the [Catalog](../catalog/ACTIONS.md) lists every action.
