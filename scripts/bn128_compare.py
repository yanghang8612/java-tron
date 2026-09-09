#!/usr/bin/env python3
"""Reproducible BN128 baseline/candidate comparison; only Python 3, git and a JDK are needed.

--tvm additionally builds java-tron and measures the actual precompile and TVM CALL.
Each variant/fork runs in its own JVM. No Gradle test cache or JaCoCo agent is involved.
"""

import argparse
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = "crypto/src/main/java/org/tron/common/crypto/zksnark"
TEST = ROOT / "crypto/src/test/java/org/tron/common/crypto/zksnark"
BASELINE = "bd2450fe06"
PACKAGE = "org.tron.common.crypto.zksnark."


def run(command, **kwargs):
    return subprocess.run(command, cwd=str(ROOT), check=True, **kwargs)


def text_command(command):
    return run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout.strip()


def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)] / 1e6


def execute_jvm(command, path):
    rows = {}
    fixture_hash = None
    with path.open("w") as log:
        process = subprocess.Popen(command, cwd=str(ROOT), stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1)
        try:
            for line in process.stdout:
                log.write(line)
                log.flush()
                if line.startswith("RAW ") or line.startswith("TIMEOUTS "):
                    kind, count, values = line.split(" ", 2)
                    rows.setdefault(int(count), {})[kind.lower()] = json.loads(values)
                elif line.startswith("INPUT_SHA256 "):
                    fixture_hash = line.strip().split()[1]
                else:
                    print(line.rstrip(), flush=True)
            if process.wait() != 0:
                raise RuntimeError("JVM failed; see " + str(path))
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()
    return rows, fixture_hash


def summarize(runs, output, timeout_ms):
    grouped = {}
    for entry in runs:
        for pairs, result in entry["rows"].items():
            key = (entry["engine"], entry["scenario"], entry["variant"], int(pairs))
            group = grouped.setdefault(key, {"raw": [], "timeouts": [], "forkP50Ms": []})
            group["raw"].extend(result["raw"])
            group["timeouts"].extend(result["timeouts"])
            group["forkP50Ms"].append(percentile(result["raw"], .50))
    summaries = []
    for key, group in sorted(grouped.items()):
        engine, scenario, variant, pairs = key
        values = group["raw"]
        summary = dict(engine=engine, scenario=scenario, variant=variant, pairs=pairs,
                       samples=len(values), meanMs=statistics.mean(values) / 1e6,
                       p50Ms=percentile(values, .50), p95Ms=percentile(values, .95),
                       p99Ms=percentile(values, .99), maxMs=max(values) / 1e6,
                       overDeadline=sum(ns > timeout_ms * 1e6 or timed_out != 0
                                        for ns, timed_out in zip(values, group["timeouts"])),
                       timeouts=sum(group["timeouts"]), forkP50Ms=group["forkP50Ms"])
        summaries.append(summary)
    index = {(s["engine"], s["scenario"], s["variant"], s["pairs"]): s for s in summaries}
    lines = ["# BN128 comparison", "", "Pooled samples across isolated JVM forks.", "",
             "| Engine | Input | Variant | Pairs | N | Mean ms | P50 ms | P99 ms | "
             "Over deadline | TVM timeouts | P50 speedup |", "|" + "---|" * 11]
    for s in summaries:
        baseline = index.get((s["engine"], s["scenario"], "baseline", s["pairs"]))
        speedup = baseline["p50Ms"] / s["p50Ms"] if baseline else None
        s["p50Speedup"] = speedup
        factor = "%.2fx" % speedup if speedup is not None else "n/a"
        lines.append("| {engine} | {scenario} | {variant} | {pairs} | {samples} | "
                     "{meanMs:.3f} | {p50Ms:.3f} | {p99Ms:.3f} | {overDeadline} | "
                     "{timeouts} | {factor} |".format(factor=factor, **s))
    lines.extend(["", "Largest tested pair counts meeting each criterion (not a guarantee):", ""])
    for engine, scenario, variant in sorted(set(k[:3] for k in grouped)):
        subset = [s for s in summaries if (s["engine"], s["scenario"], s["variant"])
                  == (engine, scenario, variant)]
        p99 = max([s["pairs"] for s in subset if s["p99Ms"] <= timeout_ms] or [0])
        all_under = max([s["pairs"] for s in subset if s["overDeadline"] == 0] or [0])
        lines.append("- %s/%s/%s: P99 ≤ %g ms: %d; all measured samples ≤ %g ms "
                     "and no TVM timeout: %d." %
                     (engine, scenario, variant, timeout_ms, p99, timeout_ms, all_under))
    (output / "summary.json").write_text(json.dumps(summaries, indent=2) + "\n")
    (output / "summary.md").write_text("\n".join(lines) + "\n")
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default=BASELINE)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--tvm", action="store_true", help="build and measure actual direct/tvm paths")
    parser.add_argument("--classpath-file", type=Path, help="reuse a freshly built TVM classpath")
    parser.add_argument("--engines", help="core, direct, tvm; default core or direct,tvm with --tvm")
    parser.add_argument("--counts", default="1,2,3,4,5,6,7,8,9,16,32")
    parser.add_argument("--samples", type=int, default=301)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup-seconds", type=int, default=30)
    parser.add_argument("--minimum-warmup", type=int, default=300)
    parser.add_argument("--timeout-ms", type=int, default=80)
    parser.add_argument("--scenarios", default="distinct", help="distinct,repeated")
    parser.add_argument("--variants", default="baseline,optimized",
                        help="baseline,optimized,fields,affine,miller (last three are ablations)")
    parser.add_argument("--reference", action="append", default=[], metavar="NAME=COMMIT",
                        help="additional immutable implementation, e.g. previous=a05b19e9aa")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--jvm-arg", action="append", default=[])
    args = parser.parse_args()
    counts = sorted(set(int(x) for x in args.counts.split(",")))
    variants = args.variants.split(",")
    references = {}
    for entry in args.reference:
        name, separator, ref = entry.partition("=")
        if (not separator or not name.isidentifier() or name in
                {"baseline", "optimized", "fields", "affine", "miller"} or name in references):
            parser.error("reference must have a unique NAME=COMMIT")
        references[name] = text_command(["git", "rev-parse", "--verify", ref + "^{commit}"])
    scenarios = args.scenarios.split(",")
    engines = (args.engines or ("direct,tvm" if args.tvm else "core")).split(",")
    if not counts or counts[0] < 1 or counts[-1] > 256:
        parser.error("counts must be in [1, 256]")
    if min(args.samples, args.forks, args.warmup_seconds, args.minimum_warmup,
           args.timeout_ms) <= 0:
        parser.error("sample, fork, warmup and timeout values must be positive")
    if set(variants) - ({"baseline", "optimized", "fields", "affine", "miller"} | set(references)):
        parser.error("unknown variant")
    if set(scenarios) - {"distinct", "repeated"} or set(engines) - {"core", "direct", "tvm"}:
        parser.error("unknown scenario or engine")
    java = str(Path(args.java_home) / "bin/java") if args.java_home else shutil.which("java")
    javac = str(Path(args.java_home) / "bin/javac") if args.java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("java and javac are required")
    baseline = text_command(["git", "rev-parse", "--verify", args.baseline + "^{commit}"])
    output = args.output.resolve() if args.output else ROOT / "build" / (
        "bn128-compare-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=False)
    work = Path(tempfile.mkdtemp(prefix="compile-", dir=str(output)))
    print("Results:", output, flush=True)
    classpath = ""
    if not args.verify_only and set(engines) - {"core"}:
        cp_file = args.classpath_file
        if cp_file is None:
            env = os.environ.copy()
            if args.java_home:
                env["JAVA_HOME"] = args.java_home
            run(["./gradlew", ":actuator:writeBn128BenchmarkClasspath", "--console=plain"], env=env)
            cp_file = ROOT / "actuator/build/bn128-benchmark-classpath.txt"
        classpath = cp_file.read_text().strip()
    paths = text_command(["git", "ls-tree", "-r", "--name-only", baseline, "--", SOURCE]).splitlines()
    paths = [p for p in paths if p.endswith(".java")]
    if not paths:
        raise RuntimeError("No BN128 sources in baseline")
    harness = [TEST / (name + ".java") for name in
               ["Bn128TestSupport", "Bn128Verification", "Bn128Benchmark"]]
    sources_hash = hashlib.sha256()
    baseline_hash = hashlib.sha256()
    frozen = {}
    candidate = {}
    for path in paths:
        frozen[path] = run(["git", "show", baseline + ":" + path],
                           stdout=subprocess.PIPE).stdout
        candidate[path] = (ROOT / path).read_bytes()
        baseline_hash.update(frozen[path])
        sources_hash.update(candidate[path])
    extra_sources = {str(p.relative_to(ROOT)): p.read_bytes()
                     for p in sorted((ROOT / SOURCE).glob("*.java"))
                     if str(p.relative_to(ROOT)) not in candidate}
    for content in extra_sources.values():
        sources_hash.update(content)
    manifest = dict(baseline=baseline, baselineSourcesSha256=baseline_hash.hexdigest(),
                    candidateHead=text_command(["git", "rev-parse", "HEAD"]),
                    candidateSourcesSha256=sources_hash.hexdigest(),
                    references=references, machine=platform.platform(), arguments=vars(args),
                    javaVersion=text_command([java, "-version"]),
                    jvmArgs=["-Xms1g", "-Xmx1g", "-XX:+UseParallelGC"] + args.jvm_arg)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str) + "\n")
    (output / "candidate.patch").write_text(text_command(["git", "diff", baseline, "--", SOURCE]) + "\n")
    classes = {}
    reference_digest = None
    # Always verify the baseline, even when only an ablation/candidate is timed.
    for variant in dict.fromkeys(["baseline"] + variants):
        src = work / variant / "src"
        dest = work / variant / "classes"
        src.mkdir(parents=True)
        dest.mkdir()
        variant_paths = paths
        if variant in references:
            variant_paths = text_command(["git", "ls-tree", "-r", "--name-only",
                                          references[variant], "--", SOURCE]).splitlines()
            variant_paths = [p for p in variant_paths if p.endswith(".java")]
        for path in variant_paths:
            name = Path(path).name
            if variant in references:
                (src / name).write_bytes(run(["git", "show", references[variant] + ":" + path],
                                             stdout=subprocess.PIPE).stdout)
                continue
            use_candidate = (variant == "optimized"
                             or variant == "fields" and name in {"Fp.java", "Fp2.java", "Fp6.java", "Fp12.java"}
                             or variant == "affine" and name == "BN128.java"
                             or variant == "miller" and name in {"PairingCheck.java", "Fp.java", "Fp2.java"})
            (src / name).write_bytes(candidate[path] if use_candidate else frozen[path])
        if variant not in references and variant != "baseline":
            for path, content in extra_sources.items():
                (src / Path(path).name).write_bytes(content)
        run([javac, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-d", str(dest)]
            + [str(p) for p in sorted(src.glob("*.java"))] + [str(p) for p in harness])
        classes[variant] = str(dest)
        print("VERIFY", variant, flush=True)
        command = [java] + manifest["jvmArgs"] + ["-cp", str(dest), PACKAGE + "Bn128Verification"]
        verification = text_command(command)
        (output / (variant + "-verification.txt")).write_text(verification + "\n")
        print(verification, flush=True)
        if reference_digest is None:
            reference_digest = verification
        elif verification != reference_digest:
            raise RuntimeError("Baseline/candidate verification mismatch: " + variant)
    if args.verify_only:
        return
    runs = []
    hashes = {}
    for engine in engines:
        for scenario in scenarios:
            for fork in range(args.forks):
                order = variants if fork % 2 == 0 else list(reversed(variants))
                for variant in order:
                    label = "%s-%s-%s-fork%d" % (engine, scenario, variant, fork + 1)
                    print("\nRUN", label, flush=True)
                    cp = classes[variant] + (os.pathsep + classpath if classpath else "")
                    command = [java] + manifest["jvmArgs"] + ["-cp", cp, PACKAGE + "Bn128Benchmark",
                               engine, ",".join(map(str, counts)), str(args.samples),
                               str(args.warmup_seconds), str(args.minimum_warmup), scenario,
                               str(128000 + fork), str(args.timeout_ms)]
                    rows, fixture_hash = execute_jvm(command, output / (label + ".log"))
                    if sorted(rows) != counts or any(len(v.get("raw", [])) != args.samples
                                                    or len(v.get("timeouts", [])) != args.samples
                                                    for v in rows.values()):
                        raise RuntimeError("Incomplete samples: " + label)
                    key = (engine, scenario)
                    if not fixture_hash or hashes.setdefault(key, fixture_hash) != fixture_hash:
                        raise RuntimeError("Input fixtures differ between JVMs")
                    runs.append(dict(engine=engine, scenario=scenario, variant=variant,
                                     fork=fork + 1, inputSha256=fixture_hash, rows=rows))
                    (output / "runs.json").write_text(json.dumps(runs, indent=2) + "\n")
                    summarize(runs, output, args.timeout_ms)
    print("\n" + "\n".join(summarize(runs, output, args.timeout_ms)), flush=True)


if __name__ == "__main__":
    main()
