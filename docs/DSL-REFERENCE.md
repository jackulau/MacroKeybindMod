# Macro / Keybind Mod DSL — Reimplementation Reference

Reverse-engineered from the decompiled LiteLoader **Macro/Keybind Mod** (`net.eq2online.macros`).
This document is the authoritative spec for a from-scratch reimplementation (Kotlin/Java on Fabric).
Every regex, sigil, sentinel char, and method signature below is taken verbatim from the decompiled source.

> **Source roots referenced** (all under `reference/decompiled/net/eq2online/macros/`):
> - `core/Macro.java`, `core/executive/MacroActionProcessor.java`, `core/executive/MacroAction.java`, `core/executive/MacroActionStackEntry.java`, `core/executive/MacroActionChat.java`, `core/executive/MacroActionContext.java`
> - `core/params/*` + `core/params/providers/*` (the `$$` engine), `core/MacroIncludeProcessor.java`, `core/MacroExecVariableProvider.java`
> - `scripting/parser/*` (ScriptParser, ScriptCore, ScriptAction, ActionParser*, ScriptContext, Unrecognised/DeniedAction)
> - `scripting/Variable.java`, `scripting/VariableExpander.java`, `scripting/ExpressionEvaluator.java`, `scripting/ScriptActionProvider.java`
> - `scripting/variable/*` + `scripting/variable/providers/*`
> - `scripting/actions/{lang,game,input,option,mod,imc}/*` (the action catalog)
> - `scripting/api/*` (all interfaces), `scripting/ModuleLoader.java`, `scripting/LoadedModuleInfo.java`

**Decompiler note:** obfuscated Minecraft types appear as short names (`bib`=Minecraft client, `hh`=IChatComponent/text, `aip`=ItemStack, `nf`=ResourceLocation, `qg`=SoundCategory, `rk`=MathHelper, `rp`=text-strip util, `a`=GameSettings inner). These are MC internals, not part of the engine. Hex escapes the decompiler emitted: `\x24`=`$`, `\x5C`=`\`, `\x7C`=`|`, `\x22`=`"`, `\x3F`=`?`, `\x20`=space, `\x21`=`!`. Several **control chars are used as internal sentinels**: ``, ``, ``, ``, `¬` (U+00AC), `££` (U+00A3 ×2).

---

## Table of Contents

1. [Lexical Grammar](#1-lexical-grammar)
2. [Parsing Model](#2-parsing-model)
3. [Execution Model](#3-execution-model)
4. [Variable System](#4-variable-system)
5. [Expression Evaluation](#5-expression-evaluation)
6. [`$$` Parameter Substitution](#6--parameter-substitution)
7. [Full Action Catalog](#7-full-action-catalog)
8. [Extension / Module API](#8-extension--module-api)
9. [Reimplementation Checklist](#9-reimplementation-checklist)

---

## 1. Lexical Grammar

A **macro** is a single line of text (bound to a key or run from the REPL/another macro). It is a mix of **literal chat text** and embedded **script blocks**. There is no top-level statement grammar — the structure is purely "chat text, with `$${ … }$$` islands of script."

### 1.1 Two-phase pipeline

Source text is transformed in this order before it ever produces actions:

```
raw macro string
  └─ Phase A: PARAMETER SUBSTITUTION   (core/params, core/Macro.compileMacro)
        processStops  ($$!)            → truncate at $$!, set stop flag
        processIncludes ($$<file.txt>) → splice file contents
        processEscapes (\$$  \|)       → neutralize escaped sequences
        processPrompts                 → prompt(...) rewrite
        evaluateParams ($$?, $$i, …)   → run every $$ provider, substitute earliest match
  └─ Phase B: COMPILATION              (MacroActionProcessor.compile / constructor)
        split on $${ … }$$             → chat parts vs script parts
        chat parts  → MacroActionChat
        script parts→ ScriptParser.parseScript → List<IMacroAction>
```

Phase A is iterative: `MacroParamTarget.compileMacro()` re-runs `evaluateParams()` and bumps an `iteration` counter until no more `$$` params match (prompts/includes can introduce new ones). Phase B produces a flat `List<IMacroAction>` — see [§2](#2-parsing-model).

### 1.2 The `$${ … }$$` script-block delimiters

**Producer/highlighter pattern** (`core/Macro.java`):
```java
public static final String PREFIX_PARAM = "$$";
protected static final Pattern PATTERN_SCRIPT =
    Pattern.compile("(?<![\\x5C])\\x24\\x24\\{(.*?)(\\}\\x24\\x24|$)");
//                   (?<![ \  or  ])  $$ {  (.*?)  ( }$$  |  end-of-string )
```
**Consumer pattern** (`core/executive/MacroActionProcessor.java`):
```java
private static Pattern PATTERN_SCRIPT = Pattern.compile("\\x24\\x24\\{(.*?)\\}\\x24\\x24");
//                                                        $$ {  (.*?)  }$$
```

Rules:
- A script block is `$${` … `}$$`. **`group(1)` (reluctant `.*?`) is the script body.**
- The producer pattern is escapable and tolerates an unterminated block (the `( }$$ | $ )` alternation; the highlighter colours an unclosed block differently by checking `group(2).length() > 0`). The runtime consumer pattern requires a closing `}$$`.
- A `$$` is only "live" when **not** preceded by a backslash `\` or the invisible-escape sentinel `` (negative lookbehind `(?<![\x5C])`).
- **Doubled delimiters are collapsed once** before matching (`MacroActionProcessor.compile`):
  ```java
  macro = macro.replace("$${$${", "$${").replace("}$$}$$", "}$$");
  ```
  This lets one level of nesting/escaping through; `exec` uses it (`"$${$$<file>}$$"`).
- Multiple blocks per line are each parsed in sequence; text between/around blocks becomes chat.

### 1.3 Plain (chat) lines vs script lines — the distinction

There is no per-line flag. The split is positional, in `MacroActionProcessor`'s constructor:

```java
Matcher m = PATTERN_SCRIPT.matcher(macro);
while (m.find()) {
   if (m.start() > 0) actions.add(new MacroActionChat(this, macro.substring(0, m.start())));  // text BEFORE block → chat
   actions.addAll(this.parser.parseScript(this, m.group(1)));                                  // INSIDE block → script
   macro = macro.substring(m.end());
   m.reset(macro);
}
if (macro.length() > 0) actions.add(new MacroActionChat(this, macro));                          // trailing text → chat
```

- **Anything outside `$${…}$$` → `MacroActionChat`** (sent to chat / typed).
- **Anything inside → parsed as script.**
- A macro with **no `$${`** at all becomes a single `MacroActionChat` containing the entire line — i.e. a plain "send this to chat" macro.
- Example: `hello $${ log("hi") }$$ world` → `[Chat("hello "), Log("hi"), Chat(" world")]`.

### 1.4 Statement separator inside a script block

`ScriptParser.parseScript` splits the block body into statements on **`;`** (and its alias ``), quote-aware:

```java
for (String stmt : tokeniseScript(script.replace('', ';'), ';')) {
   stmt = stmt.replaceAll("", "\\\\|").trim();   //  → literal "\|"
   if (!stmt.startsWith("//")) { /* parse */ }          // // = line comment
}
```

`tokeniseScript(script, ';')` is a hand-rolled, quote-aware splitter:
- Splits on `;` **outside** double-quoted strings.
- A `"` toggles quoted state **unless escaped** (`escape % 2` parity check, so `\"` does not toggle).
- The `;` delimiter is **dropped**; the quote characters are **kept** in each statement (the action parsers strip them later).
- `` is an alias for `;` (file-include CRLF joiner uses it; see [§6](#6--parameter-substitution)).
- `` is the **escaped-pipe placeholder**: each statement does `replaceAll("", "\\\\|")` to restore `\|` (protected earlier from the chat `|` split).

### 1.5 Comment syntax

- **`//` line comment only.** Any statement that, after `.trim()`, `startsWith("//")` is skipped entirely (`ScriptParser.parseScript`).
- **No block comments.** **No `#` comment** — `#` is the integer-variable sigil ([§4](#4-variable-system)).
- Empty statements are silently ignored; a non-empty statement that no parser recognizes calls `onError(stmt)` (no-op in the base parser; the REPL overrides it to report).

---

## 2. Parsing Model

### 2.1 The parser chain

`ScriptParser` holds an ordered `List<ActionParser>`. Each statement is tried against each parser **in registration order; the first non-null result wins** (`break`):

```java
for (ActionParser parser : this.parsers) {
   action = parser.parse(actionProcessor, scriptEntry);
   if (action != null) { actions.add(action); break; }
}
```

Standard registration order (assignment is tried before bare action call, directives last):

| Order | Parser | Recognizes | Becomes action |
|------:|--------|-----------|----------------|
| 1 | `ActionParserAssignment` | `var = expr` / `var := expr` | `ASSIGN` / `SET` (or RHS action with outVar) |
| 2 | `ActionParserEval` | `?expr` / `print expr` | `EVAL` (or RHS action with outVar `EVAL`) |
| 3 | `ActionParserAction` | `name(args)` | the named action |
| 4 | `ActionParserDirective` | bare `name` | the named action, no args |

### 2.2 Shared parser regexes (`scripting/ActionParser.java`)

```java
protected static Pattern PATTERN_SCRIPTACTION = Pattern.compile("^([a-z\\_]+)£?\\((.*)\\)$", 2);  // CASE_INSENSITIVE
//   ^ ([a-z_]+) optional-£  ( (.*) ) $        group1 = name, group2 = args
protected static Pattern PATTERN_DIRECTIVE    = Pattern.compile("^([a-z\\_]+)$", 2);
```
- Action names are `[a-z_]+` (case-insensitive). The optional `£` (U+00A3) before `(` is a highlighter marker, allowed and discarded.
- `PATTERN_DIRECTIVE` matches a bare identifier (no parens, no args).

### 2.3 Assignment parsing (`parser/ActionParserAssignment.java`)

```java
int equals = scriptEntry.indexOf('=');
if (equals > -1) {
   int colon = scriptEntry.indexOf(':');
   if (colon > -1 && colon == equals - 1) actionName = "SET";   // ":=" → SET
   else { colon = equals; actionName = "ASSIGN"; }              // "="  → ASSIGN
   varName = scriptEntry.substring(0, colon).trim();
   if (Variable.isValidVariableOrArraySpecifier(varName)) {
      String expr = scriptEntry.substring(equals + 1).trim();
      if (PATTERN_SCRIPTACTION.matches(expr))                   // RHS is itself a call:  v = foo(a)
         return parse(proc, group1, group2, varName);            //   → foo with outVar = v
      if (expr quoted)  expr = dequote(expr);
      return getInstance(proc, actionName, expr, expr, new String[]{varName, expr}, null);
   }
}
return null;  // not an assignment → fall through to next parser
```

- **`var = expr` → `ASSIGN`** (RHS evaluated as expression unless `&`/`@&` string var — see action 22).
- **`var := expr` → `SET`** (RHS stored as literal string).
- LHS must pass `Variable.isValidVariableOrArraySpecifier` (else not treated as assignment).
- If RHS is `name(args)`, that action runs and its return value is captured into `varName` (the **outVar** mechanism, [§3.6](#36-return-values-ireturnvalue)).

### 2.4 Argument tokenization (`parser/ActionParserAbstract.java` + `ScriptCore.tokenize`)

```java
char firstParamQuote = actionName.matches("^(if|elseif|iif)$") ? 0 : '"';   // conditions don't quote-process arg 0
String[] params = ScriptCore.tokenize(unparsedParams, ',', firstParamQuote, '"', '\\', rawParams);
```

`ScriptCore.tokenize(text, separator, firstParamQuote, otherParamsQuote, escape, rawString)` is a state machine:
- **Argument separator is `,`** (comma). Quote char is `"` (34). Escape char is `\`.
- A quote only **opens** when the current param so far is empty or all-whitespace (`matches("^\\s+$")`); otherwise a `"` is literal.
- The quote char **switches from `firstParamQuote` to `otherParamsQuote` after the first separator** — so `if`/`elseif`/`iif` leave arg 0 (the condition expression) entirely unquoted (quote char `0`/NUL), but later args use `"`.
- `\` escapes the next quote/separator/escape (doubled `\\` emits one literal `\`; `\` before an ordinary char emits both).
- `rawString` accumulates `" " + param` for each param; callers strip the leading space (`rawParams.substring(1)`).
- Trailing-separator edge case: text ending in the separator with an empty last param appends one `""`.

### 2.5 Action instantiation (`ActionParserAbstract.getInstance`)

```java
IScriptAction scriptAction = context.getAction(actionName);          // registry lookup, lowercased
if (scriptAction != null) {
   if (scriptAction.checkExecutePermission())
      return new MacroAction(proc, scriptAction, rawParams, unparsedParams, params, outVar);
   if (scriptAction.isPermissable())
      return new MacroAction(proc, new DeniedAction(context), actionName, actionName, params, outVar);
}
return new MacroAction(proc, new UnrecognisedAction(context, actionName), rawParams, unparsedParams, params, outVar);
```
Resolution: **known + permitted → real action**; **known + permissable but denied → `DeniedAction`** (prints `script.error.denied`); **unknown → `UnrecognisedAction`** (a NULL no-op keeping original-case name).

### 2.6 The action registry — reflection, NOT a keyword table

There is **no explicit `register("if", IfAction.class)` table anywhere.** `ScriptCore.initActions` discovers actions by classpath reflection:

```java
Class<?> anchor = Class.forName("net.eq2online.macros.scripting.actions.lang.ScriptActionAssign");
List<Class<? extends ScriptActionBase>> actions =
    Reflection.getSubclassesFor(ScriptActionBase.class, anchor, "ScriptAction", logger);  // scan classpath
for (Class<?> a : actions) {
   IScriptAction inst = a.getDeclaredConstructor(ScriptContext.class).newInstance(context);   // (ScriptContext) ctor
   if (actionFilter.pass(context, this, inst)) registerAction(inst);
}
```
```java
private boolean registerAction(IScriptAction a) {
   if (actions.containsKey(a.toString())) return false;   // toString() == getName() == lowercased keyword
   actions.put(a.toString(), a);                           // map key = lowercased keyword
   actionsList.add(a);
   updateScriptActionRegex();                              // rebuild highlight regex
   return true;
}
public IScriptAction getAction(String name) { return actions.get(name.toLowerCase()); }
```

**Each action declares its own keyword by passing it to `super(context, "keyword")`** (`ScriptActionBase` lowercases it; `getName()`/`toString()` return it). So "keyword → class" is one-per-class in the constructor. This makes the engine trivially extensible — drop in a `ScriptActionFoo extends ScriptActionBase` and it auto-registers.

`ScriptActionBase` (the root of every action):
```java
public abstract class ScriptActionBase implements IScriptAction {
   protected final ScriptContext context;
   protected final String actionName;
   protected ScriptActionBase(ScriptContext context, String actionName) {
      this.context = context;
      this.actionName = actionName.toLowerCase();   // KEYWORD lowercased here
   }
   public String getName() { return this.actionName; }
   public final String toString() { return this.getName(); }
}
```
`ScriptAction extends ScriptActionBase` (in `parser/`) is the rich default base (NULL execute, all the trait defaults, permission helpers, static action counters). Most concrete actions extend `ScriptAction`.

### 2.7 ScriptContexts

Named, statically-registered contexts (each owns its own `ScriptCore` registry):
```java
public static final ScriptContext MAIN       = new ScriptContext("main");
public static final ScriptContext CHATFILTER = new ScriptContext("chatfilter");
```
`getContext(name)` returns existing or creates new. Duplicate construction throws. The MAIN context is what key-bound macros use.

---

## 3. Execution Model

A compiled macro is a **flat `List<IMacroAction>`**. Control-flow nesting is **not structural** in the list — it is reconstructed at runtime by a single instruction pointer + an explicit operator stack.

### 3.1 The processor (`core/executive/MacroActionProcessor.java`)

```java
private final List<IMacroAction> actions = new ArrayList<>();
private final Deque<IMacroActionStackEntry> stack = new LinkedList<>();   // LIFO via addFirst/removeFirst
private int pointer = 0;                                                  // THE instruction pointer
private boolean suspendProcessing = false;
private boolean safeMode = true;
private int maxActionsPerTick;       // throttle: instructions per tick
private final int maxExecutionTime;  // throttle: wall-clock ms per tick
private static Object executionLock = new Object();
```

### 3.2 The execution loop (latent / normal mode)

`execute(macro, context, stop, allowLatent=true, clock)`:

```java
int processed = 0; long t0 = systemTime();
while (this.pointer < this.actions.size()) {
   processed++;
   IMacroAction cur = this.actions.get(this.pointer);

   // GATE: yield the whole tick (WITHOUT advancing) if clocked-but-not-a-clock-tick,
   //       or if in a live conditional and the action isn't ready (wait/prompt/latency).
   if (!clock && cur.isClocked()
       || getConditionalExecutionState() && !canExecuteNow(macro, context, cur))
      return true;

   boolean continueExecuting;
   synchronized (executionLock) {
      host.trace(macro.getID(), pointer, getConditionalExecutionState() ? "RUN" : "   ", cur.toString());
      continueExecuting = cur.execute(context, macro, stop && (actions.size() - pointer < 2), true);
   }

   // THROTTLE: trip suspend on instruction budget or wall-clock budget.
   suspendProcessing |= (maxActionsPerTick > 0 && processed >= maxActionsPerTick);
   suspendProcessing |= (maxExecutionTime  > 0 && systemTime() - t0 > maxExecutionTime);

   if (continueExecuting) {
      advancePointer();                 // pointer++ ONLY when the action returns true
      if (suspendProcessing) return true;  // resume next tick from advanced pointer
   }
   if (macro.isDead()) return false;
}
// fell off the end:
if (stack.size() > 0) host.addScriptError(..., I18n.get("script.error.missingpop", reason));  // unterminated block
return false;
```

Key facts:
- **`pointer++` happens only when the action returns `true`** (`continueExecuting`). The pop path returns `popStack()`'s result, which is `false` when it rewinds the pointer (loop-back) — so the rewound pointer is used as-is next iteration.
- **The gate yields without advancing** → next tick re-evaluates the same instruction. This is how `wait`/`prompt` (latency via `canExecuteNow`) and clocked-action timing work.
- **`stop` is only forwarded to an action on the last instruction** (`actions.size() - pointer < 2`) — implements `$$!` (open chat with buffer instead of sending the final line).
- **Synchronous macros bypass the `canExecuteNow` gate** (`canExecuteNow` returns true when `macro.isSynchronous()`).
- Execution of every action body is serialized on a static `executionLock`.
- **Unterminated block detection:** falling off the end with a non-empty stack logs `script.error.missingpop`; `reason` comes from the open operator's `getExpectedPopCommands()` (default literal `"missing statement"`).

**Non-latent mode** (`allowLatent=false`, used for key-held repeats): a single straight-through pass, no pointer/stack/throttle; each action runs once if `canExecuteNow`. Throws `ScriptExceptionInvalidContextSwitch` if `pointer > 0`.

### 3.3 The stack frame (`core/executive/MacroActionStackEntry.java`)

```java
private int stackPointer;              // JUMP-BACK target (instruction index); -1 = "no redirect"
private boolean conditionalFlag;       // is this frame's body currently executing?
private boolean ifFlag;                // has any branch of this if/elseif chain matched? (sticky)
private boolean elseFlag;              // has the terminal else been handled?
private IMacroAction action;           // the operator that opened this frame
public MacroActionStackEntry(int pointer, IMacroAction action, boolean conditional) {
   this.stackPointer = pointer; this.action = action;
   this.conditionalFlag = conditional; this.ifFlag = conditional;
}
```
All operator-type questions delegate to the underlying `IScriptAction` (`isStackPushOperator`, `canBePoppedBy`, `isConditionalOperator`, `isConditionalElseOperator`, `matchesConditionalOperator`, `executeStackPop`), with null-guards (`canBePoppedBy` defaults true, others default false).

### 3.4 Push / pop / conditional gate primitives

```java
void pushStack(IMacroAction a, boolean cond)        { pushStack(this.pointer, a, cond); }  // capture current ptr
void pushStack(int ptr, IMacroAction a, boolean cond){
   if (stack.size() > 32) throw new ScriptExceptionStackOverflow();   // MAX NESTING DEPTH = 32
   stack.addFirst(new MacroActionStackEntry(ptr, a, cond));
}
boolean popStack() {
   if (stack.isEmpty()) return false;
   int sp = stack.removeFirst().getStackPointer();
   if (sp <= -1) return true;                        // sentinel: discard frame, do NOT move pointer
   this.pointer = sp;                                // LOOP-BACK: rewind IP to the opener
   if (this.safeMode) this.suspendProcessing = true; // one iteration per tick in safe mode
   return false;
}
boolean getConditionalExecutionState() {             // AND of every frame's conditionalFlag
   for (IMacroActionStackEntry e : stack) if (!e.getConditionalFlag()) return false;
   return true;                                       // empty stack → true
}
void breakLoop(provider, macro, breakAction) {       // BREAK: find nearest breakable frame, suppress it
   for (IMacroActionStackEntry e : stack)
      if (e.getAction().canBreak(this, provider, macro, breakAction)) { e.setConditionalFlag(false); return; }
}
```

### 3.5 Operator dispatch (`core/executive/MacroAction.execute`)

```java
if (action.isStackPopOperator())        return executeStackPop(...);     // pop / loop-back / else / endif
else {
   if (action.isStackPushOperator())    executeStackPush(...);           // loop opener
   else if (action.isConditionalOperator()) executeConditional(...);     // if / unsafe
   else if (proc.getConditionalExecutionState()) executeAction(...);     // normal action — only when live
   return true;
}
```
**Precedence: pop → push → conditional → (if live) normal.** Only the pop path can return non-`true`.

- **`executeStackPush`** (loop opener): if `action.executeStackPush(...)` returns true **and** we're in a live conditional state → `pushStack(this, true)` (capturing the current pointer as jump-back target). Else → `pushStack(-1, this, false)` (a **dead frame** so the closer stays balanced without executing).
- **`executeConditional`** (`if`): `pushStack(-1, this, action.executeConditional(...))` — no jump-back (`-1`), `conditionalFlag` = the condition truth.
- **`executeStackPop`** (closer), three sub-cases on the top frame:
  - **loop closer** (`top.canBePoppedBy(this)`): run `top.executeStackPop(...)` then `return popStack()` (which rewinds to the loop head when the frame's pointer ≥ 0).
  - **else operator** (`top.isConditionalElseOperator(this)`, and `!top.elseFlag`): run `action.executeConditionalElse(...)` — flips the if-frame's flags in place (no pop).
  - **matching closer** (`top.matchesConditionalOperator(this)`, e.g. `endif`/`endunsafe`): run `top.executeStackPop(...)` then `popStack()` (discards the `-1` frame, advances).

### 3.6 Return values (`IReturnValue`)

`executeAction` consumes the return value and optionally captures it into the action's outVar:

```java
IReturnValue rv = action.execute(provider, macro, this, rawParams, params);
returnValueHandler.handleReturnValue(provider, macro, this, rv);   // side-effects (chat/log/raw)
if (outVar != null) {
   if (rv.isVoid()) throw new ScriptExceptionVoidResult(action.toString().toUpperCase());
   String name = Variable.getValidVariableOrArraySpecifier(provider.expand(macro, outVar, false));
   if (not array || name == null)            provider.setVariable(macro, parsedOutVar, rv);
   else if (rv instanceof IReturnValueArray) { if (!rv.shouldAppend()) provider.clearArray(macro, name);
                                               for (String s : rv.getStrings()) provider.pushValueToArray(macro, name, s); }
   else                                       provider.pushValueToArray(macro, name, rv.getString());
}
provider.updateVariableProviders(false);
```

**The interface:**
```java
public interface IReturnValue {
   boolean isVoid();           // true → cannot be captured into a variable
   boolean getBoolean();
   int     getInteger();
   String  getString();
   String  getLocalMessage();  // shown locally (chat/log overlay); null = none
   String  getRemoteMessage(); // sent to the server as chat; null = none
}
public interface IReturnValueArray extends IReturnValue {
   int size(); boolean shouldAppend();
   List<Boolean> getBooleans(); List<Integer> getIntegers(); List<String> getStrings();
}
```

**The default handler** (`MacroAction.handleReturnValue`) dispatches by message + subtype:
```java
if (rv.getRemoteMessage() != null) provider.actionSendChatMessage(macro, instance, remoteMessage);  // → server
if (rv.getLocalMessage()  != null) {
   if (localMessage.equals(""))               clearChatGui();                                  // CLEAR sentinel
   else if (rv instanceof ReturnValueLogTo)          provider.actionAddLogMessage(target, localMessage);
   else if (rv instanceof ReturnValueRaw)            Game.addChatMessage(rawComponent);              // raw IChatComponent
   else                                              provider.actionAddChatMessage(localMessage);    // local chat
}
```

**Concrete return values:**

| Class | isVoid | local | remote | notes |
|-------|:------:|-------|--------|-------|
| `ReturnValueChat(String)` | ✔ | — | the message | chat literal; cannot be captured |
| `ReturnValueLog(String)` | ✔ | the message | — | local only; `CLEAR = ""` clears chat |
| `ReturnValueLogTo(msg, target)` | ✔ | the message | — | `extends ReturnValueLog`; logs to named target |
| `ReturnValueRaw(hh component)` | **✗** | unformatted text | — | `getRawMessage()` = live `IChatComponent`; **capturable** |
| `ReturnValue(String/int/bool)` | ✗ | — | — | general assignable scalar |
| `ReturnValueArray` | ✗ | — | — | parallel bool/int/string lists + `shouldAppend()` |

`ReturnValue` truthiness: a string is `true` if `null`, equals `"true"` (ci), or parses to nonzero int. Booleans stringify as `"True"`/`"False"`.

### 3.7 How each control structure maps to the stack

| Construct | Opener (push) | jump-back ptr | Body skip mechanism | Closer (pop) | Re-entry |
|-----------|---------------|:-------------:|---------------------|--------------|----------|
| `if/elseif/else/endif` | `if` = conditional op | `-1` | `conditionalFlag` (AND across stack) gates `executeAction` | `endif` = `matchesConditionalOperator(if)` | none (no loop) |
| `do … loop` | `do` = push op | current ptr | n/a | `loop` = pop op | `popStack` rewinds to `do` |
| `do … while`/`do … until` | `do` | current ptr | n/a | `while`/`until` (extend `loop`) | `Do.executeStackPop` evaluates condition (negates for `while`) |
| `for … next` | `for` (extends `do`) | current ptr | n/a | `next` = pop op | `Do.State` counter; sets loop var each iteration |
| `foreach … next` | `foreach` (extends `do`) | current ptr | n/a | `next` | `IScriptedIterator`; registers iterator as a variable provider during body |
| `unsafe … endunsafe` | `unsafe` = conditional op (always true) | `-1` | always live | `endunsafe` = `matchesConditionalOperator(unsafe)` | none; toggles `safeMode` off/on |
| `break` | normal action | — | sets nearest breakable frame's `conditionalFlag=false` | — | loop ends on next closer |
| `wait`/`prompt` | normal action | — | gates via `canExecuteNow()` returning false | — | re-polled each tick, ptr not advanced |

**Conditional "skipping" detail:** an un-taken `if` body is **not removed** — the lines are still walked, but `executeAction` only runs the real body when `getConditionalExecutionState()` is true (AND of all `conditionalFlag`s). Operators still run (to keep the stack balanced); a push inside a dead branch pushes a dead `-1`/false frame.

**`if/elseif/else` flag logic** (all mutate the single shared `if` frame; no pointer move):
- `if`: pushes frame, `conditionalFlag = ifFlag = (condition)`.
- `elseif`: if `ifFlag` already set → `conditionalFlag=false`; else evaluate its condition into `conditionalFlag` and OR into `ifFlag`; a bare `elseif` (no args) acts like `else`.
- `else`: `conditionalFlag = !ifFlag`; `elseFlag = true` (later elseif/else ignored).

**Loop termination detail:** a loop ends when the opener's `executeStackPush` returns false on re-entry (counter overran / iterator inactive / `while`/`until` satisfied / cancelled by `break`). That re-entry pushes a `-1`/false frame, so the next closer pops the sentinel without rewinding → fall-through past the loop.

### 3.8 Unsafe blocks (loop throttle override)

```java
void beginUnsafeBlock(..., int maxActions) { if (!safeMode) throw new ScriptExceptionInvalidModeSwitch();
                                             safeMode = false; maxActionsPerTick = maxActions; }     // clamp 0..10000
void endUnsafeBlock(...)                    { if (safeMode)  throw new ScriptExceptionInvalidModeSwitch();
                                             safeMode = true;  maxActionsPerTick = originalMaxActions; }
```
While `safeMode == false`, `popStack` does **not** set `suspendProcessing` on a back-jump → loops run all iterations within a single tick (up to the raised `maxActionsPerTick`/`maxExecutionTime`). Nesting unsafe blocks throws.

### 3.9 The driver (`core/Macro.java`)

A `Macro` holds three compiled processors and dispatches per playback type each tick:
```java
protected MacroActionProcessor keyDownActions, keyHeldActions, keyUpActions;
// play(trigger, clock):
//   ONESHOT synchronous → busy-loop execute(...) to completion this tick
//   ONESHOT async       → one execute() call/tick
//   KEYSTATE            → keyDown latently every tick; keyHeld NON-latently on trigger (throttled by repeatRate);
//                         keyUp latently on release (gated by keyWasDown)
```
`PlaybackType ∈ {ONESHOT, KEYSTATE, CONDITIONAL}`. `kill()` calls `onStopped` on each processor (clears `pendingAction`, calls `action.onStopped` on every action).

### 3.10 `MacroActionChat` (the chat-literal pseudo-instruction)

```java
if (!proc.getConditionalExecutionState()) return true;           // respect conditional gate
String[] messages = this.message.split("[\\x7C\\x82]");          // split on | (0x7C) and 0x82 → multiple chat lines
for (i …) {
   if (stop && i == messages.length-1) processStop(messages[i]); // last line under $$! → open chat with buffer
   else handleReturnValue(provider, macro, this, new ReturnValueChat(messages[i]));  // send to server
}
// processStop(buffer): Minecraft.openGui(new GuiChat(buffer))
```

---

## 4. Variable System

### 4.1 Sigils & name grammar (`scripting/Variable.java`)

```java
public static final String PREFIX_SHARED = "@";    // shared / global
public static final String PREFIX_STRING = "&";    // string
public static final String PREFIX_INT    = "#";    // integer / counter
public static final String PREFIX_BOOL   = "";     // boolean / flag (no sigil)
public static final String SUFFIX_ARRAY  = "[]";
public static final Pattern variableNamePattern =
   Pattern.compile("^(@?)([#&]?)([a-z~]([a-z0-9_\\-]*))(\\[([0-9]{1,5})\\])?$", 2);  // CASE_INSENSITIVE
public static final Pattern arrayVariablePattern = Pattern.compile("\\[([0-9]{1,5})\\]$");
```

| Sigil | Type | Default value | Example |
|:-----:|------|---------------|---------|
| `#` | COUNTER (int) | `0` | `#count`, `#scores[3]` |
| `&` | STRING | `""` | `&name`, `&items[0]` |
| *(none)* | FLAG (bool) | `false` | `flag`, `ready` |
| `@` *(prefix)* | SHARED/GLOBAL modifier (orthogonal to type) | — | `@#gold`, `@&motd`, `@done` |

Name structure: `[@][#|&]name[index]` — `@` (shared) first, then type sigil, then a name starting `[a-z~]` followed by `[a-z0-9_-]*`, then an optional `[0-9]{1,5}` array index. `name[]` (empty brackets) is an **array specifier** (the whole array).

Type resolution: `#`→COUNTER, `&`→STRING, else→FLAG. `enum Type { FLAG, COUNTER, STRING }`.

### 4.2 `:=` and `=` assignment

`:=` and `=` are **not** handled in the variable layer — they are parsed in `ActionParserAssignment` ([§2.3](#23-assignment-parsing-parseractionparserassignmentjava)) into the `SET` (`:=`, literal) and `ASSIGN` (`=`, expression-evaluated) actions, which call `VariableManager.setVariable(...)`. Note `=`/`==` inside an **expression** is equality comparison ([§5](#5-expression-evaluation)), not assignment.

### 4.3 The provider interface & registry

```java
public interface IVariableProvider extends IMacrosAPIModule {
   void updateVariables(boolean clock);
   Object getVariable(String name);   // null = "I don't provide this"
   Set<String> getVariables();
}
public interface ITickableVariableProvider extends IVariableProvider { void onTick(); }
public interface IArrayProvider extends IVariableProvider {
   int indexOf(String array, String value, boolean caseSensitive);
   int getMaxArrayIndex(String array);
   boolean checkArrayExists(String array);
   Object getArrayVariableValue(String array, int index);
}
public interface IMutableArrayProvider extends IArrayProvider {
   int MISSING = -1;
   boolean push(String a, String v); String pop(String a); boolean put(String a, String v);
   void delete(String a, int i); void clear(String a);
}
// ICounterProvider (KEY="int", EMPTY=0), IFlagProvider (KEY="boolean", EMPTY=false),
// IStringProvider (KEY="string", EMPTY="") — typed get/set/unset, scalar + (offset) array forms.
public interface IVariableProviderShared extends IMutableArrayProvider, IFlagProvider, ICounterProvider, IStringProvider {
   void setSharedVariable(String n, String v); String getSharedVariable(String n); int getSharedVariable(String n, int dflt);
}
```

`VariableManager` (abstract; `ScriptActionProvider extends VariableManager`) holds the registry:
```java
private List<IVariableProvider> variableProviders;   // resolution order = registration order
private List<IArrayProvider>    arrayProviders;       // array-capable providers (excludes the shared provider)
private List<IVariableListener> variableListeners;
```
- `registerVariableProvider(p)`: de-duped by reference; if `p instanceof IArrayProvider` and `p != sharedVariableProvider`, also added to `arrayProviders`.
- **Resolution: first provider whose `getVariable(name)` returns non-null wins** (`getProviderForVariable`). A throwing provider is **removed** and an error chat line printed (fault isolation).
- `getVariable(name, IMacro macro)`: special-cases reserved env vars **first** — `KEYID`, `KEYNAME`, `CONFIG` — then delegates with `macro` as the in-context provider (the in-context provider is checked before the global list). **`IMacro` is itself an `IVariableProvider`** → that is where local per-macro variables live.

The default registered providers (`ScriptActionProvider` ctor): `VariableProviderInput`, `VariableProviderSettings`, `VariableProviderPlayer`, `VariableProviderWorld`, `VariableProviderShared`.

### 4.4 Tick semantics

- `VariableManager.onTick()` calls `onTick()` on every registered provider that is `instanceof ITickableVariableProvider`.
- `updateVariableProviders(boolean clock)` calls `updateVariables(clock)` on **all** providers (wrapped in the MC profiler, same fault isolation). `clock=true` = a real timed tick; `false` = a passive refresh (called before every variable resolution and after every action). The shared provider uses `clock` for its periodic autosave.

### 4.5 Read / write paths

There is no `getValue`/`setValue` on the manager — reads go through `getVariable(...)`, writes through overloaded `setVariable(...)`. Per-`Variable` typed accessors (`getCounter/getString/getFlag`, `setCounter/setString/setFlag`) route to the right provider:
- **Local (no `@`):** `macro.getCounterProvider()/getFlagProvider()/getStringProvider()` — per-macro storage.
- **Shared (`@`):** the `sharedVariableProvider`.

Core setter (the type sigil on the *name* selects which representation is stored):
```java
void setVariable(IMacro macro, String name, String strVal, int intVal, boolean boolVal) {
   Variable v = Variable.getVariable(sharedProvider, arrayProviders, macro, name);
   macro.markDirty();
   switch (v.getType()) { case FLAG: v.setFlag(boolVal); case COUNTER: v.setCounter(intVal); case STRING: v.setString(strVal); }
}
```
Convenience overloads: `(…, String)` parses int via `ScriptCore.tryParseInt` and bool via `ScriptCore.parseBoolean`; `(…, int)`; `(…, IReturnValue)` forwards `getString()/getInteger()/getBoolean()`.
Also: `incrementCounterVariable`, `unsetVariable` (clears array if `name[]`), `getFlagValue`/`setFlagVariable` (with cross-type coercion: counter `==1`, string `"1"`/`"true"`).

### 4.6 Scope: global vs local

- **Local** = per-macro; stored in the `IMacro` instance's own counter/flag/string/array providers; reads/writes route there when the name has **no `@`**. Each mutation marks the macro dirty (drives persistence).
- **Global/shared** = `@` prefix → `VariableProviderShared`, persisted to **`.globalvars.xml`**. Scalars live in a flat `Properties` map keyed by sigil+name (counters `#name`, strings `&name`, flags bare); pure-integer strings auto-parse to `Integer` on read. Autosaves every 100 ticks when dirty.

### 4.7 Array / counter / flag backing (`variable/VariableProviderArray.java` + `ArrayStorage`)

Three typed `ArrayStorage` instances selected by sigil; storage keyed prefix-less:
```java
ArrayStorage<Boolean> flagStore   = new ArrayStorage<>("boolean", false);
ArrayStorage<Integer> counterStore = new ArrayStorage<>("int", 0, "#");
ArrayStorage<String>  stringStore  = new ArrayStorage<>("string", "", "&");
// getStorage(name): name.startsWith("#") ? counter : name.startsWith("&") ? string : flag
```
- `push` → append at `maxIndex+1`; `put` → first free hole (else end); `pop` → remove at max index (returns string form, `null` if empty); `indexOf` → honors case-sensitivity, treats empty/false search as "find first gap".
- `getVariables()` emits `#name[]`, `&name[]`, `name[]` specifiers.
- Each `ArrayStorage` is `Map<String, TreeMap<Integer,T>>` + cached lengths (`-1` if absent); `offset < 0` means scalar (delegates to the no-offset path, which the shared subclass backs with `Properties`).

`VariableCache` (uppercase env-var grammar `^([A-Z]+)(\[…\])?$`) is a separate read-through cache used by the built-in providers (`CONFIG`, `XPOS`, etc.).

### 4.8 The built-in variable providers (selected; namespace = bare uppercase names, no prefix)

| Provider | Representative variables |
|----------|--------------------------|
| `VariableProviderPlayer` | `PLAYER`, `DISPLAYNAME`, `UUID`, `HEALTH`, `ARMOUR`, `HUNGER`, `XP`, `LEVEL`, `GAMEMODE`, `XPOS/YPOS/ZPOS(F)`, `YAW`, `CARDINALYAW`, `PITCH`, `DIRECTION`, `LIGHT`, `DIMENSION`, `BIOME`, `GUI`, `SCREEN`, `FLYING`, `VEHICLE`; equipment (`MAINHAND*`/`OFFHAND*`/armour `BOOTS*`/`HELM*`…): `ITEM`, `ITEMNAME`, `DURABILITY`, `STACKSIZE`; crosshair `HIT`, `HITNAME`, `HITID`, `HITX/Y/Z`, `HITSIDE`, dynamic `HIT_*` block-state props, `SIGNTEXT` |
| `VariableProviderWorld` | `TOTALTICKS`, `TICKS`, `DAY`, `DAYTIME`, `RAIN`, `DIFFICULTY`, `SERVER`, `ONLINEPLAYERS`, `SERVERMOTD`, `SEED`, `MAXPLAYERS`, `RESOURCEPACKS`, `DATETIME`, `DATE`, `TIME`, `TIMESTAMP`, `UNIQUEID` (fresh UUID each read) |
| `VariableProviderInput` | `CTRL`, `ALT`, `SHIFT`, `LMOUSE`, `RMOUSE`, `MIDDLEMOUSE`, and `KEY_<NAME>` for every LWJGL key (e.g. `KEY_A`, `KEY_SPACE`) |
| `VariableProviderSettings` | `FOV`, `GAMMA`, `SENSITIVITY`, `MUSIC`, `SOUND`, `*VOLUME` (per category), `CAMERA`, `FPS`, `CHUNKUPDATES`, `SHADERGROUP`, `SHADERGROUPS` |
| `VariableProviderTrace` | `TRACETYPE` (TILE/PLAYER/ENTITY/NONE), `TRACENAME`, `TRACEID`, `TRACEDATA`, `TRACEUUID`, `TRACEX/Y/Z`, `TRACESIDE`, dynamic `TRACE_*` block-state props (populated by the `trace` action) |
| `VariableProviderIMC` | none fixed; other mods inject `^[A-Z][A-Z_]*[A-Z]$` Integer/Boolean/String values at runtime |

Reserved environment variables (resolved by `VariableManager` directly): `CONFIG`, `KEYID`, `KEYNAME`.

---

## 5. Expression Evaluation

`scripting/ExpressionEvaluator.java` implements `IExpressionEvaluator`. **It is NOT shunting-yard and NOT a grammar parser — it is recursive regex-based descent over the raw string.** Everything reduces to `int` (booleans are `1`/`0`; strings are interned as descending integer ids).

```java
private static int MAX_DEPTH = 10;
private static final Pattern PATTERN_OPERATOR =
   Pattern.compile("\\={1,2}|\\<\\=|\\>\\=|\\>|\\<|\\!\\=|\\&{2}|\\|{1,2}|\\+|\\-|\\*|\\/", 2);
private static final Pattern PATTERN_STRING = Pattern.compile("\\x22([^\\x22]*)\\x22");        // "..."
private static final Pattern PATTERN_NEGATIVE_NUMBER = Pattern.compile("(?<=(^|\\())-(?=[0-9])");
private int nextStringLiteral = 2147483646;   // string-literal ids count DOWN from here
```

### 5.1 Operators

| Token(s) | Meaning |
|----------|---------|
| `+` `-` `*` `/` | integer arithmetic |
| `=` `==` | equality (**both** are equality; no assignment) |
| `!=` | inequality |
| `<` `<=` `>` `>=` | comparisons |
| `&&` / `&` | logical AND (operand truthy iff `0 < value < nextStringLiteral`) |
| `\|\|` / `\|` | logical OR (same truthiness) |
| `!` (prefix) | logical NOT (handled in `getValue`) |
| `( … )` | grouping (binds tightest) |

Comparisons/boolean results return `1`/`0`. Logical truthiness excludes the string-literal id range, so a string literal is **never** truthy as a boolean operand.

### 5.2 Precedence — **first-operator-found, left-to-right; NO arithmetic precedence**

`evaluate(expr, depth)` each call:
1. Rewrite unary minus on negative numbers to sentinel `¬`.
2. **If parentheses present:** find first `(` and its matching `)` (manual counter), recursively evaluate the inner sub-expression, splice the int result back, re-evaluate. → **parentheses are the only grouping.**
3. **Else if an operator present:** split on the **first** operator the regex finds (scanning left→right); LHS = `getValue(before)`, RHS = `evaluate(after)` (recursive). → operators apply in textual order; **`*`/`/` do NOT bind tighter than `+`/`-`**.
4. **Else:** parse the bare operand.

> Practical consequence for reimplementation: `2+3*4` evaluates as `2+(3*4)`? **No** — it evaluates left-to-right as `((2+3)... )` style chaining via the RHS recursion. To get standard precedence, **users must parenthesize.** Replicate the first-operator-split behavior exactly to stay bug-compatible.

`prepare()` preprocessing: extract `"strings"` → literal ids; `& ` → `&&`; strip all whitespace; `true`→`1`, `false`→`0` (case-insensitive). Operands: a token matching `Variable.isValidVariableName` is resolved via `provider.getVariable(name, macro)` (int / string-id / bool→0/1); otherwise `Integer.parseInt` (commas already stripped; `¬`→`-`), parse error → `0`. `MAX_DEPTH=10`; any exception → result `0`. String equality works because equal strings intern to equal ids and `==` compares ids.

`IExpressionEvaluator`: `evaluate()`, `getResult()`, `addStringLiteral(String)`, `dumpVariables()`, plus `IVariableListener` setters. `static isTrue(int) ≙ value != 0`.

---

## 6. `$$` Parameter Substitution

**Critical architecture:** `$$` codes are **NOT** handled by `VariableExpander` (that handles `%var%`, [§4.1](#41-sigils--name-grammar-scriptingvariablejava)/below) and there is **NO switch/case**. Each `$$` code is a separate `MacroParamProvider` subclass holding its own compiled `Pattern`, registered as one constant of the `core/params/MacroParam.Type` enum. `MacroParams.evaluateParams()` runs every provider's regex against the script, picks the **earliest match** (smallest `getStart()`), and that provider's `MacroParam.replace()` performs the substitution. Substitution values pass through `Macro.escapeReplacement(...)` so user `$`/`\` don't corrupt `Matcher.replaceAll`.

### 6.1 Shared constants & escaping

```java
// core/Macro.java
public static final String PREFIX_PARAM = "$$";
public static final String REPLACEMENT_PIPE = "";
public static String escapeReplacement(String p) { return p.replaceAll("\\\\","\\\\\\\\").replaceAll("\\$","\\\\\\$"); }
// core/params/MacroParam.java & MacroParams.java
ESCAPE = "\\x5C" (\)   INVISIBLE_ESCAPE = ""   PARAM_PREFIX = "\\x24\\x24" ($$)
PARAM_SEQUENCE = "(?<![\\x5C])\\x24\\x24"   // the escapable $$ prefix used by every provider
```
A `$$` is a parameter only if **not** preceded by `\` or ``. `processEscapes` turns `\$$` into `$$` (inert) and `\|` into ``.

### 6.2 Full `$$` code table

(Regexes verbatim; all share the `(?<![\x5C])\x24\x24` escapable prefix unless noted. `\x3F`=`?`.)

| Code | Regex | Provider / file | Expands to |
|------|-------|-----------------|------------|
| `$$?` | `…\x24\x24\x3F` | `MacroParamProviderStandard` (NORMAL) | **Prompt**: opens a text field; typed value substituted. The generic "ask me." |
| `$$0`–`$$9` | `…\x24\x24([0-9])` | `MacroParamProviderPreset` (PRESET) | **Preset positional params**: digit selects a preset-list value. |
| `$$[name]` | `…\x24\x24\[([a-z0-9]{1,32})\]` | `MacroParamProviderNamed` (NAMED) | **Named prompt** (prompt label = the name); substituted then recompiled. |
| `$$[N]` | `\x24\x24\[<index>\]` (dynamic) + cleanup `\x24\x24\[[0-9]+\]` | `MacroExecVariableProvider` | **Runtime positional exec args** — `exec(...)`/`macro(...)` call args replace `$$[1]`, `$$[2]`, …; also exposed as vars `#var1`/`var1`/`&var1`. Leftovers stripped. |
| `$$[lbl[opt1,opt2…]i\|u:d]` | `…\x24\x24\[([a-z0-9\x20_\-\.]*)\[([^\]\[\x24\|]+)\](([iu]?)(:d)?)\]` | `MacroParamProviderList` (LIST) | **Inline choice list**; type hint `i`→item list (`:d` includes damage), `u`→user list, else plain choice. |
| `$$i` / `$$d` / `$$i:d` | `…\x24\x24(d\|i(:d)?)` | `MacroParamProviderItem` (ITEM) | **Item id picker**: `$$i`→identifier, `$$d`→damage, `$$i:d`→`id:damage`. |
| `$$f` | `…\x24\x24f` | `MacroParamProviderFriend` (FRIEND) | **Friend name** picker (`[a-zA-Z0-9_]{2,16}`). |
| `$$u` | `…\x24\x24u` | `MacroParamProviderUser` (USER) | **Online-player name** picker. |
| `$$t` | `…\x24\x24t` | `MacroParamProviderTown` (TOWN) | Town name. |
| `$$w` | `…\x24\x24w` | `MacroParamProviderWarp` (WARP) | Warp name. |
| `$$h` | `…\x24\x24h` | `MacroParamProviderHome` (HOME) | Home name. |
| `$$s` | `…\x24\x24s` | `MacroParamProviderShader` (SHADER_GROUP) | Shader-group name. |
| `$$k` | `…\x24\x24k` | `MacroParamProviderResourcePack` (RESOURCE_PACK) | Resource-pack name. |
| `$$m` | `…\x24\x24m` | `MacroParamProviderFile` (FILE) | **Macro-file picker** → rewrites to an include `$$<file>`. |
| `$$px`/`$$py`/`$$pz`/`$$pn`/`$$p` | `…\x24\x24(px\|py\|pz\|pn\|p)` | `MacroParamProviderPlace` (PLACE) | Named place: x / y / z / place-name / formatted `"x y z"` (per `settings.coordsFormat`). |
| `$$<file.txt>` | `…\x24\x24\<([a-z0-9\x20_\-\.]+\.txt)\>` | `MacroIncludeProcessor.processIncludes` | **File include** — splices file contents (lines joined with ``); bounded by `settings.maxIncludes`. |
| `$$!` | `PATTERN_STOP = …\x24\x24!` | `core/Macro.processStops` | **Stop/truncate** — everything from `$$!` onward is cut and the macro flagged stopped (last chat line opens chat with the buffer instead of sending). |
| `$${ … }$$` | `PATTERN_SCRIPT` ([§1.2](#12-the---script-block-delimiters)) | `MacroActionProcessor` | **Inline script block** (the script island). |
| `\$$` | — | `processEscapes` | Escaped literal `$$` (rendered as text). |

### 6.3 `%var%` runtime expansion (`scripting/VariableExpander.java`) — distinct from `$$`

```java
private static Pattern variablePattern =
   Pattern.compile("%(@?[#&]?[a-z~]([a-z0-9_\\-]*?)(\\[[0-9]{1,5}\\])?)%", 2);
```
- Variables are referenced inside text/args as **`%name%`** (same sigil grammar as `variableNamePattern`).
- `apply(macro)` loops with a hard cap of **256 replacements** (anti-infinite-loop).
- Resolution via `provider.getVariable(name, macro)`. Defaults when unresolved: int→`"0"`, string→`defaultStringValue` (usually `""`), bool→`"False"`, else literal name. Integers stringify directly; booleans → `"True"`/`"False"`. `quoteStrings=true` wraps string values in `"…"` (used before feeding the expression evaluator).
- **`$$` is a *compile-time* (Phase A) substitution; `%var%` is a *runtime* (per-execution) expansion** done by `provider.expand(macro, text, quoteStrings)` inside action `execute()` methods.

---

## 7. Full Action Catalog

**126 `ScriptAction*.java` files** total: 124 concrete actions + `ScriptActionBase` and `ScriptActionProvider` (infrastructure, not catalog actions). Plus `ScriptAction.java` in `parser/` (the rich base). The keyword is the lowercased string each class passes to `super(context, "…")`. `outVar` = trailing variable arg that captures the return value. `%var%` args are runtime-expanded via `provider.expand(...)`.

### 7.1 Control flow (`actions/lang/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionIf | `if` | Push conditional frame; evaluates expression (default var `flag`). |
| ScriptActionElseIf | `elseif` | Else-if branch; mutates the open `if` frame's flags. |
| ScriptActionElse | `else` | Terminal else; runs body iff no prior branch matched. |
| ScriptActionEndif | `endif` | Close the `if` frame. |
| ScriptActionIIf | `iif` | Inline-if **expression**: `iif(cond, trueVal[, falseVal])` → returns a value (not control flow). |
| ScriptActionIfBeginsWith | `ifbeginswith` | Conditional `if` whose test is string prefix match (extends `if`). |
| ScriptActionIfContains | `ifcontains` | Conditional `if`: substring contains. |
| ScriptActionIfEndsWith | `ifendswith` | Conditional `if`: string suffix match. |
| ScriptActionIfMatches | `ifmatches` | Conditional `if`: regex match (CI); can capture a group into a var. |
| ScriptActionDo | `do` | Loop opener (base). `do [N]` counted or unlimited; captures pointer as jump-back. |
| ScriptActionLoop | `loop` | Loop closer (base); pops/rewinds to `do`. |
| ScriptActionWhile | `while` | `do … while(cond)` closer (extends `loop`); continues while true. |
| ScriptActionUntil | `until` | `do … until(cond)` closer; continues until true. |
| ScriptActionFor | `for` | Counted loop (extends `do`): `for(i=1 to 10 step 2)` or `for(i,1,10[,step])`; closed by `next`. |
| ScriptActionForEach | `foreach` | Collection loop (extends `do`): array or built-in iterator (`players`,`effects`,`enchantments`,`env`,`properties`,`controls`,`running`); closed by `next`. |
| ScriptActionNext | `next` | `for`/`foreach` closer. |
| ScriptActionBreak | `break` | Break nearest enclosing loop (cancels its state). |
| ScriptActionStop | `stop` | Stop macros: `stop` (self), `stop(all)`/`stop(*)`, or `stop(name\|id)`. |
| ScriptActionUnsafe | `unsafe` | Begin unsafe block (raise per-tick budget, disable per-iteration yield); conditional op (always true). `unsafe([maxActions])` clamp 0–10000. |
| ScriptActionEndUnsafe | `endunsafe` | End unsafe block. |
| ScriptActionWait | `wait` | Latency gate via `canExecuteNow`: `wait(N)` seconds, `wait(Nms)` ms, `wait(Nt)` ticks. |
| ScriptActionExec | `exec` | Play another macro file as a floating template; extra args become call params. |
| ScriptActionPrompt | `prompt` | Open a GUI to ask the user for input; gates until complete; `prompt(outVar, label[, source][, override][, default])`. |
| ScriptActionIsRunning | `isrunning` | Returns bool: is a macro with this name/id executing? |

### 7.2 Output / logging (`actions/lang/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionEcho | `echo` | Emit text to **local** chat (`ReturnValueChat`); `parseVars=false` (expands raw). Perm `script.chat.echo`. |
| ScriptActionLog | `log` | Console log + aqua local line (`§b`). Converts `&` colour codes. |
| ScriptActionLogRaw | `lograw` | Parse args as JSON `IChatComponent`, emit raw to chat. |
| ScriptActionLogTo | `logto` | `logto(target, msg)`: append to `logs/<target>.txt`, or log to a named in-game target. |

### 7.3 Variables / assignment (`actions/lang/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionSet | `set` | Assign variable to a **string** (no eval). `set([name][, value])`, default name `flag`. |
| ScriptActionAssign | `assign` | Assign: evaluates RHS as **expression** unless `&`/`@&` string var; pushes to array if array specifier. |
| ScriptActionInc | `inc` | Increment counter (default `counter`, step 1). |
| ScriptActionDec | `dec` | Decrement counter. |
| ScriptActionUnset | `unset` | Remove a variable (default `flag`); clears array if `name[]`. |
| ScriptActionToggle | `toggle` | Flip a boolean flag (default `flag`). |

### 7.4 String / math / array (`actions/lang/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionRandom | `random` | `random(outVar[, max][, min])` → random int in [min,max] (defaults 0–100). |
| ScriptActionSqrt | `sqrt` | `sqrt(value[, outVar])` → integer square root. |
| ScriptActionMatch | `match` | Regex (CI) match with capture: multi `{v1 v2}`, array `name[]`, or single var + group/default. |
| ScriptActionReplace | `replace` | Literal substring replace in a var (base of regexreplace). |
| ScriptActionRegexReplace | `regexreplace` | Regex replace (`String.replaceAll`); errors → `script.error.badregex`. |
| ScriptActionSplit | `split` | `split(delim, source[, arrayName])` → array (delim is `Pattern.quote`d). |
| ScriptActionJoin | `join` | `join(glue, arrayName[, outVar])` → string (bools render `True`/`False`). |
| ScriptActionIndexOf | `indexof` | `indexof(arrayName, outVar, value[, caseSensitive])` → index or -1. |
| ScriptActionUcase | `ucase` | Uppercase. |
| ScriptActionLcase | `lcase` | Lowercase. |
| ScriptActionStrip | `strip` | Strip Minecraft colour/format codes. |
| ScriptActionEncode | `encode` | Base64 encode. |
| ScriptActionDecode | `decode` | Base64 decode (`""` on failure). |
| ScriptActionArraySize | `arraysize` | Element count of an array. |
| ScriptActionPush | `push` | Append value to end of array. |
| ScriptActionPop | `pop` | Remove & return last array element (`""` if empty). |
| ScriptActionPut | `put` | Put value into first free array slot (`putValueToArray`). |
| ScriptActionCalcYawTo | `calcyawto` | `calcyawto(x, z[, yawVar][, distVar])` → yaw 0–359 + distance to target. |
| ScriptActionGetProperty | `getproperty` | Read a property of a designable GUI control. |
| ScriptActionSetProperty | `setproperty` | Set a property of a designable GUI control (base of getproperty). |
| ScriptActionSetLabel | `setlabel` | Set GUI label text (+ optional binding). |

### 7.5 Input / keys (`actions/input/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionKey | `key` | Press a named game keybinding for one tick (auto-release next tick): inventory, drop, chat, attack, use, pick, screenshot, smoothcamera, swaphands. |
| ScriptActionKeyDown | `keydown` | Hold a key down (`keyState=true`; extends keyup). |
| ScriptActionKeyUp | `keyup` | Release a key up (`keyState=false`); name set: forward, back, left, right, jump, sneak, playerlist, sprint, or keycode. |
| ScriptActionPress | `press` | Full down+up press via input queue; `press(key[, deep])`. |
| ScriptActionToggleKey | `togglekey` | Toggle a key's pressed state. |
| ScriptActionType | `type` | Type literal characters into the game (args joined by spaces). |

### 7.6 Game / world / inventory (`actions/game/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionLook | `look` | Turn/aim to direction or angle, optionally animated; relative if signed. |
| ScriptActionLooks | `looks` | Variant of `look` (extends look). |
| ScriptActionSprint | `sprint` | Start sprinting (`sprint([0\|off])`). |
| ScriptActionUnsprint | `unsprint` | Stop sprinting. |
| ScriptActionRespawn | `respawn` | Respawn from game-over screen (cooldown). |
| ScriptActionDisconnect | `disconnect` | Disconnect from the server. |
| ScriptActionPick | `pick` | Pick item(s) into hotbar by id (tries each until success). |
| ScriptActionSlot | `slot` | Select a hotbar slot. |
| ScriptActionSlotClick | `slotclick` | Click a container slot: `slotclick(n[, "l"\|"r"][, shift])`. |
| ScriptActionInventoryUp | `inventoryup` | Move hotbar selection up (`actionInventoryMove(-1)`). |
| ScriptActionInventoryDown | `inventorydown` | Move hotbar selection down (`actionInventoryMove(1)`). |
| ScriptActionGetSlot | `getslot` | Find slot containing an item → var. |
| ScriptActionGetSlotItem | `getslotitem` | Read item id / stack size / damage of a slot into vars. |
| ScriptActionSetSlotItem | `setslotitem` | Set a slot's item (creative). |
| ScriptActionGetId | `getid` | Block name/id (+damage) at x,y,z (`~` relative). |
| ScriptActionGetIdRel | `getidrel` | Block name/id relative to player (extends getid). |
| ScriptActionGetItemInfo | `getiteminfo` | Item metadata for an item id → vars. |
| ScriptActionItemId | `itemid` | Resolve item identifier → numeric/internal id. |
| ScriptActionItemName | `itemname` | Resolve item id → display name. |
| ScriptActionTileId | `tileid` | Resolve tile/block identifier → id. |
| ScriptActionTileName | `tilename` | Resolve tile/block id → name. |
| ScriptActionPlaceSign | `placesign` | Place a sign and write up to 4 lines (≤15 chars each). |
| ScriptActionPlaySound | `playsound` | Play a client-side sound (`playsound(name[, volume])`, volume 0–100). |
| ScriptActionTrace | `trace` | Ray-trace player→target; exposes `TRACE*` vars; distance 3–256. |
| ScriptActionCraft | `craft` | Auto-craft an item (queues a crafting token). |
| ScriptActionCraftAndWait | `craftandwait` | Craft then block until complete (extends craft). |
| ScriptActionClearCrafting | `clearcrafting` | Clear the auto-crafting queue. |

### 7.7 Options / video / audio (`actions/option/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionBind | `bind` | Bind a Minecraft keybinding to a key. |
| ScriptActionGamma | `gamma` | Set/animate brightness 0–200 (**base class** for all animated option actions; `gamma(value[, seconds])`). |
| ScriptActionFov | `fov` | Set/animate FOV 10–170 (no-scale, extends gamma). |
| ScriptActionVolume | `volume` | Set/animate volume 0–100 (optional category arg; extends gamma). |
| ScriptActionMusic | `music` | Set music volume (extends gamma, category `music`). |
| ScriptActionSensitivity | `sensitivity` | Set mouse sensitivity 0–200 (extends gamma). |
| ScriptActionChatScale | `chatscale` | Chat text scale 0–100 (extends gamma). |
| ScriptActionChatWidth | `chatwidth` | Chat width 40–320 (extends gamma). |
| ScriptActionChatHeight | `chatheight` | Chat height (unfocused) 20–180 (extends gamma). |
| ScriptActionChatHeightFocused | `chatheightfocused` | Chat height (focused) 20–180 (extends gamma). |
| ScriptActionChatOpacity | `chatopacity` | Chat opacity 10–100 (extends gamma). |
| ScriptActionChatVisible | `chatvisible` | Set/toggle chat visibility (shown/commands/hidden). |
| ScriptActionCamera | `camera` | Set/cycle camera perspective (0–2). |
| ScriptActionFog | `fog` | Set render distance (`fog(N)`) or cycle (`fog`). |
| ScriptActionSetRes | `setres` | Schedule a window resolution change (`setres(w,h)`). |
| ScriptActionShaderGroup | `shadergroup` | Activate/cycle (`+`/`-`)/clear a post-process shader. |
| ScriptActionResourcePacks | `resourcepacks` | Select resource packs. |
| ScriptActionReloadResources | `reloadresources` | Reload the resource manager. |

### 7.8 Mod / config (`actions/mod/`)

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionConfig | `config` | Switch active config; bare call restores prior (`config(name[, verbose])`). |
| ScriptActionImport | `import` | Overlay another config without switching; bare call restores. **params[0] not expanded.** |
| ScriptActionUnimport | `unimport` | Remove the overlaid config. |

### 7.9 IMC + UI/toast (`actions/imc/` and `actions/`)

> Note: only `ScriptActionSelectChannel` and `ScriptActionSendMessage` live in `actions/imc/`; the others below are directly in `actions/`.

| Class | Keyword | Purpose |
|-------|---------|---------|
| ScriptActionSendMessage | `sendmessage` | Send an IMC message over LiteLoader's MessageBus on the selected channel. |
| ScriptActionSelectChannel | `selectchannel` | Select/validate the IMC channel (stored in macro state). |
| ScriptActionGui | `gui` | Open a GUI screen, or `gui(bind, slot, layout)` to bind. |
| ScriptActionShowGui | `showgui` | Display a custom designable screen (+back screen, triggers); bare call closes. |
| ScriptActionBindGui | `bindgui` | Bind a custom GUI screen to a slot (default `playback`). |
| ScriptActionPopupMessage | `popupmessage` | Show a popup/notification (`& ` colour codes; optional colour animate). |
| ScriptActionTitle | `title` | On-screen title/subtitle with fade/stay timing; bare call clears. |
| ScriptActionToast | `toast` | Show a configurable toast, or `toast(clear[, all])`; type fuzzy-matched; ticks 5–600. |
| ScriptActionAchievementGet | `achievementget` | Show an "advancement get" toast (default icon `grass`). |
| ScriptActionClearChat | `clearchat` | Clear the chat log (returns `ReturnValueLog("")`). |
| ScriptActionTime | `time` | Format current date/time into a var (default ISO; bad pattern → `'Bad date format'`). |
| ScriptActionStore | `store` | Persist data (currently: save a named Place at player coords). |

**Thread-safety / permissions conventions:** actions touching MC GUI/world override `isThreadSafe()` → false. Input actions are `isPermissable()` (group `input`); chat actions (`echo`,`iif`) group `chat`; permission nodes are `script.<group>.<keyword>` with wildcard `script.*` and `script.<group>.*`.

---

## 8. Extension / Module API

### 8.1 `IMacrosAPIModule` — the base every module implements

```java
public interface IMacrosAPIModule { void onInit(); }   // single lifecycle hook, called after instantiation
```
`IScriptAction extends IMacrosAPIModule` and `IVariableProvider extends IMacrosAPIModule`, so actions and providers are modules.

### 8.2 `@APIVersion` — the version gate

```java
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
public @interface APIVersion { int value(); }
```
**Current API version = `26`.** A module class **must** be annotated `@APIVersion(26)` or it is rejected (`ModuleLoader.checkAPIVersion`: annotation present AND `value() == 26`; the loader's `API_VERSION_MIN == API_VERSION == 26`).

### 8.3 `ModuleLoader` — discovery & registration (convention-based, not ServiceLoader)

Discovery:
- **Filesystem:** archives in `<macros>/modules/` named `module_*.zip` or `module_*.jar`. The jar/zip is added to the `LaunchClassLoader` at runtime (`classLoader.addURL`). Each `.class` entry **not** containing `$` (skips inner classes) is examined.
- **Command line:** system property `-Dmacros.modules=class1,class2` loads those class names directly.

Registration dispatch is by **class simple-name prefix**:

| Class-name prefix | Registered as | Required interface |
|-------------------|---------------|-------------------|
| `ScriptAction*` | script action | `IScriptAction` |
| `VariableProvider*` | variable provider | `IVariableProvider` |
| `ScriptedIterator*` | foreach iterator | `IScriptedIterator` |
| `EventProvider*` | event provider | `IMacroEventProvider` |
| *(other)* | ignored | — |

Instantiation: `moduleClass.newInstance()` (**public no-arg constructor required**), then `onInit()` is invoked. `LoadedModuleInfo` tracks per-archive counts and dedupes (actions by `toString()`, others by simple name).

> **Note:** this is distinct from how *built-in* actions register. Built-ins are found by `ScriptCore.initActions` reflection over `ScriptActionBase` subclasses with a `(ScriptContext)` ctor ([§2.6](#26-the-action-registry--reflection-not-a-keyword-table)). Third-party module classes use a **public no-arg ctor** + the `module_*` archive + `@APIVersion(26)` path instead.

### 8.4 The runtime service surface — `IScriptActionProvider`

Every action's `execute(provider, macro, instance, rawParams, params)` receives an `IScriptActionProvider` (implemented by `ScriptActionProvider extends VariableManager`). It exposes: engine/settings access; the full variable plumbing (`getVariable`, `setVariable` overloads incl. `(…, IReturnValue)`, `expand`, `getExpressionEvaluator`, array ops, flag ops, provider register/unregister, listeners); chat/log sinks (`actionSendChatMessage`, `actionAddChatMessage`, `actionAddLogMessage`); control-flow callbacks (`actionBreakLoop`, `actionBeginUnsafeBlock`, `actionEndUnsafeBlock`); and game actions (gui/config/inventory/keys/items/sound/toast/resolution/camera/render-distance). Nested `enum ToastType { ADVANCEMENT, CHALLENGE, GOAL, RECIPE, TUTORIAL, HINT, NARRATOR }`.

`IMacroEngine` (for `exec`/`stop`/`isrunning`): `getFile`, `getMacroNameForId`, `getMacroIdForName`, `getActiveConfig(Name)`, `playMacro(template, …)`, `createFloatingTemplate`, `getExecutingMacroStatus`.

---

## 9. Reimplementation Checklist

Load-bearing facts to replicate for bug-compatibility:

1. **Two-phase pipeline:** `$$` parameter substitution (compile-time, iterative) → `$${…}$$` split into chat/script → `;`-tokenize → parse → flat action list. Keep them separate (`$$` ≠ `%var%`).
2. **`$${(.*?)}$$`** with collapse of `$${$${`/`}$$}$$`; escape via lookbehind `(?<![\x5C])`. `//` line comments. Statement sep `;` (quote-aware, `\"` doesn't toggle), with `` alias and ``→`\|` restore.
3. **Reflection registry**, keyword = lowercased `super(context, "…")` arg; first-match-wins parser chain (assignment → eval → action → directive).
4. **Single int pointer + Deque stack (max depth 32).** `pointer++` only when an action returns true. Loop frames store the jump-back pointer; conditional frames store `-1`. `popStack` rewinds when ptr ≥ 0 (and yields in safe mode). `getConditionalExecutionState` = AND of all `conditionalFlag`s. Operator dispatch order: pop → push → conditional → normal.
5. **Conditionals skip by gating, not by jumping** (lines still walked; `if/elseif/else` only flip `conditionalFlag`/`ifFlag`/`elseFlag`).
6. **`wait`/`prompt` gate via `canExecuteNow` returning false** (re-poll, no advance) — not the stack. Synchronous macros bypass the gate.
7. **Throttle** via `maxActionsPerTick` + `maxExecutionTime` → `suspendProcessing` → resume next tick. `unsafe` disables the per-loop-iteration yield.
8. **Return values:** `IReturnValue` with local/remote messages; handler dispatches `""`-clear / LogTo / Raw / chat; outVar capture rejects `isVoid()`, handles arrays via `shouldAppend()`.
9. **Variables:** `#`/`&`/(flag) sigils, `@` shared; local = per-macro provider, global = `.globalvars.xml`; `%name%` runtime expansion (cap 256); reserved `CONFIG`/`KEYID`/`KEYNAME`; first-non-null provider wins; fault-isolating provider removal.
10. **Expression evaluator: recursive regex split on the FIRST operator, no arithmetic precedence** — parentheses only. Strings interned as descending int ids; `=`==`==` equality; truthy iff `0 < v < nextStringLiteral`.
11. **Modules:** `module_*.{jar,zip}` in `<macros>/modules/`, public no-arg ctor classes prefixed `ScriptAction`/`VariableProvider`/`ScriptedIterator`/`EventProvider`, annotated `@APIVersion(26)`, loaded onto the classloader, `onInit()` called.
