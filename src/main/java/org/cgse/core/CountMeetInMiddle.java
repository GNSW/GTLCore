package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact finite-domain matching. Every signature includes every remaining constraint. */
final class CountMeetInMiddle implements AutoCloseable {

    private static final int MAX_STATES = 1_048_576;

    private static final class LocalLimit extends RuntimeException {

        LocalLimit() {
            super(null, null, false, false);
        }
    }

    /** A scalar interval, or a proved disjoint at-most/exactly-one group. */
    private static final class Domain {

        final int[] variables;
        final boolean group;
        int size;
        boolean zero;
        int[][] allocations;

        Domain(int[] variables, int size, boolean group, boolean zero) {
            this.variables = variables;
            this.size = size;
            this.group = group;
            this.zero = zero;
        }

        BigInteger value(Map<Integer, BigInteger> row, int option) {
            if (allocations != null) {
                BigInteger result = BigInteger.ZERO;
                for (int i = 0; i < variables.length; i++) result = result.add(row.getOrDefault(variables[i], BigInteger.ZERO).multiply(BigInteger.valueOf(allocations[option][i])));
                return result;
            }
            if (!group) return row.getOrDefault(variables[0], BigInteger.ZERO).multiply(BigInteger.valueOf(option));
            return zero && option == 0 ? BigInteger.ZERO : row.getOrDefault(variables[option - (zero ? 1 : 0)], BigInteger.ZERO);
        }

        void assign(BigInteger[] counts, int option) {
            if (allocations != null) {
                for (int i = 0; i < variables.length; i++) counts[variables[i]] = counts[variables[i]].add(BigInteger.valueOf(allocations[option][i]));
            } else if (!group) counts[variables[0]] = counts[variables[0]].add(BigInteger.valueOf(option));
            else if (!zero || option > 0) {
                int id = variables[option - (zero ? 1 : 0)];
                counts[id] = counts[id].add(BigInteger.ONE);
            }
        }
    }

    private record Entry(BigInteger[] values, int code) {}

    private final List<ExactLinearProgram.Constraint> original;
    private final Map<Map<Integer, BigInteger>, BigInteger> rows = new LinkedHashMap<>();
    private final Map<List<BigInteger>, Integer> left = new HashMap<>();
    private final PlanningBudget budget;
    private final BigInteger[] lower, upper;
    private final List<Domain> domains = new ArrayList<>();
    private long allowance;
    private boolean costProbe;
    private BigInteger[][][] coefficients;
    private BigInteger[] goalLow, goalHigh, sum, counts;
    private Enumeration enumeratingLeft, enumeratingRight;
    private long entryBytes;
    private Entry[] ranged;
    private BigInteger[] queryLow, queryHigh;
    private int[] pointWidths;
    private int pointStates, pointCursor;
    private long memory, work;
    private int phase, rowIndex, split, leftStates, rightStates, trials, rangeCursor = -1;
    private boolean complete, infeasible, equalities;

    /** A cheap upper estimate for a bounded, weighted scalar-domain scout. */
    static long scoutWork(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                          BigInteger[] upper, PlanningBudget budget) {
        if (lower.length > 128 || rows.size() > 256) return 0;
        long bytes = 128L + 8L * lower.length + 64L * rows.size();
        if (!budget.tryReserve(bytes)) return 0;
        try {
            int[] sizes = new int[lower.length];
            int count = 0;
            for (int i = 0; i < lower.length; i++) {
                budget.check();
                if (upper[i] == null || lower[i].bitLength() > 1024 || upper[i].bitLength() > 1024) return 0;
                BigInteger size = upper[i].subtract(lower[i]).add(BigInteger.ONE);
                if (size.signum() <= 0 || size.compareTo(BigInteger.valueOf(MAX_STATES)) > 0) return 0;
                if (!size.equals(BigInteger.ONE)) sizes[count++] = size.intValueExact();
            }
            if (count == 0) return 0;
            Arrays.sort(sizes, 0, count);
            long left = 1, right = 1;
            for (int i = count - 1; i >= 0; i--) {
                budget.check();
                if (left <= right) left *= sizes[i];
                else right *= sizes[i];
                if (left > MAX_STATES || right > MAX_STATES) return 0;
            }
            boolean weighted = false;
            int bits = 1, activeRows = 0;
            long terms = 0;
            Set<Map<Integer, BigInteger>> dimensionsSeen = new HashSet<>();
            for (var row : rows) {
                if (row.upper().bitLength() > 1024) return 0;
                BigInteger maximum = BigInteger.ZERO;
                boolean rowWeighted = false;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    terms++;
                    int id = term.getKey();
                    BigInteger coefficient = term.getValue();
                    if (coefficient.bitLength() > 1024) return 0;
                    maximum = maximum.add(coefficient.multiply(coefficient.signum() > 0 ? upper[id] : lower[id]));
                    if (lower[id].equals(upper[id])) continue;
                    int width = coefficient.abs().bitLength();
                    bits = Math.max(bits, width + upper[id].subtract(lower[id]).bitLength() + 7);
                    rowWeighted |= width > 1;
                }
                if (maximum.compareTo(row.upper()) > 0) {
                    weighted |= rowWeighted;
                    if (dimensionsSeen.contains(row.terms())) continue;
                    long temporary = 128L + 96L * row.terms().size();
                    if (!budget.tryReserve(temporary)) return 0;
                    try {
                        Map<Integer, BigInteger> opposite = new HashMap<>();
                        for (var term : row.terms().entrySet()) {
                            budget.check();
                            opposite.put(term.getKey(), term.getValue().negate());
                        }
                        // Opposite inequalities query the same signature
                        // dimension. Counting an equality twice can incorrectly
                        // exclude a small table in favor of a costly LP search.
                        if (!dimensionsSeen.contains(opposite)) activeRows++;
                        dimensionsSeen.add(row.terms());
                    } finally {
                        budget.release(temporary);
                    }
                }
            }
            if (!weighted) return 0;
            // No exponential work is admitted just because the variable count
            // is small. Include signature dimension, integer payload and both
            // tables' traversal; the later exact matcher retains its own caps.
            long dimensions = Math.max(1, activeRows);
            long estimatedBytes = left * (160 + dimensions * (80 + (bits + 31L) / 8));
            long estimatedWork = Math.max(1024, 8 * (left + right) * dimensions + 8 * terms);
            if (estimatedBytes > budget.availableBytes() / 4 || estimatedWork > budget.remainingWork() / 16) return 0;
            // Traversal and pruning costs vary with the signatures actually
            // reached. Keep bounded headroom for that estimation error rather
            // than discarding a nearly finished table at the predicted cost.
            return Math.min(budget.remainingWork() / 16, 2 * estimatedWork);
        } finally {
            budget.release(bytes);
        }
    }

    CountMeetInMiddle(List<ExactLinearProgram.Constraint> original, BigInteger[] lower,
                      BigInteger[] upper, PlanningBudget budget) {
        this(original, lower, upper, budget, 12_000_000);
    }

    CountMeetInMiddle(List<ExactLinearProgram.Constraint> original, BigInteger[] lower,
                      BigInteger[] upper, PlanningBudget budget, long maxWork) {
        this.original = original;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        allowance = Math.min(maxWork, budget.remainingWork() / 3 * 2);
        long entries = original.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 1024 + 192L * entries + 256L * original.size() + 256L * lower.length;
        if (allowance < 1024 || !budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        try {
            charge();
            switch (phase) {
                case 0 -> {
                    if (rowIndex == original.size()) return prepare();
                    var row = original.get(rowIndex++);
                    Map<Integer, BigInteger> terms = new TreeMap<>();
                    BigInteger bound = row.upper(), minimum = BigInteger.ZERO, maximum = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) {
                        charge();
                        int id = term.getKey();
                        bound = bound.subtract(term.getValue().multiply(lower[id]));
                        if (lower[id].equals(upper[id]) || term.getValue().signum() == 0) continue;
                        if (upper[id] == null) return finish(false, "unbounded_domain");
                        BigInteger width = upper[id].subtract(lower[id]);
                        if (width.signum() < 0) return finish(true, "empty_domain");
                        terms.put(id, term.getValue());
                        minimum = minimum.add(term.getValue().min(BigInteger.ZERO).multiply(width));
                        maximum = maximum.add(term.getValue().max(BigInteger.ZERO).multiply(width));
                    }
                    if (minimum.compareTo(bound) > 0) return finish(true, "bounds_infeasible");
                    if (maximum.compareTo(bound) <= 0) return false;
                    var normalized = CountReduction.normalize(new ExactLinearProgram.Constraint(terms, bound));
                    rows.merge(normalized.terms(), normalized.upper(), BigInteger::min);
                }
                case 1 -> {
                    if (!enumeratingLeft.step()) return false;
                    if (enumeratingLeft.done) {
                        if (!equalities && pointWidths == null) {
                            ranged = new Entry[left.size()];
                            int i = 0;
                            for (var entry : left.entrySet()) ranged[i++] = new Entry(entry.getKey().toArray(BigInteger[]::new), entry.getValue());
                            Arrays.sort(ranged, (a, b) -> {
                                charge();
                                return a.values()[0].compareTo(b.values()[0]);
                            });
                            left.clear();
                        }
                        phase = 2;
                    } else {
                        List<BigInteger> signature = List.of(enumeratingLeft.values.clone());
                        if (!left.containsKey(signature)) {
                            if (!budget.tryReserve(entryBytes)) return finish(false, "memory_limit");
                            memory += entryBytes;
                            left.put(signature, enumeratingLeft.code);
                        }
                        enumeratingLeft.consume();
                    }
                }
                case 2 -> {
                    if (!enumeratingRight.step()) return false;
                    if (enumeratingRight.done) return finish(true, "exhaustive_infeasible");
                    sum = enumeratingRight.values;
                    Integer matched;
                    if (equalities || pointWidths != null) {
                        BigInteger[] complement = new BigInteger[sum.length];
                        int option = pointCursor;
                        for (int d = 0; d < sum.length; d++) {
                            charge();
                            if (pointWidths == null) complement[d] = goalHigh[d].subtract(sum[d]);
                            else {
                                complement[d] = goalLow[d].add(BigInteger.valueOf(option % pointWidths[d])).subtract(sum[d]);
                                option /= pointWidths[d];
                            }
                        }
                        matched = left.get(List.of(complement));
                        if (pointWidths != null && matched == null && ++pointCursor < pointStates) return false;
                        pointCursor = 0;
                    } else {
                        if (rangeCursor < 0) beginRange();
                        // Yield between candidates; broad intervals obey the same
                        // scheduler slices and work accounting as exact matching.
                        if (rangeCursor < ranged.length && (queryHigh[0] == null || ranged[rangeCursor].values()[0].compareTo(queryHigh[0]) <= 0)) {
                            Entry candidate = ranged[rangeCursor++];
                            if (!inside(candidate.values())) return false;
                            matched = candidate.code();
                        } else matched = null;
                    }
                    if (matched != null) {
                        counts = lower.clone();
                        decode(matched, 0, split);
                        decode(enumeratingRight.code, split, domains.size());
                        rowIndex = 0;
                        phase = 3;
                    } else {
                        rangeCursor = -1;
                        enumeratingRight.consume();
                    }
                }
                case 3 -> {
                    if (rowIndex == original.size()) return finish(false, "witness");
                    var row = original.get(rowIndex++);
                    BigInteger total = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) {
                        charge();
                        total = total.add(term.getValue().multiply(counts[term.getKey()]));
                    }
                    if (total.compareTo(row.upper()) > 0) throw new IllegalStateException("Finite-domain witness violates original row");
                }
                default -> throw new IllegalStateException("Invalid count matching phase");
            }
            return false;
        } catch (LocalLimit limit) {
            counts = null;
            return finish(false, costProbe ? "estimated_cost_probe_limit" : "work_limit");
        }
    }

    private boolean prepare() {
        BitSet grouped = new BitSet();
        for (var row : rows.entrySet()) {
            if (row.getValue().signum() <= 0 || row.getValue().compareTo(BigInteger.valueOf(64)) > 0 || row.getKey().size() < 2 ||
                    row.getValue().compareTo(BigInteger.ONE) > 0 && row.getKey().size() > 8)
                continue;
            boolean eligible = true;
            for (var term : row.getKey().entrySet()) {
                charge();
                int id = term.getKey();
                if (grouped.get(id) || !term.getValue().equals(BigInteger.ONE) || upper[id] == null || upper[id].subtract(lower[id]).compareTo(row.getValue()) < 0) eligible = false;
            }
            if (!eligible) continue;
            Map<Integer, BigInteger> opposite = new TreeMap<>();
            row.getKey().forEach((id, value) -> opposite.put(id, value.negate()));
            boolean optional = !row.getValue().negate().equals(rows.get(opposite));
            int[] ids = row.getKey().keySet().stream().mapToInt(Integer::intValue).toArray();
            if (row.getValue().equals(BigInteger.ONE)) domains.add(new Domain(ids, ids.length + (optional ? 1 : 0), true, optional));
            else {
                int capacity = row.getValue().intValueExact();
                BigInteger reverse = rows.get(opposite);
                int minimum = reverse == null ? 0 : reverse.negate().max(BigInteger.ZERO).min(BigInteger.valueOf(capacity + 1L)).intValueExact();
                List<int[]> options = new ArrayList<>();
                if (!compositions(options, new int[ids.length], 0, capacity, minimum)) continue;
                if (options.isEmpty()) return finish(true, "group_domain_empty");
                Domain domain = new Domain(ids, options.size(), true, false);
                domain.allocations = options.toArray(int[][]::new);
                domains.add(domain);
            }
            for (int id : ids) grouped.set(id);
        }
        for (int id = 0; id < lower.length; id++) if (!grouped.get(id) && !lower[id].equals(upper[id])) {
            if (upper[id] == null) return finish(false, "unbounded_domain");
            BigInteger size = upper[id].subtract(lower[id]).add(BigInteger.ONE);
            if (size.signum() <= 0) return finish(true, "empty_domain");
            if (size.compareTo(BigInteger.valueOf(MAX_STATES)) > 0) return finish(false, "domain_limit");
            domains.add(new Domain(new int[] { id }, size.intValueExact(), false, false));
        }
        if (domains.size() > 128) return finish(false, "domain_limit");
        // An optional saturated face can supply a witness, but never a proof of
        // infeasibility for the full domain. Other strategies retain zero choices.
        if (!fits() || domains.size() >= 8 && domains.stream().anyMatch(domain -> domain.zero)) {
            for (Domain domain : domains) if (domain.zero) {
                domain.zero = false;
                domain.size--;
                trials++;
            }
        }
        if (!fits()) return finish(false, "state_limit");
        List<Domain> a = new ArrayList<>(), b = new ArrayList<>();
        long na = 1, nb = 1;
        domains.sort(Comparator.comparingInt((Domain domain) -> domain.size).reversed());
        for (Domain domain : domains) {
            if (na <= nb) {
                a.add(domain);
                na *= domain.size;
            } else {
                b.add(domain);
                nb *= domain.size;
            }
        }
        domains.clear();
        domains.addAll(a);
        domains.addAll(b);
        split = a.size();
        leftStates = (int) na;
        rightStates = (int) nb;
        int width = domains.stream().mapToInt(domain -> domain.size).sum();
        long projectionBytes = 128L * width * rows.size();
        if (!budget.tryReserve(projectionBytes)) return finish(false, "projection_memory_limit");
        memory += projectionBytes;
        Map<List<BigInteger>, BigInteger> projected = new LinkedHashMap<>();
        for (var row : rows.entrySet()) {
            BigInteger bound = row.getValue(), minimum = BigInteger.ZERO, maximum = BigInteger.ZERO;
            BigInteger[] values = new BigInteger[width];
            int cursor = 0;
            for (Domain domain : domains) {
                BigInteger base = domain.value(row.getKey(), 0), lo = BigInteger.ZERO, hi = BigInteger.ZERO;
                bound = bound.subtract(base);
                for (int i = 0; i < domain.size; i++) {
                    charge();
                    BigInteger value = domain.value(row.getKey(), i).subtract(base);
                    values[cursor++] = value;
                    lo = lo.min(value);
                    hi = hi.max(value);
                }
                minimum = minimum.add(lo);
                maximum = maximum.add(hi);
            }
            if (minimum.compareTo(bound) > 0) return finish(true, "group_bounds_infeasible");
            if (maximum.compareTo(bound) <= 0) continue;
            BigInteger gcd = BigInteger.ZERO;
            for (BigInteger value : values) gcd = gcd.gcd(value);
            if (gcd.compareTo(BigInteger.ONE) > 0) {
                for (int i = 0; i < values.length; i++) values[i] = values[i].divide(gcd);
                BigInteger[] div = bound.divideAndRemainder(gcd);
                bound = div[1].signum() < 0 ? div[0].subtract(BigInteger.ONE) : div[0];
            }
            projected.merge(List.of(values), bound, BigInteger::min);
        }
        // A saturated sum of necessary rows forces each row to be tight.
        // Evaluate its true minimum over the compiled choices, including all
        // options in each group. This also exposes complementary multiway outputs.
        BigInteger[] total = new BigInteger[width];
        Arrays.fill(total, BigInteger.ZERO);
        BigInteger totalBound = BigInteger.ZERO;
        for (var row : projected.entrySet()) {
            totalBound = totalBound.add(row.getValue());
            for (int i = 0; i < width; i++) {
                charge();
                total[i] = total[i].add(row.getKey().get(i));
            }
        }
        BigInteger minimum = BigInteger.ZERO;
        int position = 0;
        for (Domain domain : domains) {
            BigInteger least = total[position];
            for (int i = 0; i < domain.size; i++) least = least.min(total[position++]);
            minimum = minimum.add(least);
        }
        if (minimum.compareTo(totalBound) > 0) return finish(true, "aggregate_infeasible");
        if (!projected.isEmpty() && minimum.equals(totalBound)) {
            for (var row : new ArrayList<>(projected.entrySet()))
                projected.merge(row.getKey().stream().map(BigInteger::negate).toList(), row.getValue().negate(), BigInteger::min);
        }
        List<List<BigInteger>> dimensions = new ArrayList<>();
        List<BigInteger> lows = new ArrayList<>(), highs = new ArrayList<>();
        Set<List<BigInteger>> included = new HashSet<>();
        for (var row : projected.entrySet()) {
            if (included.contains(row.getKey())) continue;
            var opposite = row.getKey().stream().map(BigInteger::negate).toList();
            BigInteger other = projected.get(opposite);
            dimensions.add(row.getKey());
            lows.add(other == null ? null : other.negate());
            highs.add(row.getValue());
            included.add(row.getKey());
            included.add(opposite);
        }
        if (dimensions.isEmpty()) {
            counts = lower.clone();
            decode(0, 0, domains.size());
            rowIndex = 0;
            phase = 3;
            return false;
        }
        int dims = dimensions.size(), bits = 1;
        if (dims > 128) return finish(false, "dimension_workspace_limit");
        for (var dimension : dimensions) for (BigInteger value : dimension) bits = Math.max(bits, value.abs().bitLength());
        entryBytes = 160L + dims * (80L + (bits + 31L) / 8);
        long estimatedBytes = entryBytes * leftStates;
        long estimatedWork = ((long) leftStates + rightStates) * dims;
        // Enumeration may prune or merge signatures, so retain a small scout
        // even when the unpruned table is too expensive. Do not spend most of
        // an order discovering an already predictable memory/work overrun.
        if (estimatedBytes / 2 > budget.availableBytes() && estimatedWork > allowance) {
            allowance = Math.min(allowance, work + 65536);
            costProbe = true;
        }
        budget.note("count_match_admission", "left=" + leftStates + "; right=" + rightStates +
                "; dimensions=" + dims + "; coefficient_bits=" + bits + "; estimated_bytes=" + estimatedBytes +
                "; estimated_work=" + estimatedWork + "; allowance=" + allowance + "; scout=" + costProbe);
        long bytes = 48L * dims * width + 256L * dims * domains.size();
        if (!budget.tryReserve(bytes)) return finish(false, "memory_limit");
        memory += bytes;
        coefficients = new BigInteger[domains.size()][][];
        int cursor = 0;
        for (int i = 0; i < domains.size(); i++) {
            coefficients[i] = new BigInteger[domains.get(i).size][dims];
            for (int option = 0; option < domains.get(i).size; option++, cursor++) for (int d = 0; d < dims; d++) coefficients[i][option][d] = dimensions.get(d).get(cursor);
        }
        goalLow = lows.toArray(BigInteger[]::new);
        goalHigh = highs.toArray(BigInteger[]::new);
        equalities = Arrays.equals(goalLow, goalHigh);
        if (!equalities) preparePointRange();
        sum = new BigInteger[dims];
        Arrays.fill(sum, BigInteger.ZERO);
        enumeratingLeft = new Enumeration(0, split, split, domains.size());
        enumeratingRight = new Enumeration(split, domains.size(), 0, split);
        phase = 1;
        return false;
    }

    /** Complete small simplex domains; no per-count Boolean expansion. */
    private boolean compositions(List<int[]> options, int[] values, int position, int remaining, int minimum) {
        charge();
        if (position == values.length) {
            if (minimum > 0) return true;
            if (options.size() >= 4096) return false;
            long bytes = 48L + 4L * values.length;
            if (!budget.tryReserve(bytes)) throw new LocalLimit();
            memory += bytes;
            options.add(values.clone());
            return true;
        }
        for (int value = position == values.length - 1 ? Math.max(0, minimum) : 0; value <= remaining; value++) {
            values[position] = value;
            if (!compositions(options, values, position + 1, remaining - value, minimum - value)) return false;
        }
        return true;
    }

    /** Small integer boxes need a few hash lookups, not a sort of every left state. */
    private void preparePointRange() {
        int[] widths = new int[goalLow.length];
        int states = 1;
        for (int d = 0; d < widths.length; d++) {
            charge();
            if (goalLow[d] == null || goalHigh[d] == null) return;
            BigInteger width = goalHigh[d].subtract(goalLow[d]).add(BigInteger.ONE);
            if (width.signum() <= 0 || width.compareTo(BigInteger.valueOf(64 / states)) > 0) return;
            widths[d] = width.intValueExact();
            states *= widths[d];
        }
        pointWidths = widths;
        pointStates = states;
    }

    private boolean fits() {
        long a = 1, b = 1;
        for (Domain domain : domains.stream().sorted(Comparator.comparingInt((Domain d) -> d.size).reversed()).toList()) {
            if (a <= b) a *= domain.size;
            else b *= domain.size;
            if (a > MAX_STATES || b > MAX_STATES) return false;
        }
        return true;
    }

    /** Enumerate only prefixes whose optimistic suffix still intersects every row. */
    private final class Enumeration {

        final int start, size;
        final int[] next, chosen, strides;
        final BigInteger[][] low, high;
        final BigInteger[][] reachable;
        final BigInteger[] values;
        int depth, code;
        boolean ready, done;

        Enumeration(int start, int end, int otherStart, int otherEnd) {
            this.start = start;
            size = end - start;
            next = new int[size + 1];
            chosen = new int[size];
            strides = new int[size];
            values = new BigInteger[goalHigh.length];
            Arrays.fill(values, BigInteger.ZERO);
            low = new BigInteger[size + 1][values.length];
            high = new BigInteger[size + 1][values.length];
            reachable = new BigInteger[size + 1][values.length];
            Arrays.fill(low[size], BigInteger.ZERO);
            Arrays.fill(high[size], BigInteger.ZERO);
            for (int i = otherStart; i < otherEnd; i++) addRange(i, low[size], high[size]);
            for (int i = size - 1; i >= 0; i--) {
                low[i] = low[i + 1].clone();
                high[i] = high[i + 1].clone();
                addRange(start + i, low[i], high[i]);
            }
            if (equalities && domains.stream().allMatch(domain -> domain.size <= 16)) {
                for (int d = 0; d < values.length; d++) {
                    BigInteger width = high[0][d].subtract(low[0][d]);
                    if (width.compareTo(BigInteger.valueOf(32768)) > 0) continue;
                    long bytes = (size + 1L) * (64 + (width.longValueExact() + 8) / 8);
                    if (!budget.tryReserve(bytes)) continue;
                    memory += bytes;
                    BigInteger set = BigInteger.ONE;
                    for (int i = otherStart; i < otherEnd; i++) set = extend(set, i, d);
                    reachable[size][d] = set;
                    for (int i = size - 1; i >= 0; i--) reachable[i][d] = extend(reachable[i + 1][d], start + i, d);
                }
            }
            int stride = 1;
            for (int i = 0; i < size; i++) {
                strides[i] = stride;
                stride *= domains.get(start + i).size;
            }
        }

        void addRange(int i, BigInteger[] lower, BigInteger[] upper) {
            for (int d = 0; d < values.length; d++) {
                BigInteger a = BigInteger.ZERO, b = BigInteger.ZERO;
                for (var option : coefficients[i]) {
                    charge();
                    a = a.min(option[d]);
                    b = b.max(option[d]);
                }
                lower[d] = lower[d].add(a);
                upper[d] = upper[d].add(b);
            }
        }

        /** Exact one-row suffix sums; their intersection remains an overapproximation. */
        BigInteger extend(BigInteger previous, int variable, int dimension) {
            BigInteger offset = BigInteger.ZERO;
            for (var option : coefficients[variable]) offset = offset.min(option[dimension]);
            BigInteger result = BigInteger.ZERO;
            Set<Integer> shifts = new HashSet<>();
            for (var option : coefficients[variable]) {
                charge();
                int shift = option[dimension].subtract(offset).intValueExact();
                if (shifts.add(shift)) result = result.or(previous.shiftLeft(shift));
            }
            return result;
        }

        boolean step() {
            if (done || ready) return true;
            for (int quantum = 0; quantum < 16; quantum++) {
                charge();
                if (depth == size) {
                    ready = true;
                    return true;
                }
                if (next[depth] == domains.get(start + depth).size) {
                    if (depth == 0) {
                        done = true;
                        return true;
                    }
                    depth--;
                    undo();
                    continue;
                }
                int option = next[depth]++;
                chosen[depth] = option;
                code += strides[depth] * option;
                boolean viable = true;
                int changed = 0;
                for (int d = 0; d < values.length; d++) {
                    charge();
                    values[d] = values[d].add(coefficients[start + depth][option][d]);
                    changed++;
                    if (goalHigh[d] != null && values[d].add(low[depth + 1][d]).compareTo(goalHigh[d]) > 0 ||
                            goalLow[d] != null && values[d].add(high[depth + 1][d]).compareTo(goalLow[d]) < 0) {
                        viable = false;
                        break;
                    }
                    BigInteger suffix = reachable[depth + 1][d];
                    if (suffix != null) {
                        charge();
                        BigInteger needed = goalHigh[d].subtract(values[d]).subtract(low[depth + 1][d]);
                        if (needed.signum() < 0 || needed.compareTo(BigInteger.valueOf(suffix.bitLength())) >= 0 || !suffix.testBit(needed.intValue())) {
                            viable = false;
                            break;
                        }
                    }
                }
                if (!viable) undo(changed);
                else {
                    depth++;
                    next[depth] = 0;
                }
            }
            return false;
        }

        void consume() {
            ready = false;
            if (size == 0) done = true;
            else {
                depth--;
                undo();
            }
        }

        void undo() {
            undo(values.length);
        }

        void undo(int changed) {
            int option = chosen[depth];
            code -= strides[depth] * option;
            for (int d = 0; d < changed; d++) {
                charge();
                values[d] = values[d].subtract(coefficients[start + depth][option][d]);
            }
        }
    }

    private void decode(int code, int start, int end) {
        for (int i = start; i < end; i++) {
            Domain domain = domains.get(i);
            domain.assign(counts, code % domain.size);
            code /= domain.size;
        }
    }

    private void beginRange() {
        queryLow = new BigInteger[sum.length];
        queryHigh = new BigInteger[sum.length];
        for (int d = 0; d < sum.length; d++) {
            charge();
            queryLow[d] = goalLow[d] == null ? null : goalLow[d].subtract(sum[d]);
            queryHigh[d] = goalHigh[d] == null ? null : goalHigh[d].subtract(sum[d]);
        }
        int lo = 0, hi = ranged.length;
        while (lo < hi) {
            charge();
            int mid = (lo + hi) >>> 1;
            if (queryLow[0] != null && ranged[mid].values()[0].compareTo(queryLow[0]) < 0) lo = mid + 1;
            else hi = mid;
        }
        rangeCursor = lo;
    }

    private boolean inside(BigInteger[] values) {
        for (int d = 0; d < values.length; d++) {
            charge();
            if (queryLow[d] != null && values[d].compareTo(queryLow[d]) < 0 || queryHigh[d] != null && values[d].compareTo(queryHigh[d]) > 0) return false;
        }
        return true;
    }

    private void charge() {
        budget.check();
        if (++work > allowance) throw new LocalLimit();
    }

    private boolean finish(boolean impossible, String detail) {
        infeasible = impossible && trials == 0;
        complete = true;
        budget.note("count_match", detail + "; choices=" + domains.size() + "; dimensions=" + (goalHigh == null ? 0 : goalHigh.length) +
                "; left_states=" + leftStates + "; right_states=" + rightStates + "; trial_counts=" + trials +
                "; proven_infeasible=" + infeasible + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return complete && counts != null ? counts.clone() : null;
    }

    boolean infeasible() {
        return complete && infeasible;
    }

    @Override
    public void close() {
        left.clear();
        rows.clear();
        budget.release(memory);
        memory = 0;
    }
}
