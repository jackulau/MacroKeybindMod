# Parameters & Variables

MacroMod has **two** substitution systems that are easy to confuse. Getting them
straight is the key to writing scripts that behave:

| System | When | Looks like | Purpose |
| --- | --- | --- | --- |
| **`$$` parameters** | **compile time** (once, before the macro runs) | `$$?`, `$$i`, `$$<file>` | prompts, pickers, file includes |
| **`%var%` expansion** | **run time** (every time an instruction executes) | `%#count%`, `%&name%` | inject current variable values |

## `$$` parameters (compile time)

`$$` codes are resolved **before** the macro executes. They produce text that becomes
part of the compiled macro. Because they run once at compile time, an interactive code
like `$$?` prompts you a single time, and the typed value is baked in.

A `$$` is only "live" when not preceded by a backslash; write `\$$` for a literal.

### Code table

| Code | Expands to |
| --- | --- |
| `$$?` | **Prompt** — opens a text field; your input is substituted |
| `$$0` … `$$9` | **Preset positional** values supplied to the macro |
| `$$[name]` | **Named prompt** — labelled with `name` |
| `$$i` / `$$d` / `$$i:d` | **Item picker** — id / damage / `id:damage` |
| `$$f` | **Friend** name picker |
| `$$u` | Online **user** name picker |
| `$$t` / `$$w` / `$$h` | **Town** / **warp** / **home** name |
| `$$<file.txt>` | **Include** — splices another script file's contents |
| `$$!` | **Stop** — truncates here; the last chat line opens chat with the buffer instead of sending |
| `$${ … }$$` | A **script block** (the script island itself) |

!!! example
    ```text
    /msg $$f hello             # pick a friend, then send /msg <friend> hello
    /give $$u $$i $$?          # pick a player, an item, and prompt for a quantity
    $$<greetings.txt>          # splice in another script file
    ```

In the pure engine, interactive codes are resolved through a **`ParamResolver`** the
host supplies (the Fabric mod opens the real pickers/prompts). With no resolver, an
interactive code resolves to empty — handy for headless testing.

## `%var%` expansion (run time)

`%name%` is replaced with the variable's **current** value, every time the surrounding
instruction runs. This is what you use inside loops:

```macro
$${ for(#i, 1, 3); log("iteration %#i%"); next }$$
# logs: iteration 1 / iteration 2 / iteration 3
```

### Defaults

If a variable is unset, `%var%` falls back to a type-appropriate default:

| Type | Unset value |
| --- | --- |
| counter `#` | `0` |
| string `&` | empty |
| flag *(none)* | `False` |

### Cascading

Expansion repeats until no `%…%` remain (bounded to prevent loops), so values can
reference other variables:

```macro
$${ &target := "%&player%"; &cmd := "/tp %&target%"; }$$
```

### Quoting for expressions

When a string variable is fed into an expression, its value is automatically quoted so
it stays a string rather than being parsed as a number or name. You normally don't
think about this — `if(&name == "bob")` just works whether you write `&name` or
`%&name%`.

## Putting it together

```macro
$${
  // $$?  → asked ONCE at compile time
  &who := "$$?";
  // %&who% → expanded each loop iteration at run time
  for(#i, 1, 3);
    log("hello %&who% (%#i%)");
  next;
}$$
```

Compile time bakes in *who* once; run time stamps the counter each pass. That division
of labour is the whole point of having two systems.
