/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ql.optimizer;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ql.expression.Alias;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Expressions;
import org.elasticsearch.xpack.ql.expression.FieldAttribute;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.Nullability;
import org.elasticsearch.xpack.ql.expression.predicate.BinaryOperator;
import org.elasticsearch.xpack.ql.expression.predicate.Predicates;
import org.elasticsearch.xpack.ql.expression.predicate.Range;
import org.elasticsearch.xpack.ql.expression.predicate.logical.And;
import org.elasticsearch.xpack.ql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.ql.expression.predicate.logical.Or;
import org.elasticsearch.xpack.ql.expression.predicate.nulls.IsNotNull;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Div;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Mod;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Mul;
import org.elasticsearch.xpack.ql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.ql.expression.predicate.operator.comparison.NullEquals;
import org.elasticsearch.xpack.ql.expression.predicate.regex.Like;
import org.elasticsearch.xpack.ql.expression.predicate.regex.LikePattern;
import org.elasticsearch.xpack.ql.expression.predicate.regex.RLike;
import org.elasticsearch.xpack.ql.expression.predicate.regex.RLikePattern;
import org.elasticsearch.xpack.ql.optimizer.OptimizerRules.BooleanEqualsSimplification;
import org.elasticsearch.xpack.ql.optimizer.OptimizerRules.BooleanLiteralsOnTheRight;
import org.elasticsearch.xpack.ql.optimizer.OptimizerRules.BooleanSimplification;
import org.elasticsearch.xpack.ql.optimizer.OptimizerRules.CombineBinaryComparisons;
import org.elasticsearch.xpack.ql.optimizer.OptimizerRules.ConstantFolding;
import org.elasticsearch.xpack.ql.optimizer.OptimizerRules.PropagateEquals;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataType;
import org.elasticsearch.xpack.ql.type.EsField;
import org.elasticsearch.xpack.ql.util.StringUtils;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.xpack.ql.TestUtils.equalsOf;
import static org.elasticsearch.xpack.ql.TestUtils.greaterThanOf;
import static org.elasticsearch.xpack.ql.TestUtils.greaterThanOrEqualOf;
import static org.elasticsearch.xpack.ql.TestUtils.lessThanOf;
import static org.elasticsearch.xpack.ql.TestUtils.lessThanOrEqualOf;
import static org.elasticsearch.xpack.ql.TestUtils.notEqualsOf;
import static org.elasticsearch.xpack.ql.TestUtils.nullEqualsOf;
import static org.elasticsearch.xpack.ql.TestUtils.of;
import static org.elasticsearch.xpack.ql.TestUtils.rangeOf;
import static org.elasticsearch.xpack.ql.expression.Literal.FALSE;
import static org.elasticsearch.xpack.ql.expression.Literal.NULL;
import static org.elasticsearch.xpack.ql.expression.Literal.TRUE;
import static org.elasticsearch.xpack.ql.tree.Source.EMPTY;
import static org.elasticsearch.xpack.ql.type.DataTypes.BOOLEAN;
import static org.elasticsearch.xpack.ql.type.DataTypes.INTEGER;

public class OptimizerRulesTests extends ESTestCase {

    private static final Expression DUMMY_EXPRESSION = new DummyBooleanExpression(EMPTY, 0);

    private static final Literal ONE = L(1);
    private static final Literal TWO = L(2);
    private static final Literal THREE = L(3);
    private static final Literal FOUR = L(4);
    private static final Literal FIVE = L(5);
    private static final Literal SIX = L(6);

    public static class DummyBooleanExpression extends Expression {

        private final int id;

        public DummyBooleanExpression(Source source, int id) {
            super(source, Collections.emptyList());
            this.id = id;
        }

        @Override
        protected NodeInfo<? extends Expression> info() {
            return NodeInfo.create(this, DummyBooleanExpression::new, id);
        }

        @Override
        public Expression replaceChildren(List<Expression> newChildren) {
            throw new UnsupportedOperationException("this type of node doesn't have any children");
        }

        @Override
        public Nullability nullable() {
            return Nullability.FALSE;
        }

        @Override
        public DataType dataType() {
            return BOOLEAN;
        }

        @Override
        public int hashCode() {
            int h = getClass().hashCode();
            h = 31 * h + id;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            return id == ((DummyBooleanExpression) obj).id;
        }
    }

    private static Literal L(Object value) {
        return of(value);
    }

    private static FieldAttribute getFieldAttribute() {
        return getFieldAttribute("a");
    }

    private static FieldAttribute getFieldAttribute(String name) {
        return new FieldAttribute(EMPTY, name, new EsField(name + "f", INTEGER, emptyMap(), true));
    }

    //
    // Constant folding
    //

    public void testConstantFolding() {
        Expression exp = new Add(EMPTY, TWO, THREE);

        assertTrue(exp.foldable());
        Expression result = new ConstantFolding().rule(exp);
        assertTrue(result instanceof Literal);
        assertEquals(5, ((Literal) result).value());

        // check now with an alias
        result = new ConstantFolding().rule(new Alias(EMPTY, "a", exp));
        assertEquals("a", Expressions.name(result));
        assertEquals(Alias.class, result.getClass());
    }

    public void testConstantFoldingBinaryComparison() {
        assertEquals(FALSE, new ConstantFolding().rule(greaterThanOf(TWO, THREE)).canonical());
        assertEquals(FALSE, new ConstantFolding().rule(greaterThanOrEqualOf(TWO, THREE)).canonical());
        assertEquals(FALSE, new ConstantFolding().rule(equalsOf(TWO, THREE)).canonical());
        assertEquals(FALSE, new ConstantFolding().rule(nullEqualsOf(TWO, THREE)).canonical());
        assertEquals(FALSE, new ConstantFolding().rule(nullEqualsOf(TWO, NULL)).canonical());
        assertEquals(TRUE, new ConstantFolding().rule(notEqualsOf(TWO, THREE)).canonical());
        assertEquals(TRUE, new ConstantFolding().rule(lessThanOrEqualOf(TWO, THREE)).canonical());
        assertEquals(TRUE, new ConstantFolding().rule(lessThanOf(TWO, THREE)).canonical());
    }

    public void testConstantFoldingBinaryLogic() {
        assertEquals(FALSE,
                new ConstantFolding().rule(new And(EMPTY, greaterThanOf(TWO, THREE), TRUE)).canonical());
        assertEquals(TRUE,
                new ConstantFolding().rule(new Or(EMPTY, greaterThanOrEqualOf(TWO, THREE), TRUE)).canonical());
    }

    public void testConstantFoldingBinaryLogic_WithNullHandling() {
        assertEquals(Nullability.TRUE, new ConstantFolding().rule(new And(EMPTY, NULL, TRUE)).canonical().nullable());
        assertEquals(Nullability.TRUE, new ConstantFolding().rule(new And(EMPTY, TRUE, NULL)).canonical().nullable());
        assertEquals(FALSE, new ConstantFolding().rule(new And(EMPTY, NULL, FALSE)).canonical());
        assertEquals(FALSE, new ConstantFolding().rule(new And(EMPTY, FALSE, NULL)).canonical());
        assertEquals(Nullability.TRUE, new ConstantFolding().rule(new And(EMPTY, NULL, NULL)).canonical().nullable());

        assertEquals(TRUE, new ConstantFolding().rule(new Or(EMPTY, NULL, TRUE)).canonical());
        assertEquals(TRUE, new ConstantFolding().rule(new Or(EMPTY, TRUE, NULL)).canonical());
        assertEquals(Nullability.TRUE, new ConstantFolding().rule(new Or(EMPTY, NULL, FALSE)).canonical().nullable());
        assertEquals(Nullability.TRUE, new ConstantFolding().rule(new Or(EMPTY, FALSE, NULL)).canonical().nullable());
        assertEquals(Nullability.TRUE, new ConstantFolding().rule(new Or(EMPTY, NULL, NULL)).canonical().nullable());
    }

    public void testConstantFoldingRange() {
        assertEquals(true, new ConstantFolding().rule(rangeOf(FIVE, FIVE, true, L(10), false)).fold());
        assertEquals(false, new ConstantFolding().rule(rangeOf(FIVE, FIVE, false, L(10), false)).fold());
    }

    public void testConstantNot() {
        assertEquals(FALSE, new ConstantFolding().rule(new Not(EMPTY, TRUE)));
        assertEquals(TRUE, new ConstantFolding().rule(new Not(EMPTY, FALSE)));
    }

    public void testConstantFoldingLikes() {
        assertEquals(TRUE,
                new ConstantFolding().rule(new Like(EMPTY, of("test_emp"), new LikePattern("test%", (char) 0)))
                        .canonical());
        assertEquals(TRUE,
                new ConstantFolding().rule(new RLike(EMPTY, of("test_emp"), new RLikePattern("test.emp"))).canonical());
    }

    public void testArithmeticFolding() {
        assertEquals(10, foldOperator(new Add(EMPTY, L(7), THREE)));
        assertEquals(4, foldOperator(new Sub(EMPTY, L(7), THREE)));
        assertEquals(21, foldOperator(new Mul(EMPTY, L(7), THREE)));
        assertEquals(2, foldOperator(new Div(EMPTY, L(7), THREE)));
        assertEquals(1, foldOperator(new Mod(EMPTY, L(7), THREE)));
    }

    private static Object foldOperator(BinaryOperator<?, ?, ?, ?> b) {
        return ((Literal) new ConstantFolding().rule(b)).value();
    }

    //
    // Logical simplifications
    //

    public void testLiteralsOnTheRight() {
        Alias a = new Alias(EMPTY, "a", L(10));
        Expression result = new BooleanLiteralsOnTheRight().rule(equalsOf(FIVE, a));
        assertTrue(result instanceof Equals);
        Equals eq = (Equals) result;
        assertEquals(a, eq.left());
        assertEquals(FIVE, eq.right());

        a = new Alias(EMPTY, "a", L(10));
        result = new BooleanLiteralsOnTheRight().rule(nullEqualsOf(FIVE, a));
        assertTrue(result instanceof NullEquals);
        NullEquals nullEquals= (NullEquals) result;
        assertEquals(a, nullEquals.left());
        assertEquals(FIVE, nullEquals.right());
    }

    public void testBoolSimplifyOr() {
        BooleanSimplification simplification = new BooleanSimplification();

        assertEquals(TRUE, simplification.rule(new Or(EMPTY, TRUE, TRUE)));
        assertEquals(TRUE, simplification.rule(new Or(EMPTY, TRUE, DUMMY_EXPRESSION)));
        assertEquals(TRUE, simplification.rule(new Or(EMPTY, DUMMY_EXPRESSION, TRUE)));

        assertEquals(FALSE, simplification.rule(new Or(EMPTY, FALSE, FALSE)));
        assertEquals(DUMMY_EXPRESSION, simplification.rule(new Or(EMPTY, FALSE, DUMMY_EXPRESSION)));
        assertEquals(DUMMY_EXPRESSION, simplification.rule(new Or(EMPTY, DUMMY_EXPRESSION, FALSE)));
    }

    public void testBoolSimplifyAnd() {
        BooleanSimplification simplification = new BooleanSimplification();

        assertEquals(TRUE, simplification.rule(new And(EMPTY, TRUE, TRUE)));
        assertEquals(DUMMY_EXPRESSION, simplification.rule(new And(EMPTY, TRUE, DUMMY_EXPRESSION)));
        assertEquals(DUMMY_EXPRESSION, simplification.rule(new And(EMPTY, DUMMY_EXPRESSION, TRUE)));

        assertEquals(FALSE, simplification.rule(new And(EMPTY, FALSE, FALSE)));
        assertEquals(FALSE, simplification.rule(new And(EMPTY, FALSE, DUMMY_EXPRESSION)));
        assertEquals(FALSE, simplification.rule(new And(EMPTY, DUMMY_EXPRESSION, FALSE)));
    }

    public void testBoolCommonFactorExtraction() {
        BooleanSimplification simplification = new BooleanSimplification();

        Expression a1 = new DummyBooleanExpression(EMPTY, 1);
        Expression a2 = new DummyBooleanExpression(EMPTY, 1);
        Expression b = new DummyBooleanExpression(EMPTY, 2);
        Expression c = new DummyBooleanExpression(EMPTY, 3);

        Expression actual = new Or(EMPTY, new And(EMPTY, a1, b), new And(EMPTY, a2, c));
        Expression expected = new And(EMPTY, a1, new Or(EMPTY, b, c));

        assertEquals(expected, simplification.rule(actual));
    }

    public void testBoolEqualsSimplification() {
        BooleanEqualsSimplification s = new BooleanEqualsSimplification();

        assertEquals(DUMMY_EXPRESSION, s.rule(new Equals(EMPTY, DUMMY_EXPRESSION, TRUE)));
        assertEquals(new Not(EMPTY, DUMMY_EXPRESSION), s.rule(new Equals(EMPTY, DUMMY_EXPRESSION, FALSE)));

        assertEquals(new Not(EMPTY, DUMMY_EXPRESSION), s.rule(notEqualsOf(DUMMY_EXPRESSION, TRUE)));
        assertEquals(DUMMY_EXPRESSION, s.rule(notEqualsOf(DUMMY_EXPRESSION, FALSE)));

        assertEquals(NULL, s.rule(new Equals(EMPTY, NULL, TRUE)));
        assertEquals(new Not(EMPTY, NULL), s.rule(new Equals(EMPTY, NULL, FALSE)));

        assertEquals(new Not(EMPTY, NULL), s.rule(notEqualsOf(NULL, TRUE)));
        assertEquals(NULL, s.rule(notEqualsOf(NULL, FALSE)));
    }

    //
    // Range optimization
    //

    // 6 < a <= 5  -> FALSE
    public void testFoldExcludingRangeToFalse() {
        FieldAttribute fa = getFieldAttribute();

        Range r = rangeOf(fa, SIX, false, FIVE, true);
        assertTrue(r.foldable());
        assertEquals(Boolean.FALSE, r.fold());
    }

    // 6 < a <= 5.5 -> FALSE
    public void testFoldExcludingRangeWithDifferentTypesToFalse() {
        FieldAttribute fa = getFieldAttribute();

        Range r = rangeOf(fa, SIX, false, L(5.5d), true);
        assertTrue(r.foldable());
        assertEquals(Boolean.FALSE, r.fold());
    }

    // Conjunction

    public void testCombineBinaryComparisonsNotComparable() {
        FieldAttribute fa = getFieldAttribute();
        LessThanOrEqual lte = lessThanOrEqualOf(fa, SIX);
        LessThan lt = lessThanOf(fa, FALSE);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        And and = new And(EMPTY, lte, lt);
        Expression exp = rule.rule(and);
        assertEquals(exp, and);
    }

    // a <= 6 AND a < 5  -> a < 5
    public void testCombineBinaryComparisonsUpper() {
        FieldAttribute fa = getFieldAttribute();
        LessThanOrEqual lte = lessThanOrEqualOf(fa, SIX);
        LessThan lt = lessThanOf(fa, FIVE);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();

        Expression exp = rule.rule(new And(EMPTY, lte, lt));
        assertEquals(LessThan.class, exp.getClass());
        LessThan r = (LessThan) exp;
        assertEquals(FIVE, r.right());
    }

    // 6 <= a AND 5 < a  -> 6 <= a
    public void testCombineBinaryComparisonsLower() {
        FieldAttribute fa = getFieldAttribute();
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, SIX);
        GreaterThan gt = greaterThanOf(fa, FIVE);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();

        Expression exp = rule.rule(new And(EMPTY, gte, gt));
        assertEquals(GreaterThanOrEqual.class, exp.getClass());
        GreaterThanOrEqual r = (GreaterThanOrEqual) exp;
        assertEquals(SIX, r.right());
    }

    // 5 <= a AND 5 < a  -> 5 < a
    public void testCombineBinaryComparisonsInclude() {
        FieldAttribute fa = getFieldAttribute();
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, FIVE);
        GreaterThan gt = greaterThanOf(fa, FIVE);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();

        Expression exp = rule.rule(new And(EMPTY, gte, gt));
        assertEquals(GreaterThan.class, exp.getClass());
        GreaterThan r = (GreaterThan) exp;
        assertEquals(FIVE, r.right());
    }

    // 2 < a AND (2 <= a < 3) -> 2 < a < 3
    public void testCombineBinaryComparisonsAndRangeLower() {
        FieldAttribute fa = getFieldAttribute();

        GreaterThan gt = greaterThanOf(fa, TWO);
        Range range = rangeOf(fa, TWO, true, THREE, false);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(new And(EMPTY, gt, range));
        assertEquals(Range.class, exp.getClass());
        Range r = (Range)exp;
        assertEquals(TWO, r.lower());
        assertFalse(r.includeLower());
        assertEquals(THREE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a < 4 AND (1 < a < 3) -> 1 < a < 3
    public void testCombineBinaryComparisonsAndRangeUpper() {
        FieldAttribute fa = getFieldAttribute();

        LessThan lt = lessThanOf(fa, FOUR);
        Range range = rangeOf(fa, ONE, false, THREE, false);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(new And(EMPTY, range, lt));
        assertEquals(Range.class, exp.getClass());
        Range r = (Range)exp;
        assertEquals(ONE, r.lower());
        assertFalse(r.includeLower());
        assertEquals(THREE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a <= 2 AND (1 < a < 3) -> 1 < a <= 2
    public void testCombineBinaryComparisonsAndRangeUpperEqual() {
        FieldAttribute fa = getFieldAttribute();

        LessThanOrEqual lte = lessThanOrEqualOf(fa, TWO);
        Range range = rangeOf(fa, ONE, false, THREE, false);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(new And(EMPTY, lte, range));
        assertEquals(Range.class, exp.getClass());
        Range r = (Range)exp;
        assertEquals(ONE, r.lower());
        assertFalse(r.includeLower());
        assertEquals(TWO, r.upper());
        assertTrue(r.includeUpper());
    }

    // 3 <= a AND 4 < a AND a <= 7 AND a < 6 -> 4 < a < 6
    public void testCombineMultipleBinaryComparisons() {
        FieldAttribute fa = getFieldAttribute();
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, THREE);
        GreaterThan gt = greaterThanOf(fa, FOUR);
        LessThanOrEqual lte = lessThanOrEqualOf(fa, L(7));
        LessThan lt = lessThanOf(fa, SIX);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();

        Expression exp = rule.rule(new And(EMPTY, gte, new And(EMPTY, gt, new And(EMPTY, lt, lte))));
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(FOUR, r.lower());
        assertFalse(r.includeLower());
        assertEquals(SIX, r.upper());
        assertFalse(r.includeUpper());
    }

    // 3 <= a AND TRUE AND 4 < a AND a != 5 AND a <= 7 -> 4 < a <= 7 AND a != 5 AND TRUE
    public void testCombineMixedMultipleBinaryComparisons() {
        FieldAttribute fa = getFieldAttribute();
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, THREE);
        GreaterThan gt = greaterThanOf(fa, FOUR);
        LessThanOrEqual lte = lessThanOrEqualOf(fa, L(7));
        Expression ne = new Not(EMPTY, equalsOf(fa, FIVE));

        CombineBinaryComparisons rule = new CombineBinaryComparisons();

        // TRUE AND a != 5 AND 4 < a <= 7
        Expression exp = rule.rule(new And(EMPTY, gte, new And(EMPTY, TRUE, new And(EMPTY, gt, new And(EMPTY, ne, lte)))));
        assertEquals(And.class, exp.getClass());
        And and = ((And) exp);
        assertEquals(Range.class, and.right().getClass());
        Range r = (Range) and.right();
        assertEquals(FOUR, r.lower());
        assertFalse(r.includeLower());
        assertEquals(L(7), r.upper());
        assertTrue(r.includeUpper());
    }

    // 1 <= a AND a < 5  -> 1 <= a < 5
    public void testCombineComparisonsIntoRange() {
        FieldAttribute fa = getFieldAttribute();
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, ONE);
        LessThan lt = lessThanOf(fa, FIVE);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(new And(EMPTY, gte, lt));
        assertEquals(Range.class, rule.rule(exp).getClass());

        Range r = (Range) exp;
        assertEquals(ONE, r.lower());
        assertTrue(r.includeLower());
        assertEquals(FIVE, r.upper());
        assertFalse(r.includeUpper());
    }

    // 1 < a AND a < 3 AND 2 < b AND b < 4 AND c < 4 -> (1 < a < 3) AND (2 < b < 4) AND c < 4
    public void testCombineMultipleComparisonsIntoRange() {
        FieldAttribute fa = getFieldAttribute("a");
        FieldAttribute fb = getFieldAttribute("b");
        FieldAttribute fc = getFieldAttribute("c");

        ZoneId zoneId = randomZone();
        GreaterThan agt1 = new GreaterThan(EMPTY, fa, ONE, zoneId);
        LessThan alt3 = new LessThan(EMPTY, fa, THREE, zoneId);
        GreaterThan bgt2 = new GreaterThan(EMPTY, fb, TWO, zoneId);
        LessThan blt4 = new LessThan(EMPTY, fb, FOUR, zoneId);
        LessThan clt4 = new LessThan(EMPTY, fc, FOUR, zoneId);

        Expression inputAnd = Predicates.combineAnd(Arrays.asList(agt1, alt3, bgt2, blt4, clt4));

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression outputAnd = rule.rule(inputAnd);

        Range agt1lt3 = new Range(EMPTY, fa, ONE, false, THREE, false, zoneId);
        Range bgt2lt4 = new Range(EMPTY, fb, TWO, false, FOUR, false, zoneId);

        // The actual outcome is (c < 4) AND (1 < a < 3) AND (2 < b < 4), due to the way the Expression types are combined in the Optimizer
        Expression expectedAnd = Predicates.combineAnd(Arrays.asList(clt4, agt1lt3, bgt2lt4));

        assertTrue(outputAnd.semanticEquals(expectedAnd));
    }

    // (2 < a < 3) AND (1 < a < 4) -> (2 < a < 3)
    public void testCombineBinaryComparisonsConjunctionOfIncludedRange() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, ONE, false, FOUR, false);

        And and = new And(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(r1, exp);
    }

    // (2 < a < 3) AND a < 2 -> 2 < a < 2
    public void testCombineBinaryComparisonsConjunctionOfNonOverlappingBoundaries() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, ONE, false, TWO, false);

        And and = new And(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(TWO, r.lower());
        assertFalse(r.includeLower());
        assertEquals(TWO, r.upper());
        assertFalse(r.includeUpper());
        assertEquals(Boolean.FALSE, r.fold());
    }

    // (2 < a < 3) AND (2 < a <= 3) -> 2 < a < 3
    public void testCombineBinaryComparisonsConjunctionOfUpperEqualsOverlappingBoundaries() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, TWO, false, THREE, true);

        And and = new And(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(r1, exp);
    }

    // (2 < a < 3) AND (1 < a < 3) -> 2 < a < 3
    public void testCombineBinaryComparisonsConjunctionOverlappingUpperBoundary() {
        FieldAttribute fa = getFieldAttribute();

        Range r2 = rangeOf(fa, TWO, false, THREE, false);
        Range r1 = rangeOf(fa, ONE, false, THREE, false);

        And and = new And(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(r2, exp);
    }

    // (2 < a <= 3) AND (1 < a < 3) -> 2 < a < 3
    public void testCombineBinaryComparisonsConjunctionWithDifferentUpperLimitInclusion() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, ONE, false, THREE, false);
        Range r2 = rangeOf(fa, TWO, false, THREE, true);

        And and = new And(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(TWO, r.lower());
        assertFalse(r.includeLower());
        assertEquals(THREE, r.upper());
        assertFalse(r.includeUpper());
    }

    // (0 < a <= 1) AND (0 <= a < 2) -> 0 < a <= 1
    public void testRangesOverlappingConjunctionNoLowerBoundary() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, L(0), false, ONE, true);
        Range r2 = rangeOf(fa, L(0), true, TWO, false);

        And and = new And(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(r1, exp);
    }

    // a != 2 AND 3 < a < 5 -> 3 < a < 5
    public void testCombineBinaryComparisonsConjunction_Neq2AndRangeGt3Lt5() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        Range range = rangeOf(fa, THREE, false, FIVE, false);
        And and = new And(EMPTY, range, neq);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(THREE, r.lower());
        assertFalse(r.includeLower());
        assertEquals(FIVE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a != 2 AND 0 < a < 1 -> 0 < a < 1
    public void testCombineBinaryComparisonsConjunction_Neq2AndRangeGt0Lt1() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        Range range = rangeOf(fa, L(0), false, ONE, false);
        And and = new And(EMPTY, neq, range);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(L(0), r.lower());
        assertFalse(r.includeLower());
        assertEquals(ONE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a != 2 AND 2 <= a < 3 -> 2 < a < 3
    public void testCombineBinaryComparisonsConjunction_Neq2AndRangeGte2Lt3() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        Range range = rangeOf(fa, TWO, true, THREE, false);
        And and = new And(EMPTY, neq, range);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(TWO, r.lower());
        assertFalse(r.includeLower());
        assertEquals(THREE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a != 3 AND 2 < a <= 3 -> 2 < a < 3
    public void testCombineBinaryComparisonsConjunction_Neq3AndRangeGt2Lte3() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, THREE);
        Range range = rangeOf(fa, TWO, false, THREE, true);
        And and = new And(EMPTY, neq, range);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(TWO, r.lower());
        assertFalse(r.includeLower());
        assertEquals(THREE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a != 2 AND 1 < a < 3
    public void testCombineBinaryComparisonsConjunction_Neq2AndRangeGt1Lt3() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        Range range = rangeOf(fa, ONE, false, THREE, false);
        And and = new And(EMPTY, neq, range);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(And.class, exp.getClass()); // can't optimize
    }

    // a != 2 AND a > 3 -> a > 3
    public void testCombineBinaryComparisonsConjunction_Neq2AndGt3() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        GreaterThan gt = greaterThanOf(fa, THREE);
        And and = new And(EMPTY, neq, gt);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(gt, exp);
    }

    // a != 2 AND a >= 2 -> a > 2
    public void testCombineBinaryComparisonsConjunction_Neq2AndGte2() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, TWO);
        And and = new And(EMPTY, neq, gte);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(GreaterThan.class, exp.getClass());
        GreaterThan gt = (GreaterThan) exp;
        assertEquals(TWO, gt.right());
    }

    // a != 2 AND a >= 1 -> nop
    public void testCombineBinaryComparisonsConjunction_Neq2AndGte1() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, ONE);
        And and = new And(EMPTY, neq, gte);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(And.class, exp.getClass()); // can't optimize
    }

    // a != 2 AND a <= 3 -> nop
    public void testCombineBinaryComparisonsConjunction_Neq2AndLte3() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        LessThanOrEqual lte = lessThanOrEqualOf(fa, THREE);
        And and = new And(EMPTY, neq, lte);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(and, exp); // can't optimize
    }

    // a != 2 AND a <= 2 -> a < 2
    public void testCombineBinaryComparisonsConjunction_Neq2AndLte2() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        LessThanOrEqual lte = lessThanOrEqualOf(fa, TWO);
        And and = new And(EMPTY, neq, lte);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(LessThan.class, exp.getClass());
        LessThan lt = (LessThan) exp;
        assertEquals(TWO, lt.right());
    }

    // a != 2 AND a <= 1 -> a <= 1
    public void testCombineBinaryComparisonsConjunction_Neq2AndLte1() {
        FieldAttribute fa = getFieldAttribute();

        NotEquals neq = notEqualsOf(fa, TWO);
        LessThanOrEqual lte = lessThanOrEqualOf(fa, ONE);
        And and = new And(EMPTY, neq, lte);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(and);
        assertEquals(lte, exp);
    }

    // Disjunction

    public void testCombineBinaryComparisonsDisjunctionNotComparable() {
        FieldAttribute fa = getFieldAttribute();

        GreaterThan gt1 = greaterThanOf(fa, ONE);
        GreaterThan gt2 = greaterThanOf(fa, FALSE);

        Or or = new Or(EMPTY, gt1, gt2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(exp, or);
    }


    // 2 < a OR 1 < a OR 3 < a -> 1 < a
    public void testCombineBinaryComparisonsDisjunctionLowerBound() {
        FieldAttribute fa = getFieldAttribute();

        GreaterThan gt1 = greaterThanOf(fa, ONE);
        GreaterThan gt2 = greaterThanOf(fa, TWO);
        GreaterThan gt3 = greaterThanOf(fa, THREE);

        Or or = new Or(EMPTY, gt1, new Or(EMPTY, gt2, gt3));

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(GreaterThan.class, exp.getClass());

        GreaterThan gt = (GreaterThan) exp;
        assertEquals(ONE, gt.right());
    }

    // 2 < a OR 1 < a OR 3 <= a -> 1 < a
    public void testCombineBinaryComparisonsDisjunctionIncludeLowerBounds() {
        FieldAttribute fa = getFieldAttribute();

        GreaterThan gt1 = greaterThanOf(fa, ONE);
        GreaterThan gt2 = greaterThanOf(fa, TWO);
        GreaterThanOrEqual gte3 = greaterThanOrEqualOf(fa, THREE);

        Or or = new Or(EMPTY, new Or(EMPTY, gt1, gt2), gte3);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(GreaterThan.class, exp.getClass());

        GreaterThan gt = (GreaterThan) exp;
        assertEquals(ONE, gt.right());
    }

    // a < 1 OR a < 2 OR a < 3 ->  a < 3
    public void testCombineBinaryComparisonsDisjunctionUpperBound() {
        FieldAttribute fa = getFieldAttribute();

        LessThan lt1 = lessThanOf(fa, ONE);
        LessThan lt2 = lessThanOf(fa, TWO);
        LessThan lt3 = lessThanOf(fa, THREE);

        Or or = new Or(EMPTY, new Or(EMPTY, lt1, lt2), lt3);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(LessThan.class, exp.getClass());

        LessThan lt = (LessThan) exp;
        assertEquals(THREE, lt.right());
    }

    // a < 2 OR a <= 2 OR a < 1 ->  a <= 2
    public void testCombineBinaryComparisonsDisjunctionIncludeUpperBounds() {
        FieldAttribute fa = getFieldAttribute();

        LessThan lt1 = lessThanOf(fa, ONE);
        LessThan lt2 = lessThanOf(fa, TWO);
        LessThanOrEqual lte2 = lessThanOrEqualOf(fa, TWO);

        Or or = new Or(EMPTY, lt2, new Or(EMPTY, lte2, lt1));

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(LessThanOrEqual.class, exp.getClass());

        LessThanOrEqual lte = (LessThanOrEqual) exp;
        assertEquals(TWO, lte.right());
    }

    // a < 2 OR 3 < a OR a < 1 OR 4 < a ->  a < 2 OR 3 < a
    public void testCombineBinaryComparisonsDisjunctionOfLowerAndUpperBounds() {
        FieldAttribute fa = getFieldAttribute();

        LessThan lt1 = lessThanOf(fa, ONE);
        LessThan lt2 = lessThanOf(fa, TWO);

        GreaterThan gt3 = greaterThanOf(fa, THREE);
        GreaterThan gt4 = greaterThanOf(fa, FOUR);

        Or or = new Or(EMPTY, new Or(EMPTY, lt2, gt3), new Or(EMPTY, lt1, gt4));

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(Or.class, exp.getClass());

        Or ro = (Or) exp;

        assertEquals(LessThan.class, ro.left().getClass());
        LessThan lt = (LessThan) ro.left();
        assertEquals(TWO, lt.right());
        assertEquals(GreaterThan.class, ro.right().getClass());
        GreaterThan gt = (GreaterThan) ro.right();
        assertEquals(THREE, gt.right());
    }

    // (2 < a < 3) OR (1 < a < 4) -> (1 < a < 4)
    public void testCombineBinaryComparisonsDisjunctionOfIncludedRangeNotComparable() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, ONE, false, FALSE, false);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(or, exp);
    }

    // (2 < a < 3) OR (1 < a < 4) -> (1 < a < 4)
    public void testCombineBinaryComparisonsDisjunctionOfIncludedRange() {
        FieldAttribute fa = getFieldAttribute();


        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, ONE, false, FOUR, false);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(Range.class, exp.getClass());

        Range r = (Range) exp;
        assertEquals(ONE, r.lower());
        assertFalse(r.includeLower());
        assertEquals(FOUR, r.upper());
        assertFalse(r.includeUpper());
    }

    // (2 < a < 3) OR (1 < a < 2) -> same
    public void testCombineBinaryComparisonsDisjunctionOfNonOverlappingBoundaries() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, ONE, false, TWO, false);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(or, exp);
    }

    // (2 < a < 3) OR (2 < a <= 3) -> 2 < a <= 3
    public void testCombineBinaryComparisonsDisjunctionOfUpperEqualsOverlappingBoundaries() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, TWO, false, THREE, false);
        Range r2 = rangeOf(fa, TWO, false, THREE, true);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(r2, exp);
    }

    // (2 < a < 3) OR (1 < a < 3) -> 1 < a < 3
    public void testCombineBinaryComparisonsOverlappingUpperBoundary() {
        FieldAttribute fa = getFieldAttribute();

        Range r2 = rangeOf(fa, TWO, false, THREE, false);
        Range r1 = rangeOf(fa, ONE, false, THREE, false);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(r1, exp);
    }

    // (2 < a <= 3) OR (1 < a < 3) -> same (the <= prevents the ranges from being combined)
    public void testCombineBinaryComparisonsWithDifferentUpperLimitInclusion() {
        FieldAttribute fa = getFieldAttribute();

        Range r1 = rangeOf(fa, ONE, false, THREE, false);
        Range r2 = rangeOf(fa, TWO, false, THREE, true);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(or, exp);
    }

    // (a = 1 AND b = 3 AND c = 4) OR (a = 2 AND b = 3 AND c = 4) -> (b = 3 AND c = 4) AND (a = 1 OR a = 2)
    public void testBooleanSimplificationCommonExpressionSubstraction() {
        FieldAttribute fa = getFieldAttribute("a");
        FieldAttribute fb = getFieldAttribute("b");
        FieldAttribute fc = getFieldAttribute("c");

        Expression a1 = equalsOf(fa, ONE);
        Expression a2 = equalsOf(fa, TWO);
        And common = new And(EMPTY, equalsOf(fb, THREE), equalsOf(fc, FOUR));
        And left = new And(EMPTY, a1, common);
        And right = new And(EMPTY, a2, common);
        Or or = new Or(EMPTY, left, right);

        Expression exp = new BooleanSimplification().rule(or);
        assertEquals(new And(EMPTY, common, new Or(EMPTY, a1, a2)), exp);
    }

    // (0 < a <= 1) OR (0 < a < 2) -> 0 < a < 2
    public void testRangesOverlappingNoLowerBoundary() {
        FieldAttribute fa = getFieldAttribute();

        Range r2 = rangeOf(fa, L(0), false, TWO, false);
        Range r1 = rangeOf(fa, L(0), false, ONE, true);

        Or or = new Or(EMPTY, r1, r2);

        CombineBinaryComparisons rule = new CombineBinaryComparisons();
        Expression exp = rule.rule(or);
        assertEquals(r2, exp);
    }


    // Equals & NullEquals

    // 1 <= a < 10 AND a == 1 -> a == 1
    public void testEliminateRangeByEqualsInInterval() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq1 = equalsOf(fa, ONE);
        Range r = rangeOf(fa, ONE, true, L(10), false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq1, r));
        assertEquals(eq1, rule.rule(exp));
    }

    // 1 <= a < 10 AND a <=> 1 -> a <=> 1
    public void testEliminateRangeByNullEqualsInInterval() {
        FieldAttribute fa = getFieldAttribute();
        NullEquals eq1 = nullEqualsOf(fa, ONE);
        Range r = rangeOf(fa, ONE, true, L(10), false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq1, r));
        assertEquals(eq1, rule.rule(exp));
    }


    // The following tests should work only to simplify filters and
    // not if the expressions are part of a projection
    // See: https://github.com/elastic/elasticsearch/issues/35859

    // a == 1 AND a == 2 -> FALSE
    public void testDualEqualsConjunction() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq1 = equalsOf(fa, ONE);
        Equals eq2 = equalsOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq1, eq2));
        assertEquals(FALSE, rule.rule(exp));
    }

    // a <=> 1 AND a <=> 2 -> FALSE
    public void testDualNullEqualsConjunction() {
        FieldAttribute fa = getFieldAttribute();
        NullEquals eq1 = nullEqualsOf(fa, ONE);
        NullEquals eq2 = nullEqualsOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq1, eq2));
        assertEquals(FALSE, rule.rule(exp));
    }

    // 1 < a < 10 AND a == 10 -> FALSE
    public void testEliminateRangeByEqualsOutsideInterval() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq1 = equalsOf(fa, L(10));
        Range r = rangeOf(fa, ONE, false, L(10), false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq1, r));
        assertEquals(FALSE, rule.rule(exp));
    }

    // 1 < a < 10 AND a <=> 10 -> FALSE
    public void testEliminateRangeByNullEqualsOutsideInterval() {
        FieldAttribute fa = getFieldAttribute();
        NullEquals eq1 = nullEqualsOf(fa, L(10));
        Range r = rangeOf(fa, ONE, false, L(10), false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq1, r));
        assertEquals(FALSE, rule.rule(exp));
    }

    // a != 3 AND a = 3 -> FALSE
    public void testPropagateEquals_VarNeq3AndVarEq3() {
        FieldAttribute fa = getFieldAttribute();
        NotEquals neq = notEqualsOf(fa, THREE);
        Equals eq = equalsOf(fa, THREE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, neq, eq));
        assertEquals(FALSE, rule.rule(exp));
    }

    // a != 4 AND a = 3 -> a = 3
    public void testPropagateEquals_VarNeq4AndVarEq3() {
        FieldAttribute fa = getFieldAttribute();
        NotEquals neq = notEqualsOf(fa, FOUR);
        Equals eq = equalsOf(fa, THREE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, neq, eq));
        assertEquals(Equals.class, exp.getClass());
        assertEquals(eq, rule.rule(exp));
    }

    // a = 2 AND a < 2 -> FALSE
    public void testPropagateEquals_VarEq2AndVarLt2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        LessThan lt = lessThanOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq, lt));
        assertEquals(FALSE, exp);
    }

    // a = 2 AND a <= 2 -> a = 2
    public void testPropagateEquals_VarEq2AndVarLte2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        LessThanOrEqual lt = lessThanOrEqualOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq, lt));
        assertEquals(eq, exp);
    }

    // a = 2 AND a <= 1 -> FALSE
    public void testPropagateEquals_VarEq2AndVarLte1() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        LessThanOrEqual lt = lessThanOrEqualOf(fa, ONE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq, lt));
        assertEquals(FALSE, exp);
    }

    // a = 2 AND a > 2 -> FALSE
    public void testPropagateEquals_VarEq2AndVarGt2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        GreaterThan gt = greaterThanOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq, gt));
        assertEquals(FALSE, exp);
    }

    // a = 2 AND a >= 2 -> a = 2
    public void testPropagateEquals_VarEq2AndVarGte2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        GreaterThanOrEqual gte = greaterThanOrEqualOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq, gte));
        assertEquals(eq, exp);
    }

    // a = 2 AND a > 3 -> FALSE
    public void testPropagateEquals_VarEq2AndVarLt3() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        GreaterThan gt = greaterThanOf(fa, THREE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new And(EMPTY, eq, gt));
        assertEquals(FALSE, exp);
    }

    // a = 2 AND a < 3 AND a > 1 AND a != 4 -> a = 2
    public void testPropagateEquals_VarEq2AndVarLt3AndVarGt1AndVarNeq4() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        LessThan lt = lessThanOf(fa, THREE);
        GreaterThan gt = greaterThanOf(fa, ONE);
        NotEquals neq = notEqualsOf(fa, FOUR);

        PropagateEquals rule = new PropagateEquals();
        Expression and = Predicates.combineAnd(Arrays.asList(eq, lt, gt, neq));
        Expression exp = rule.rule(and);
        assertEquals(eq, exp);
    }

    // a = 2 AND 1 < a < 3 AND a > 0 AND a != 4 -> a = 2
    public void testPropagateEquals_VarEq2AndVarRangeGt1Lt3AndVarGt0AndVarNeq4() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        Range range = rangeOf(fa, ONE, false, THREE, false);
        GreaterThan gt = greaterThanOf(fa, L(0));
        NotEquals neq = notEqualsOf(fa, FOUR);

        PropagateEquals rule = new PropagateEquals();
        Expression and = Predicates.combineAnd(Arrays.asList(eq, range, gt, neq));
        Expression exp = rule.rule(and);
        assertEquals(eq, exp);
    }

    // a = 2 OR a > 1 -> a > 1
    public void testPropagateEquals_VarEq2OrVarGt1() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        GreaterThan gt = greaterThanOf(fa, ONE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, gt));
        assertEquals(gt, exp);
    }

    // a = 2 OR a > 2 -> a >= 2
    public void testPropagateEquals_VarEq2OrVarGte2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        GreaterThan gt = greaterThanOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, gt));
        assertEquals(GreaterThanOrEqual.class, exp.getClass());
        GreaterThanOrEqual gte = (GreaterThanOrEqual) exp;
        assertEquals(TWO, gte.right());
    }

    // a = 2 OR a < 3 -> a < 3
    public void testPropagateEquals_VarEq2OrVarLt3() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        LessThan lt = lessThanOf(fa, THREE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, lt));
        assertEquals(lt, exp);
    }

    // a = 3 OR a < 3 -> a <= 3
    public void testPropagateEquals_VarEq3OrVarLt3() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, THREE);
        LessThan lt = lessThanOf(fa, THREE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, lt));
        assertEquals(LessThanOrEqual.class, exp.getClass());
        LessThanOrEqual lte = (LessThanOrEqual) exp;
        assertEquals(THREE, lte.right());
    }

    // a = 2 OR 1 < a < 3 -> 1 < a < 3
    public void testPropagateEquals_VarEq2OrVarRangeGt1Lt3() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        Range range = rangeOf(fa, ONE, false, THREE, false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, range));
        assertEquals(range, exp);
    }

    // a = 2 OR 2 < a < 3 -> 2 <= a < 3
    public void testPropagateEquals_VarEq2OrVarRangeGt2Lt3() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        Range range = rangeOf(fa, TWO, false, THREE, false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, range));
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(TWO, r.lower());
        assertTrue(r.includeLower());
        assertEquals(THREE, r.upper());
        assertFalse(r.includeUpper());
    }

    // a = 3 OR 2 < a < 3 -> 2 < a <= 3
    public void testPropagateEquals_VarEq3OrVarRangeGt2Lt3() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, THREE);
        Range range = rangeOf(fa, TWO, false, THREE, false);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, range));
        assertEquals(Range.class, exp.getClass());
        Range r = (Range) exp;
        assertEquals(TWO, r.lower());
        assertFalse(r.includeLower());
        assertEquals(THREE, r.upper());
        assertTrue(r.includeUpper());
    }

    // a = 2 OR a != 2 -> TRUE
    public void testPropagateEquals_VarEq2OrVarNeq2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        NotEquals neq = notEqualsOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, neq));
        assertEquals(TRUE, exp);
    }

    // a = 2 OR a != 5 -> a != 5
    public void testPropagateEquals_VarEq2OrVarNeq5() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        NotEquals neq = notEqualsOf(fa, FIVE);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(new Or(EMPTY, eq, neq));
        assertEquals(NotEquals.class, exp.getClass());
        NotEquals ne = (NotEquals) exp;
        assertEquals(FIVE, ne.right());
    }

    // a = 2 OR 3 < a < 4 OR a > 2 OR a!= 2 -> TRUE
    public void testPropagateEquals_VarEq2OrVarRangeGt3Lt4OrVarGt2OrVarNe2() {
        FieldAttribute fa = getFieldAttribute();
        Equals eq = equalsOf(fa, TWO);
        Range range = rangeOf(fa, THREE, false, FOUR, false);
        GreaterThan gt = greaterThanOf(fa, TWO);
        NotEquals neq = notEqualsOf(fa, TWO);

        PropagateEquals rule = new PropagateEquals();
        Expression exp = rule.rule(Predicates.combineOr(Arrays.asList(eq, range, neq, gt)));
        assertEquals(TRUE, exp);
    }

    //
    // Like / Regex
    //
    public void testMatchAllLikeToExist() throws Exception {
        for (String s : Arrays.asList("%", "%%", "%%%")) {
            LikePattern pattern = new LikePattern(s, (char) 0);
            FieldAttribute fa = getFieldAttribute();
            Like l = new Like(EMPTY, fa, pattern);
            Expression e = new OptimizerRules.ReplaceRegexMatch().rule(l);
            assertEquals(IsNotNull.class, e.getClass());
            IsNotNull inn = (IsNotNull) e;
            assertEquals(fa, inn.field());
        }
    }

    public void testMatchAllRLikeToExist() throws Exception {
            RLikePattern pattern = new RLikePattern(".*");
            FieldAttribute fa = getFieldAttribute();
            RLike l = new RLike(EMPTY, fa, pattern);
            Expression e = new OptimizerRules.ReplaceRegexMatch().rule(l);
            assertEquals(IsNotNull.class, e.getClass());
            IsNotNull inn = (IsNotNull) e;
            assertEquals(fa, inn.field());
    }

    public void testExactMatchLike() throws Exception {
        for (String s : Arrays.asList("ab", "ab0%", "ab0_c")) {
            LikePattern pattern = new LikePattern(s, '0');
            FieldAttribute fa = getFieldAttribute();
            Like l = new Like(EMPTY, fa, pattern);
            Expression e = new OptimizerRules.ReplaceRegexMatch().rule(l);
            assertEquals(Equals.class, e.getClass());
            Equals eq = (Equals) e;
            assertEquals(fa, eq.left());
            assertEquals(s.replace("0", StringUtils.EMPTY), eq.right().fold());
        }
    }

    public void testExactMatchRLike() throws Exception {
        RLikePattern pattern = new RLikePattern("abc");
        FieldAttribute fa = getFieldAttribute();
        RLike l = new RLike(EMPTY, fa, pattern);
        Expression e = new OptimizerRules.ReplaceRegexMatch().rule(l);
        assertEquals(Equals.class, e.getClass());
        Equals eq = (Equals) e;
        assertEquals(fa, eq.left());
        assertEquals("abc", eq.right().fold());
    }
}
