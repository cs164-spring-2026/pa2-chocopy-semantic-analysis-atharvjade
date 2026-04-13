# Multiple distinct (non-conflicting) violations of the semantic rules
# listed in Section 5.2 of the PA2 spec.  Each labelled section below
# exercises a different rule.

# --- Rule 11: type annotation refers to undefined class -----------------
bad_ann:Nonexistent = None

# --- Rule 1: duplicate identifier in the global scope -------------------
dup:int = 1
dup:int = 2

# --- Rule 4: super-class is a special (forbidden) class -----------------
class C1(int):
    pass

# --- Rule 4: super-class not previously defined -------------------------
class C2(NotYet):
    pass

# --- Rule 5: attribute overrides inherited attribute --------------------
class Base(object):
    x:int = 0

    def m(self:"Base") -> int:
        return 0

class BadAttr(Base):
    x:int = 1                    # cannot re-define inherited field

# --- Rule 5: method overrides inherited attribute -----------------------
class BadMethodOverAttr(Base):
    def x(self:"BadMethodOverAttr") -> int:
        return 0

# --- Rule 7: method override with a different signature -----------------
class BadOverride(Base):
    def m(self:"BadOverride", extra:int) -> int:
        return extra

# --- Rule 6: missing first 'self' parameter in a method -----------------
class NoSelf(object):
    def m() -> int:
        return 0

# --- Rule 6: first method param has wrong type --------------------------
class WrongSelf(object):
    def m(self:"Base") -> int:
        return 0

# --- Rule 9: function/method returning int lacks return on all paths ---
def missing_return(flag:bool) -> int:
    if flag:
        return 1
    # else branch falls through without returning

# --- Rule 3: bad nonlocal / global declarations ------------------------
outer_var:int = 0

def bad_scope() -> int:
    def inner() -> int:
        nonlocal not_in_enclosing       # Rule 3: no such enclosing local
        return 0
    global missing_global               # Rule 3: not a global variable
    return 0

# --- Rule 1: duplicate parameter names in a function -------------------
def dup_param(p:int, p:int) -> int:
    return p
