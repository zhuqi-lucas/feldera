package org.dbsp.sqlCompiler.compiler.visitors.outer.monotonicity;

import org.dbsp.sqlCompiler.circuit.DBSPCircuit;
import org.dbsp.sqlCompiler.circuit.operator.DBSPAggregateLinearPostprocessOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPAggregateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPApply2Operator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPApplyOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPAsofJoinOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPBinaryOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPControlledFilterOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDeindexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDelayOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDelayedIntegralOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPDistinctOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPFilterOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPFlatMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPHopOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateTraceRetainKeysOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPIntegrateTraceRetainValuesOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPJoinFilterMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPJoinOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapIndexOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPMapOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPPartitionedRollingAggregateOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPPartitionedRollingAggregateWithWaterlineOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSinkOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSourceMultisetOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPStreamJoinOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPSumOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPViewOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPWaterlineOperator;
import org.dbsp.sqlCompiler.circuit.operator.DBSPWindowOperator;
import org.dbsp.sqlCompiler.compiler.IErrorReporter;
import org.dbsp.sqlCompiler.compiler.IHasColumnsMetadata;
import org.dbsp.sqlCompiler.compiler.IHasLateness;
import org.dbsp.sqlCompiler.compiler.IHasWatermark;
import org.dbsp.sqlCompiler.compiler.errors.CompilationError;
import org.dbsp.sqlCompiler.compiler.errors.InternalCompilerError;
import org.dbsp.sqlCompiler.compiler.errors.UnimplementedException;
import org.dbsp.sqlCompiler.compiler.frontend.ExpressionCompiler;
import org.dbsp.sqlCompiler.compiler.frontend.calciteObject.CalciteObject;
import org.dbsp.sqlCompiler.compiler.visitors.inner.Projection;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.IMaybeMonotoneType;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.MonotoneClosureType;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.MonotoneExpression;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.MonotoneTransferFunctions;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.MonotoneType;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.NonMonotoneType;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.PartiallyMonotoneTuple;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.ScalarMonotoneType;
import org.dbsp.sqlCompiler.compiler.visitors.inner.monotone.CustomOrdMonotoneType;
import org.dbsp.sqlCompiler.compiler.visitors.outer.CircuitCloneVisitor;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.AggregateExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.CommonJoinExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.DistinctExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.JoinExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.JoinFilterMapExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.OperatorExpansion;
import org.dbsp.sqlCompiler.compiler.visitors.outer.expansion.ReplacementExpansion;
import org.dbsp.sqlCompiler.ir.DBSPParameter;
import org.dbsp.sqlCompiler.circuit.annotation.AlwaysMonotone;
import org.dbsp.sqlCompiler.circuit.annotation.NoIntegrator;
import org.dbsp.sqlCompiler.circuit.annotation.Waterline;
import org.dbsp.sqlCompiler.ir.expression.DBSPClosureExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPIfExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPOpcode;
import org.dbsp.sqlCompiler.ir.expression.DBSPRawTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPTupleExpression;
import org.dbsp.sqlCompiler.ir.expression.DBSPVariablePath;
import org.dbsp.sqlCompiler.ir.expression.literal.DBSPBoolLiteral;
import org.dbsp.sqlCompiler.ir.type.DBSPType;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeRawTuple;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeTuple;
import org.dbsp.sqlCompiler.ir.type.derived.DBSPTypeTupleBase;
import org.dbsp.sqlCompiler.ir.type.IsBoundedType;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBaseType;
import org.dbsp.sqlCompiler.ir.type.primitive.DBSPTypeBool;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeIndexedZSet;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeTypedBox;
import org.dbsp.sqlCompiler.ir.type.user.DBSPTypeWithCustomOrd;
import org.dbsp.util.Linq;
import org.dbsp.util.Logger;
import org.dbsp.util.NullableFunction;
import org.dbsp.util.Utilities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.dbsp.sqlCompiler.ir.expression.DBSPOpcode.AND;

/** As a result of the Monotonicity analysis, this pass inserts new operators:
 * - apply operators that compute the bounds that drive the controlled filters
 * - {@link DBSPControlledFilterOperator} operators to throw away tuples that are not "useful"
 * - {@link DBSPWaterlineOperator} operators near sources with lateness information
 * - {@link DBSPIntegrateTraceRetainKeysOperator} to prune data from integral operators
 * - {@link DBSPPartitionedRollingAggregateWithWaterlineOperator} operators
 * - {@link DBSPIntegrateTraceRetainValuesOperator} to prune data from integral operators
 * This also inserts WINDOWS before views that have "emit_final" annotations.
 *
 * <P>This visitor is tricky because it operates on a circuit, but takes the information
 * required to rewrite the graph from a different circuit, the expandedCircuit and
 * the expandedInto map.  Moreover, the current circuit is being rewritten by the
 * visitor while it is being processed.
 *
 * <p>Each apply operator has the signature (bool, arg) -> (bool, result), where the
 * boolean field indicates whether this is the first step.  In the first step the
 * apply operators do not compute, since the inputs may cause exceptions.
 **/
public class InsertLimiters extends CircuitCloneVisitor {
    /** For each operator in the expansion of the operators of this circuit
     * the list of its monotone output columns */
    public final Monotonicity.MonotonicityInformation expansionMonotoneValues;
    /** Circuit that contains the expansion of the circuit we are modifying */
    public final DBSPCircuit expandedCircuit;
    /** Maps each original operator to the set of operators it was expanded to */
    public final Map<DBSPOperator, OperatorExpansion> expandedInto;
    /** Maps each operator to the one that computes its lower bound.
     * The keys in this map can be both operators from this circuit and from
     * the expanded circuit. */
    public final Map<DBSPOperator, DBSPOperator> bound;
    /** Information about joins */
    final  NullableFunction<DBSPBinaryOperator, KeyPropagation.JoinDescription> joinInformation;
    // Debugging aid, normally 'true'
    static final boolean INSERT_RETAIN_VALUES = true;
    // Debugging aid, normally 'true'
    static final boolean INSERT_RETAIN_KEYS = true;

    public InsertLimiters(IErrorReporter reporter,
                          DBSPCircuit expandedCircuit,
                          Monotonicity.MonotonicityInformation expansionMonotoneValues,
                          Map<DBSPOperator, OperatorExpansion> expandedInto,
                          NullableFunction<DBSPBinaryOperator, KeyPropagation.JoinDescription> joinInformation) {
        super(reporter, false);
        this.expandedCircuit = expandedCircuit;
        this.expansionMonotoneValues = expansionMonotoneValues;
        this.expandedInto = expandedInto;
        this.joinInformation = joinInformation;
        this.bound = new HashMap<>();
    }

    void markBound(DBSPOperator operator, DBSPOperator bound) {
        Logger.INSTANCE.belowLevel(this, 1)
                .append("Bound for ")
                .append(operator.toString())
                .append(" computed by ")
                .append(bound.toString())
                .newline();
        Utilities.putNew(this.bound, operator, bound);
    }

    /** Given a function for an apply operator, synthesizes an operator that performs
     * the following computation:
     * <p>
     * |param| (
     *    param.0,
     *    if param.0 {
     *       function(param.1)
     *    } else {
     *       min
     *    })
     * where 'min' is the minimum constant value with the appropriate type.
     * Inserts the operator in the circuit.  'param.0' is true when param.1 is
     * not the minimum legal value - i.e., the waterline has seen some data.
     *
     * @param source   Input operator.
     * @param function Function to apply to the data.
     */
    DBSPApplyOperator createApply(DBSPOperator source, DBSPClosureExpression function) {
        DBSPVariablePath var = source.outputType.ref().var();
        DBSPExpression v0 = var.deref().field(0);
        DBSPExpression v1 = var.deref().field(1);
        DBSPExpression min = function.getResultType().minimumValue();
        DBSPExpression call = function.call(v1.borrow()).reduce(this.errorReporter);
        DBSPExpression cond = new DBSPTupleExpression(v0,
                new DBSPIfExpression(source.getNode(), v0, call, min));
        DBSPApplyOperator result = new DBSPApplyOperator(source.getNode(), cond.closure(var.asParameter()),
                source, "(" + source.getDerivedFrom() + ")");
        this.addOperator(result);
        result.addAnnotation(new Waterline());
        return result;
    }

    /** Given a function for an apply2 operator, synthesizes an operator that performs
     * the following computation:
     * <p>
     * |param0, param1|
     *    (param0.0 && param1.0,
     *     if param0.0 && param1.0 {
     *       function(param0.1, param1.1)
     *     } else {
     *       min
     *     })
     * where 'min' is the minimum constant value with the appropriate type.
     * Inserts the operator in the circuit.  'param0.0' is true when param0.1 is
     * not the minimum legal value - i.e., the waterline has seen some data
     * (same on the other side).
     *
     * @param left     Left input operator.
     * @param right    Right input operator
     * @param function Function to apply to the data.
     */
    DBSPApply2Operator createApply2(DBSPOperator left, DBSPOperator right, DBSPClosureExpression function) {
        DBSPVariablePath leftVar = left.outputType.ref().var();
        DBSPVariablePath rightVar = right.outputType.ref().var();
        DBSPExpression v0 = leftVar.deref().field(0);
        DBSPExpression v1 = rightVar.deref().field(0);
        DBSPExpression v01 = leftVar.deref().field(1);
        DBSPExpression v11 = rightVar.deref().field(1);
        DBSPExpression and = ExpressionCompiler.makeBinaryExpression(left.getNode(),
                v0.getType(), AND, v0, v1);
        DBSPExpression min = function.getResultType().minimumValue();
        DBSPExpression cond = new DBSPTupleExpression(and,
                new DBSPIfExpression(left.getNode(), and,
                        function.call(v01.borrow(), v11.borrow()), min));
        DBSPApply2Operator result = new DBSPApply2Operator(
                left.getNode(), cond.closure(leftVar.asParameter(), rightVar.asParameter()),
                left, right);
        result.addAnnotation(new Waterline());
        this.addOperator(result);
        return result;
    }

    /**
     * @param operatorFromExpansion Operator produced as the expansion of
     *                              another operator.
     * @param input                 Input of the operatorFromExpansion which
     *                              is used.
     * @return Add an operator which computes the smallest legal value
     * for the output of an operator. */
    @SuppressWarnings("SameParameterValue")
    @Nullable
    DBSPApplyOperator addBounds(@Nullable DBSPOperator operatorFromExpansion, int input) {
        if (operatorFromExpansion == null)
            return null;
        MonotoneExpression monotone = this.expansionMonotoneValues.get(operatorFromExpansion);
        if (monotone == null)
            return null;
        DBSPOperator source = operatorFromExpansion.inputs.get(input);  // Even for binary operators
        DBSPOperator boundSource = Utilities.getExists(this.bound, source);
        DBSPClosureExpression function = monotone.getReducedExpression().to(DBSPClosureExpression.class);

        DBSPApplyOperator bound = this.createApply(boundSource, function);
        this.markBound(operatorFromExpansion, bound);
        return bound;
    }

    void nonMonotone(DBSPOperator operator) {
        Logger.INSTANCE.belowLevel(this, 1)
                .append("Not monotone: ")
                .append(operator.toString())
                .newline();
    }

    @Nullable
    ReplacementExpansion getReplacement(DBSPOperator operator) {
        OperatorExpansion expanded = this.expandedInto.get(operator);
        if (expanded == null)
            return null;
        return expanded.to(ReplacementExpansion.class);
    }

    @Override
    public void postorder(DBSPHopOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null)
            this.addBounds(expanded.replacement, 0);
        else
            this.nonMonotone(operator);
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPDeindexOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null)
            this.addBounds(expanded.replacement, 0);
        else
            this.nonMonotone(operator);
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPMapOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPOperator bound = this.addBounds(expanded.replacement, 0);
            if (operator != expanded.replacement && bound != null)
                this.markBound(operator, bound);
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPFlatMapOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPOperator bound = this.addBounds(expanded.replacement, 0);
            if (operator != expanded.replacement && bound != null)
                this.markBound(operator, bound);
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPWindowOperator operator) {
        // Treat as an identity function for the left input
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPOperator bound = this.addBounds(expanded.replacement, 0);
            if (operator != expanded.replacement && bound != null)
                this.markBound(operator, bound);
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPFilterOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPOperator bound = this.processFilter(expanded.replacement.to(DBSPFilterOperator.class));
            if (operator != expanded.replacement && bound != null) {
                this.markBound(operator, bound);
            }
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    @Nullable
    DBSPOperator processFilter(@Nullable DBSPFilterOperator expansion) {
        if (expansion == null)
            return null;
        return this.addBounds(expansion, 0);
    }

    @Override
    public void postorder(DBSPMapIndexOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPOperator bound = this.addBounds(expanded.replacement, 0);
            if (operator != expanded.replacement && bound != null)
                this.markBound(operator, bound);
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPAggregateLinearPostprocessOperator aggregator) {
        DBSPOperator source = this.mapped(aggregator.input());
        OperatorExpansion expanded = this.expandedInto.get(aggregator);
        if (expanded == null) {
            this.nonMonotone(aggregator);
            super.postorder(aggregator);
            return;
        }

        ReplacementExpansion ae = expanded.to(ReplacementExpansion.class);
        DBSPOperator limiter = this.bound.get(aggregator.input());
        if (limiter == null) {
            super.postorder(aggregator);
            this.nonMonotone(aggregator);
            return;
        }

        DBSPOperator filteredAggregator = aggregator.withInputs(Linq.list(source), false);
        DBSPOperator limiter2 = this.addBounds(ae.replacement, 0);
        if (limiter2 == null) {
            this.map(aggregator, filteredAggregator);
            return;
        }

        this.addOperator(filteredAggregator);
        MonotoneExpression monotoneValue2 = this.expansionMonotoneValues.get(ae.replacement);
        IMaybeMonotoneType projection2 = Monotonicity.getBodyType(Objects.requireNonNull(monotoneValue2));

        if (INSERT_RETAIN_KEYS) {
            DBSPIntegrateTraceRetainKeysOperator after = DBSPIntegrateTraceRetainKeysOperator.create(
                    aggregator.getNode(), filteredAggregator, projection2, this.createDelay(limiter2));
            this.addOperator(after);
            // output of 'after'' is not used in the graph, but the DBSP Rust layer will use it
        }

        this.map(aggregator, filteredAggregator, false);
    }

    DBSPDelayOperator createDelay(DBSPOperator source) {
        DBSPExpression initial = source.outputType.minimumValue();
        DBSPDelayOperator result = new DBSPDelayOperator(source.getNode(), initial, source);
        this.addOperator(result);
        return result;
    }

    @Override
    public void postorder(DBSPAggregateOperator aggregator) {
        DBSPOperator source = this.mapped(aggregator.input());
        OperatorExpansion expanded = this.expandedInto.get(aggregator);
        if (expanded == null) {
            this.nonMonotone(aggregator);
            super.postorder(aggregator);
            return;
        }

        AggregateExpansion ae = expanded.to(AggregateExpansion.class);
        DBSPOperator limiter = this.bound.get(aggregator.input());
        if (limiter == null) {
            super.postorder(aggregator);
            this.nonMonotone(aggregator);
            return;
        }

        DBSPOperator filteredAggregator = aggregator.withInputs(Linq.list(source), false);
        // We use the input 0; input 1 comes from the integrator
        DBSPOperator limiter2 = this.addBounds(ae.aggregator, 0);
        if (limiter2 == null) {
            this.map(aggregator, filteredAggregator);
            return;
        }

        this.addOperator(filteredAggregator);
        MonotoneExpression monotoneValue2 = this.expansionMonotoneValues.get(ae.aggregator);
        IMaybeMonotoneType projection2 = Monotonicity.getBodyType(Objects.requireNonNull(monotoneValue2));

        if (INSERT_RETAIN_KEYS) {
            // The before and after filters are actually identical for now.
            DBSPOperator delay = this.createDelay(limiter2);
            DBSPIntegrateTraceRetainKeysOperator before = DBSPIntegrateTraceRetainKeysOperator.create(
                    aggregator.getNode(), source, projection2, delay);
            this.addOperator(before);
            // output of 'before' is not used in the graph, but the DBSP Rust layer will use it

            DBSPIntegrateTraceRetainKeysOperator after = DBSPIntegrateTraceRetainKeysOperator.create(
                    aggregator.getNode(), filteredAggregator, projection2, delay);
            this.addOperator(after);
            // output of 'after'' is not used in the graph, but the DBSP Rust layer will use it
        }

        DBSPApplyOperator limiter3 = this.addBounds(ae.upsert, 0);
        this.markBound(aggregator, Objects.requireNonNull(limiter3));

        this.map(aggregator, filteredAggregator, false);
    }

    @Override
    public void postorder(DBSPPartitionedRollingAggregateOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded == null) {
            super.postorder(operator);
            this.nonMonotone(operator);
            return;
        }

        DBSPOperator source = expanded.replacement.inputs.get(0);
        MonotoneExpression inputValue = this.expansionMonotoneValues.get(source);
        if (inputValue == null) {
            super.postorder(operator);
            this.nonMonotone(operator);
            return;
        }

        DBSPOperator boundSource = this.bound.get(source);
        if (boundSource == null) {
            super.postorder(operator);
            this.nonMonotone(operator);
            return;
        }

        // Preserve the field that the data is indexed on from the source
        IMaybeMonotoneType projection = Monotonicity.getBodyType(inputValue);
        PartiallyMonotoneTuple tuple = projection.to(PartiallyMonotoneTuple.class);
        IMaybeMonotoneType tuple0 = tuple.getField(0);
        // Drop field 1 of the value projection.
        if (!tuple0.mayBeMonotone()) {
            super.postorder(operator);
            this.nonMonotone(operator);
            return;
        }

        // Compute the waterline for the new rolling aggregate operator
        DBSPTypeTupleBase varType = projection.getType().to(DBSPTypeTupleBase.class);
        assert varType.size() == 2 : "Expected a pair, got " + varType;
        varType = new DBSPTypeRawTuple(varType.tupFields[0].ref(), varType.tupFields[1].ref());
        final DBSPVariablePath var = varType.var();
        DBSPExpression body = var.field(0).deref();
        body = DBSPTypeTypedBox.wrapTypedBox(body, true);
        DBSPClosureExpression closure = body.closure(var.asParameter());
        MonotoneTransferFunctions analyzer = new MonotoneTransferFunctions(
                this.errorReporter, operator, MonotoneTransferFunctions.ArgumentKind.IndexedZSet, projection);
        MonotoneExpression monotone = analyzer.applyAnalysis(closure);
        Objects.requireNonNull(monotone);

        DBSPClosureExpression function = monotone.getReducedExpression().to(DBSPClosureExpression.class);
        DBSPOperator waterline = this.createApply(boundSource, function);
        Logger.INSTANCE.belowLevel(this, 2)
                .append("WATERLINE FUNCTION: ")
                .append(function)
                .newline();

        // The bound for the output is different from the waterline
        body = new DBSPRawTupleExpression(new DBSPTupleExpression(var.field(0).deref()));
        closure = body.closure(var.asParameter());
        analyzer = new MonotoneTransferFunctions(
                this.errorReporter, operator, MonotoneTransferFunctions.ArgumentKind.IndexedZSet, projection);
        monotone = analyzer.applyAnalysis(closure);
        Objects.requireNonNull(monotone);

        function = monotone.getReducedExpression().to(DBSPClosureExpression.class);
        Logger.INSTANCE.belowLevel(this, 2)
                .append("BOUND FUNCTION: ")
                .append(function)
                .newline();

        DBSPOperator bound = this.createApply(boundSource, function);
        this.markBound(expanded.replacement, bound);

        // Drop the boolean flag from the waterline
        DBSPVariablePath tmp = waterline.outputType.ref().var();
        DBSPClosureExpression drop = tmp.deref().field(1).applyCloneIfNeeded().closure(tmp.asParameter());
        // Must insert the delay on the waterline, if we try to insert the delay
        // on the output of the dropApply Rust will be upset, since Delays
        // cannot have types that include TypedBox.
        DBSPApplyOperator dropApply = new DBSPApplyOperator(operator.getNode(), drop, this.createDelay(waterline), null);
        this.addOperator(dropApply);

        DBSPPartitionedRollingAggregateWithWaterlineOperator replacement =
                new DBSPPartitionedRollingAggregateWithWaterlineOperator(operator.getNode(),
                        operator.partitioningFunction, operator.function, operator.aggregate,
                        operator.lower, operator.upper, operator.getOutputIndexedZSetType(),
                        this.mapped(operator.input()), dropApply);
        this.map(operator, replacement);
    }

    void addJoinAnnotations(DBSPBinaryOperator operator) {
        KeyPropagation.JoinDescription info = this.joinInformation.apply(operator);
        if (info != null)
            operator.addAnnotation(new NoIntegrator(info.leftIsKey(), !info.leftIsKey()));
    }

    public void postorder(DBSPStreamJoinOperator operator) {
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null)
            this.processJoin(expanded.replacement.to(DBSPStreamJoinOperator.class));
        else
            this.nonMonotone(operator);
        this.addJoinAnnotations(operator);
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPDistinctOperator operator) {
        DBSPOperator source = this.mapped(operator.input());
        OperatorExpansion expanded = this.expandedInto.get(operator);
        if (expanded == null) {
            super.postorder(operator);
            this.nonMonotone(operator);
            return;
        }
        DistinctExpansion expansion = expanded.to(DistinctExpansion.class);
        DBSPOperator sourceLimiter = this.bound.get(operator.input());
        if (sourceLimiter == null) {
            super.postorder(operator);
            this.nonMonotone(operator);
            return;
        }

        DBSPOperator result = operator.withInputs(Linq.list(source), false);
        MonotoneExpression sourceMonotone = this.expansionMonotoneValues.get(expansion.distinct.right());
        IMaybeMonotoneType projection = Monotonicity.getBodyType(Objects.requireNonNull(sourceMonotone));
        if (INSERT_RETAIN_KEYS) {
            DBSPIntegrateTraceRetainKeysOperator r = DBSPIntegrateTraceRetainKeysOperator.create(
                    operator.getNode(), source, projection, this.createDelay(sourceLimiter));
            this.addOperator(r);
        }
        // Same limiter as the source
        this.markBound(expansion.distinct, sourceLimiter);
        this.markBound(operator, sourceLimiter);
        this.map(operator, result, true);
    }

    @Nullable
    DBSPOperator gcJoin(DBSPBinaryOperator join, CommonJoinExpansion expansion) {
        DBSPOperator leftLimiter = this.bound.get(join.left());
        DBSPOperator rightLimiter = this.bound.get(join.right());
        if (leftLimiter == null && rightLimiter == null) {
            return null;
        }

        DBSPOperator left = this.mapped(join.left());
        DBSPOperator right = this.mapped(join.right());
        DBSPBinaryOperator result = join.withInputs(Linq.list(left, right), false)
                .to(DBSPBinaryOperator.class);
        if (leftLimiter != null && expansion.getLeftIntegrator() != null) {
            MonotoneExpression leftMonotone = this.expansionMonotoneValues.get(
                    expansion.getLeftIntegrator().input());
            // Yes, the limit of the left input is applied to the right one.
            IMaybeMonotoneType leftProjection = Monotonicity.getBodyType(Objects.requireNonNull(leftMonotone));
            // Check if the "key" field is monotone
            if (leftProjection.to(PartiallyMonotoneTuple.class).getField(0).mayBeMonotone()) {
                if (INSERT_RETAIN_KEYS) {
                    DBSPIntegrateTraceRetainKeysOperator r = DBSPIntegrateTraceRetainKeysOperator.create(
                            join.getNode(), right, leftProjection, this.createDelay(leftLimiter));
                    this.addOperator(r);
                }
            }
        }

        if (rightLimiter != null && expansion.getRightIntegrator() != null) {
            MonotoneExpression rightMonotone = this.expansionMonotoneValues.get(
                    expansion.getRightIntegrator().input());
            // Yes, the limit of the right input is applied to the left one.
            IMaybeMonotoneType rightProjection = Monotonicity.getBodyType(Objects.requireNonNull(rightMonotone));
            // Check if the "key" field is monotone
            if (rightProjection.to(PartiallyMonotoneTuple.class).getField(0).mayBeMonotone()) {
                if (INSERT_RETAIN_KEYS) {
                    DBSPIntegrateTraceRetainKeysOperator l = DBSPIntegrateTraceRetainKeysOperator.create(
                            join.getNode(), left, rightProjection, this.createDelay(rightLimiter));
                    this.addOperator(l);
                }
            }
        }
        return result;
    }

    @Override
    public void postorder(DBSPJoinOperator join) {
        OperatorExpansion expanded = this.expandedInto.get(join);
        if (expanded == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        this.addJoinAnnotations(join);
        JoinExpansion expansion = expanded.to(JoinExpansion.class);
        DBSPOperator result = this.gcJoin(join, expansion);
        if (result == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        this.processIntegral(expansion.leftIntegrator);
        this.processIntegral(expansion.rightIntegrator);
        this.processJoin(expansion.leftDelta);
        this.processJoin(expansion.rightDelta);
        this.processJoin(expansion.both);
        this.processSum(expansion.sum);

        this.map(join, result, true);
    }

    private void processIntegral(@Nullable DBSPDelayedIntegralOperator replacement) {
        if (replacement == null)
            return;
        if (replacement.hasAnnotation(a -> a.is(AlwaysMonotone.class))) {
            DBSPOperator limiter = this.bound.get(replacement.input());
            if (limiter != null) {
                this.markBound(replacement, limiter);
            } else {
                this.nonMonotone(replacement);
            }
        }
    }

    DBSPApplyOperator extractTimestamp(PartiallyMonotoneTuple sourceType, int tsIndex, DBSPOperator source) {
        // First index to apply to the limiter
        int outerIndex = sourceType.getField(0).mayBeMonotone() ? 1 : 0;
        PartiallyMonotoneTuple sourceTypeValue = sourceType.getField(1)
                .to(CustomOrdMonotoneType.class).getWrappedType();
        // Second index to apply to the limiter
        int innerIndex = 0;
        for (int i = 0; i < tsIndex; i++) {
            if (sourceTypeValue.getField(i).mayBeMonotone())
                innerIndex++;
        }

        DBSPVariablePath var = this.getLimiterDataOutputType(source).ref().var();
        DBSPClosureExpression tsFunction = var
                .deref()
                .field(outerIndex)
                .field(innerIndex)
                .closure(var.asParameter());
        return this.createApply(source, tsFunction);
    }

    @Override
    public void postorder(DBSPAsofJoinOperator join) {
        OperatorExpansion expansion = this.expandedInto.get(join);
        if (expansion == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }
        ReplacementExpansion repl = expansion.to(ReplacementExpansion.class);
        DBSPAsofJoinOperator expanded = repl.replacement.to(DBSPAsofJoinOperator.class);
        this.processJoin(expanded);

        DBSPOperator leftLimiter = this.bound.get(join.left());
        DBSPOperator rightLimiter = this.bound.get(join.right());
        if (leftLimiter == null || rightLimiter == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        int leftTSIndex = join.getLeftTimestampIndex();
        int rightTSIndex = join.getRightTimestampIndex();
        // Check if the timestamps are monotone

        PartiallyMonotoneTuple leftMono = null;
        MonotoneExpression lm = this.expansionMonotoneValues.get(expanded.left());
        if (lm != null) {
            leftMono = Monotonicity.getBodyType(lm).to(PartiallyMonotoneTuple.class);
        }
        PartiallyMonotoneTuple rightMono = null;
        MonotoneExpression rm = this.expansionMonotoneValues.get(expanded.right());
        if (rm != null) {
            rightMono = Monotonicity.getBodyType(rm).to(PartiallyMonotoneTuple.class);
        }

        if (leftMono == null || rightMono == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        // Extract the value part from the key-value tuple
        IMaybeMonotoneType leftValue = leftMono.getField(1);
        IMaybeMonotoneType rightValue = rightMono.getField(1);
        if (!leftValue.mayBeMonotone() || !rightValue.mayBeMonotone()) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        PartiallyMonotoneTuple leftValueTuple = leftValue.to(CustomOrdMonotoneType.class).getWrappedType();
        PartiallyMonotoneTuple rightValueTuple = rightValue.to(CustomOrdMonotoneType.class).getWrappedType();
        IMaybeMonotoneType leftTS = leftValueTuple.getField(leftTSIndex);
        IMaybeMonotoneType rightTS = rightValueTuple.getField(rightTSIndex);
        if (!leftTS.mayBeMonotone() || !rightTS.mayBeMonotone()) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        // Extract the timestamps from the limiters
        DBSPApplyOperator extractLeftTS = this.extractTimestamp(leftMono, leftTSIndex, leftLimiter);
        DBSPApplyOperator extractRightTS = this.extractTimestamp(rightMono, rightTSIndex, rightLimiter);

        // Compute the min of the timestamps
        DBSPVariablePath leftVar = this.getLimiterDataOutputType(extractLeftTS).ref().var();
        DBSPVariablePath rightVar = this.getLimiterDataOutputType(extractRightTS).ref().var();
        DBSPExpression min = new DBSPTupleExpression(this.min(leftVar.deref(), rightVar.deref()));
        DBSPApply2Operator minOperator = this.createApply2(extractLeftTS, extractRightTS,
                min.closure(leftVar.asParameter(), rightVar.asParameter()));

        DBSPTypeTuple keyType = join.getKeyType().to(DBSPTypeTuple.class);
        PartiallyMonotoneTuple keyPart = PartiallyMonotoneTuple.noMonotoneFields(keyType);

        DBSPTypeWithCustomOrd leftValueType = join.getLeftInputValueType().to(DBSPTypeWithCustomOrd.class);
        List<IMaybeMonotoneType> value = new ArrayList<>();
        for (int i = 0; i < leftValueType.size(); i++) {
            DBSPType field = leftValueType.getDataType().getFieldType(i);
            IMaybeMonotoneType mono;
            if (i == leftTSIndex) {
                mono = new MonotoneType(field);
            } else {
                mono = NonMonotoneType.nonMonotone(field);
            }
            value.add(mono);
        }
        PartiallyMonotoneTuple valuePart = new PartiallyMonotoneTuple(value, false, false);
        PartiallyMonotoneTuple dataProjection = new PartiallyMonotoneTuple(
                Linq.list(keyPart, new CustomOrdMonotoneType(valuePart, leftValueType)), true, false);

        if (INSERT_RETAIN_VALUES) {
            DBSPIntegrateTraceRetainValuesOperator retain = DBSPIntegrateTraceRetainValuesOperator.create(
                    join.getNode(), this.mapped(join.left()), dataProjection, this.createDelay(minOperator));
            this.addOperator(retain);
        }

        super.postorder(join);
    }

    @Override
    public void postorder(DBSPJoinFilterMapOperator join) {
        OperatorExpansion expanded = this.expandedInto.get(join);
        if (expanded == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }
        JoinFilterMapExpansion expansion = expanded.to(JoinFilterMapExpansion.class);

        this.addJoinAnnotations(join);
        DBSPOperator result = this.gcJoin(join, expansion);
        if (result == null) {
            super.postorder(join);
            this.nonMonotone(join);
            return;
        }

        this.processIntegral(expansion.leftIntegrator);
        this.processIntegral(expansion.rightIntegrator);
        this.processJoin(expansion.leftDelta);
        this.processJoin(expansion.rightDelta);
        this.processJoin(expansion.both);
        this.processFilter(expansion.filter);
        this.processFilter(expansion.leftFilter);
        this.processFilter(expansion.rightFilter);
        this.processSum(expansion.sum);

        // If any of the filters leftFilter or rightFilter has monotone outputs,
        // we can use these to GC the other input of the join using
        // DBSPIntegrateTraceRetainValuesOperator.

        Projection proj = new Projection(this.errorReporter, true);
        proj.apply(join.getFunction());
        assert(proj.isProjection);
        Projection.IOMap iomap = proj.getIoMap();
        // This will look something like.  Ordered by output field number.
        // [input#, field#]
        // [0, 0]
        // [1, 0]
        // [1, 2]
        // [0, 1]
        // [2, 0]
        // [2, 1]
        // input# is 0 = key, 1 = left, 2 = right
        int leftValueSize = join.left().getOutputIndexedZSetType().elementType.to(DBSPTypeTuple.class).size();
        int rightValueSize = join.right().getOutputIndexedZSetType().elementType.to(DBSPTypeTuple.class).size();

        DBSPTypeTuple keyType = join.getKeyType().to(DBSPTypeTuple.class);
        PartiallyMonotoneTuple keyPart = PartiallyMonotoneTuple.noMonotoneFields(keyType);

        // Check the left side and insert a GC operator if possible
        DBSPOperator leftLimiter = this.bound.get(expansion.leftFilter);
        if (leftLimiter != null && expansion.leftFilter != null) {
            MonotoneExpression monotone = this.expansionMonotoneValues.get(expansion.leftFilter);
            IMaybeMonotoneType projection = Monotonicity.getBodyType(Objects.requireNonNull(monotone));
            if (projection.mayBeMonotone()) {
                PartiallyMonotoneTuple tuple = projection.to(PartiallyMonotoneTuple.class);
                assert tuple.size() == iomap.size();

                List<IMaybeMonotoneType> value = new ArrayList<>();
                DBSPVariablePath var = Objects.requireNonNull(tuple.getProjectedType()).ref().var();
                List<DBSPExpression> monotoneFields = new ArrayList<>();
                for (int index = 0, field = 0; field < leftValueSize; field++) {
                    int firstOutputField = iomap.firstOutputField(1, field);
                    // We assume that every left input field is used as an output
                    assert firstOutputField >= 0;
                    IMaybeMonotoneType compareField = tuple.getField(firstOutputField);
                    value.add(compareField);
                    if (compareField.mayBeMonotone()) {
                        monotoneFields.add(var.deref().field(index++));
                    }
                }
                PartiallyMonotoneTuple valuePart = new PartiallyMonotoneTuple(value, false, false);

                // Put the fields together
                PartiallyMonotoneTuple together = new PartiallyMonotoneTuple(
                        Linq.list(keyPart, valuePart), true, false);

                DBSPExpression func = new DBSPTupleExpression(monotoneFields, false);
                DBSPApplyOperator extractLeft = this.createApply(
                        leftLimiter, func.closure(var.asParameter()));

                if (INSERT_RETAIN_VALUES) {
                    DBSPIntegrateTraceRetainValuesOperator l = DBSPIntegrateTraceRetainValuesOperator.create(
                            join.getNode(), this.mapped(join.left()), together, this.createDelay(extractLeft));
                    this.addOperator(l);
                }
            }
        }

        // Exact same procedure on the right hand side
        DBSPOperator rightLimiter = this.bound.get(expansion.rightFilter);
        if (rightLimiter != null && expansion.rightFilter != null) {
            MonotoneExpression monotone = this.expansionMonotoneValues.get(expansion.rightFilter);
            IMaybeMonotoneType projection = Monotonicity.getBodyType(Objects.requireNonNull(monotone));
            if (projection.mayBeMonotone()) {
                PartiallyMonotoneTuple tuple = projection.to(PartiallyMonotoneTuple.class);
                assert tuple.size() == iomap.size();

                List<IMaybeMonotoneType> value = new ArrayList<>();
                DBSPVariablePath var = Objects.requireNonNull(tuple.getProjectedType()).ref().var();
                List<DBSPExpression> monotoneFields = new ArrayList<>();
                for (int field = 0, index = 0; field < rightValueSize; field++) {
                    int firstOutputField = iomap.firstOutputField(2, field);
                    // We assume that every left input field is used as an output
                    assert firstOutputField >= 0;
                    IMaybeMonotoneType compareField = tuple.getField(firstOutputField);
                    value.add(compareField);
                    if (compareField.mayBeMonotone()) {
                        monotoneFields.add(var.deref().field(index++));
                    }
                }
                PartiallyMonotoneTuple valuePart = new PartiallyMonotoneTuple(value, false, false);

                // Put the fields together
                PartiallyMonotoneTuple together = new PartiallyMonotoneTuple(
                        Linq.list(keyPart, valuePart), true, false);

                DBSPExpression func = new DBSPTupleExpression(monotoneFields, false);
                DBSPApplyOperator extractRight = this.createApply(
                        rightLimiter, func.closure(var.asParameter()));

                if (INSERT_RETAIN_VALUES) {
                    DBSPIntegrateTraceRetainValuesOperator r = DBSPIntegrateTraceRetainValuesOperator.create(
                            join.getNode(), this.mapped(join.right()), together, this.createDelay(extractRight));
                    this.addOperator(r);
                }
            }
        }

        this.map(join, result, true);
    }

    /** Given two expressions with the same type, compute the MAX expression pointwise,
     * only on their monotone fields.
     * @param left             Left expression.
     * @param right            Right expression.
     * @param leftProjection   Describes monotone fields of left expression.
     * @param rightProjection  Describes monotone fields of right expression. */
    DBSPExpression max(DBSPExpression left,
                       DBSPExpression right,
                       IMaybeMonotoneType leftProjection,
                       IMaybeMonotoneType rightProjection) {
        if (leftProjection.is(ScalarMonotoneType.class)) {
            if (leftProjection.is(NonMonotoneType.class)) {
                return right;
            } else if (rightProjection.is(NonMonotoneType.class)) {
                return left;
            } else {
                return ExpressionCompiler.makeBinaryExpression(left.getNode(),
                        left.getType(), DBSPOpcode.MAX, left, right);
            }
        } else if (leftProjection.is(PartiallyMonotoneTuple.class)) {
            PartiallyMonotoneTuple l = leftProjection.to(PartiallyMonotoneTuple.class);
            PartiallyMonotoneTuple r = rightProjection.to(PartiallyMonotoneTuple.class);
            assert r.size() == l.size();
            List<DBSPExpression> fields = new ArrayList<>();
            int leftIndex = 0;
            int rightIndex = 0;
            for (int i = 0; i < l.size(); i++) {
                IMaybeMonotoneType li = l.getField(i);
                IMaybeMonotoneType ri = r.getField(i);
                DBSPExpression le = null;
                DBSPExpression re = null;
                if (li.mayBeMonotone()) {
                    le = left.field(leftIndex++);
                }
                if (ri.mayBeMonotone()) {
                    re = right.field(rightIndex++);
                }
                if (le == null && re == null)
                    continue;
                if (le == null) {
                    fields.add(re);
                } else if (re == null) {
                    fields.add(le);
                } else {
                    fields.add(max(le, re, li, ri));
                }
            }
            return new DBSPTupleExpression(CalciteObject.EMPTY, fields);
        }
        throw new InternalCompilerError("Not yet handlex: max of type " + leftProjection,
                left.getNode());
    }

    /** Utility function for Apply operators that are introduced by the {@link InsertLimiters}
     * pass, converting their output type into a tuple.
     * This tuple always has a boolean on the first position. */
    DBSPTypeTupleBase getLimiterOutputType(DBSPOperator operator) {
        return operator.outputType.to(DBSPTypeTupleBase.class);
    }

    DBSPType getLimiterDataOutputType(DBSPOperator operator) {
        return this.getLimiterOutputType(operator).tupFields[1];
    }

    void processJoin(@Nullable DBSPBinaryOperator expanded) {
        if (expanded == null)
            return;
        MonotoneExpression monotoneValue = this.expansionMonotoneValues.get(expanded);
        if (monotoneValue == null || !monotoneValue.mayBeMonotone()) {
            this.nonMonotone(expanded);
            return;
        }
        DBSPOperator leftLimiter = this.bound.get(expanded.left());
        DBSPOperator rightLimiter = this.bound.get(expanded.right());
        if (leftLimiter == null && rightLimiter == null) {
            this.nonMonotone(expanded);
            return;
        }

        PartiallyMonotoneTuple out = Monotonicity.getBodyType(monotoneValue).to(PartiallyMonotoneTuple.class);
        DBSPType outputType = out.getProjectedType();
        assert outputType != null;
        DBSPOperator merger;
        PartiallyMonotoneTuple leftMono = null;
        MonotoneExpression lm = this.expansionMonotoneValues.get(expanded.left());
        if (lm != null) {
            leftMono = Monotonicity.getBodyType(lm).to(PartiallyMonotoneTuple.class);
        }
        PartiallyMonotoneTuple rightMono = null;
        MonotoneExpression rm = this.expansionMonotoneValues.get(expanded.right());
        if (rm != null) {
            rightMono = Monotonicity.getBodyType(rm).to(PartiallyMonotoneTuple.class);
        }

        if (leftLimiter != null && rightLimiter != null) {
            // (kl, l), (kr, r) -> (union(kl, kr), l, r)
            assert leftMono != null;
            assert rightMono != null;
            DBSPVariablePath l = new DBSPVariablePath(this.getLimiterDataOutputType(leftLimiter).ref());
            DBSPVariablePath r = new DBSPVariablePath(this.getLimiterDataOutputType(rightLimiter).ref());
            DBSPExpression[] fields = new DBSPExpression[3];
            int leftIndex = 0;
            int rightIndex = 0;

            if (leftMono.getField(0).mayBeMonotone()) {
                leftIndex++;
                if (rightMono.getField(0).mayBeMonotone()) {
                    rightIndex++;
                    fields[0] = max(
                            l.deref().field(0),
                            r.deref().field(0),
                            leftMono.getField(0),
                            rightMono.getField(0));
                } else {
                    fields[0] = l.deref().field(0);
                }
            } else {
                if (rightMono.getField(0).mayBeMonotone()) {
                    rightIndex++;
                    fields[0] = r.deref().field(0);
                } else {
                    fields[0] = new DBSPTupleExpression();
                }
            }

            if (leftMono.getField(1).mayBeMonotone())
                fields[1] = l.deref().field(leftIndex);
            else
                fields[1] = new DBSPTupleExpression();
            if (rightMono.getField(1).mayBeMonotone())
                fields[2] = r.deref().field(rightIndex);
            else
                fields[2] = new DBSPTupleExpression();

            DBSPClosureExpression closure =
                    new DBSPRawTupleExpression(fields)
                            .closure(l.asParameter(), r.asParameter());
            merger = this.createApply2(leftLimiter, rightLimiter, closure);
        } else if (leftLimiter != null) {
            // (k, l) -> (k, l, Tup0<>)
            assert leftMono != null;
            DBSPVariablePath var = new DBSPVariablePath(this.getLimiterDataOutputType(leftLimiter).ref());
            DBSPExpression k = new DBSPTupleExpression();
            int currentField = 0;
            if (leftMono.getField(0).mayBeMonotone()) {
                k = var.deref().field(currentField++);
            }
            DBSPExpression l = new DBSPTupleExpression();
            if (leftMono.getField(1).mayBeMonotone())
                l = var.deref().field(currentField);
            DBSPClosureExpression closure =
                    new DBSPRawTupleExpression(
                            k,
                            l,
                            new DBSPTupleExpression())
                            .closure(var.asParameter());
            merger = this.createApply(leftLimiter, closure);
        } else {
            // (k, r) -> (k, Tup0<>, r)
            assert rightMono != null;
            DBSPVariablePath var = new DBSPVariablePath(this.getLimiterDataOutputType(rightLimiter).ref());
            DBSPExpression k = new DBSPTupleExpression();
            DBSPExpression r = new DBSPTupleExpression();
            int currentField = 0;
            if (rightMono.getField(0).mayBeMonotone()) {
                k = var.deref().field(currentField++);
            }
            if (rightMono.getField(1).mayBeMonotone())
                r = var.deref().field(currentField);
            DBSPClosureExpression closure =
                    new DBSPRawTupleExpression(
                            k,
                            new DBSPTupleExpression(),
                            r)
                            .closure(var.asParameter());
            merger = this.createApply(rightLimiter, closure);
        }

        DBSPClosureExpression clo = monotoneValue.getReducedExpression().to(DBSPClosureExpression.class);
        DBSPOperator limiter = this.createApply(merger, clo);
        this.markBound(expanded, limiter);
    }

    /** Generates a closure that computes the max of two tuple timestamps fieldwise */
    public static DBSPClosureExpression timestampMax(CalciteObject node, DBSPTypeTupleBase type) {
        // Generate the max function for the timestamp tuple
        DBSPVariablePath left = type.ref().var();
        DBSPVariablePath right = type.ref().var();
        List<DBSPExpression> maxes = new ArrayList<>();
        for (int i = 0; i < type.size(); i++) {
            DBSPType ftype = type.tupFields[i];
            maxes.add(ExpressionCompiler.makeBinaryExpression(node, ftype, DBSPOpcode.MAX,
                    left.deref().field(i), right.deref().field(i)));
        }
        DBSPExpression max = new DBSPTupleExpression(maxes, false);
        return max.closure(left.asParameter(), right.asParameter());
    }

    /** Generate an expression that compares two other expressions for equality */
    DBSPExpression eq(DBSPExpression left, DBSPExpression right) {
        DBSPType type = left.getType();
        if (type.is(DBSPTypeBaseType.class)) {
            boolean mayBeNull = type.mayBeNull || right.getType().mayBeNull;
            DBSPExpression compare = ExpressionCompiler.makeBinaryExpression(left.getNode(),
                    DBSPTypeBool.create(mayBeNull), DBSPOpcode.EQ, left, right);
            return ExpressionCompiler.wrapBoolIfNeeded(compare);
        } else if (type.is(DBSPTypeTupleBase.class)) {
            DBSPTypeTupleBase tuple = type.to(DBSPTypeTupleBase.class);
            DBSPExpression result = new DBSPBoolLiteral(true);
            for (int i = 0; i < tuple.size(); i++) {
                DBSPExpression compare = eq(left.field(i).simplify(), right.field(i).simplify());
                result = ExpressionCompiler.makeBinaryExpression(left.getNode(),
                        DBSPTypeBool.create(false), AND, result, compare);
            }
            return result;
        } else {
            throw new InternalCompilerError("Not handled equality of type " + type.asSqlString(), type);
        }
    }

    /** Process LATENESS annotations.
     * @return Return the original operator if there aren't any annotations, or
     * the operator that produces the result of the input filtered otherwise. */
    DBSPOperator processLateness(DBSPOperator operator, DBSPOperator expansion) {
        MonotoneExpression expression = this.expansionMonotoneValues.get(expansion);
        if (expression == null) {
            this.nonMonotone(expansion);
            return operator;
        }
        List<DBSPExpression> timestamps = new ArrayList<>();
        int index = 0;
        DBSPVariablePath t = operator.getOutputZSetType().elementType.ref().var();
        for (IHasLateness column: operator.to(IHasColumnsMetadata.class).getLateness()) {
            DBSPExpression lateness = column.getLateness();
            if (lateness != null) {
                DBSPExpression field = t.deref().field(index);
                field = ExpressionCompiler.makeBinaryExpression(operator.getNode(), field.getType(),
                        DBSPOpcode.SUB, field, lateness);
                timestamps.add(field);
            }
            index++;
        }

        if (timestamps.isEmpty()) {
            this.nonMonotone(expansion);
            return operator;
        }

        List<DBSPOperator> sources = Linq.map(operator.inputs, this::mapped);
        DBSPOperator replacement = operator.withInputs(sources, this.force);
        replacement.setDerivedFrom(operator.id);
        this.addOperator(replacement);

        // The waterline operator will compute the *minimum legal value* of all the
        // inputs that have a lateness attached.  The output signature contains only
        // the columns that have lateness.
        DBSPTupleExpression timestamp = new DBSPTupleExpression(timestamps, false);
        DBSPExpression min = timestamp.getType().minimumValue();
        DBSPClosureExpression max = timestampMax(operator.getNode(), min.getType().to(DBSPTypeTupleBase.class));

        DBSPWaterlineOperator waterline = new DBSPWaterlineOperator(
                operator.getNode(), min.closure(),
                // second parameter unused for timestamp
                timestamp.closure(t.asParameter(), new DBSPTypeRawTuple().ref().var().asParameter()),
                max, replacement);
        this.addOperator(waterline);

        // Waterline fed through a delay
        DBSPDelayOperator delay = new DBSPDelayOperator(
                operator.getNode(), min, waterline);
        this.addOperator(delay);

        // An apply operator to add a Boolean bit to the waterline.
        // This bit is 'true' when the waterline produces a value
        // that is not 'minimum'.
        DBSPVariablePath var = timestamp.getType().ref().var();
        DBSPExpression eq = eq(min, var.deref());
        DBSPOperator extend = new DBSPApplyOperator(operator.getNode(),
                new DBSPTupleExpression(
                        eq.not(),
                        var.deref().applyClone()).closure(var.asParameter()),
                waterline, null);
        extend.addAnnotation(new Waterline());
        this.addOperator(extend);

        this.markBound(replacement, extend);
        if (operator != replacement)
            this.markBound(operator, extend);
        if (operator != expansion)
            this.markBound(expansion, extend);
        return DBSPControlledFilterOperator.create(
                operator.getNode(), replacement, Monotonicity.getBodyType(expression), delay);
    }

    @Override
    public void postorder(DBSPSourceMultisetOperator operator) {
        ReplacementExpansion replacementExpansion = Objects.requireNonNull(this.getReplacement(operator));
        DBSPOperator replacement = this.processLateness(operator, replacementExpansion.replacement);

        // Process watermark annotations.  Very similar to lateness annotations.
        int index = 0;
        DBSPType dataType = operator.getOutputZSetType().elementType;
        DBSPVariablePath t = dataType.ref().var();
        List<DBSPExpression> fields = new ArrayList<>();
        List<DBSPExpression> timestamps = new ArrayList<>();
        List<DBSPExpression> minimums = new ArrayList<>();
        for (IHasWatermark column: operator.to(IHasColumnsMetadata.class).getWatermarks()) {
            DBSPExpression watermark = column.getWatermark();
            if (watermark != null) {
                DBSPExpression field = t.deref().field(index);
                fields.add(field);
                DBSPType type = field.getType();
                field = ExpressionCompiler.makeBinaryExpression(operator.getNode(), field.getType(),
                        DBSPOpcode.SUB, field.deepCopy(), watermark);
                timestamps.add(field);
                DBSPExpression min = type.to(IsBoundedType.class).getMinValue();
                minimums.add(min);
            }
            index++;
        }

        // Currently we only support at most 1 watermark column per table.
        // TODO: support multiple fields.
        if (minimums.size() > 1) {
            throw new UnimplementedException("More than 1 watermark per table not yet supported",
                    2734, operator.getNode());
        }

        if (!minimums.isEmpty()) {
            assert fields.size() == 1;
            this.addOperator(replacement);

            DBSPTupleExpression min = new DBSPTupleExpression(minimums, false);
            DBSPTupleExpression timestamp = new DBSPTupleExpression(timestamps, false);
            DBSPParameter parameter = t.asParameter();
            DBSPClosureExpression max = timestampMax(operator.getNode(), min.getTypeAsTupleBase());
            DBSPWaterlineOperator waterline = new DBSPWaterlineOperator(
                    operator.getNode(), min.closure(),
                    // Second parameter unused for timestamp
                    timestamp.closure(parameter, new DBSPTypeRawTuple().ref().var().asParameter()),
                    max, operator);
            this.addOperator(waterline);

            DBSPVariablePath var = timestamp.getType().ref().var();
            DBSPExpression makePair = new DBSPRawTupleExpression(
                    DBSPTypeTypedBox.wrapTypedBox(minimums.get(0), false),
                    DBSPTypeTypedBox.wrapTypedBox(var.deref().field(0), false));
            DBSPApplyOperator apply = new DBSPApplyOperator(
                    operator.getNode(), makePair.closure(var.asParameter()), waterline,
                    "(" + operator.getDerivedFrom() + ")");
            this.addOperator(apply);

            // Window requires data to be indexed
            DBSPOperator ix = new DBSPMapIndexOperator(operator.getNode(),
                    new DBSPRawTupleExpression(
                            fields.get(0).applyCloneIfNeeded(),
                            t.deref().applyCloneIfNeeded()).closure(t.asParameter()),
                    new DBSPTypeIndexedZSet(operator.getNode(),
                            fields.get(0).getType(), dataType), true, replacement);
            this.addOperator(ix);
            DBSPWindowOperator window = new DBSPWindowOperator(
                    operator.getNode(), true, true, ix, apply);
            this.addOperator(window);
            replacement = new DBSPDeindexOperator(operator.getNode(), window);
        }

        if (replacement == operator) {
            this.replace(operator);
        } else {
            this.map(operator, replacement);
        }
    }

    @Override
    public void postorder(DBSPSumOperator operator) {
        // Treat like an identity function
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPOperator bound = this.processSum(expanded.replacement.to(DBSPSumOperator.class));
            if (bound != null && expanded.replacement != operator)
                this.markBound(operator, bound);
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    /** Compute an expression which projects a source expression into a subset of
     * its fields.  For example, the source may be v: (i32), while the sourceProjection
     * is (NonMonotone(i64), Monotone(i32)).  The destination may be (Monotone(i32)).
     * In this case the result will be just (v.0).
     * @param source                  Expression to project.
     * @param sourceProjection        Describes the type of source.
     *                                Source is the projection of some other expression.
     * @param destinationProjection   Projection desired for result.
     *                                The fields of destinationProjection are always
     *                                a subset of the fields in the sourceProjection.
     */
    DBSPExpression project(DBSPExpression source,
                           IMaybeMonotoneType sourceProjection,
                           IMaybeMonotoneType destinationProjection) {
        if (destinationProjection.is(ScalarMonotoneType.class)) {
            assert sourceProjection.is(ScalarMonotoneType.class);
            return source;
        } else if (destinationProjection.is(PartiallyMonotoneTuple.class)) {
            assert sourceProjection.is(PartiallyMonotoneTuple.class);
            PartiallyMonotoneTuple src = sourceProjection.to(PartiallyMonotoneTuple.class);
            PartiallyMonotoneTuple dest = destinationProjection.to(PartiallyMonotoneTuple.class);
            assert src.size() == dest.size();
            List<DBSPExpression> fields = new ArrayList<>();
            int currentIndex = 0;
            for (int i = 0; i < dest.size(); i++) {
                if (dest.getField(i).mayBeMonotone()) {
                    fields.add(source.field(currentIndex));
                }
                if (src.getField(i).mayBeMonotone()) {
                    currentIndex++;
                }
            }
            return new DBSPTupleExpression(source.getNode(), fields);
        }
        throw new InternalCompilerError("Not yet handled " + destinationProjection, source.getNode());
    }

    /** Apply MIN pointwise to two expressions */
    DBSPExpression min(DBSPExpression left,
                       DBSPExpression right) {
        assert left.getType().sameTypeIgnoringNullability(right.getType());
        if (left.getType().is(DBSPTypeBaseType.class)) {
            return ExpressionCompiler.makeBinaryExpression(
                    left.getNode(), left.getType(), DBSPOpcode.MIN, left, right);
        } else if (left.getType().is(DBSPTypeTupleBase.class)) {
            DBSPTypeTupleBase lt = left.getType().to(DBSPTypeTupleBase.class);
            DBSPExpression[] mins = new DBSPExpression[lt.size()];
            for (int i = 0; i < lt.size(); i++) {
                mins[i] = min(left.field(i), right.field(i));
            }
            return lt.makeTuple(mins);
        }
        throw new InternalCompilerError("MIN of expressions of type " + left.getType() + " not yet supported",
                left.getNode());
    }

    /**
     * Create and insert an operator which projects the output of limit.
     *
     * @param limit       The output of this operator is being projected.
     * @param source      Monotonicity information about the output of limit.
     * @param destination Monotonicity information for the desired output.
     * @return The operator performing the projection.
     * The operator is inserted in the graph.
     */
    DBSPApplyOperator project(
            DBSPOperator limit, IMaybeMonotoneType source,
            IMaybeMonotoneType destination) {
        DBSPVariablePath var = this.getLimiterDataOutputType(limit).ref().var();
        DBSPExpression proj = this.project(var.deref(), source, destination);
        return this.createApply(limit, proj.closure(var.asParameter()));
    }

    @Nullable
    DBSPOperator processSum(DBSPSumOperator expanded) {
        MonotoneExpression monotoneValue = this.expansionMonotoneValues.get(expanded);
        if (monotoneValue == null || !monotoneValue.mayBeMonotone()) {
            this.nonMonotone(expanded);
            return null;
        }

        // Create a projection for each input
        // Collect input limits
        List<DBSPOperator> limiters = new ArrayList<>();
        List<IMaybeMonotoneType> mono = new ArrayList<>();
        for (DBSPOperator input: expanded.inputs) {
            DBSPOperator limiter = this.bound.get(input);
            if (limiter == null) {
                this.nonMonotone(expanded);
                return null;
            }
            limiters.add(limiter);
            MonotoneExpression me = this.expansionMonotoneValues.get(input);
            mono.add(Objects.requireNonNull(Monotonicity.getBodyType(Objects.requireNonNull(me))));
        }

        IMaybeMonotoneType out = Monotonicity.getBodyType(monotoneValue);
        DBSPType outputType = out.getProjectedType();
        assert outputType != null;

        // Same function everywhere
        DBSPVariablePath l = new DBSPVariablePath(outputType.ref());
        DBSPVariablePath r = new DBSPVariablePath(outputType.ref());
        DBSPClosureExpression min = this.min(l.deref(), r.deref())
                .closure(l.asParameter(), r.asParameter());

        // expand into a binary unbalanced tree (this could be a star if we had an applyN operator).
        DBSPOperator current = this.project(limiters.get(0), mono.get(0), out);
        for (int i = 1; i < expanded.inputs.size(); i++) {
            DBSPOperator next = this.project(limiters.get(i), mono.get(i), out);
            current = this.createApply2(current, next, min);
        }

        this.markBound(expanded, current);
        return current;
    }

    @Override
    public void postorder(DBSPViewOperator operator) {
        if (operator.hasLateness()) {
            ReplacementExpansion expanded = this.getReplacement(operator);
            // Treat like a source operator
            DBSPOperator replacement = this.processLateness(
                    operator, Objects.requireNonNull(expanded).replacement);
            if (replacement == operator) {
                super.postorder(operator);
            } else {
                this.map(operator, replacement);
            }
            return;
        }
        // Treat like an identity function
        ReplacementExpansion expanded = this.getReplacement(operator);
        if (expanded != null) {
            DBSPApplyOperator bound = this.addBounds(expanded.replacement, 0);
            if (bound != null && operator != expanded.replacement)
                this.markBound(operator, bound);
        } else {
            this.nonMonotone(operator);
        }
        super.postorder(operator);
    }

    @Override
    public void postorder(DBSPSinkOperator operator) {
        int monotoneFieldIndex = operator.metadata.emitFinalColumn;
        if (monotoneFieldIndex >= 0) {
            ReplacementExpansion expanded = this.getReplacement(operator);
            if (expanded != null) {
                DBSPOperator operatorFromExpansion = expanded.replacement;
                MonotoneExpression monotone = this.expansionMonotoneValues.get(operatorFromExpansion);
                if (monotone != null && monotone.mayBeMonotone()) {
                    DBSPOperator source = operatorFromExpansion.inputs.get(0);
                    DBSPOperator boundSource = Utilities.getExists(this.bound, source);

                    MonotoneClosureType monoClosure = monotone.getMonotoneType().to(MonotoneClosureType.class);
                    IMaybeMonotoneType bodyType = monoClosure.getBodyType();
                    assert bodyType.mayBeMonotone();
                    PartiallyMonotoneTuple tuple = bodyType.to(PartiallyMonotoneTuple.class);
                    if (!tuple.getField(monotoneFieldIndex).mayBeMonotone()) {
                        throw new CompilationError("Compiler could not infer a waterline for column " +
                                Utilities.singleQuote(operator.metadata.columns.get(monotoneFieldIndex).columnName),
                                operator.getNode());
                    }
                    int controlFieldIndex = 0;
                    assert monotoneFieldIndex < tuple.size();
                    for (int i = 0; i < monotoneFieldIndex; i++) {
                        IMaybeMonotoneType field = tuple.getField(i);
                        if (field.mayBeMonotone())
                            controlFieldIndex++;
                    }

                    DBSPTypeTupleBase dataType = operator.getOutputZSetType().elementType.to(DBSPTypeTupleBase.class);
                    DBSPVariablePath t = dataType.ref().var();
                    DBSPType tsType = dataType.getFieldType(monotoneFieldIndex);
                    DBSPExpression minimum = tsType.to(IsBoundedType.class).getMinValue();

                    // boundSource has a type (boolean, TupN), extract the corresponding
                    // value in the second tuple
                    DBSPVariablePath var = boundSource.outputType.ref().var();
                    DBSPExpression makePair = new DBSPRawTupleExpression(
                            DBSPTypeTypedBox.wrapTypedBox(minimum, false),
                            DBSPTypeTypedBox.wrapTypedBox(var.deref().field(1).field(controlFieldIndex), false));
                    DBSPApplyOperator apply = new DBSPApplyOperator(
                            operator.getNode(), makePair.closure(var.asParameter()), boundSource,
                            "(" + operator.getDerivedFrom() + ")");
                    this.addOperator(apply);

                    // Window requires data to be indexed
                    DBSPExpression field = t.deref().field(monotoneFieldIndex);
                    DBSPOperator ix = new DBSPMapIndexOperator(operator.getNode(),
                            new DBSPRawTupleExpression(
                                    field.applyCloneIfNeeded(),
                                    t.deref().applyCloneIfNeeded()).closure(t.asParameter()),
                            new DBSPTypeIndexedZSet(operator.getNode(),
                                    field.getType(), dataType), true,
                            this.mapped(operator.input()));
                    this.addOperator(ix);
                    DBSPWindowOperator window = new DBSPWindowOperator(
                            operator.getNode(), true, true, ix, apply);
                    this.addOperator(window);
                    DBSPOperator deindex = new DBSPDeindexOperator(operator.getNode(), window);
                    this.addOperator(deindex);
                    DBSPOperator sink = operator.withInputs(Linq.list(deindex), false);
                    this.map(operator, sink);
                    return;
                }
            }
            throw new CompilationError("Could not infer a waterline for column " +
                    Utilities.singleQuote(operator.metadata.columns.get(monotoneFieldIndex).columnName) +
                    " of view " + Utilities.singleQuote(operator.viewName) + " which has an " +
                    "'emit_final' annotation", operator.getNode());
        }
        super.postorder(operator);
    }
}