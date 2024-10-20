/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.dbsp.sqlCompiler.compiler.backend.rust;

import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.errors.InternalCompilerError;
import org.dbsp.sqlCompiler.compiler.errors.UnsupportedException;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.ir.expression.*;
import org.dbsp.sqlCompiler.ir.type.*;
import org.dbsp.sqlCompiler.ir.type.primitive.*;
import org.dbsp.sqlCompiler.compiler.errors.UnimplementedException;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.*;

/** This class encodes (part of) the interface to the SQL
 * runtime library: support functions that implement the SQL semantics. */
@SuppressWarnings({"SpellCheckingInspection"})
public class RustSqlRuntimeLibrary {
    private final LinkedHashMap<String, DBSPOpcode> arithmeticFunctions = new LinkedHashMap<>();
    private final LinkedHashMap<String, DBSPOpcode> dateFunctions = new LinkedHashMap<>();
    private final LinkedHashMap<String, DBSPOpcode> stringFunctions = new LinkedHashMap<>();
    private final LinkedHashMap<String, DBSPOpcode> booleanFunctions = new LinkedHashMap<>();
    private final LinkedHashMap<String, DBSPOpcode> otherFunctions = new LinkedHashMap<>();

    public static final RustSqlRuntimeLibrary INSTANCE = new RustSqlRuntimeLibrary();

    protected RustSqlRuntimeLibrary() {
        this.arithmeticFunctions.put("eq", DBSPOpcode.EQ);
        this.arithmeticFunctions.put("neq", DBSPOpcode.NEQ);
        this.arithmeticFunctions.put("lt", DBSPOpcode.LT);
        this.arithmeticFunctions.put("gt", DBSPOpcode.GT);
        this.arithmeticFunctions.put("lte", DBSPOpcode.LTE);
        this.arithmeticFunctions.put("gte", DBSPOpcode.GTE);
        this.arithmeticFunctions.put("plus", DBSPOpcode.ADD);
        this.arithmeticFunctions.put("minus", DBSPOpcode.SUB);
        this.arithmeticFunctions.put("modulo", DBSPOpcode.MOD);
        this.arithmeticFunctions.put("times", DBSPOpcode.MUL);
        this.arithmeticFunctions.put("div", DBSPOpcode.DIV);
        this.arithmeticFunctions.put("div_null", DBSPOpcode.DIV_NULL);
        this.arithmeticFunctions.put("band", DBSPOpcode.BW_AND);
        this.arithmeticFunctions.put("bor", DBSPOpcode.BW_OR);
        this.arithmeticFunctions.put("bxor", DBSPOpcode.XOR);
        this.arithmeticFunctions.put("min", DBSPOpcode.MIN);
        this.arithmeticFunctions.put("max", DBSPOpcode.MAX);
        this.arithmeticFunctions.put("is_distinct", DBSPOpcode.IS_DISTINCT);
        this.arithmeticFunctions.put("is_same", DBSPOpcode.IS_NOT_DISTINCT);
        this.arithmeticFunctions.put("mul_by_ref", DBSPOpcode.MUL_WEIGHT);
        this.arithmeticFunctions.put("agg_plus", DBSPOpcode.AGG_ADD);
        this.arithmeticFunctions.put("agg_min", DBSPOpcode.AGG_MIN);
        this.arithmeticFunctions.put("agg_max", DBSPOpcode.AGG_MAX);
        this.arithmeticFunctions.put("agg_and", DBSPOpcode.AGG_AND);
        this.arithmeticFunctions.put("agg_or", DBSPOpcode.AGG_OR);
        this.arithmeticFunctions.put("agg_xor", DBSPOpcode.AGG_XOR);
        this.arithmeticFunctions.put("agg_lte", DBSPOpcode.AGG_LTE);
        this.arithmeticFunctions.put("agg_gte", DBSPOpcode.AGG_GTE);
        this.arithmeticFunctions.put("gte_left", DBSPOpcode.GTE_LEFT);

        this.dateFunctions.put("plus", DBSPOpcode.ADD);
        this.dateFunctions.put("minus", DBSPOpcode.SUB);
        this.dateFunctions.put("times", DBSPOpcode.MUL);
        this.dateFunctions.put("eq", DBSPOpcode.EQ);
        this.dateFunctions.put("neq", DBSPOpcode.NEQ);
        this.dateFunctions.put("lt", DBSPOpcode.LT);
        this.dateFunctions.put("gt", DBSPOpcode.GT);
        this.dateFunctions.put("lte", DBSPOpcode.LTE);
        this.dateFunctions.put("gte", DBSPOpcode.GTE);
        this.dateFunctions.put("is_same", DBSPOpcode.IS_NOT_DISTINCT);
        this.dateFunctions.put("is_distinct", DBSPOpcode.IS_DISTINCT);
        this.dateFunctions.put("agg_max", DBSPOpcode.AGG_MAX);
        this.dateFunctions.put("agg_min", DBSPOpcode.AGG_MIN);
        this.dateFunctions.put("agg_lte", DBSPOpcode.AGG_LTE);
        this.dateFunctions.put("agg_gte", DBSPOpcode.AGG_GTE);
        this.dateFunctions.put("gte_left", DBSPOpcode.GTE_LEFT);
        this.dateFunctions.put("min", DBSPOpcode.MIN);
        this.dateFunctions.put("max", DBSPOpcode.MAX);

        this.stringFunctions.put("concat", DBSPOpcode.CONCAT);
        this.stringFunctions.put("eq", DBSPOpcode.EQ);
        this.stringFunctions.put("neq", DBSPOpcode.NEQ);
        this.stringFunctions.put("lt", DBSPOpcode.LT);
        this.stringFunctions.put("gt", DBSPOpcode.GT);
        this.stringFunctions.put("lte", DBSPOpcode.LTE);
        this.stringFunctions.put("gte", DBSPOpcode.GTE);
        this.stringFunctions.put("is_same", DBSPOpcode.IS_NOT_DISTINCT);
        this.stringFunctions.put("is_distinct", DBSPOpcode.IS_DISTINCT);
        this.stringFunctions.put("agg_min", DBSPOpcode.AGG_MIN);
        this.stringFunctions.put("agg_max", DBSPOpcode.AGG_MAX);
        this.stringFunctions.put("agg_lte", DBSPOpcode.AGG_LTE);
        this.stringFunctions.put("agg_gte", DBSPOpcode.AGG_GTE);
        this.stringFunctions.put("gte_left", DBSPOpcode.GTE_LEFT);

        this.booleanFunctions.put("eq", DBSPOpcode.EQ);
        this.booleanFunctions.put("neq", DBSPOpcode.NEQ);
        this.booleanFunctions.put("and", DBSPOpcode.AND);
        this.booleanFunctions.put("or", DBSPOpcode.OR);
        this.booleanFunctions.put("min", DBSPOpcode.MIN);
        this.booleanFunctions.put("max", DBSPOpcode.MAX);
        this.booleanFunctions.put("is_false", DBSPOpcode.IS_FALSE);
        this.booleanFunctions.put("is_not_true", DBSPOpcode.IS_NOT_TRUE);
        this.booleanFunctions.put("is_true", DBSPOpcode.IS_TRUE);
        this.booleanFunctions.put("is_not_false", DBSPOpcode.IS_NOT_FALSE);
        this.booleanFunctions.put("agg_min", DBSPOpcode.AGG_MIN);
        this.booleanFunctions.put("agg_max", DBSPOpcode.AGG_MAX);
        this.booleanFunctions.put("agg_lte", DBSPOpcode.AGG_LTE);
        this.booleanFunctions.put("agg_gte", DBSPOpcode.AGG_GTE);
        this.booleanFunctions.put("is_same", DBSPOpcode.IS_NOT_DISTINCT);
        this.booleanFunctions.put("is_distinct", DBSPOpcode.IS_DISTINCT);
        this.booleanFunctions.put("gte_left", DBSPOpcode.GTE_LEFT);

        // These are defined for VARBIT types
        this.otherFunctions.put("agg_and", DBSPOpcode.AGG_AND);
        this.otherFunctions.put("agg_or", DBSPOpcode.AGG_OR);
        this.otherFunctions.put("agg_xor", DBSPOpcode.AGG_XOR);
        this.otherFunctions.put("concat", DBSPOpcode.CONCAT);
        this.otherFunctions.put("agg_lte", DBSPOpcode.AGG_LTE);
        this.otherFunctions.put("agg_gte", DBSPOpcode.AGG_GTE);
        this.otherFunctions.put("gte_left", DBSPOpcode.GTE_LEFT);
        // These are defined for all types
        this.otherFunctions.put("eq", DBSPOpcode.EQ);
        this.otherFunctions.put("neq", DBSPOpcode.NEQ);
        this.otherFunctions.put("lt", DBSPOpcode.LT);
        this.otherFunctions.put("gt", DBSPOpcode.GT);
        this.otherFunctions.put("lte", DBSPOpcode.LTE);
        this.otherFunctions.put("gte", DBSPOpcode.GTE);
    }

    public static class FunctionDescription {
        public final String function;
        public final DBSPType returnType;

        public FunctionDescription(String function, DBSPType returnType) {
            this.function = function;
            this.returnType = returnType;
        }

        @Override
        public String toString() {
            return "FunctionDescription{" +
                    "function='" + function + '\'' +
                    ", returnType=" + returnType +
                    '}';
        }
    }

    public static FunctionDescription getWindowBound(CalciteObject node,
            DBSPType unsignedType, DBSPType sortType, DBSPType boundType) {
        // we ignore nullability because window bounds are constants and cannot be null
        if (boundType.is(DBSPTypeMonthsInterval.class)) {
            throw new UnsupportedException("""
                    Currently the compiler only supports constant OVER window bounds.
                    Intervals such as 'INTERVAL 1 MONTH' or 'INTERVAL 1 YEAR' are not constant.
                    Can you rephrase the query using an interval such as 'INTERVAL 30 DAYS' instead?""", node);
        }
        return new FunctionDescription("to_bound_" +
                boundType.setMayBeNull(false).baseTypeWithSuffix() + "_" +
                sortType.setMayBeNull(false).baseTypeWithSuffix() + "_" +
                unsignedType.baseTypeWithSuffix(), unsignedType);
    }

    /** The name of the Rust function in the sqllib which implements a specific operation
     * @param node                Calcite node.
     * @param opcode              Operation opcode.
     * @param expectedReturnType  Return type of the function.
     * @param ltype               Type of first argument.
     * @param rtype               Type of second argument; null if function takes only one argument.
     * @return                    The function name.
     */
    public String getFunctionName(CalciteObject node,
                                  DBSPOpcode opcode, DBSPType expectedReturnType,
                                  DBSPType ltype, @Nullable DBSPType rtype) {
        if (ltype.is(DBSPTypeAny.class) || (rtype != null && rtype.is(DBSPTypeAny.class)))
            throw new InternalCompilerError("Unexpected type _ for operand of " + opcode, ltype);
        HashMap<String, DBSPOpcode> map = null;
        String suffixReturn = "";  // suffix based on the return type

        if (ltype.as(DBSPTypeBool.class) != null) {
            map = this.booleanFunctions;
        } else if (ltype.is(IsDateType.class)) {
            map = this.dateFunctions;
            if (opcode == DBSPOpcode.SUB || opcode == DBSPOpcode.ADD) {
                if (ltype.is(DBSPTypeTimestamp.class) || ltype.is(DBSPTypeDate.class)) {
                    assert rtype != null;
                    suffixReturn = "_" + expectedReturnType.baseTypeWithSuffix();
                    if (rtype.is(IsNumericType.class))
                        throw new CompilationError("Cannot apply operation " + Utilities.singleQuote(opcode.toString()) +
                                " to arguments of type " + ltype.asSqlString() + " and " + rtype.asSqlString(), node);
                }
            }
        } else if (ltype.is(IsNumericType.class)) {
            map = this.arithmeticFunctions;
        } else if (ltype.is(DBSPTypeString.class)) {
            map = this.stringFunctions;
        } else if (ltype.is(DBSPTypeBinary.class) || ltype.is(DBSPTypeVariant.class)) {
            map = this.otherFunctions;
        }
        if (rtype != null && rtype.is(IsDateType.class)) {
            if (opcode.equals(DBSPOpcode.MUL)) {
                // e.g., 10 * INTERVAL
                map = this.dateFunctions;
            }
        }

        String suffixl = ltype.nullableSuffix();
        String suffixr = rtype == null ? "" : rtype.nullableSuffix();
        String tsuffixl;
        String tsuffixr;
        if (opcode == DBSPOpcode.IS_DISTINCT || opcode == DBSPOpcode.IS_NOT_DISTINCT) {
            tsuffixl = "";
            tsuffixr = "";
            suffixl = "";
            suffixr = "";
        } else if (opcode == DBSPOpcode.AGG_GTE || opcode == DBSPOpcode.AGG_LTE || opcode == DBSPOpcode.GTE_LEFT) {
            tsuffixl = "";
            tsuffixr = "";
        } else {
            tsuffixl = ltype.to(DBSPTypeBaseType.class).shortName();
            tsuffixr = (rtype == null) ? "" : rtype.to(DBSPTypeBaseType.class).shortName();
        }
        if (map == null)
            throw new UnimplementedException(opcode.toString());
        for (String k: map.keySet()) {
            DBSPOpcode inMap = map.get(k);
            if (opcode.equals(inMap)) {
                return k + "_" + tsuffixl + suffixl + "_" + tsuffixr + suffixr + suffixReturn;
            }
        }
        throw new UnimplementedException("Could not find `" + opcode + "` for type " + ltype);
    }
}
