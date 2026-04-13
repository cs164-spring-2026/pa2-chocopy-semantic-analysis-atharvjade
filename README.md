# CS 164: Programming Assignment 2

[ChocoPy Specification]: https://sites.google.com/berkeley.edu/cs164-sp26/chocopy?authuser=1

Note: Users running Windows should replace the colon (`:`) with a semicolon (`;`) in the classpath argument for all command listed below.

## Getting started

Run the following command to build your semantic analysis, and then run all the provided tests:

    mvn clean package

    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=.s --dir src/test/data/pa2/sample/ --test


In the starter code, only two tests should pass. Your objective is to implement a semantic analysis that passes all the provided tests and meets the assignment specifications.

You can also run the semantic analysis on one input file at at time. In general, running the semantic analysis on a ChocoPy program is a two-step process. First, run the reference parser to get an AST JSON:


    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=r <chocopy_input_file> --out <ast_json_file> 


Second, run the semantic analysis on the AST JSON to get a typed AST JSON:

    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        -pass=.s  <ast_json_file> --out <typed_ast_json_file>


The `src/tests/data/pa2/sample` directory already contains the AST JSONs for the test programs (with extension `.ast`); therefore, you can skip the first step for the sample test programs.

To observe the output of the reference implementation of the semantic analysis, replace the second step with the following command:


    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=.r <ast_json_file> --out <typed_ast_json_file>


In either step, you can omit the `--out <output_file>` argument to have the JSON be printed to standard output instead.

You can combine AST generation by the reference parser with your 
semantic analysis as well:

    java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
        --pass=rs <chocopy_input_file> --out <typed_ast_json_file>


## Assignment specifications

See the PA2 specification on the course
website for a detailed specification of the assignment.

Refer to the ChocoPy Specification on the CS164 web site
for the specification of the ChocoPy language. 

## Receiving updates to this repository

Add the `upstream` repository remotes (you only need to do this once in your local clone):

    git remote add upstream https://github.com/cs164-spring-2026/pa2-chocopy-semantic-analysis.git


To sync with updates upstream:

    git pull upstream master

## Submission writeup

Team member 1: Jade Mon

Team member 2: Atharv Sampath

### Acknowledgements

No outside help was used beyond the PA2 specification, the ChocoPy language
reference manual, and the staff-provided reference implementation (used only
via `--pass=.r` to compare outputs on our own test programs).

### Late submission

No late hours were consumed on this assignment.

### Q1: Number of passes and their purpose

Our semantic analysis performs **two** AST passes, both orchestrated by
`StudentAnalysis.process` in `src/main/java/chocopy/pa2/StudentAnalysis.java`:

1. **`DeclarationAnalyzer`** (`src/main/java/chocopy/pa2/DeclarationAnalyzer.java`).
   A shallow pass that walks top-level declarations and populates the global
   symbol table. For each `VarDef` it inserts the declared `ValueType`; for
   each `FuncDef` it builds a `FuncType` (including duplicate-parameter
   detection at line 92); for each `ClassDef` it inserts a placeholder
   `ClassValueType`. It does *not* recurse into function or class bodies —
   those are handled in the next pass. Its purpose is to make every
   top-level name visible before any body is type-checked, so forward
   references between functions and classes resolve correctly.

2. **`TypeChecker`** (`src/main/java/chocopy/pa2/TypeChecker.java`).
   A full recursive pass over the AST that enforces every remaining rule in
   §5.2 and assigns `inferredType` to each expression. Before visiting any
   body, `analyze(Program)` (line 623) does three sub-passes over the
   top-level declaration list: (a) collect class names and module-level
   variable names (for shadow and `global` checks), (b) validate each
   class's super-class against the classes declared *so far* (rule 4), and
   (c) register inherited fields/methods with override checks in
   `registerClassMetadata` (line 315). Only then does it recursively
   type-check the body of each function, method, and top-level statement,
   using a stack of nested `SymbolTable`s plus `functionStack` for
   `nonlocal`/`global` resolution.

### Q2: Hardest component

The hardest component was **class inheritance with override checking**, and
in particular doing it in the right order. Three separate things needed to
agree:

- **Super-class validity** (rule 4) has to be checked against only the
  classes declared *before* the current one, which is why
  `validateClassSuper` (`TypeChecker.java` line 231) consults
  `classesDeclaredSoFar` rather than the global symbol table.
- **Attribute/method collection** (`registerClassMetadata`,
  `TypeChecker.java` line 315) must copy the parent's field and method maps
  before adding the child's, because rule 5 forbids re-defining any
  *inherited* name — not just directly declared ones. We also have to
  treat `__init__` specially: it isn't inherited as a method (constructor
  signatures are per-class), but its signature *is* compared against the
  parent's using the same `methodOverridesCompatible` predicate
  (`TypeChecker.java` line 162) that normal overrides use, with the caveat
  that the first (`self`) parameter is allowed — indeed required — to
  change type.
- **Subtype relationships** have to be consulted by `isAssignable`
  (`TypeChecker.java` line 123), which queries `classSuper` transitively
  (via `isSubclassOf`, line 109). That map is filled during metadata
  registration, so the ordering of the three sub-passes in
  `analyze(Program)` matters: validate supers → register metadata →
  type-check bodies.

Getting any of those three orderings wrong produced cascading incorrect
errors (e.g. treating a class with an invalid super as if it inherited
from `object`, which then made unrelated override checks succeed
spuriously), so a lot of debugging was spent making sure each sub-pass
only relied on data that had already been fully populated.

### Q3: Why recover with the most-specific type?

When an expression fails to type-check, the type we attach to it propagates
to every containing expression. If we collapsed every ill-typed node to
`object`, we would lose the information that almost every typing rule
needs to keep making useful progress, and we would generate a flood of
cascading `Expected type T; got type object` errors that bury the real
problem.

Concrete examples from
`src/test/data/pa2/student_contributed/bad_types.py`:

- **`x = 1 - "two"`**: the rule for `-` requires both operands to be
  `int`. One of them isn't, but the *result* of `-` is specified to be
  `int` regardless, so we infer `int`. If we inferred `object`, the
  assignment `x = ...` would then fail with a second, redundant
  "Expected int; got object" — even though the user already knows the
  issue is with the subtraction.
- **`x = f("hi")`** where `def f(n:int) -> int`: the call fails because
  of the argument. We still infer the call's type as `int` (the declared
  return type) because that lets the surrounding `x = ...` succeed. If
  we had blanket-returned `object`, the user would see two errors for
  one mistake.
- **`x = nums["a"]`** where `nums:[int]`: the index expression's type is
  the element type (`int`) regardless of whether the index itself was
  well-typed — we drop the *`int`-index* premise, not the list-select
  conclusion.
- **`x = a.missing`**: here we genuinely have no way to pick a
  more-specific type (the field doesn't exist), so we fall back to
  `object`. The key is to only do that when every premise of the typing
  rule is broken — not whenever any of them is.

In short: inferring the most-specific type keeps a single mistake
producing a single error, which is what makes the error output readable
on programs that have many small type bugs.
