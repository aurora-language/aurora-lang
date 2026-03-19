# Aurora VM & Language Implementation Tasks

## Phase 2: Control Flow & Advanced Types
- [x] Complete `throw` support (Compiler -> VM emission).
  - ⚠️ `visitThrowStmt` is empty in Compiler.java. `THROW` opcode exists in VM but is never emitted.
- [ ] Add support for `DestructurePattern` in records (match expression).
- [ ] Implement `record` type (Compiler implementation needed).
    - [ ] Built-in `copy()`, `toString()`, and equals.
    - [ ] Decomposition support in match.
- [ ] Implement `enum` type (Compiler implementation needed).
    - ⚠️ `visitEnumDecl` is empty in Compiler.java.
    - [ ] Support for associated values/body members.
- [ ] Support linearization for multiple trait inheritance.
  - ⚠️ Currently just uses `putIfAbsent` to mix in methods (VM.java inflate).
- [ ] Support `lazy val` (Deferred assignment for local variables only — declare now, assign once later; compiler enforces: no read before assignment, no reassignment, all code paths must assign before use; not applicable to class fields).

## Phase 3: Concurrency & Async
- [ ] Implement `async/await` (Coroutines).
    - [ ] Compiler support for `async` functions and `await` expressions.
      - ⚠️ `visitAwaitExpr` is empty in Compiler.java.
    - [ ] VM support for suspension/resumption (state snapshots).
    - [ ] Task scheduler implementation (event loop).

## Phase 4: Standard Library & Ecosystem
## This phase runs after completes the new VM written in rust
- [ ] Implement `Collections` module (List, Map, Set).
  - ⚠️ Trait definitions exist in `Collections.ar` but no implementation classes.
- [ ] Implement `Math` module (abs, sin, cos, etc.).
- [ ] Implement `Json` module (Serialization/Deserialization).
- [ ] Implement `Http` module (Async client).

## Phase 5: Optimization & Internals
- Supplement: Java implementation is made for early developing, so performance optimization are currently not required
- [ ] Optimize VM loop (Switch-table optimization).
- [ ] Improve stack management (Register-based VM experimentation?).
- [ ] Refine module loader (Caching, circular dependency resolution).
- [ ] Implement `nonnull` runtime assertion check.
  - ⚠️ `visitUnaryExpr` in Compiler.java has a TODO comment — runtime check is not implemented.

## Phase 6: Tooling & DX
- [x] LSP: Hover (`buildHoverContent` implemented).
- [x] LSP: Completion (keywords and declaration completion implemented).
- [x] LSP: Semantic Highlighting (`SemanticTokenVisitor` implemented).
- [x] LSP: Diagnostics / `publishDiagnostics` implemented.
- [ ] LSP: Go to Definition / Find References.
  - ⚠️ `definition()` is implemented but cross-file reference resolution is incomplete.
- [ ] LSP: Rename refactoring support.
- [ ] LSP: Semantic Highlighting refinements (type variables, constants).
- [ ] VSCode: Debugger Integration (DAP - Debug Adapter Protocol).
- [ ] VSCode: Integrated REPL or "Run in Aurora" command.

## Phase 2b: Singleton Object
- [ ] Implement `object` declaration (syntactic sugar — desugars to `pub class` with all members forced `static`).
    - [ ] `AuroraParser.g4`: Add `objectDeclaration` rule (`CLASS | OBJECT` is lexed but object semantics are unimplemented).
    - [ ] Compiler: desugar `object Foo { ... }` → `pub class Foo { static ... }` before type-checking pass.
    - [ ] Support anonymous companion `object` block inside a class body (desugars to static members on the enclosing class).
    - [ ] Access syntax: `Foo::member` (consistent with existing `Io::println`, `Color::Red`).
    - [ ] `var` fields inside `object` → compile error (enforce immutability; satisfies `Sendable` automatically).
    - [ ] Namespace support: `object` definable inside any namespace, resolved via `use`.

## Phase 8: Known Issues & Bugs
- [x] Compiler: `visitElvisExpr` is empty — Elvis operator (`?:`) produces no bytecode.
- [x] Compiler: `visitThrowStmt` is empty — `throw` statements are silently ignored.
- [x] Compiler: `visitTryStmt` and `visitTryStmtCatch` are empty — try/catch blocks are not compiled.
- [x] TypeChecker: `visitThrowStmt` calls `inherits(throwable, "")` with an empty string — Throwable check never works.
- [ ] VM: Improve error reporting for ClassCastException in `as` operator.
- [ ] Refactor: `NodeFinder` is duplicated across `aurora.lsp` and `aurora.analyzer` packages.
- [ ] TypeChecker: Output error `Thrown type 'RuntimeError' must inherit from Throwable` for failing to resolve RuntimeError inheritance

## Phase 9: Language Design Improvements
- [ ] **#1 Remove generic type erasure** (reified generics)
    - [ ] Design reification strategy on JVM (implicit `ArClass` tokens or inline reified functions).
    - [ ] Update `ArInstance` / `ArClass` to carry runtime type information.
    - [ ] Enable `T is String`-style runtime type checks in generic contexts.
    - [ ] Note: revisit after Rust VM migration for full monomorphization option.
- [ ] **#2 Remove `new` keyword**
    - [ ] `AuroraLexer.g4`: Remove `NEW` token.
    - [ ] `AuroraParser.g4`: Remove `objectCreation` rule; instantiation falls into `CallExpr`.
    - [ ] `TypeChecker.java` (`visitCallExpr`): Resolve callee — if class/record, compile as `NewExpr`.
    - [ ] Add PascalCase naming violation as a hard compiler error to preserve disambiguation.
    - [ ] Deprecation phase: `new` emits warning → remove in next major version.
    - [ ] Add `aurora fmt --fix` to strip `new` automatically.
    - [ ] Tests: `test_newless.ar`, `test_naming_violation.ar`.
- [ ] **#4 Compile-time concurrency safety** (`Sendable` trait)
    - ⚠️ `Sendable` trait is defined in `Concurrent.ar` but the type checker does not enforce it.
    - [ ] Auto-tag types: primitives and `val`-only classes → `Sendable`; any `var` field → non-`Sendable`.
    - [ ] `TypeChecker.java` (`visitThreadExpr`): Verify all arguments to `thread Name(...)` are `Sendable`; emit error if not.
    - [ ] Upgrade `Mutex<T>` to expose `withLock { }` scoped API; hardcode as intrinsically `Sendable`.
    - [ ] Add lightweight escape analysis: block locked value from escaping `withLock` closure scope.
    - [ ] Tests: data race caught at compile time, `Mutex.withLock` usage.
- [ ] **#7 Keyword reduction**
    - [x] `inject` → receiver syntax (`pub fun TypeName.method()`) — `receiverType` field implemented in `FunctionDecl` and `AuroraParser.g4`.
    - [ ] `lazy` → keep as compiler keyword (deferred assignment for local variables, no runtime overhead).
    - [ ] `expect` → remove keyword; move to `Result.expect(msg)` standard library method.
- [x] **#8 Nested block comments** — `commentDepth` counter and push/pop mode implemented in `AuroraLexer.g4`.

## シンタックスハイライトの問題
- if式の中身に色がつかない
  - (例) if exc v >= min && v < max else v >= min && v <= max
- Range.ar:25
  - pub override fun iterator() -> Iterator<T> => RangeIterator<T>(self)
  - この関数の戻り値の型(Iterator<T>)の部分で、<>にまで色がついてしまっている、また他にもそうなってしまっている部分がいくつかある
    - constructor(range: Range<T>) パラメーターの型アノテーションの部分
    - pub class RangeIterator<out T default int> : Iterator<T> 継承しているIterator<T>の部分
    - RangeIterator<T>(self)の型の部分の色(RangeIterator,T)が青になっている
- (Reference)Error.ar
  - pub class ReferenceError(reason: string) : RuntimeError(reason)
  - pub class RuntimeError(reason: string) : Error(reason) {}
  - 両者とも()や:など記号以外がキーワード含めすべて青になってしまっている
- これらの原因はおそらくほぼ確定でAuroraParserのシンタックスシュガー解決にあります。なので、SemanticTokenVisitorはどちらにせよ行うのはハイライトだけなので一部をAuroraParserから流用しつつANTLR4のVisitorを使うのが最適だと思われます。