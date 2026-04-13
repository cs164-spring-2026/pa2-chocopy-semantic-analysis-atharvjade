# Type errors covering as many typing rules as our implementation supports.
# Each error exercises our most-specific type recovery (PA2 Section 5.3.2):
# when an expression fails to type-check we still attach the tightest type
# the rule can yield once the offending premise is dropped, instead of
# blanket `object`.  Excerpts from this file are discussed in the README.

# --- Setup (ChocoPy requires all declarations before any statement) -----
x:int = 0
y:bool = True
s:str = "hi"
nums:[int] = None
strs:[str] = None
nested:[[int]] = None
o:object = None

class A(object):
    v:int = 0

    def m(self:"A", n:int) -> int:
        return n

class B(A):
    w:str = ""

    def __init__(self:"B"):
        self.v = 0

    def g(self:"B") -> int:
        return 0

a:A = None
b:B = None

# ---- VarDef initializer mismatches (VAR-INIT rule) ---------------------
bad_bool:int = True         # Expected int; got bool
bad_int:str  = 5            # Expected str; got int
bad_none:int = None         # Expected int; got <None>
bad_nested:[int] = None
bad_obj:A = 0               # Expected A; got int

# ---- Functions used by the statement section --------------------------
def f(n:int) -> int:
    return n

def two(a1:int, a2:bool) -> int:
    return a1

# Return-type mismatches inside a function body (RETURN-STMT rule)
def ret_bad_1() -> int:
    return "hi"             # Expected int; got str

def ret_bad_2() -> str:
    return [1, 2]           # Expected str; got [int]

def ret_missing() -> int:
    return                  # Expected int; got None

def ret_void_val():
    return 5                # Function declared <None>-returning; got int

# ========================================================================
# Top-level statements: each section triggers a distinct typing rule.
# ========================================================================

# ---- Arithmetic BINARY-OP (-, *, //, %) on non-ints; result still int -
x = 1 - "two"
x = True * 3
x = "a" // 2
x = s % 3
x = None - 1
x = 1 // False              # bool operand is not int

# ---- BINARY-OP `+`: mixed operands (one int, one non-int) ---------------
x = "hi" + 1                # infer object (mixed rule)
x = 1 + [1]                 # infer object
x = "a" + True              # infer object
x = True + 1                # infer int (bool rejected, but int-result)
# List concat with incompatible element types joins to [object]
nums = [1, 2] + ["a"]
# String + list is ill-typed
s = "pre" + [1]

# ---- COMPARE ordering (<, <=, >, >=) requires same int or str ---------
y = 1 < "str"
y = 1 <= None
y = "a" >= 1
y = True < 1                # bool/int not comparable

# ---- EQUALITY (==, !=) requires matching non-None types ---------------
y = 1 == "str"
y = None == None
y = None != 1
y = [1] == [1]              # list types are not equality-comparable

# ---- LOGICAL (and/or) and NOT require bool ----------------------------
y = 1 and True
y = True or "x"
y = None and False
y = not 1
y = not "hi"

# ---- UNARY-OP minus requires int; not requires bool ------------------
x = -"str"
x = -True
x = -None
y = not 1                   # (second occurrence of non-bool not)

# ---- IS operator: both sides must be non-special reference types ----
y = 1 is None
y = True is None
y = "a" is None
y = 1 is 2

# ---- IF/WHILE/IF-EXPR condition must be bool -------------------------
if 1:
    x = 2
while "x":
    x = 0
if None:
    x = 0
x = 0 if 1 else 1
x = (0 if "b" else 1)

# ---- FOR: iterable must be a str or a list --------------------------
for x in 5:
    x = x + 1
for x in True:
    x = x + 1
for x in None:
    x = x + 1

# For-loop where the induction variable type mismatches the element type
for s in nums:              # iterating [int] into a str variable
    s = s
for x in "hello":           # iterating str into an int variable
    x = x + 1

# ---- LIST-SELECT: index must be int; list type required -------------
x = nums["a"]               # non-int index; still infer int
s = strs[True]              # non-int index; still infer str
x = 5[0]                    # cannot index int
x = a[0]                    # cannot index a class value
x = None[0]                 # cannot index None

# Nested list indexing with non-int inner index
x = nested[0]["q"]

# ---- MEMBER-EXPR / METHOD-CALL --------------------------------------
s = a.v                     # field is int, not str
x = a.missing               # attribute does not exist; recover object
x = a.missing.also          # member expr on object; attribute missing
x = a.missing_method(1)     # method does not exist; object
x = a.m(True)               # method arg type mismatch; still int
x = a.m(1, 2)               # wrong arity; still int
x = 5.foo                   # member on non-class
x = "s".bar                 # member on non-class (str)
x = (1).baz                 # member on non-class
x = nums.first              # lists have no attributes

# ---- CALL-EXPR --------------------------------------------------------
x = f("hi")                 # wrong arg type
x = f(1, 2)                 # wrong arity
x = f()                     # wrong arity
x = two(1, 2)               # second arg wrong type
x = two("a", True)          # first arg wrong type
x = not_a_func(1)           # unknown identifier -> object
x = x(1)                    # x is int, not callable
x = B()                     # ok -> B ... but then assign to int below
x = B(1)                    # B ctor takes no args

# ---- ASSIGN-STMT --------------------------------------------------
x = "hi"                    # str -> int
nums = [True]               # [bool] -> [int]
nums = "hello"              # str -> [int]
a = 1                       # int -> A
a = s                       # str -> A
s = None                    # None -> str (special type)
x = None                    # None -> int

# Subclass assignment works both ways check: A -> B is bad
b = a                       # A -> B (not a subclass)

# Member assignment type mismatch
a.v = "oops"                # field is int

# Indexed assignment errors
nums[0] = "hi"              # element type mismatch
nums["z"] = 1               # bad index type
s[0] = "x"                  # str is not assignable via index

# Multi-target assignment with mixed target / value types (§5.3.2)
x = y = "wrong"             # int/bool targets; str value
nums[0] = x = True          # [int] elem and int target; bool value
