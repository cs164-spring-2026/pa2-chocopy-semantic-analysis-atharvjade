package chocopy.pa2;

import java.util.HashSet;
import java.util.Set;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.ClassValueType;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.VarDef;

/**
 * Analyzes declarations to create a top-level symbol table.
 */
public class DeclarationAnalyzer extends AbstractNodeAnalyzer<Type> {

    /** Current symbol table.  Changes with new declarative region. */
    private SymbolTable<Type> sym;
    /** Global symbol table. */
    private final SymbolTable<Type> globals;
    /** Receiver for semantic error messages. */
    private final Errors errors;

    /** A new declaration analyzer sending errors to ERRORS0. */
    public DeclarationAnalyzer(Errors errors0) {
        this(errors0, new SymbolTable<>());
    }

    /** A new declaration analyzer using INITIAL as the global symbol table. */
    public DeclarationAnalyzer(Errors errors0, SymbolTable<Type> initial) {
        errors = errors0;
        sym = initial;
        globals = sym;
    }


    public SymbolTable<Type> getGlobals() {
        return globals;
    }

    @Override
    public Type analyze(Program program) {
        for (Declaration decl : program.declarations) {
            Identifier id = decl.getIdentifier();
            String name = id.name;

            Type type = decl.dispatch(this);

            if (type == null) {
                continue;
            }

            if (sym.declares(name)) {
                errors.semError(id,
                                "Duplicate declaration of identifier in same "
                                + "scope: %s",
                                name);
            } else {
                sym.put(name, type);
            }
        }

        return null;
    }

    @Override
    public Type analyze(VarDef varDef) {
        return ValueType.annotationToValueType(varDef.var.type);
    }

    /** Builds a {@link FuncType} from a function definition (signature only). */
    static FuncType funcTypeFromFuncDef(FuncDef funcDef) {
        java.util.ArrayList<ValueType> params = new java.util.ArrayList<>();
        for (TypedVar p : funcDef.params) {
            params.add(ValueType.annotationToValueType(p.type));
        }
        ValueType ret = ValueType.annotationToValueType(funcDef.returnType);
        return new FuncType(params, ret);
    }

    @Override
    public Type analyze(FuncDef funcDef) {
        Set<String> seen = new HashSet<>();
        for (TypedVar p : funcDef.params) {
            String pname = p.identifier.name;
            if (seen.contains(pname)) {
                errors.semError(p.identifier,
                                "Duplicate declaration of identifier in same "
                                + "scope: %s",
                                pname);
            } else {
                seen.add(pname);
            }
        }
        return funcTypeFromFuncDef(funcDef);
    }

    @Override
    public Type analyze(ClassDef classDef) {
        return new ClassValueType(classDef.name.name);
    }


}
