package chocopy.pa2;

import java.util.ArrayList;
import java.util.List;

import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;

import static chocopy.common.analysis.types.Type.INT_TYPE;
import static chocopy.common.analysis.types.Type.NONE_TYPE;
import static chocopy.common.analysis.types.Type.OBJECT_TYPE;
import static chocopy.common.analysis.types.Type.STR_TYPE;

/** Built-in global symbols (print, len, etc.). */
public final class BuiltinTypes {

    private BuiltinTypes() {
    }

    /** Returns a new symbol table pre-populated with built-in functions. */
    public static SymbolTable<Type> newGlobalsWithBuiltins() {
        SymbolTable<Type> g = new SymbolTable<>();
        List<ValueType> oneObject = new ArrayList<>();
        oneObject.add(OBJECT_TYPE);
        g.put("print", new FuncType(oneObject, NONE_TYPE));
        g.put("len", new FuncType(oneObject, INT_TYPE));
        g.put("input", new FuncType(new ArrayList<>(), STR_TYPE));
        return g;
    }
}
