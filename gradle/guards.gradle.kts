// ============================================================================
//  GUARDS — Playbook §1 Step 1, "these land first or they never land".
//
//  Many agents adding dependencies freely is exactly the mechanism that breaks
//  R17 (permissions), P4 (no analytics/ads/crash SDKs) and the event's
//  attribution rule. Promises do not survive a hackathon; build failures do.
//
//    ./gradlew guards          runs all three
//    ./gradlew guardPermissions
//    ./gradlew guardDependencies
//    ./gradlew generateAttribution   (regenerates, does not check)
//
//  Deliberately plain Gradle with no plugins: an agent can read this file
//  top-to-bottom and fix it at 03:00.
// ============================================================================

val allowedPermissionsFile = rootProject.file("config/allowed-permissions.txt")
val allowedDependenciesFile = rootProject.file("config/allowed-dependencies.txt")
val attributionFile = rootProject.file("ATTRIBUTION.md")

// ---------------------------------------------------------------------------
// R17 — the merged manifest's permission list must equal the allowlist.
// Checks the MERGED manifest, so a library that quietly adds INTERNET fails
// the build rather than shipping. That is R17's acceptance test, automated.
// ---------------------------------------------------------------------------
val guardPermissions = tasks.register("guardPermissions") {
    group = "verification"
    description = "Fails if the merged manifest's permissions differ from config/allowed-permissions.txt"
    // These three read the resolved dependency graph and the merged manifest at
    // execution time, which the configuration cache does not allow. Opting out here
    // keeps the cache for every other task — which is where the build-time win is —
    // and costs nothing, because guards run once before a commit, not on every edit.
    notCompatibleWithConfigurationCache("reads the merged manifest at execution time")

    // Run the merge first, so this checks what actually ships rather than what we wrote.
    // A library adding INTERNET is invisible in the source manifest — that is the whole
    // failure R17 exists to catch.
    dependsOn(":app:processDebugMainManifest")

    val manifestCandidates = listOf(
        "app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        "app/build/intermediates/merged_manifests/debug/processDebugMainManifest/AndroidManifest.xml",
        "app/build/intermediates/merged_manifest/debug/AndroidManifest.xml",
        "app/build/intermediates/merged_manifests/debug/AndroidManifest.xml",
    ).map { rootProject.file(it) }
    val sourceManifest = rootProject.file("app/src/main/AndroidManifest.xml")
    val allowFile = allowedPermissionsFile

    doLast {
        val merged = manifestCandidates.firstOrNull { it.exists() }
        val manifest = merged ?: sourceManifest
        if (merged == null) {
            logger.warn(
                "guardPermissions: no merged manifest found — checking the SOURCE manifest only.\n" +
                "  This does NOT satisfy R17. Run `./gradlew :app:processDebugMainManifest guardPermissions`\n" +
                "  before any gate check or submission."
            )
        }
        if (!manifest.exists()) throw GradleException("guardPermissions: no manifest at ${manifest.path}")

        val rx = Regex("""uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
        val found = rx.findAll(manifest.readText()).map { it.groupValues[1] }.toSortedSet()
        val allowed = allowFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()

        val added = found - allowed
        val removed = allowed - found

        if (added.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("R17 VIOLATION — permissions in the build that are not in the allowlist:")
                added.forEach { appendLine("    + $it") }
                appendLine()
                appendLine("Manifest checked: ${manifest.relativeTo(rootProject.projectDir)}")
                appendLine("If this is deliberate, add it to config/allowed-permissions.txt WITH a reason,")
                appendLine("and say on the record which feature needs it. If it is not deliberate, a")
                appendLine("dependency added it for you — find which, and drop the dependency.")
            })
        }
        if (removed.isNotEmpty()) {
            logger.lifecycle("guardPermissions: allowlisted but unused (fine, tighten when you can): $removed")
        }
        logger.lifecycle("guardPermissions: OK — ${found.size} permission(s), all allowlisted.")
    }
}

// ---------------------------------------------------------------------------
// P4 — every resolved external dependency must be on the allowlist.
// No analytics, no ads, no crash reporting, ever. Enforced, not promised.
//
// Each module reports its OWN resolved graph. Gradle 9 will not let the root project
// resolve another project's configuration at execution time — it returns nothing
// rather than failing, which would have made this guard silently useless. Hence the
// per-module task plus an aggregator.
// ---------------------------------------------------------------------------
subprojects {
    tasks.register("reportResolvedDependencies") {
        group = "verification"
        description = "Writes this module's resolved dependency coordinates for the guards"

        // Captured at CONFIGURATION time. `rootComponent` is a Provider designed for
        // exactly this, so the task body touches no Project and the configuration cache
        // stays intact for the whole build.
        val roots = configurations
            .filter {
                it.isCanBeResolved && it.name.lowercase().let { n ->
                    n == "runtimeclasspath" || n == "debugruntimeclasspath" ||
                        n == "compileclasspath" || n == "debugcompileclasspath"
                }
            }
            .map { it.incoming.resolutionResult.rootComponent }

        val out = layout.buildDirectory.file("reports/resolved-dependencies.txt")
        outputs.file(out)

        doLast {
            val seen = sortedSetOf<String>()
            roots.forEach { rootProvider ->
                val start = runCatching { rootProvider.get() }.getOrNull() ?: return@forEach
                val queue = ArrayDeque(listOf(start))
                val visited = mutableSetOf<Any>()
                while (queue.isNotEmpty()) {
                    val component = queue.removeFirst()
                    if (!visited.add(component.id)) continue
                    component.moduleVersion?.let { mv ->
                        // Project dependencies have no external coordinate worth listing.
                        if (mv.group.isNotBlank() && mv.group != "One-Take") {
                            seen += "${mv.group}:${mv.name}:${mv.version}"
                        }
                    }
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { queue.addLast(it.selected) }
                }
            }
            val f = out.get().asFile
            f.parentFile.mkdirs()
            f.writeText(seen.joinToString("\n"))
        }
    }
}

val moduleReports = rootProject.subprojects.map { "${it.path}:reportResolvedDependencies" }
val reportFiles = rootProject.subprojects.map {
    it.layout.buildDirectory.file("reports/resolved-dependencies.txt")
}

val guardDependencies = tasks.register("guardDependencies") {
    group = "verification"
    description = "Fails if any resolved dependency is not in config/allowed-dependencies.txt"
    dependsOn(moduleReports)

    val allowFile = allowedDependenciesFile
    val files = reportFiles
    val aggregate = rootProject.layout.buildDirectory.file("reports/resolved-dependencies.txt")

    doLast {
        val allowed = allowFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val seen = sortedSetOf<String>()
        files.forEach { f ->
            val file = f.get().asFile
            if (file.exists()) {
                file.readLines().filter { it.isNotBlank() }.forEach { seen += it.trim() }
            }
        }

        val violations = sortedSetOf<String>()
        seen.forEach { coord ->
            val ga = coord.substringBeforeLast(':')
            val ok = allowed.any { rule ->
                rule == ga || (rule.endsWith("*") && ga.startsWith(rule.dropLast(1)))
            }
            if (!ok) violations += ga
        }

        val agg = aggregate.get().asFile
        agg.parentFile.mkdirs()
        agg.writeText(seen.joinToString("\n"))

        if (violations.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("P4 / dependency-allowlist VIOLATION — not in config/allowed-dependencies.txt:")
                violations.forEach { appendLine("    + $it") }
                appendLine()
                appendLine("Full resolved list: build/reports/resolved-dependencies.txt")
                appendLine("Before allowlisting, answer three questions in the commit message:")
                appendLine("  1. Does it add a manifest permission?  (run guardPermissions after)")
                appendLine("  2. Does it make a network call, ever?  (P1)")
                appendLine("  3. Does it ship a second ONNX Runtime or QNN runtime? A second")
                appendLine("     libonnxruntime.so fails at dlopen, on the phone, not at build time.")
            })
        }
        logger.lifecycle("guardDependencies: OK — ${seen.size} resolved artifact(s), all allowlisted.")
    }
}

// ---------------------------------------------------------------------------
// Event rule: ATTRIBUTION, generated from the real resolved graph rather than
// remembered at 04:00 on Sunday.
// ---------------------------------------------------------------------------
val generateAttribution = tasks.register("generateAttribution") {
    group = "documentation"
    description = "Regenerates ATTRIBUTION.md from the resolved dependency graph"
    dependsOn(moduleReports)

    val out = attributionFile
    val files = reportFiles
    val manualFile = rootProject.file("config/attribution-manual.md")

    doLast {
        val seen = sortedSetOf<String>()
        files.forEach { f ->
            val file = f.get().asFile
            if (file.exists()) file.readLines().filter { it.isNotBlank() }.forEach { seen += it.trim() }
        }
        val manual = if (manualFile.exists()) manualFile.readText() else ""

        out.writeText(buildString {
            appendLine("# Attribution")
            appendLine()
            appendLine("Generated by `./gradlew generateAttribution` from the resolved dependency")
            appendLine("graph. Do not hand-edit — edit `config/attribution-manual.md` for anything")
            appendLine("Gradle cannot see (vendored AARs, model files, native source).")
            appendLine()
            if (manual.isNotBlank()) { appendLine(manual); appendLine() }
            appendLine("## Gradle dependencies")
            appendLine()
            seen.forEach { appendLine("- `$it`") }
        })
        logger.lifecycle("generateAttribution: wrote ${seen.size} entries to ${out.name}")
    }
}

tasks.register("guards") {
    group = "verification"
    description = "All build guards: permissions (R17) and dependency allowlist (P4)"
    dependsOn(guardPermissions, guardDependencies)
}

// ---------------------------------------------------------------------------
// The one command an agent runs before saying it is done.
// ---------------------------------------------------------------------------
tasks.register("verify") {
    group = "verification"
    description = "Engine tests + all module unit tests + lint + guards. Run before every commit."
    dependsOn(
        ":engine:test",
        ":engine-fixtures:test",
        ":eval:test",
        ":app:assembleDebug",
        "guards",
    )
}
