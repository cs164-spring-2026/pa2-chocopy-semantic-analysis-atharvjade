package chocopy.pa2;

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
import chocopy.common.astnodes.StringLiteral;
import chocopy.common.astnodes.Stmt;
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

    private boolean isAssignable(Type target, Type value) {
        if (target == null || value == null) {
            return false;
        }
        if (target.equals(value)) {
            return true;
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
            return targetElem != null && targetElem.equals(valueElem);
        }
        return false;
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

        Map<String, ValueType> fields = new HashMap<>();
        if (classFields.containsKey(sup)) {
            fields.putAll(new HashMap<>(classFields.get(sup)));
        }
        for (Declaration d : cd.declarations) {
            if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                fields.put(vd.var.identifier.name,
                           ValueType.annotationToValueType(vd.var.type));
            }
        }
        classFields.put(cn, fields);

        Map<String, FuncType> methods = new HashMap<>();
        if (classMethods.containsKey(sup)) {
            methods.putAll(new HashMap<>(classMethods.get(sup)));
        }
        for (Declaration d : cd.declarations) {
            if (d instanceof FuncDef) {
                FuncDef fd = (FuncDef) d;
                if (!"__init__".equals(fd.name.name)) {
                    methods.put(fd.name.name,
                                DeclarationAnalyzer.funcTypeFromFuncDef(fd));
                }
            }
        }
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
        return !NONE_TYPE.equals(ret);
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

    private void processFuncDef(FuncDef fd) {
        SymbolTable<Type> savedSym = sym;
        Set<String> savedGlobals = currentGlobals;
        ValueType savedReturn = currentFunctionReturn;
        boolean savedInFunc = inFunction;

        sym = new SymbolTable<>(sym);
        currentGlobals = new HashSet<>();
        currentFunctionReturn = ValueType.annotationToValueType(fd.returnType);
        inFunction = true;

        try {
            for (TypedVar p : fd.params) {
                sym.put(p.identifier.name,
                        ValueType.annotationToValueType(p.type));
            }
            for (Declaration d : fd.declarations) {
                dispatchDeclInFunction(d);
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
            sym = savedSym;
            currentGlobals = savedGlobals;
            currentFunctionReturn = savedReturn;
            inFunction = savedInFunc;
        }
    }

    private void dispatchDeclInFunction(Declaration d) {
        if (d instanceof VarDef) {
            analyze((VarDef) d);
        } else if (d instanceof FuncDef) {
            FuncDef inner = (FuncDef) d;
            FuncType ft = DeclarationAnalyzer.funcTypeFromFuncDef(inner);
            if (sym.declares(inner.name.name)) {
                err(inner.name,
                    "Duplicate declaration of identifier in same scope: %s",
                    inner.name.name);
            } else {
                sym.put(inner.name.name, ft);
            }
        } else if (d instanceof GlobalDecl) {
            currentGlobals.add(((GlobalDecl) d).variable.name);
        } else if (d instanceof NonLocalDecl) {
            /* Reserved for full nonlocal checking. */
        }
    }

    @Override
    public Type analyze(Program program) {
        classConstructorTypes.clear();
        classFields.clear();
        classMethods.clear();
        classSuper.clear();

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
                processFuncDef((FuncDef) d);
            } else if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                Type initType = vd.value.dispatch(this);
                ValueType vt = ValueType.annotationToValueType(vd.var.type);
                if (!isAssignable(vt, initType)) {
                    err(vd.value, "Expected type `%s`; got type `%s`",
                        vt, initType);
                }
            }
        }
        return null;
    }

    @Override
    public Type analyze(FuncDef funcDef) {
        processFuncDef(funcDef);
        return null;
    }

    @Override
    public Type analyze(ExprStmt s) {
        s.expr.dispatch(this);
        return null;
    }

    @Override
    public Type analyze(VarDef varDef) {
        ValueType declaredType =
            ValueType.annotationToValueType(varDef.var.type);
        Type initType = varDef.value.dispatch(this);

        if (!isAssignable(declaredType, initType)) {
            err(varDef.value, "Expected type `%s`; got type `%s`",
                declaredType, initType);
        }

        if (sym.getParent() != null) {
            String name = varDef.var.identifier.name;
            if (sym.declares(name)) {
                err(varDef.var.identifier,
                    "Duplicate declaration of identifier in same scope: %s",
                    name);
            } else {
                sym.put(name, declaredType);
            }
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
            } else if ((t1.isListType() || Type.EMPTY_TYPE.equals(t1))
                       && (t2.isListType() || Type.EMPTY_TYPE.equals(t2))) {
                return e.setInferredType(joinTypes(t1, t2));
            } else {
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                    e.operator, t1, t2);
                return e.setInferredType(INT_TYPE);
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
            if (t1 != null && t1.equals(t2) && !NONE_TYPE.equals(t1)) {
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
        for (Expr target : stmt.targets) {
            if (target instanceof IndexExpr) {
                Type listType = ((IndexExpr) target).list.dispatch(this);
                if (STR_TYPE.equals(listType)) {
                    err(target, "`str` is not a list type");
                    continue;
                }
            }
            Type targetType = target.dispatch(this);
            if (!isAssignable(targetType, valueType)) {
                err(stmt, "Expected type `%s`; got type `%s`",
                    targetType, valueType);
                break;
            }
        }
        return null;
    }

    @Override
    public Type analyze(IfStmt stmt) {
        Type condType = stmt.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(stmt.condition, "Condition expression cannot be of type `%s`",
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
            err(stmt.condition, "Condition expression cannot be of type `%s`",
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
        if (STR_TYPE.equals(iterType)) {
            elemType = STR_TYPE;
        } else if (iterType.isListType()) {
            elemType = ((ListValueType) iterType).elementType();
        } else {
            err(stmt.iterable, "Cannot iterate over type `%s`", iterType);
            elemType = OBJECT_TYPE;
        }
        SymbolTable<Type> saved = sym;
        sym = new SymbolTable<>(sym);
        String iname = stmt.identifier.name;
        if (sym.declares(iname)) {
            err(stmt.identifier,
                "Duplicate declaration of identifier in same scope: %s",
                iname);
        } else {
            sym.put(iname, elemType);
        }
        for (Stmt s : stmt.body) {
            s.dispatch(this);
        }
        sym = saved;
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
                err(node, "Not a function or class: %s", fname);
                return node.setInferredType(OBJECT_TYPE);
            }
            int userParams = ctor.parameters.size() - 1;
            if (userParams != node.args.size()) {
                err(node, "Expected %d arguments; got %d",
                    userParams, node.args.size());
            } else {
                for (int i = 0; i < node.args.size(); i++) {
                    Type argT = node.args.get(i).dispatch(this);
                    if (!isAssignable(ctor.parameters.get(i + 1), argT)) {
                        err(node, "Expected type `%s`; got type `%s` in "
                            + "parameter %d",
                            ctor.parameters.get(i + 1), argT, i);
                    }
                }
            }
            return node.setInferredType(cv);
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
            err(node, "Class `%s` has no method `%s`", cname, mname);
            return node.setInferredType(OBJECT_TYPE);
        }
        int need = ft.parameters.size() - 1;
        if (need != node.args.size()) {
            err(node, "Expected %d arguments; got %d", need, node.args.size());
        } else {
            for (int i = 0; i < node.args.size(); i++) {
                Type argT = node.args.get(i).dispatch(this);
                if (!isAssignable(ft.parameters.get(i + 1), argT)) {
                    err(node, "Expected type `%s`; got type `%s` in parameter %d",
                        ft.parameters.get(i + 1), argT, i);
                }
            }
        }
        return node.setInferredType(ft.returnType);
    }

    @Override
    public Type analyze(MemberExpr e) {
        Type ot = e.object.dispatch(this);
        if (!(ot instanceof ClassValueType)) {
            err(e.object, "Invalid object type `%s`", ot);
            return e.setInferredType(OBJECT_TYPE);
        }
        String cname = ((ClassValueType) ot).className();
        ValueType ft = lookupField(cname, e.member.name);
        if (ft == null) {
            err(e.member, "Unknown attribute `%s` on class `%s`",
                e.member.name, cname);
            return e.setInferredType(OBJECT_TYPE);
        }
        e.member.setInferredType(ft);
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
            err(e.condition, "Condition expression cannot be of type `%s`",
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
