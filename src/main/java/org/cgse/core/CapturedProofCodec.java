// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Versioned, bounded data codec; no Java object deserialization or live key lookup. */
final class CapturedProofCodec {

    private static final int MAGIC = 0x43474431; // CGD1, separate from scoped CGP arithmetic proofs
    private static final long MAX_FILE = 64L << 20;
    private final CapturedProof.Guard guard = new CapturedProof.Guard(20_000_000, 128L << 20);

    static void write(Path path, CapturedProof.Certificate proof) throws IOException {
        try (var output = new DataOutputStream(new BufferedOutputStream(new FilterOutputStream(Files.newOutputStream(path)) {
            long remaining = MAX_FILE;
            @Override public void write(int value) throws IOException {
                if (--remaining < 0) throw new IOException("Captured proof exceeds file limit");
                out.write(value);
            }
            @Override public void write(byte[] values, int offset, int length) throws IOException {
                if (length > remaining) throw new IOException("Captured proof exceeds file limit");
                remaining -= length; out.write(values, offset, length);
            }
        }))) {
            new CapturedProofCodec().encode(output, proof);
        } catch (CapturedProof.Limit | IllegalArgumentException failure) {
            throw new IOException("Invalid or oversized captured proof", failure);
        }
    }

    static CapturedProof.Certificate read(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE) throw new IOException("Captured proof exceeds file limit");
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            var proof = new CapturedProofCodec().decode(input);
            if (input.read() != -1) throw new IOException("Trailing captured proof bytes");
            return proof;
        } catch (CapturedProof.Limit | IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("Invalid or oversized captured proof", failure);
        }
    }

    private void encode(DataOutputStream out, CapturedProof.Certificate proof) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(proof.snapshot().resources()); out.writeBoolean(proof.snapshot().complete());
        count(out, proof.snapshot().patterns().size(), 256);
        for (var pattern : proof.snapshot().patterns()) {
            text(out, pattern.id()); text(out, pattern.binding()); out.writeBoolean(pattern.external()); out.writeBoolean(pattern.complete());
            count(out, pattern.inputs().size(), 128);
            for (var input : pattern.inputs()) {
                out.writeLong(input.multiplier()); count(out, input.candidates().size(), 192);
                for (var candidate : input.candidates()) {
                    out.writeInt(candidate.stack().what()); out.writeLong(candidate.stack().amount());
                    out.writeInt(candidate.remaining() == null ? -1 : candidate.remaining());
                    out.writeBoolean(candidate.configuration()); out.writeBoolean(candidate.reusable());
                }
            }
            count(out, pattern.outputs().size(), 64);
            for (var output : pattern.outputs()) { out.writeInt(output.what()); out.writeLong(output.amount()); }
        }
        var request = proof.request();
        out.writeInt(request.target()); out.writeLong(request.amount());
        amounts(out, request.stock()); amounts(out, request.requiredSeeds());
        count(out, request.external().size(), 64);
        for (int key : new TreeSet<>(request.external())) out.writeInt(key);
        out.writeBoolean(request.preserve()); out.writeBoolean(request.forceCraft());
        out.writeLong(proof.coverage().revision()); out.writeBoolean(proof.coverage().complete());
        count(out, proof.coverage().domains().size(), 256);
        for (var domain : proof.coverage().domains()) {
            text(out, domain.id()); integer(out, domain.expanded()); integer(out, domain.total()); out.writeBoolean(domain.captureComplete());
            count(out, domain.additional().size(), 32);
            for (var extra : domain.additional()) integer(out, extra);
        }
        var witness = proof.witness(); out.writeBoolean(witness != null);
        if (witness == null) return;
        count(out, witness.recipes().size(), 512);
        for (var entry : witness.recipes().entrySet()) {
            text(out, entry.getKey()); var recipe = entry.getValue(); text(out, recipe.id()); text(out, recipe.binding());
            count(out, recipe.slots().size(), 192);
            for (var slot : recipe.slots()) {
                out.writeInt(slot.key()); out.writeLong(slot.amount()); out.writeInt(slot.inputSlot());
                out.writeBoolean(slot.configuration()); out.writeBoolean(slot.reusable());
            }
            amounts(out, recipe.outputs());
        }
        count(out, witness.program().size(), 192);
        for (var op : witness.program()) {
            out.writeByte(op.kind().ordinal()); out.writeBoolean(op.recipe() != null);
            if (op.recipe() != null) text(out, op.recipe());
            count(out, op.children().size(), 32);
            for (int child : op.children()) out.writeInt(child);
            out.writeLong(op.times());
        }
        count(out, witness.initial().size(), 96);
        for (var e : new TreeMap<>(witness.initial()).entrySet()) { out.writeInt(e.getKey()); integer(out, e.getValue()); }
        amounts(out, witness.seeds());
    }

    private CapturedProof.Certificate decode(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Unknown captured proof format");
        int resources = in.readInt(); boolean complete = in.readBoolean();
        var patterns = new ArrayList<CapturedProof.Pattern>();
        for (int n = count(in, 256); n > 0; n--) {
            String id = text(in), binding = text(in); boolean external = in.readBoolean(), captured = in.readBoolean();
            var inputs = new ArrayList<CapturedRecipe.Input<Integer>>();
            for (int m = count(in, 128); m > 0; m--) {
                long multiplier = in.readLong(); var candidates = new ArrayList<CapturedRecipe.Candidate<Integer>>();
                for (int k = count(in, 192); k > 0; k--) {
                    int key = in.readInt(); long amount = in.readLong(); int remaining = in.readInt();
                    if (remaining < -1) throw new IOException("Invalid return resource");
                    candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(key, amount),
                            remaining == -1 ? null : remaining, in.readBoolean(), in.readBoolean()));
                }
                inputs.add(new CapturedRecipe.Input<>(multiplier, candidates));
            }
            var outputs = new ArrayList<CapturedRecipe.Amount<Integer>>();
            for (int m = count(in, 64); m > 0; m--) outputs.add(new CapturedRecipe.Amount<>(in.readInt(), in.readLong()));
            patterns.add(new CapturedProof.Pattern(id, binding, inputs, outputs, external, captured));
        }
        int target = in.readInt(); long amount = in.readLong();
        var stock = amounts(in); var seeds = amounts(in); Set<Integer> external = new LinkedHashSet<>();
        for (int n = count(in, 64); n > 0; n--) if (!external.add(in.readInt())) throw new IOException("Duplicate external resource");
        var request = new CapturedProof.Request(target, amount, stock, external, seeds, in.readBoolean(), in.readBoolean());
        long revision = in.readLong(); boolean covered = in.readBoolean(); var domains = new ArrayList<CapturedCatalog.DomainCoverage>();
        for (int n = count(in, 256); n > 0; n--) {
            String id = text(in); BigInteger prefix = integer(in), total = integer(in); boolean captured = in.readBoolean();
            var extra = new ArrayList<BigInteger>();
            for (int m = count(in, 32); m > 0; m--) extra.add(integer(in));
            domains.add(new CapturedCatalog.DomainCoverage(id, prefix, total, extra, captured));
        }
        CapturedProof.Witness witness = null;
        if (in.readBoolean()) {
            Map<String, GraphRecipe<Integer>> recipes = new LinkedHashMap<>();
            for (int n = count(in, 512); n > 0; n--) {
                String name = text(in), id = text(in), binding = text(in); var slots = new ArrayList<GraphRecipe.Slot<Integer>>();
                for (int m = count(in, 192); m > 0; m--)
                    slots.add(new GraphRecipe.Slot<>(in.readInt(), in.readLong(), in.readInt(), in.readBoolean(), in.readBoolean()));
                if (recipes.put(name, new GraphRecipe<>(id, binding, slots, amounts(in))) != null) throw new IOException("Duplicate concrete recipe");
            }
            var program = new ArrayList<CapturedProof.Instruction>();
            for (int n = count(in, 192); n > 0; n--) {
                int kind = in.readUnsignedByte();
                if (kind >= CapturedProof.Kind.values().length) throw new IOException("Unknown witness opcode");
                String id = in.readBoolean() ? text(in) : null; var children = new ArrayList<Integer>();
                for (int m = count(in, 32); m > 0; m--) children.add(in.readInt());
                program.add(new CapturedProof.Instruction(CapturedProof.Kind.values()[kind], id, children, in.readLong()));
            }
            Map<Integer, BigInteger> initial = new LinkedHashMap<>();
            for (int n = count(in, 96); n > 0; n--)
                if (initial.put(in.readInt(), integer(in)) != null) throw new IOException("Duplicate initial resource");
            witness = new CapturedProof.Witness(recipes, program, initial, amounts(in));
        }
        return new CapturedProof.Certificate(new CapturedProof.Snapshot(resources, patterns, complete), request,
                new CapturedCatalog.Coverage(revision, domains, covered), witness);
    }

    private void amounts(DataOutputStream out, Map<Integer, Long> values) throws IOException {
        count(out, values.size(), 96);
        for (var e : new TreeMap<>(values).entrySet()) { out.writeInt(e.getKey()); out.writeLong(e.getValue()); }
    }

    private Map<Integer, Long> amounts(DataInputStream in) throws IOException {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (int n = count(in, 96); n > 0; n--)
            if (result.put(in.readInt(), in.readLong()) != null) throw new IOException("Duplicate amount resource");
        return result;
    }

    private int count(DataInputStream in, long bytes) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 1_000_000) throw new IOException("Invalid captured proof length");
        guard.work(); guard.keep(bytes * count); return count;
    }

    private void count(DataOutputStream out, int count, long bytes) throws IOException {
        if (count < 0 || count > 1_000_000) throw new IOException("Invalid captured proof length");
        guard.work(); guard.keep(bytes * count); out.writeInt(count);
    }

    private String text(DataInputStream in) throws IOException {
        guard.work(); String value = in.readUTF(); guard.text(value); return value;
    }

    private void text(DataOutputStream out, String value) throws IOException { guard.work(); guard.text(value); out.writeUTF(value); }

    private BigInteger integer(DataInputStream in) throws IOException {
        guard.work(); int size = in.readInt();
        if (size <= 0 || size > 65536) throw new IOException("Invalid captured proof integer length");
        guard.keep(80L + 4L * size);
        byte[] bytes = new byte[size]; in.readFully(bytes); return new BigInteger(bytes);
    }

    private void integer(DataOutputStream out, BigInteger value) throws IOException {
        guard.work(); guard.integer(value);
        if (value.bitLength() >= 65536 * 8) throw new IOException("Oversized captured proof integer");
        byte[] bytes = value.toByteArray(); out.writeInt(bytes.length); out.write(bytes);
    }
}
