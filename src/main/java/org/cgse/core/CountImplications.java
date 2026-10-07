package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Binary implication graph compilation (the SAT inprocessing technique used by
 * OR-Tools' BinaryImplicationGraph). Exact resource bounds justify each edge;
 * SCCs expose equivalent literals and reachability exposes failed literals.
 * Every original weighted constraint remains in the model.
 */
final class CountImplications implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> distinct;
    private final BitSet[] forward, reverse;
    private final BitSet binary = new BitSet(), visited = new BitSet();
    private final List<Integer> order = new ArrayList<>();
    private final Deque<Integer> stack = new ArrayDeque<>();
    private final int[] next, component, first;
    private final long allowance;
    private long work, memory;
    private int phase, cursor, scan, group, origin, edges;
    private boolean complete;

    CountImplications(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        distinct = new HashSet<>(rows);
        allowance = Math.min(65_536, budget.remainingWork() / 32);
        int size = lower.length * 2;
        forward = new BitSet[size];
        reverse = new BitSet[size];
        next = new int[size];
        component = new int[size];
        first = new int[size];
        Arrays.fill(component, -1);
        Arrays.fill(first, -1);
        for (int i = 0; i < lower.length; i++) if (lower[i].signum() == 0 && BigInteger.ONE.equals(upper[i])) binary.set(i);
        long bytes = 2048 + 96L * size + 16L * size * ((size + 63L) / 64) + 192L * rows.size();
        if (lower.length > 512 || rows.size() > 2048 || binary.cardinality() < 2 || allowance < 1024 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        for (int i = 0; i < size; i++) {
            forward[i] = new BitSet(size);
            reverse[i] = new BitSet(size);
        }
    }

    boolean step() {
        if (complete) return true;
        charge();
        if (work >= allowance) return finish("work_limit");
        if (phase == 0) {
            if (cursor < rows.size() && work < allowance / 2) {
                extract(rows.get(cursor++));
                return false;
            }
            cursor = 0;
            phase = 1;
        }
        if (phase == 1) {
            if (stack.isEmpty()) {
                while (cursor < forward.length && visited.get(cursor)) cursor++;
                if (cursor == forward.length) {
                    cursor = order.size() - 1;
                    phase = 2;
                    return false;
                }
                visited.set(cursor);
                stack.push(cursor);
            }
            int v = stack.peek(), child = forward[v].nextSetBit(next[v]);
            if (child < 0) {
                stack.pop();
                order.add(v);
            } else {
                next[v] = child + 1;
                if (!visited.get(child)) {
                    visited.set(child);
                    stack.push(child);
                }
            }
            return false;
        }
        if (phase == 2) {
            if (stack.isEmpty()) {
                while (cursor >= 0 && component[order.get(cursor)] >= 0) cursor--;
                if (cursor < 0) {
                    phase = 3;
                    cursor = 0;
                    return false;
                }
                int v = order.get(cursor);
                component[v] = group++;
                stack.push(v);
            }
            int v = stack.pop();
            first[component[v]] = first[component[v]] < 0 ? v : Math.min(first[component[v]], v);
            for (int u = reverse[v].nextSetBit(0); u >= 0; u = reverse[v].nextSetBit(u + 1)) {
                charge();
                if (component[u] < 0) {
                    component[u] = component[v];
                    stack.push(u);
                }
            }
            return false;
        }
        if (phase == 3) {
            if (cursor == lower.length) {
                visited.clear();
                phase = 4;
                return false;
            }
            int id = cursor++;
            if (!binary.get(id)) return false;
            int literal = 2 * id + 1;
            if (component[literal] == component[literal ^ 1]) {
                forbid(literal);
                forbid(literal ^ 1);
                return finish("contradictory_literals");
            }
            int representative = first[component[literal]];
            if (representative / 2 != id) {
                BigInteger direction = (representative & 1) == 1 ? BigInteger.ONE.negate() : BigInteger.ONE;
                BigInteger rhs = (representative & 1) == 1 ? BigInteger.ZERO : BigInteger.ONE;
                add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE, representative / 2, direction), rhs));
                add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate(), representative / 2, direction.negate()), rhs.negate()));
            }
            return false;
        }
        if (stack.isEmpty()) {
            while (scan < forward.length && (!binary.get(scan / 2) || forward[scan].isEmpty())) scan++;
            if (scan == forward.length) return finish("complete");
            origin = scan++;
            visited.clear();
            visited.set(origin);
            stack.push(origin);
        }
        int v = stack.pop();
        for (int u = forward[v].nextSetBit(0); u >= 0; u = forward[v].nextSetBit(u + 1)) {
            charge();
            if (u == (origin ^ 1)) {
                forbid(origin);
                stack.clear();
                return false;
            }
            if (!visited.get(u)) {
                visited.set(u);
                stack.push(u);
            }
        }
        return false;
    }

    private void extract(ExactLinearProgram.Constraint row) {
        if (row.terms().size() > 128) return;
        BigInteger minimum = BigInteger.ZERO;
        List<Integer> literals = new ArrayList<>();
        List<BigInteger> costs = new ArrayList<>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            BigInteger bound = term.getValue().signum() < 0 ? upper[id] : lower[id];
            if (bound == null) return;
            minimum = minimum.add(term.getValue().multiply(bound));
            if (binary.get(id) && term.getValue().signum() != 0) {
                literals.add(2 * id + (term.getValue().signum() > 0 ? 1 : 0));
                costs.add(term.getValue().abs());
            }
        }
        BigInteger slack = row.upper().subtract(minimum);
        BigInteger largest = BigInteger.ZERO, second = BigInteger.ZERO;
        for (BigInteger cost : costs) {
            if (cost.compareTo(largest) > 0) {
                second = largest;
                largest = cost;
            } else second = second.max(cost);
        }
        if (largest.compareTo(slack) <= 0 && largest.add(second).compareTo(slack) <= 0) return;
        for (int i = 0; i < literals.size(); i++) {
            if (costs.get(i).compareTo(slack) > 0) edge(literals.get(i), literals.get(i) ^ 1);
            for (int j = i + 1; j < literals.size() && work < allowance / 2; j++) {
                charge();
                if (costs.get(i).add(costs.get(j)).compareTo(slack) > 0) {
                    edge(literals.get(i), literals.get(j) ^ 1);
                    edge(literals.get(j), literals.get(i) ^ 1);
                }
            }
        }
    }

    private void edge(int from, int to) {
        if (!forward[from].get(to)) {
            forward[from].set(to);
            reverse[to].set(from);
            edges++;
        }
    }

    private void forbid(int literal) {
        add(new ExactLinearProgram.Constraint(Map.of(literal / 2, (literal & 1) == 1 ? BigInteger.ONE : BigInteger.ONE.negate()),
                (literal & 1) == 1 ? BigInteger.ZERO : BigInteger.ONE.negate()));
    }

    private void add(ExactLinearProgram.Constraint row) {
        if (cuts.size() >= 128 || !distinct.add(row)) return;
        cuts.add(row);
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(String detail) {
        complete = true;
        if (!cuts.isEmpty()) {
            if (budget.proofJournal() != null) {
                var scope = new ArrayList<>(rows);
                for (int i = 0; i < lower.length; i++) {
                    scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                    if (upper[i] != null) scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
                }
                List<CountConflict> forbidden = new ArrayList<>();
                for (var row : cuts) {
                    Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                    row.terms().forEach((id, value) -> terms.put(id, value.negate()));
                    forbidden.add(new CountConflict(List.of(new ExactLinearProgram.Constraint(terms, row.upper().negate().subtract(BigInteger.ONE)))));
                }
                budget.proofJournal().add(CountProof.certificate("binary_implications", lower.length, scope, forbidden, null, false));
            }
            budget.note("count_implications", detail + "; edges=" + edges + "; necessary_rows=" + cuts.size() + "; work=" + work);
        }
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
