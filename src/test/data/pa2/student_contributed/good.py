# --- Global variable definitions (all VarDefs must precede statements) ---
x:int = 0
y:bool = True
s:str = "hi"
nums:[int] = None
matrix:[[int]] = None
none_val:object = None
a:int = 0
b:int = 0
d:object = None
p:object = None
ns:[int] = None
an:object = None
o:object = None

# --- Class hierarchy with inheritance, override, and __init__ ---
class Animal(object):
    name:str = ""
    legs:int = 4

    def __init__(self:"Animal"):
        self.name = "creature"

    def speak(self:"Animal") -> str:
        return self.name

    def info(self:"Animal") -> str:
        return self.name

class Dog(Animal):
    tricks:[int] = None

    def __init__(self:"Dog"):
        self.name = "dog"
        self.legs = 4
        self.tricks = [1, 2, 3]

    # Valid override: same signature except self
    def speak(self:"Dog") -> str:
        return "woof"

class Puppy(Dog):
    age:int = 0

    def __init__(self:"Puppy"):
        self.name = "puppy"
        self.age = 1

# --- Top-level functions ---
def add(m:int, n:int) -> int:
    return m + n

def greet(who:str):
    print(who)

# Nested function exercising nonlocal and global
def outer(n:int) -> int:
    count:int = 0
    def inner(m:int) -> int:
        nonlocal count
        global x
        count = count + m
        x = x + 1
        return count
    return inner(n)

# Return on all paths through nested if/else
def classify(n:int) -> str:
    if n > 0:
        return "pos"
    else:
        if n < 0:
            return "neg"
        else:
            return "zero"

# --- Top-level statements exercising expressions, stmts, types ---

# Arithmetic binary operators
x = 1 + 2
x = x - 3
x = x * 4
x = x // 5
x = x % 6

# String concatenation and list concatenation
s = "foo" + "bar"
nums = [1, 2] + [3, 4]

# Comparison operators
y = 1 < 2
y = 1 <= 2
y = 2 > 1
y = 2 >= 1
y = 1 == 1
y = 1 != 2
y = "a" == "a"
y = True == False

# Logical / not
y = True and False
y = True or False
y = not y

# 'is' on reference types
y = None is None
y = nums is None

# Unary minus and conditional expression
x = -1
x = -(x + 1)
x = 0 if y else 1

# Lists: literal, index read, index assign, nested index
nums = [10, 20, 30]
x = nums[0]
nums[1] = 99
matrix = [[1, 2], [3, 4]]
x = matrix[0][1]

# Chained assignment
a = b = 5

# String indexing yields str
s = "abc"
s = s[0]

# if / elif / else
if x > 0:
    x = 1
elif x == 0:
    x = 2
else:
    x = 3

# while, for-over-list, for-over-string
while x > 0:
    x = x - 1

for x in nums:
    x = x + 1

for s in "word":
    print(s)

# Subtype assignment: Puppy -> Dog -> Animal (covariance through `object`)
d = Puppy()
an = d

# object is a supertype of every value type
o = 1
o = "str"
o = True
o = [1, 2, 3]
o = d

# Method calls: overridden and inherited
print(Dog().speak())
print(Animal().info())

# Constructor call, member read/write
d = Dog()

# Function calls and builtins
x = add(1, 2)
x = len(s)
x = len(nums)

# IfExpr as right-hand side (join of types -> object)
o = 1 if y else "str"

# Empty list assigned to any list type (EMPTY_TYPE -> [T])
nums = []

# Expression statements
print(classify(-1))
greet("world")
