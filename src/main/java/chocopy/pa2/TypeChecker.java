package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.ListValueType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.AssignStmt;
import chocopy.common.astnodes.BinaryExpr;
import chocopy.common.astnodes.BooleanLiteral;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.Expr;
import chocopy.common.astnodes.ExprStmt;
import chocopy.common.astnodes.IfExpr;
import chocopy.common.astnodes.IfStmt;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.IndexExpr;
import chocopy.common.astnodes.IntegerLiteral;
import chocopy.common.astnodes.ListExpr;
import chocopy.common.astnodes.Node;
import chocopy.common.astnodes.NoneLiteral;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.StringLiteral;
import chocopy.common.astnodes.Stmt;
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

    /** The current symbol table (changes depending on the function
     *  being analyzed). */
    private SymbolTable<Type> sym;
    /** Collector for errors. */
    private Errors errors;


    /** Creates a type checker using GLOBALSYMBOLS for the initial global
     *  symbol table and ERRORS0 to receive semantic errors. */
    public TypeChecker(SymbolTable<Type> globalSymbols, Errors errors0) {
        sym = globalSymbols;
        errors = errors0;
    }

    /** Inserts an error message in NODE if there isn't one already.
     *  The message is constructed with MESSAGE and ARGS as for
     *  String.format. */
    private void err(Node node, String message, Object... args) {
        errors.semError(node, message, args);
    }

    /** Returns whether VALUE can be assigned to TARGET in this simplified
     *  analyzer stage. */
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

    /** Join two expression value-types conservatively. */
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

    @Override
    public Type analyze(Program program) {
        for (Declaration decl : program.declarations) {
            decl.dispatch(this);
        }
        for (Stmt stmt : program.statements) {
            stmt.dispatch(this);
        }
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
        Type varType = sym.get(varName);

        if (varType != null && varType.isValueType()) {
            return id.setInferredType(varType);
        }

        err(id, "Not a variable: %s", varName);
        return id.setInferredType(ValueType.OBJECT_TYPE);
    }
}
