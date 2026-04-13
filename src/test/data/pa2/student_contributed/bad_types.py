# Type errors covering assignment, operators, conditions, indexing, and
# function / method / class usage.  Each error exercises our most-specific
# type recovery: the inferred type on every ill-typed expression is the
# tightest type the rule can still yield once the offending premise is
# dropped, rather than a blanket `object`.

# --- Setup (all declarations come before any statement, per ChocoPy) -----
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
bad_str:str = 5             # Expected str; got int

def f(n:int) -> int:
    return n

# --- Top-level statements ------------------------------------------------

# Arithmetic: one int, one non-int -> still infer int
x = 1 - "two"               # recover as int
x = True * 3                # recover as int
x = "a" // 2                # recover as int
x = s % 3                   # recover as int

# Mixed `+`: one str, one int -> per rule, infer object
x = "hi" + 1                # object
x = 1 + [1]                 # object
nums = [1, 2] + ["a"]       # list-join -> [object]

# Comparison on mismatched types still gives bool
y = 1 < "str"               # bool
y = 1 == "str"              # bool
y = None == None            # bool (== on None not allowed)
y = "a" >= 1                # bool

# Logical / not require bool
y = 1 and True              # bool
y = True or "x"             # bool
y = not 1                   # bool

# Unary minus on non-int recovers as int
x = -"str"                  # int
x = -True                   # int

# 'is' requires non-special types on both sides
y = 1 is None               # bool

# Conditions must be bool
if 1:                       # int condition
    x = 2
while "x":                  # str condition
    x = 0
x = 0 if 1 else 1           # IfExpr int condition; result int

# for: iterable must be str or list
for x in 5:                 # cannot iterate over int
    x = x + 1

for x in True:              # cannot iterate over bool
    x = x + 1

# Index / member expressions recover to element/field type
x = nums["a"]               # non-int index; still int
s = strs[True]              # still str
x = 5[0]                    # cannot index int; recover as object
s = a.v                     # field is int, not str -> assignment mismatch
x = a.missing               # no such attribute; object

# Function / method calls
x = f("hi")                 # wrong arg type; return still int
x = f(1, 2)                 # wrong arity; still int
x = a.m(True)               # method arg type error; still int
x = a.missing_method(1)     # no such method; recover as object
x = not_a_func(1)           # no such identifier; object

# Constructor call type is the class, even on bad args
b = B(1)                    # __init__ takes no extra args; still B

# Assignments to values not assignable
x = "hi"                    # str to int
nums = [True]               # [bool] to [int]
a = 1                       # int to A
s = None                    # None to str (special type)
x = None                    # None to int (special type)

# Multi-target assignment: leftmost-errored-target gets attached (§5.3.2)
x = y = "wrong"             # int / bool targets; str value

# Indexed assignment errors
nums[0] = "hi"              # element type mismatch
nums["z"] = 1               # bad index type
s[0] = "x"                  # str is not a list type
