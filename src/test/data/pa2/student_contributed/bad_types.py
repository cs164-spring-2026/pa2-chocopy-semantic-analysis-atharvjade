# Type errors covering assignment, operators, conditions, indexing, and
# function / method / class usage.  Each error exercises our most-specific
# type recovery: the inferred type on every ill-typed expression is the
# tightest type the rule can still yield once the offending premise is
# dropped, rather than a blanket `object`.

# --- Setup ----------------------------------------------------------------
x:int = 0
y:bool = True
s:str = "hi"
nums:[int] = None
strs:[str] = None

class A(object):
    v:int = 0
    def m(self:"A", n:int) -> int:
        return n

class B(A):
    def __init__(self:"B"):
        self.v = 0

a:A = None
b:B = None

# --- VarDef initializer type mismatch ------------------------------------
bad_var:int = True          # Expected int; got bool

# --- Arithmetic: one int, one non-int -> still infer int -----------------
x = 1 - "two"               # recover as int
x = True * 3                # recover as int

# --- Mixed `+`: one str, one int -> per rule, infer object ---------------
x = "hi" + 1                # object, not int
# One int, one list -> object
x = 1 + [1]                 # object
# Two lists of different element types join to [object]
nums = [1, 2] + ["a"]       # list-join join-> [object]

# --- Comparison on mismatched types still gives bool ---------------------
y = 1 < "str"               # bool
y = 1 == "str"              # bool
y = None == None            # bool (== on None is a type error)

# --- Logical / not require bool ------------------------------------------
y = 1 and True              # bool
y = not 1                   # bool

# --- Unary minus on non-int recovers as int ------------------------------
x = -"str"                  # int

# --- Conditions must be bool ---------------------------------------------
if 1:                       # condition type error; body still checked
    x = 2
while "x":                  # condition type error
    x = 0
x = 0 if 1 else 1           # IfExpr condition type error; result int

# --- for: iterable must be str or list; loop body still checked ----------
for x in 5:                 # cannot iterate over int
    x = x + 1

# --- Index / member expressions recover to element/field type -----------
x = nums["a"]               # non-int index; still int (element type)
s = strs[True]              # still str (element type)
x = 5[0]                    # cannot index int; recover as object
s = a.v                     # field is int, not str -- assignment mismatch
x = a.missing               # no such attribute; recover as object

# --- Function / method calls --------------------------------------------
def f(n:int) -> int:
    return n

x = f("hi")                 # wrong arg type; return type still int
x = f(1, 2)                 # wrong arity; still int
x = a.m(True)               # method arg type error; still int
x = a.missing_method(1)     # no such method; recover as object

# --- Constructor call type is the class, even on bad args ---------------
b = B(1)                    # B.__init__ takes no args; still B

# --- Assignments to values not assignable --------------------------------
x = "hi"                    # assign str to int
nums = [True]               # assign [bool] to [int]
a = 1                       # assign int to A

# --- Multi-target assignment: leftmost-errored-target gets attached -----
# (per §5.3.2 special case)
x = y = "wrong"             # int / bool targets; str value

# --- List of None still unifies through object -> [object] --------------
nums = [None, 1]            # join int/<None> -> object, but int assignability fails
