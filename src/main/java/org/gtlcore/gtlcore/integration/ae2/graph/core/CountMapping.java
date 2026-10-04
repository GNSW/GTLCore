package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Exact affine pullback of rows and forbidden conjunctions; never approximates a literal. */
final class CountMapping {

    record Expression(Map<Integer, BigInteger> terms, BigInteger constant) {

        Expression {
            terms = Map.copyOf(terms);
        }

        static Expression variable(int id) {
            return new Expression(Map.of(id, BigInteger.ONE), BigInteger.ZERO);
        }
    }

    private final List<Expression> coordinates;
    private final List<ExactLinearProgram.Constraint> guards;

    CountMapping(List<Expression> coordinates) {
        this(coordinates, List.of());
    }

    CountMapping(List<Expression> coordinates, List<ExactLinearProgram.Constraint> guards) {
        this.coordinates = List.copyOf(coordinates);
        this.guards = List.copyOf(guards);
    }

    List<Expression> coordinates() {
        return coordinates;
    }

    ExactLinearProgram.Constraint row(ExactLinearProgram.Constraint input, PlanningBudget budget) {
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        BigInteger upper = input.upper();
        for (var term : input.terms().entrySet()) {
            budget.check();
            Expression coordinate = coordinates.get(term.getKey());
            upper = upper.subtract(term.getValue().multiply(coordinate.constant));
            for (var entry : coordinate.terms.entrySet()) {
                budget.check();
                terms.merge(entry.getKey(), entry.getValue().multiply(term.getValue()), BigInteger::add);
            }
        }
        terms.values().removeIf(value -> value.signum() == 0);
        return new ExactLinearProgram.Constraint(terms, upper);
    }

    CountConflict conflict(CountConflict input, PlanningBudget budget) {
        List<ExactLinearProgram.Constraint> result = new ArrayList<>(guards);
        for (var assumption : input.assumptions()) result.add(row(assumption, budget));
        return new CountConflict(result);
    }

    List<CountConflict> conflicts(Collection<CountConflict> values, PlanningBudget budget) {
        List<CountConflict> result = new ArrayList<>();
        for (var value : values) result.add(conflict(value, budget));
        return result;
    }

    static CountMapping representatives(int[] indices) {
        return new CountMapping(Arrays.stream(indices).mapToObj(Expression::variable).toList());
    }

    static CountMapping sums(List<List<Integer>> groups, BigInteger[] shifts) {
        List<Expression> expressions = new ArrayList<>();
        for (var group : groups) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            BigInteger constant = BigInteger.ZERO;
            for (int id : group) {
                terms.put(id, BigInteger.ONE);
                constant = constant.subtract(shifts[id]);
            }
            expressions.add(new Expression(terms, constant));
        }
        return new CountMapping(expressions);
    }

    static long retain(List<CountConflict> destination, List<CountConflict> source, PlanningBudget budget) {
        long memory = 0;
        for (var conflict : source) {
            if (destination.size() >= 128) break;
            long bytes = 128 + conflict.assumptions().stream().mapToLong(row -> 128L + 96L * row.terms().size()).sum();
            if (!budget.tryReserve(bytes)) break;
            memory += bytes;
            destination.add(conflict);
        }
        return memory;
    }
}
