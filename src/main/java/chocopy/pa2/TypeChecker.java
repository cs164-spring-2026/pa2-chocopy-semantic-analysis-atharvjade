package chocopy.pa2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.ClassValueType;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.ListValueType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.AssignStmt;
import chocopy.common.astnodes.BinaryExpr;
import chocopy.common.astnodes.BooleanLiteral;
import chocopy.common.astnodes.CallExpr;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.ClassType;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.Expr;
import chocopy.common.astnodes.ExprStmt;
import chocopy.common.astnodes.ForStmt;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.GlobalDecl;
import chocopy.common.astnodes.IfExpr;
import chocopy.common.astnodes.IfStmt;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.IndexExpr;
import chocopy.common.astnodes.IntegerLiteral;
import chocopy.common.astnodes.ListExpr;
import chocopy.common.astnodes.MemberExpr;
import chocopy.common.astnodes.MethodCallExpr;
import chocopy.common.astnodes.Node;
import chocopy.common.astnodes.NoneLiteral;
import chocopy.common.astnodes.NonLocalDecl;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.ReturnStmt;
import chocopy.common.astnodes.ListType;
import chocopy.common.astnodes.StringLiteral;
import chocopy.common.astnodes.Stmt;
import chocopy.common.astnodes.TypeAnnotation;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.UnaryExpr;
import chocopy.common.astnodes.VarDef;
import chocopy.common.astnodes.WhileStmt;

import static chocopy.common.analysis.types.Type.BOOL_TYPE;
import static chocopy.common.analysis.types.Type.INT_TYPE;
import static chocopy.common.analysis.types.Type.NONE_TYPE;
import static chocopy.common.analysis.types.Type.OBJECT_TYPE;
import static chocopy.common.analysis.types.Type.STR_TYPE;

/** Analyzer that performs ChocoPy type checks on all nodes.  Applied after
 *  collecting declarations. */
public class TypeChecker extends AbstractNodeAnalyzer<Type> {

    /** Unchanging reference to the global symbol table. */
    private final SymbolTable<Type> globals;
    /** The current symbol table (changes depending on the function
     *  being analyzed). */
    private SymbolTable<Type> sym;
    /** Collector for errors. */
    private final Errors errors;

    /** Names declared {@code global} in the current function. */
    private Set<String> currentGlobals = new HashSet<>();
    /** Names declared {@code nonlocal} in the current function. */
    private Set<String> currentNonlocals = new HashSet<>();
    /** {@code global} names already declared in this function (duplicate check). */
    private Set<String> globalDeclSeen = new HashSet<>();
    /** Top-level {@code VarDef} names (valid targets of {@code global}). */
    private Set<String> moduleVarNames = new HashSet<>();
    /** User-defined class names plus built-in class names that cannot be shadowed. */
    private Set<String> shadowForbiddenClassNames = new HashSet<>();
    /** Stack of nested functions for {@code nonlocal} resolution. */
    private final List<FuncDef> functionStack = new ArrayList<>();
    /** Expected return type of the innermost function, or null at top level. */
    private ValueType currentFunctionReturn;
    /** True while type-checking a function or method body. */
    private boolean inFunction;

    /** Per-class constructor signatures (includes {@code self}). */
    private final Map<String, FuncType> classConstructorTypes = new HashMap<>();
    /** Inherited instance field types per class name. */
    private final Map<String, Map<String, ValueType>> classFields = new HashMap<>();
    /** Inherited methods per class name. */
    private final Map<String, Map<String, FuncType>> classMethods = new HashMap<>();
    /** Immediate superclass name per class. */
    private final Map<String, String> classSuper = new HashMap<>();


    /** Creates a type checker using GLOBALSYMBOLS for the initial global
     *  symbol table and ERRORS0 to receive semantic errors. */
    public TypeChecker(SymbolTable<Type> globalSymbols, Errors errors0) {
        globals = globalSymbols;
        sym = globalSymbols;
        errors = errors0;
    }

    private void err(Node node, String message, Object... args) {
        errors.semError(node, message, args);
    }

    private boolean isSubclassOf(String subClassName, String superClassName) {
        if (subClassName.equals(superClassName)) {
            return true;
        }
        String cn = subClassName;
        while (cn != null) {
            if (cn.equals(superClassName)) {
                return true;
            }
            cn = classSuper.get(cn);
        }
        return false;
    }

    private boolean isAssignable(Type target, Type value) {
        if (target == null || value == null) {
            return false;
        }
        if (target.equals(value)) {
            return true;
        }
        if (target instanceof ClassValueType && value instanceof ClassValueType) {
            String tn = ((ClassValueType) target).className();
            String vn = ((ClassValueType) value).className();
            if (isSubclassOf(vn, tn)) {
                return true;
            }
        }
        if (target.equals(OBJECT_TYPE)) {
            return value.isValueType();
        }
        if (value.equals(NONE_TYPE)) {
            return !target.isSpecialType();
        }
        if (value.equals(Type.EMPTY_TYPE)) {
            return target.isListType();
        }
        if (target.isListType() && value.isListType()) {
            Type targetElem = ((ListValueType) target).elementType();
            Type valueElem = ((ListValueType) value).elementType();
            if (targetElem != null && targetElem.equals(valueElem)) {
                return true;
            }
            if (targetElem != null && targetElem.equals(OBJECT_TYPE)
                && valueElem != null && valueElem.equals(NONE_TYPE)) {
                return true;
            }
            return false;
        }
        return false;
    }

    /** True if CHILD overrides PARENT for a method in a subclass (self type may differ). */
    private static boolean methodOverridesCompatible(FuncType parent, FuncType child,
            String parentClassName, String childClassName) {
        if (parent == null || child == null) {
            return false;
        }
        if (!parent.returnType.equals(child.returnType)) {
            return false;
        }
        if (parent.parameters.size() != child.parameters.size()) {
            return false;
        }
        if (parent.parameters.isEmpty()) {
            return true;
        }
        ValueType ps = parent.parameters.get(0);
        ValueType cs = child.parameters.get(0);
        if (!(ps instanceof ClassValueType) || !(cs instanceof ClassValueType)) {
            return false;
        }
        if (!((ClassValueType) ps).className().equals(parentClassName)
            || !((ClassValueType) cs).className().equals(childClassName)) {
            return false;
        }
        for (int i = 1; i < parent.parameters.size(); i++) {
            if (!parent.parameters.get(i).equals(child.parameters.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> localVariableNames(FuncDef fd) {
        Set<String> s = new HashSet<>();
        for (TypedVar p : fd.params) {
            s.add(p.identifier.name);
        }
        for (Declaration d : fd.declarations) {
            if (d instanceof VarDef) {
                s.add(((VarDef) d).var.identifier.name);
            }
        }
        return s;
    }

    private void checkTypeAnnotation(TypeAnnotation ann) {
        if (ann instanceof ClassType) {
            ClassType ct = (ClassType) ann;
            Type t = globals.get(ct.className);
            if (!(t instanceof ClassValueType)) {
                String msg = "Invalid type annotation; there is no class named: "
                    + ct.className;
                ct.setErrorMsg(msg);
                err(ct, "Invalid type annotation; there is no class named: %s",
                    ct.className);
            }
        } else {
            ListType lt = (ListType) ann;
            checkTypeAnnotation(lt.elementType);
        }
    }

    private void checkShadowClassName(Identifier id) {
        if (shadowForbiddenClassNames.contains(id.name)) {
            String msg = "Cannot shadow class name: " + id.name;
            id.setErrorMsg(msg);
            err(id, "Cannot shadow class name: %s", id.name);
        }
    }

    private void validateClassSuper(ClassDef cd, Set<String> classesDeclaredSoFar) {
        String sup = cd.superClass.name;
        Type t = globals.get(sup);
        if (t == null) {
            cd.superClass.setErrorMsg("Super-class not defined: " + sup);
            err(cd.superClass, "Super-class not defined: %s", sup);
            return;
        }
        if (!(t instanceof ClassValueType)) {
            err(cd.superClass, "Super-class must be a class: %s", sup);
            return;
        }
        ClassValueType cvt = (ClassValueType) t;
        if (!cvt.className().equals(sup)) {
            err(cd.superClass, "Super-class must be a class: %s", sup);
            return;
        }
        if (!classesDeclaredSoFar.contains(sup)) {
            cd.superClass.setErrorMsg("Super-class not defined: " + sup);
            err(cd.superClass, "Super-class not defined: %s", sup);
            return;
        }
        if ("int".equals(sup) || "bool".equals(sup) || "str".equals(sup)) {
            err(cd.superClass, "Cannot extend special class: %s", sup);
        }
    }

    private void checkMethodSelfParam(FuncDef fd, String className) {
        ClassValueType selfType = new ClassValueType(className);
        if (fd.params.isEmpty()) {
            fd.name.setErrorMsg(
                "First parameter of the following method must be of the "
                + "enclosing class: " + fd.name.name);
            err(fd.name,
                "First parameter of the following method must be of the "
                + "enclosing class: %s",
                fd.name.name);
            return;
        }
        TypedVar first = fd.params.get(0);
        if (!"self".equals(first.identifier.name)) {
            fd.name.setErrorMsg(
                "First parameter of the following method must be of the "
                + "enclosing class: " + fd.name.name);
            err(fd.name,
                "First parameter of the following method must be of the "
                + "enclosing class: %s",
                fd.name.name);
            return;
        }
        ValueType got = ValueType.annotationToValueType(first.type);
        if (!selfType.equals(got)) {
            fd.name.setErrorMsg(
                "First parameter of the following method must be of the "
                + "enclosing class: " + fd.name.name);
            err(fd.name,
                "First parameter of the following method must be of the "
                + "enclosing class: %s",
                fd.name.name);
        }
    }

    private Type joinTypes(Type t1, Type t2) {
        if (t1.equals(t2)) {
            return t1;
        }
        if (t1.equals(Type.EMPTY_TYPE) && t2.isListType()) {
            return t2;
        }
        if (t2.equals(Type.EMPTY_TYPE) && t1.isListType()) {
            return t1;
        }
        if (t1.isListType() && t2.isListType()) {
            ValueType e1 = ((ListValueType) t1).elementType();
            ValueType e2 = ((ListValueType) t2).elementType();
            if (e1.equals(e2)) {
                return new ListValueType(e1);
            } else {
                return new ListValueType(OBJECT_TYPE);
            }
        }
        return OBJECT_TYPE;
    }

    private void registerClassMetadata(ClassDef cd) {
        String cn = cd.name.name;
        String sup = cd.superClass.name;
        classSuper.put(cn, sup);

        Map<String, ValueType> inheritedFields =
            classFields.containsKey(sup)
            ? new HashMap<>(classFields.get(sup)) : new HashMap<>();
        Map<String, FuncType> inheritedMethods =
            classMethods.containsKey(sup)
            ? new HashMap<>(classMethods.get(sup)) : new HashMap<>();

        Map<String, ValueType> fields = new HashMap<>(inheritedFields);
        Map<String, FuncType> methods = new HashMap<>(inheritedMethods);

        Set<String> declaredInClass = new HashSet<>();

        for (Declaration d : cd.declarations) {
            if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                String fn = vd.var.identifier.name;
                if (declaredInClass.contains(fn)) {
                    vd.var.identifier.setErrorMsg(
                        "Duplicate declaration of identifier in same scope: "
                        + fn);
                    err(vd.var.identifier,
                        "Duplicate declaration of identifier in same scope: %s",
                        fn);
                    continue;
                }
                if (inheritedFields.containsKey(fn)
                    || inheritedMethods.containsKey(fn)) {
                    vd.var.identifier.setErrorMsg(
                        "Cannot re-define attribute: " + fn);
                    err(vd.var.identifier, "Cannot re-define attribute: %s",
                        fn);
                    continue;
                }
                declaredInClass.add(fn);
                fields.put(fn, ValueType.annotationToValueType(vd.var.type));
            } else if (d instanceof FuncDef) {
                FuncDef fd = (FuncDef) d;
                String mn = fd.name.name;
                if (declaredInClass.contains(mn)) {
                    fd.name.setErrorMsg(
                        "Duplicate declaration of identifier in same scope: "
                        + mn);
                    err(fd.name,
                        "Duplicate declaration of identifier in same scope: %s",
                        mn);
                    continue;
                }
                if (inheritedFields.containsKey(mn)) {
                    fd.name.setErrorMsg("Cannot re-define attribute: " + mn);
                    err(fd.name, "Cannot re-define attribute: %s", mn);
                    continue;
                }
                FuncType childFt = DeclarationAnalyzer.funcTypeFromFuncDef(fd);
                if ("__init__".equals(mn)) {
                    FuncType parentCtor = classConstructorTypes.get(sup);
                    if (parentCtor != null
                        && !methodOverridesCompatible(parentCtor, childFt,
                                                      sup, cn)) {
                        fd.name.setErrorMsg(
                            "Method overridden with different type signature: "
                            + "__init__");
                        err(fd.name,
                            "Method overridden with different type signature: "
                            + "__init__");
                    }
                } else {
                    if (inheritedMethods.containsKey(mn)) {
                        FuncType parentFt = inheritedMethods.get(mn);
                        if (!methodOverridesCompatible(parentFt, childFt,
                                                       sup, cn)) {
                            fd.name.setErrorMsg(
                                "Method overridden with different type "
                                + "signature: " + mn);
                            err(fd.name,
                                "Method overridden with different type "
                                + "signature: %s",
                                mn);
                        }
                    }
                    methods.put(mn, childFt);
                }
                declaredInClass.add(mn);
            }
        }
        classFields.put(cn, fields);
        classMethods.put(cn, methods);

        FuncDef init = findInit(cd);
        java.util.ArrayList<ValueType> selfOnly = new java.util.ArrayList<>();
        selfOnly.add(new ClassValueType(cn));
        FuncType ctorFt = init == null
            ? new FuncType(selfOnly, NONE_TYPE)
            : DeclarationAnalyzer.funcTypeFromFuncDef(init);
        classConstructorTypes.put(cn, ctorFt);
    }

    private static FuncDef findInit(ClassDef cd) {
        for (Declaration d : cd.declarations) {
            if (d instanceof FuncDef) {
                FuncDef fd = (FuncDef) d;
                if ("__init__".equals(fd.name.name)) {
                    return fd;
                }
            }
        }
        return null;
    }

    private ValueType lookupField(String className, String fieldName) {
        String cn = className;
        while (cn != null) {
            Map<String, ValueType> f = classFields.get(cn);
            if (f != null && f.containsKey(fieldName)) {
                return f.get(fieldName);
            }
            cn = classSuper.get(cn);
        }
        return null;
    }

    private boolean needsReturnOnAllPaths(ValueType ret) {
        return ret.isSpecialType();
    }

    private boolean blockReturnsFully(List<Stmt> stmts) {
        for (Stmt s : stmts) {
            if (stmtReturnsAllPaths(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean stmtReturnsAllPaths(Stmt s) {
        if (s instanceof ReturnStmt) {
            return true;
        }
        if (s instanceof IfStmt) {
            IfStmt ifs = (IfStmt) s;
            return blockReturnsFully(ifs.thenBody)
                && blockReturnsFully(ifs.elseBody);
        }
        return false;
    }

    private void processFuncDef(FuncDef fd, String enclosingClassName) {
        SymbolTable<Type> savedSym = sym;
        Set<String> savedGlobals = currentGlobals;
        Set<String> savedNonlocals = currentNonlocals;
        Set<String> savedGlobalDeclSeen = globalDeclSeen;
        ValueType savedReturn = currentFunctionReturn;
        boolean savedInFunc = inFunction;

        sym = new SymbolTable<>(sym);
        currentGlobals = new HashSet<>();
        currentNonlocals = new HashSet<>();
        globalDeclSeen = new HashSet<>();
        currentFunctionReturn = ValueType.annotationToValueType(fd.returnType);
        inFunction = true;
        functionStack.add(fd);

        try {
            checkTypeAnnotation(fd.returnType);
            for (TypedVar p : fd.params) {
                checkShadowClassName(p.identifier);
                checkTypeAnnotation(p.type);
                sym.put(p.identifier.name,
                        ValueType.annotationToValueType(p.type));
            }
            if (enclosingClassName != null) {
                checkMethodSelfParam(fd, enclosingClassName);
            }

            for (Declaration d : fd.declarations) {
                if (d instanceof VarDef) {
                    VarDef vd = (VarDef) d;
                    checkShadowClassName(vd.var.identifier);
                    checkTypeAnnotation(vd.var.type);
                    if (sym.declares(vd.var.identifier.name)) {
                        vd.var.identifier.setErrorMsg(
                            "Duplicate declaration of identifier in same "
                            + "scope: " + vd.var.identifier.name);
                        err(vd.var.identifier,
                            "Duplicate declaration of identifier in same "
                            + "scope: %s",
                            vd.var.identifier.name);
                    } else {
                        sym.put(vd.var.identifier.name,
                                ValueType.annotationToValueType(vd.var.type));
                    }
                } else if (d instanceof FuncDef) {
                    FuncDef inner = (FuncDef) d;
                    checkShadowClassName(inner.name);
                    checkTypeAnnotation(inner.returnType);
                    for (TypedVar tp : inner.params) {
                        checkTypeAnnotation(tp.type);
                    }
                    FuncType ft = DeclarationAnalyzer.funcTypeFromFuncDef(inner);
                    if (sym.declares(inner.name.name)) {
                        inner.name.setErrorMsg(
                            "Duplicate declaration of identifier in same "
                            + "scope: " + inner.name.name);
                        err(inner.name,
                            "Duplicate declaration of identifier in same "
                            + "scope: %s",
                            inner.name.name);
                    } else {
                        sym.put(inner.name.name, ft);
                    }
                }
            }

            for (Declaration d : fd.declarations) {
                if (d instanceof VarDef) {
                    VarDef vd = (VarDef) d;
                    Type initType = vd.value.dispatch(this);
                    ValueType vt = ValueType.annotationToValueType(vd.var.type);
                    if (!isAssignable(vt, initType)) {
                        err(vd, "Expected type `%s`; got type `%s`",
                            vt, initType);
                    }
                } else if (d instanceof GlobalDecl) {
                    dispatchGlobalDecl((GlobalDecl) d);
                } else if (d instanceof NonLocalDecl) {
                    dispatchNonLocalDecl((NonLocalDecl) d);
                }
            }
            for (Declaration d : fd.declarations) {
                if (d instanceof FuncDef) {
                    processFuncDef((FuncDef) d, null);
                }
            }
            for (Stmt st : fd.statements) {
                st.dispatch(this);
            }
            if (needsReturnOnAllPaths(currentFunctionReturn)
                && !blockReturnsFully(fd.statements)) {
                err(fd.name,
                    "All paths in this function/method must have a return "
                    + "statement: %s",
                    fd.name.name);
            }
        } finally {
            functionStack.remove(functionStack.size() - 1);
            sym = savedSym;
            currentGlobals = savedGlobals;
            currentNonlocals = savedNonlocals;
            globalDeclSeen = savedGlobalDeclSeen;
            currentFunctionReturn = savedReturn;
            inFunction = savedInFunc;
        }
    }

    private void dispatchGlobalDecl(GlobalDecl g) {
        Identifier v = g.variable;
        String name = v.name;
        if (sym.declares(name)) {
            v.setErrorMsg(
                "Duplicate declaration of identifier in same scope: " + name);
            err(v, "Duplicate declaration of identifier in same scope: %s",
                name);
            return;
        }
        if (globalDeclSeen.contains(name)) {
            v.setErrorMsg(
                "Duplicate declaration of identifier in same scope: " + name);
            err(v, "Duplicate declaration of identifier in same scope: %s",
                name);
            return;
        }
        globalDeclSeen.add(name);
        if (!moduleVarNames.contains(name)) {
            v.setErrorMsg("Not a global variable: " + name);
            err(v, "Not a global variable: %s", name);
            return;
        }
        currentGlobals.add(name);
    }

    private void dispatchNonLocalDecl(NonLocalDecl n) {
        Identifier v = n.variable;
        String name = v.name;
        if (functionStack.size() < 2) {
            v.setErrorMsg("Not a nonlocal variable: " + name);
            err(v, "Not a nonlocal variable: %s", name);
            return;
        }
        boolean found = false;
        for (int i = functionStack.size() - 2; i >= 0; i--) {
            FuncDef enc = functionStack.get(i);
            if (localVariableNames(enc).contains(name)) {
                found = true;
                break;
            }
        }
        if (!found) {
            v.setErrorMsg("Not a nonlocal variable: " + name);
            err(v, "Not a nonlocal variable: %s", name);
        } else {
            currentNonlocals.add(name);
        }
    }

    @Override
    public Type analyze(Program program) {
        classConstructorTypes.clear();
        classFields.clear();
        classMethods.clear();
        classSuper.clear();

        moduleVarNames.clear();
        shadowForbiddenClassNames.clear();
        shadowForbiddenClassNames.add("object");
        shadowForbiddenClassNames.add("int");
        shadowForbiddenClassNames.add("str");
        shadowForbiddenClassNames.add("bool");
        for (Declaration decl : program.declarations) {
            if (decl instanceof ClassDef) {
                shadowForbiddenClassNames.add(((ClassDef) decl).name.name);
            }
            if (decl instanceof VarDef) {
                moduleVarNames.add(((VarDef) decl).var.identifier.name);
            }
        }

        java.util.ArrayList<ValueType> objSelf = new java.util.ArrayList<>();
        objSelf.add(OBJECT_TYPE);
        classConstructorTypes.put("object", new FuncType(objSelf, NONE_TYPE));
        classFields.put("object", new HashMap<>());
        classMethods.put("object", new HashMap<>());

        Set<String> classesDeclaredSoFar = new HashSet<>();
        classesDeclaredSoFar.add("object");
        classesDeclaredSoFar.add("int");
        classesDeclaredSoFar.add("str");
        classesDeclaredSoFar.add("bool");
        for (Declaration decl : program.declarations) {
            if (decl instanceof ClassDef) {
                ClassDef cd = (ClassDef) decl;
                validateClassSuper(cd, classesDeclaredSoFar);
                classesDeclaredSoFar.add(cd.name.name);
            }
        }
        for (Declaration decl : program.declarations) {
            if (decl instanceof ClassDef) {
                registerClassMetadata((ClassDef) decl);
            }
        }
        for (Declaration decl : program.declarations) {
            decl.dispatch(this);
        }
        for (Stmt stmt : program.statements) {
            stmt.dispatch(this);
        }
        return null;
    }

    @Override
    public Type analyze(ClassDef classDef) {
        for (Declaration d : classDef.declarations) {
            if (d instanceof FuncDef) {
                processFuncDef((FuncDef) d, classDef.name.name);
            } else if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                checkShadowClassName(vd.var.identifier);
                checkTypeAnnotation(vd.var.type);
                Type initType = vd.value.dispatch(this);
                ValueType vt = ValueType.annotationToValueType(vd.var.type);
                if (!isAssignable(vt, initType)) {
                    err(vd, "Expected type `%s`; got type `%s`",
                        vt, initType);
                }
            }
        }
        return null;
    }

    @Override
    public Type analyze(FuncDef funcDef) {
        processFuncDef(funcDef, null);
        return null;
    }

    @Override
    public Type analyze(ExprStmt s) {
        s.expr.dispatch(this);
        return null;
    }

    @Override
    public Type analyze(VarDef varDef) {
        checkShadowClassName(varDef.var.identifier);
        checkTypeAnnotation(varDef.var.type);
        ValueType declaredType =
            ValueType.annotationToValueType(varDef.var.type);
        Type initType = varDef.value.dispatch(this);

        if (!isAssignable(declaredType, initType)) {
            err(varDef, "Expected type `%s`; got type `%s`",
                declaredType, initType);
        }

        return null;
    }

    @Override
    public Type analyze(IntegerLiteral i) {
        return i.setInferredType(Type.INT_TYPE);
    }

    @Override
    public Type analyze(StringLiteral s) {
        return s.setInferredType(STR_TYPE);
    }

    @Override
    public Type analyze(BooleanLiteral b) {
        return b.setInferredType(BOOL_TYPE);
    }

    @Override
    public Type analyze(NoneLiteral n) {
        return n.setInferredType(NONE_TYPE);
    }

    @Override
    public Type analyze(BinaryExpr e) {
        Type t1 = e.left.dispatch(this);
        Type t2 = e.right.dispatch(this);

        switch (e.operator) {
        case "+":
            if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2)) {
                return e.setInferredType(INT_TYPE);
            } else if (STR_TYPE.equals(t1) && STR_TYPE.equals(t2)) {
                return e.setInferredType(STR_TYPE);
            } else if (t1.isListType() && t2.isListType()) {
                return e.setInferredType(joinTypes(t1, t2));
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                if (INT_TYPE.equals(t1) || INT_TYPE.equals(t2)) {
                    return e.setInferredType(INT_TYPE);
                }
                return e.setInferredType(OBJECT_TYPE);
            }
        case "-":
        case "*":
        case "//":
        case "%":
            if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2)) {
                return e.setInferredType(INT_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                return e.setInferredType(INT_TYPE);
            }
        case "<":
        case "<=":
        case ">":
        case ">=":
            if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2)) {
                return e.setInferredType(BOOL_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            }
        case "==":
        case "!=":
            if (t1 != null && t1.equals(t2)
                && (INT_TYPE.equals(t1) || STR_TYPE.equals(t1)
                    || BOOL_TYPE.equals(t1))) {
                return e.setInferredType(BOOL_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            }
        case "and":
        case "or":
            if (BOOL_TYPE.equals(t1) && BOOL_TYPE.equals(t2)) {
                return e.setInferredType(BOOL_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            }
        case "is":
            if (!t1.isSpecialType() && !t2.isSpecialType()) {
                return e.setInferredType(BOOL_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            }
        default:
            return e.setInferredType(OBJECT_TYPE);
        }

    }

    @Override
    public Type analyze(AssignStmt stmt) {
        Type valueType = stmt.value.dispatch(this);
        boolean emittedAssignTypeError = false;
        for (Expr target : stmt.targets) {
            if (target instanceof IndexExpr) {
                IndexExpr ix = (IndexExpr) target;
                Type listType = ix.list.dispatch(this);
                if (STR_TYPE.equals(listType)) {
                    ix.index.dispatch(this);
                    target.setInferredType(STR_TYPE);
                    err(target, "`str` is not a list type");
                    continue;
                }
            }
            Type targetType = target.dispatch(this);
            if (inFunction && target instanceof Identifier) {
                String tname = ((Identifier) target).name;
                if (!currentGlobals.contains(tname) && !sym.declares(tname)
                    && !currentNonlocals.contains(tname)) {
                    err(target,
                        "Cannot assign to variable that is not explicitly "
                        + "declared in this scope: %s",
                        tname);
                }
            }
            if (!isAssignable(targetType, valueType)) {
                if (!emittedAssignTypeError) {
                    err(stmt, "Expected type `%s`; got type `%s`",
                        targetType, valueType);
                    emittedAssignTypeError = true;
                }
            }
        }
        return null;
    }

    @Override
    public Type analyze(IfStmt stmt) {
        Type condType = stmt.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(stmt, "Condition expression cannot be of type `%s`",
                condType);
        }
        for (Stmt s : stmt.thenBody) {
            s.dispatch(this);
        }
        for (Stmt s : stmt.elseBody) {
            s.dispatch(this);
        }
        return null;
    }

    @Override
    public Type analyze(WhileStmt stmt) {
        Type condType = stmt.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(stmt, "Condition expression cannot be of type `%s`",
                condType);
        }
        for (Stmt s : stmt.body) {
            s.dispatch(this);
        }
        return null;
    }

    @Override
    public Type analyze(ForStmt stmt) {
        Type iterType = stmt.iterable.dispatch(this);
        ValueType elemType;
        boolean iterOk = true;
        if (STR_TYPE.equals(iterType)) {
            elemType = STR_TYPE;
        } else if (iterType.isListType()) {
            elemType = ((ListValueType) iterType).elementType();
        } else {
            err(stmt, "Cannot iterate over value of type `%s`", iterType);
            elemType = OBJECT_TYPE;
            iterOk = false;
        }

        String idName = stmt.identifier.name;
        Type idType;
        if (inFunction && currentGlobals.contains(idName)) {
            idType = globals.get(idName);
        } else {
            idType = sym.get(idName);
        }
        if (idType == null || !idType.isValueType()) {
            err(stmt.identifier, "Not a variable: %s", idName);
            idType = OBJECT_TYPE;
        } else if (iterOk && !isAssignable(idType, elemType)) {
            err(stmt, "Expected type `%s`; got type `%s`",
                idType, elemType);
        }
        if (iterOk && isAssignable(idType, elemType)) {
            stmt.identifier.setInferredType((ValueType) idType);
        }
        for (Stmt s : stmt.body) {
            s.dispatch(this);
        }
        return null;
    }

    @Override
    public Type analyze(ReturnStmt stmt) {
        if (!inFunction) {
            err(stmt, "Return statement cannot appear at the top level");
            return null;
        }
        if (stmt.value == null) {
            if (!isAssignable(currentFunctionReturn, NONE_TYPE)) {
                err(stmt, "Expected type `%s`; got `None`",
                    currentFunctionReturn);
            }
            return null;
        }
        Type t = stmt.value.dispatch(this);
        if (!isAssignable(currentFunctionReturn, t)) {
            err(stmt, "Expected type `%s`; got type `%s`",
                currentFunctionReturn, t);
        }
        return null;
    }

    @Override
    public Type analyze(CallExpr node) {
        String fname = node.function.name;
        Type calleeT = sym.get(fname);

        if (calleeT != null && calleeT.isFuncType()) {
            FuncType ft = (FuncType) calleeT;
            node.function.setInferredType(ft);
            if (ft.parameters.size() != node.args.size()) {
                for (Expr arg : node.args) {
                    arg.dispatch(this);
                }
                err(node, "Expected %d arguments; got %d",
                    ft.parameters.size(), node.args.size());
            } else {
                for (int i = 0; i < node.args.size(); i++) {
                    Type argT = node.args.get(i).dispatch(this);
                    if (!isAssignable(ft.parameters.get(i), argT)) {
                        err(node, "Expected type `%s`; got type `%s` in "
                            + "parameter %d",
                            ft.parameters.get(i), argT, i);
                    }
                }
            }
            return node.setInferredType(ft.returnType);
        }

        if (calleeT instanceof ClassValueType) {
            ClassValueType cv = (ClassValueType) calleeT;
            FuncType ctor = classConstructorTypes.get(cv.className());
            if (ctor == null) {
                for (Expr arg : node.args) {
                    arg.dispatch(this);
                }
                err(node, "Not a function or class: %s", fname);
                return node.setInferredType(OBJECT_TYPE);
            }
            int userParams = ctor.parameters.size() - 1;
            if (userParams != node.args.size()) {
                for (Expr arg : node.args) {
                    arg.dispatch(this);
                }
                err(node, "Expected %d arguments; got %d",
                    userParams, node.args.size());
            } else {
                for (int i = 0; i < node.args.size(); i++) {
                    Type argT = node.args.get(i).dispatch(this);
                    if (!isAssignable(ctor.parameters.get(i + 1), argT)) {
                        err(node, "Expected type `%s`; got type `%s` in "
                            + "parameter %d",
                            ctor.parameters.get(i + 1), argT, i + 1);
                    }
                }
            }
            return node.setInferredType(cv);
        }

        for (Expr arg : node.args) {
            arg.dispatch(this);
        }
        err(node, "Not a function or class: %s", fname);
        return node.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(MethodCallExpr node) {
        MemberExpr m = node.method;
        Type recvT = m.object.dispatch(this);
        if (!(recvT instanceof ClassValueType)) {
            err(m.object, "Invalid receiver type `%s`", recvT);
            return node.setInferredType(OBJECT_TYPE);
        }
        String cname = ((ClassValueType) recvT).className();
        Map<String, FuncType> ms = classMethods.get(cname);
        String mname = m.member.name;
        FuncType ft = ms != null ? ms.get(mname) : null;
        if (ft == null) {
            for (Expr arg : node.args) {
                arg.dispatch(this);
            }
            node.setErrorMsg(
                "There is no method named `" + mname + "` in class `" + cname
                + "`");
            err(node, "There is no method named `%s` in class `%s`",
                mname, cname);
            return node.setInferredType(OBJECT_TYPE);
        }
        m.setInferredType(ft);
        int need = ft.parameters.size() - 1;
        if (need != node.args.size()) {
            for (Expr arg : node.args) {
                arg.dispatch(this);
            }
            err(node, "Expected %d arguments; got %d", need, node.args.size());
        } else {
            for (int i = 0; i < node.args.size(); i++) {
                Type argT = node.args.get(i).dispatch(this);
                if (!isAssignable(ft.parameters.get(i + 1), argT)) {
                    err(node, "Expected type `%s`; got type `%s` in parameter %d",
                        ft.parameters.get(i + 1), argT, i + 1);
                }
            }
        }
        return node.setInferredType(ft.returnType);
    }

    @Override
    public Type analyze(MemberExpr e) {
        Type ot = e.object.dispatch(this);
        if (!(ot instanceof ClassValueType)) {
            err(e, "Cannot access member of non-class type `%s`", ot);
            return e.setInferredType(OBJECT_TYPE);
        }
        String cname = ((ClassValueType) ot).className();
        ValueType ft = lookupField(cname, e.member.name);
        if (ft == null) {
            e.setErrorMsg(
                "There is no attribute named `" + e.member.name
                + "` in class `" + cname + "`");
            err(e, "There is no attribute named `%s` in class `%s`",
                e.member.name, cname);
            return e.setInferredType(OBJECT_TYPE);
        }
        return e.setInferredType(ft);
    }

    @Override
    public Type analyze(UnaryExpr e) {
        Type operandType = e.operand.dispatch(this);
        switch (e.operator) {
        case "-":
            if (INT_TYPE.equals(operandType)) {
                return e.setInferredType(INT_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on type `%s`",
                    e.operator, operandType);
                return e.setInferredType(INT_TYPE);
            }
        case "not":
            if (BOOL_TYPE.equals(operandType)) {
                return e.setInferredType(BOOL_TYPE);
            } else {
                err(e, "Cannot apply operator `%s` on type `%s`",
                    e.operator, operandType);
                return e.setInferredType(BOOL_TYPE);
            }
        default:
            return e.setInferredType(OBJECT_TYPE);
        }
    }

    @Override
    public Type analyze(IfExpr e) {
        Type condType = e.condition.dispatch(this);
        Type thenType = e.thenExpr.dispatch(this);
        Type elseType = e.elseExpr.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(e, "Condition expression cannot be of type `%s`",
                condType);
        }
        return e.setInferredType(joinTypes(thenType, elseType));
    }

    @Override
    public Type analyze(ListExpr e) {
        if (e.elements.isEmpty()) {
            return e.setInferredType(Type.EMPTY_TYPE);
        }

        Type elemType = e.elements.get(0).dispatch(this);
        for (int i = 1; i < e.elements.size(); i += 1) {
            Type t = e.elements.get(i).dispatch(this);
            elemType = joinTypes(elemType, t);
        }
        return e.setInferredType(new ListValueType(elemType));
    }

    @Override
    public Type analyze(IndexExpr e) {
        Type listType = e.list.dispatch(this);
        Type indexType = e.index.dispatch(this);

        if (!INT_TYPE.equals(indexType)) {
            err(e, "Index is of non-integer type `%s`", indexType);
            if (listType.isListType()) {
                return e.setInferredType(((ListValueType) listType).elementType());
            } else if (STR_TYPE.equals(listType)) {
                return e.setInferredType(STR_TYPE);
            } else {
                return e.setInferredType(OBJECT_TYPE);
            }
        }

        if (listType.isListType()) {
            return e.setInferredType(((ListValueType) listType).elementType());
        } else if (STR_TYPE.equals(listType)) {
            return e.setInferredType(STR_TYPE);
        } else {
            err(e, "Cannot index into type `%s`", listType);
            return e.setInferredType(OBJECT_TYPE);
        }
    }

    @Override
    public Type analyze(Identifier id) {
        String varName = id.name;
        Type varType;
        if (inFunction && currentGlobals.contains(varName)) {
            varType = globals.get(varName);
        } else {
            varType = sym.get(varName);
        }

        if (varType != null && varType.isFuncType()) {
            return id.setInferredType(varType);
        }
        if (varType != null && varType.isValueType()) {
            return id.setInferredType((ValueType) varType);
        }

        err(id, "Not a variable: %s", varName);
        return id.setInferredType(OBJECT_TYPE);
    }
}
