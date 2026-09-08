# NekoBox Custom Proxy Groups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build user-defined, overlapping proxy groups sourced from one or more subscriptions, filtered by include/exclude regex, emitted as sing-box `selector` or `urltest` outbounds, and explicitly selectable by route rules without changing unrelated NekoBox behavior.

**Architecture:** Keep the existing internal Router layer but remove the incorrect fixed-group, exclusive-membership, and semantic-route assumptions. Persist group definitions, subscription-source relations, materialized members, stable selector identity, and explicit `RuleEntity` references in Room; resolve them through a focused repository before `ConfigBuilder` emits stable sing-box tags. Add a dedicated list/editor UI and route-group picker while preserving every legacy outbound path.

**Tech Stack:** Kotlin, AndroidX Room 2.6.1, AndroidX Preference/RecyclerView, Kotlin coroutines, JUnit 4, Android instrumentation tests, sing-box Java bindings, gomobile/libcore AAR, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-04-custom-proxy-groups-design.md`

## Global Constraints

- User-created groups only; never create predefined `US`, `US low`, `SG`, or `JP` groups.
- A node may belong to any number of custom groups; deduplication is local to one group.
- Sources are existing subscription `ProxyGroup` rows only, and each custom group may select multiple sources.
- Empty include regex means all source nodes; exclude regex is evaluated second and wins.
- Invalid regex blocks saving and identifies the invalid field.
- Modes are exactly `selector` and `url-test`; no fallback, load-balance, nesting, region, protocol, multiplier, or Clash YAML import.
- Routes reference a custom-group database ID explicitly; no rule-name/domain semantic inference is allowed.
- `RuleEntity.outbound` keeps its existing `0`, `-1`, `-2`, and positive-profile semantics when `routerGroupId == 0`.
- Stable group tags are generated once as `router.<lowercase UUID without dashes>` and never change on rename.
- An enabled referenced group with no current members, or a missing referenced group, must stop configuration generation with a group-specific error; never silently fall back.
- Failed/empty subscription refresh retains the last valid materialized members; successful refresh recomputes them.
- Preserve subscription behavior, ordinary profiles, App routing, AdBlock, `.invalid` load rules, DNS/TUN, settings, and existing routes unless the user explicitly edits a group reference.
- Preserve and work around all pre-existing worktree changes. Do not touch `A7.yaml`, `nekobox_isA8.json`, or files outside this repository.
- Do not commit or ship an APK before JVM tests, migration/backup tests, clean-build ABI verification, lint assessment, and debug assembly succeed.
- The pinned Java binding exposes URL-test `url`, `interval`, and `tolerance`, but no outbound request-timeout field. Store no fictitious JSON field; keep the existing core probe timeout for an explicit manual test action and treat this as the implementation limit of the pinned core.

---

## File Map

- `route/RouterFilter.kt`: validated include/exclude configuration and JSON codec.
- `route/RouterMatcher.kt`: pure, independently evaluated membership matching.
- `database/RouterGroup.kt`: group record, stable tag, mode, selected stable key, latest error.
- `database/RouterGroupSource.kt`: many-to-many group-to-subscription relation and DAO.
- `database/RouterMember.kt`: materialized many-to-many group membership.
- `database/RouterGroupRepository.kt`: validation, CRUD, preview, recomputation, deletion guard, and runtime resolution.
- `database/GroupManager.kt`: subscription lifecycle hooks only; delegates group logic to the repository.
- `route/RouterReconciler.kt`: stable-identity remap scoped by source and last-valid snapshot behavior.
- `route/RouterRuntime.kt`: strict runtime outbound descriptions and empty/missing error values.
- `fmt/ConfigBuilder.kt`: build node outbounds once, emit custom groups, resolve explicit route references.
- `ui/RouterGroupListActivity.kt` and `ui/RouterGroupListFragment.kt`: dedicated custom-group list and runtime actions.
- `ui/RouterGroupSettingsActivity.kt`: focused editor with validation and live preview.
- `ui/RouterGroupSelectActivity.kt`: enabled/non-empty group picker for route editing.
- `ui/GroupFragment.kt`: one entry point to the dedicated group list; no fixed cards.
- `ui/RouteSettingsActivity.kt` and `widget/OutboundPreference.kt`: explicit group selection while preserving legacy choices.
- `ui/BackupFragment.kt` and `fmt/BackupSerializer.kt`: versioned group/source/member/reference round trip.
- `build.gradle.kts`: Room test setup and libcore ABI/package gate.

### Task 1: Replace Fixed and Exclusive Matching with the Confirmed Filter Contract

**Files:**
- Create: `app/src/main/java/io/nekohasekai/sagernet/route/RouterFilter.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/route/RouterMatcher.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/route/RouterMembership.kt`
- Delete: `app/src/main/java/io/nekohasekai/sagernet/route/RouterDefaults.kt`
- Test: `app/src/test/java/io/nekohasekai/sagernet/route/RouterMatcherTest.kt`
- Test: `app/src/test/java/io/nekohasekai/sagernet/route/RouterMembershipTest.kt`
- Delete: `app/src/test/java/io/nekohasekai/sagernet/route/RouterDefaultsTest.kt`

**Interfaces:**
- Produces: `RouterFilterConfig(includeRegex: String, excludeRegex: String, testUrl: String, intervalSeconds: Long, toleranceMs: Int)`.
- Produces: `RouterFilterValidation(include: Regex?, exclude: Regex?)` and `RouterFilterException(field: Field, cause: Throwable)`.
- Produces: `RouterMatcher.match(nodes: Iterable<RouterNodeSnapshot>, requests: Iterable<RouterMatchRequest>): Map<Long, List<Long>>`.
- Produces: `RouterMatchRequest(routerId: Long, sourceGroupIds: Set<Long>, filter: RouterFilterValidation)`.

- [ ] **Step 1: Replace the matcher tests with the approved behavior**

```kotlin
@Test fun sameNodeMayAppearInTwoGroups() {
    val node = RouterNodeSnapshot(7, "source:10/node:a", "US A", subscriptionId = 10)
    val requests = listOf(
        RouterMatchRequest(1, setOf(10), RouterFilterConfig("US", "").validate()),
        RouterMatchRequest(2, setOf(10), RouterFilterConfig("A", "").validate()),
    )
    assertEquals(mapOf(1L to listOf(7L), 2L to listOf(7L)), RouterMatcher.match(listOf(node), requests))
}

@Test fun excludeWinsAndEmptyIncludeMeansAll() {
    val nodes = listOf(
        RouterNodeSnapshot(1, "10/a", "US Premium", subscriptionId = 10),
        RouterNodeSnapshot(2, "10/b", "US Expired", subscriptionId = 10),
    )
    val request = RouterMatchRequest(3, setOf(10), RouterFilterConfig("", "Expired").validate())
    assertEquals(listOf(1L), RouterMatcher.match(nodes, listOf(request)).getValue(3))
}

@Test fun invalidIncludeAndExcludeIdentifyTheirFields() {
    assertEquals(RouterFilterException.Field.INCLUDE, assertThrows<RouterFilterException> {
        RouterFilterConfig("[", "").validate()
    }.field)
    assertEquals(RouterFilterException.Field.EXCLUDE, assertThrows<RouterFilterException> {
        RouterFilterConfig("", "[").validate()
    }.field)
}
```

- [ ] **Step 2: Run the focused tests and confirm the old API fails**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.route.RouterMatcherTest" --tests "io.nekohasekai.sagernet.route.RouterMembershipTest"`

Expected: FAIL because `RouterMatchRequest`, validation, and overlapping membership do not exist and the old test expects reserved-node exclusion.

- [ ] **Step 3: Implement the minimal filter and independent matcher**

```kotlin
data class RouterFilterConfig(
    val includeRegex: String = "",
    val excludeRegex: String = "",
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val intervalSeconds: Long = 300,
    val toleranceMs: Int = 50,
) {
    fun validate() = RouterFilterValidation(
        include = includeRegex.takeIf(String::isNotBlank)?.compile(RouterFilterException.Field.INCLUDE),
        exclude = excludeRegex.takeIf(String::isNotBlank)?.compile(RouterFilterException.Field.EXCLUDE),
    )
}

data class RouterMatchRequest(
    val routerId: Long,
    val sourceGroupIds: Set<Long>,
    val filter: RouterFilterValidation,
)

fun match(nodes: Iterable<RouterNodeSnapshot>, requests: Iterable<RouterMatchRequest>) =
    requests.associate { request ->
        request.routerId to nodes.asSequence()
            .filter { it.enabled && it.available && it.subscriptionId in request.sourceGroupIds }
            .filter { request.filter.include?.containsMatchIn(it.name) != false }
            .filterNot { request.filter.exclude?.containsMatchIn(it.name) == true }
            .distinctBy { it.id }
            .map { it.id }
            .toList()
    }
```

Remove `RouterRegion`, stable-ID/manual lists, multiplier fields, global assigned-ID sets, and `reservedProxyIds`. Preserve input order instead of sorting IDs.

- [ ] **Step 4: Run focused tests**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.route.RouterMatcherTest" --tests "io.nekohasekai.sagernet.route.RouterMembershipTest"`

Expected: PASS; the same proxy ID appears in both results.

- [ ] **Step 5: Commit the matching contract**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/route app/src/test/java/io/nekohasekai/sagernet/route
git commit -m "feat: support overlapping custom group filters"
```

### Task 2: Add Source Relations, Stable Selection, Errors, and Explicit Route References

**Files:**
- Create: `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroupSource.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroup.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt`
- Create: `app/schemas/io.nekohasekai.sagernet.database.SagerDatabase/10.json`
- Modify: `app/src/androidTest/java/io/nekohasekai/sagernet/database/RouterMigrationTest.kt`

**Interfaces:**
- Produces: `RouterGroupSource(routerId: Long, sourceGroupId: Long)` with composite primary key.
- Produces: `RouterGroup.selectedNodeKey: String` and `RouterGroup.lastError: String`.
- Produces: `RuleEntity.routerGroupId: Long`, where `0L` means use legacy `outbound`.
- Produces: `RouterGroupSource.Dao.sourcesFor(routerId)`, `routersForSource(sourceGroupId)`, `replaceSources(routerId, sourceIds)`, and cleanup methods.

- [ ] **Step 1: Write migration and DAO tests**

```kotlin
@Test fun migratesNineToTenWithoutCreatingDefaultGroupsOrChangingLegacyRoutes() {
    migrationHelper.createDatabase(TEST_DB, 9).apply {
        execSQL("INSERT INTO rules (id,name,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,outbound,packages,config,ruleset) VALUES (1,'legacy',0,1,'','','','','','','',-1,'','','')")
        close()
    }
    migrationHelper.runMigrationsAndValidate(TEST_DB, 10, true, SagerDatabase_AutoMigration_9_10_Impl()).use { db ->
        assertEquals(0L, db.singleLong("SELECT routerGroupId FROM rules WHERE id=1"))
        assertEquals(-1L, db.singleLong("SELECT outbound FROM rules WHERE id=1"))
        assertEquals(0L, db.singleLong("SELECT COUNT(*) FROM router_groups"))
        assertEquals(0L, db.singleLong("SELECT COUNT(*) FROM router_group_sources"))
    }
}

@Test fun oneSubscriptionAndOneNodeCanBelongToMultipleRouters() {
    val a = database.routerGroupDao().create(RouterGroup(stableTag = "router.a", name = "A"))
    val b = database.routerGroupDao().create(RouterGroup(stableTag = "router.b", name = "B"))
    database.routerGroupSourceDao().insert(listOf(RouterGroupSource(a, 10), RouterGroupSource(b, 10)))
    database.routerMemberDao().insert(listOf(RouterMember(a, 20), RouterMember(b, 20)))
    assertEquals(listOf(a, b), database.routerGroupSourceDao().routersForSource(10).map { it.routerId })
}
```

- [ ] **Step 2: Run instrumentation compilation/test and verify failure**

Run: `.\gradlew.bat :app:compileOssDebugAndroidTestKotlin`

Expected: FAIL because schema version 10, `RouterGroupSource`, and `routerGroupId` do not exist.

- [ ] **Step 3: Implement schema version 10**

```kotlin
@Entity(tableName = "router_group_sources", primaryKeys = ["routerId", "sourceGroupId"], indices = [Index("sourceGroupId")])
data class RouterGroupSource(var routerId: Long = 0, var sourceGroupId: Long = 0) : Serializable()
```

Add `selectedNodeKey` and `lastError` to `RouterGroup`, bump its buffer payload version from 0 to 1, and read the extra values only when `version >= 1`. Add this at the end of `RuleEntity` so Room gives old rows zero:

```kotlin
@IgnoredOnParcel
@ColumnInfo(defaultValue = "0")
var routerGroupId: Long = 0L,
```

`@IgnoredOnParcel` intentionally preserves the legacy `RuleEntity` Parcel layout; Task 8 exports route references separately.

- [ ] **Step 4: Register the entity/DAO and generate the schema**

Change `SagerDatabase` to version 10, add `AutoMigration(from = 9, to = 10)`, add `RouterGroupSource::class`, and expose `routerGroupSourceDao`. Run:

`.\gradlew.bat :app:kspOssDebugKotlin`

Expected: `app/schemas/io.nekohasekai.sagernet.database.SagerDatabase/10.json` exists and contains `router_group_sources`, `selectedNodeKey`, `lastError`, and `routerGroupId`.

- [ ] **Step 5: Run database tests**

Run: `.\gradlew.bat :app:compileOssDebugAndroidTestKotlin`

If an emulator/device is connected, also run: `.\gradlew.bat :app:connectedOssDebugAndroidTest`

Expected: compilation PASS; connected migration tests PASS when a device is present.

- [ ] **Step 6: Commit the persistent model**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/database app/src/androidTest/java/io/nekohasekai/sagernet/database app/schemas
git commit -m "feat: persist custom group sources and route references"
```

### Task 3: Centralize CRUD, Preview, Reconciliation, and Deletion Safety

**Files:**
- Create: `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroupRepository.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/database/GroupManager.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/route/RouterReconciler.kt`
- Test: `app/src/test/java/io/nekohasekai/sagernet/route/RouterReconcilerTest.kt`
- Create: `app/src/androidTest/java/io/nekohasekai/sagernet/database/RouterGroupRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 filter/matcher and Task 2 DAOs.
- Produces: `RouterGroupDraft`, `RouterGroupPreview`, `RouterDeleteResult`, and repository methods `preview`, `save`, `delete`, `reconcileAfterRefresh`, `reconcileBeforeBuild`.
- Produces: `RouterNodeKey.of(sourceGroupId: Long, stableId: String): String`.

- [ ] **Step 1: Add repository behavior tests**

```kotlin
@Test fun enabledGroupRequiresSourceButDisabledDraftDoesNot() {
    assertThrows<RouterGroupValidationException> {
        repository.save(RouterGroupDraft(name = "A", mode = RouterGroup.MODE_SELECTOR, enabled = true, sourceGroupIds = emptySet(), filter = RouterFilterConfig()))
    }
    assertTrue(repository.save(RouterGroupDraft(name = "Draft", mode = RouterGroup.MODE_SELECTOR, enabled = false, sourceGroupIds = emptySet(), filter = RouterFilterConfig())).id > 0)
}

@Test fun stableTagDoesNotChangeWhenDisplayNameChanges() {
    val created = repository.save(enabledDraft("A", setOf(subscriptionId)))
    val updated = repository.save(enabledDraft("Renamed", setOf(subscriptionId)).copy(id = created.id))
    assertEquals(created.stableTag, updated.stableTag)
}

@Test fun deleteIsBlockedWhenRulesReferenceTheGroup() {
    val group = repository.save(enabledDraft("A", setOf(subscriptionId)))
    database.rulesDao().createRule(RuleEntity(name = "r", routerGroupId = group.id))
    assertEquals(RouterDeleteResult.Referenced(1), repository.delete(group.id))
    assertNotNull(database.routerGroupDao().getById(group.id))
}

@Test fun failedRefreshPreservesMembersAndSuccessfulRefreshRemapsSelection() {
    val group = repository.save(enabledDraft("A", setOf(subscriptionId)))
    repository.reconcileAfterRefresh(subscriptionId, refreshSucceeded = false, previous = repository.snapshot())
    assertTrue(database.routerMemberDao().getByRouter(group.id).isNotEmpty())
    assertTrue(database.routerGroupDao().getById(group.id)!!.lastError.isNotBlank())
    repository.reconcileAfterRefresh(subscriptionId, refreshSucceeded = true, previous = repository.snapshot())
    assertTrue(database.routerGroupDao().getById(group.id)!!.lastError.isBlank())
}
```

The test class `@Before` creates an in-memory `SagerDatabase`, one subscription group, ordered proxy rows, and `RouterGroupRepository(database)`; `enabledDraft` returns a draft with include `""` and exclude `""`. Add a separate assertion that saving `A` and then `a` throws `RouterGroupValidationException(Field.NAME)`, and a two-source deletion case asserting only the deleted source relation disappears.

- [ ] **Step 2: Run tests and verify failure**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.route.RouterReconcilerTest" :app:compileOssDebugAndroidTestKotlin`

Expected: FAIL because repository contracts and source-scoped selection are absent.

- [ ] **Step 3: Implement source-scoped stable identities and repository validation**

```kotlin
data class RouterGroupDraft(
    val id: Long = 0,
    val name: String,
    val mode: Int,
    val enabled: Boolean,
    val sourceGroupIds: Set<Long>,
    val filter: RouterFilterConfig,
)

sealed interface RouterDeleteResult {
    data object Deleted : RouterDeleteResult
    data class Referenced(val ruleCount: Int) : RouterDeleteResult
}
```

Generate new tags with `"router." + UUID.randomUUID().toString().replace("-", "").lowercase()`; never regenerate on update. Check sources against `groupDao.subscriptions()`. Validate name, uniqueness, mode, sources, regex, interval `>= 10`, and tolerance `0..65535` before opening the transaction.

- [ ] **Step 4: Implement deterministic preview and materialization**

Load source groups in the editor-selected order and nodes in each source's `userOrder, id` order. On successful recomputation, replace only that router's rows, preserve existing `userOrder` for surviving source-scoped stable keys, append new rows, remap `selectedProxyId`, update `selectedNodeKey`, and clear `lastError`. On failed or truly empty refresh, retain prior members and write the error; on a valid non-empty source snapshot whose regex matches zero, materialize empty and write `No nodes match <name>`.

- [ ] **Step 5: Replace lifecycle logic with repository calls**

Remove `ensureDefaultRouterGroups`, region inference, multiplier parsing, and reserved-node collection from `GroupManager`. Keep snapshot-before-update and call `reconcileAfterRefresh(sourceGroupId, refreshSucceeded, previous)` after `GroupUpdater` and `RawUpdater`; source delete calls `removeSourceAndReconcile(sourceGroupId)`.

- [ ] **Step 6: Run repository and reconciler tests**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.route.*" :app:compileOssDebugAndroidTestKotlin`

Expected: PASS.

- [ ] **Step 7: Commit repository behavior**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/database app/src/main/java/io/nekohasekai/sagernet/group app/src/main/java/io/nekohasekai/sagernet/route app/src/test app/src/androidTest
git commit -m "feat: reconcile custom groups from subscriptions"
```

### Task 4: Emit Strict Selector/URL-Test Outbounds and Resolve Explicit Routes

**Files:**
- Modify: `app/src/main/java/io/nekohasekai/sagernet/route/RouterRuntime.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt`
- Modify: `app/src/test/java/io/nekohasekai/sagernet/route/RouterRuntimeTest.kt`
- Modify: `app/src/test/java/io/nekohasekai/sagernet/fmt/RouterOutboundConfigTest.kt`
- Replace: `app/src/test/java/io/nekohasekai/sagernet/fmt/RouterRouteSemanticTest.kt`

**Interfaces:**
- Consumes: `RuleEntity.routerGroupId`, repository runtime snapshot, Task 1 URL-test settings.
- Produces: `resolveRouteOutbound(rule, mainProxyTag, proxyTags, routerTagsById): String`.
- Produces: `RouterRuntimeException(groupId: Long, groupName: String, reason: Reason)`.

- [ ] **Step 1: Add config and route regression tests**

```kotlin
@Test fun routeUsesOnlyExplicitCustomGroupReference() {
    val legacy = RuleEntity(name = "Google", outbound = -1, routerGroupId = 0)
    assertEquals(TAG_BYPASS, resolveRouteOutbound(legacy, "proxy", emptyMap(), mapOf(5L to "router.x")))
    assertEquals("router.x", resolveRouteOutbound(legacy.copy(routerGroupId = 5), "proxy", emptyMap(), mapOf(5L to "router.x")))
}

@Test fun sharedNodeTagCanBeReferencedByMultipleRouterOutbounds() {
    val built = buildRouterOutbounds(
        listOf(
            RouterRuntimeGroup(1, "A", "router.a", RouterRuntimeMode.SELECTOR, listOf(9), 9, RouterFilterConfig()),
            RouterRuntimeGroup(2, "B", "router.b", RouterRuntimeMode.URL_TEST, listOf(9), -1, RouterFilterConfig()),
        ),
        proxyTags = mapOf(9L to "node-9"),
    )
    assertEquals(listOf("node-9"), built[0].asMap()["outbounds"])
    assertEquals(listOf("node-9"), built[1].asMap()["outbounds"])
}

@Test fun referencedMissingGroupThrowsInsteadOfFallingBack() {
    val error = assertThrows<RouterRuntimeException> {
        resolveRouteOutbound(RuleEntity(routerGroupId = 88), "proxy", emptyMap(), emptyMap())
    }
    assertEquals(88L, error.groupId)
    assertEquals(RouterRuntimeException.Reason.MISSING, error.reason)
}

@Test fun unreferencedEmptyGroupIsOmitted() {
    assertTrue(buildRouterOutbounds(listOf(emptyRuntimeGroup), emptyMap()).isEmpty())
}
```

For URL-test, assert the generated map contains the configured URL, `300_000_000_000L` interval, and `50` tolerance. Define `emptyRuntimeGroup` in the test as an enabled group with no member IDs.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.fmt.*Router*" --tests "io.nekohasekai.sagernet.route.RouterRuntimeTest"`

Expected: FAIL because semantic matching remains and URL-test settings/group-ID resolution are missing.

- [ ] **Step 3: Remove semantic mapping and default creation**

Delete `ROUTER_US_TAG`, `ROUTER_US_LOW_TAG`, `ROUTER_SG_TAG`, `ROUTER_JP_TAG`, `routerSemanticTag()`, and the call to `ensureDefaultRouterGroups()`. Replace route resolution with:

```kotlin
internal fun resolveRouteOutbound(
    rule: RuleEntity,
    mainProxyTag: String,
    proxyTags: Map<Long, String>,
    routerTagsById: Map<Long, String>,
    primaryProxyId: Long = Long.MIN_VALUE,
): String {
    if (rule.routerGroupId > 0) return routerTagsById[rule.routerGroupId]
        ?: throw RouterRuntimeException(rule.routerGroupId, "", RouterRuntimeException.Reason.MISSING)
    return when (val id = rule.outbound) {
        0L -> mainProxyTag
        -1L -> TAG_BYPASS
        -2L -> TAG_BLOCK
        else -> if (id == primaryProxyId) mainProxyTag else proxyTags[id].orEmpty()
    }
}
```

- [ ] **Step 4: Make runtime group generation strict only for referenced groups**

Build all nodes once into `tagMap`; references from multiple groups reuse those tags. Emit non-empty enabled groups. Before adding route rules, calculate referenced IDs and throw a named error if any reference is absent, disabled, or has no resolved member. Populate `Outbound_URLTestOptions.url`, `.interval` (nanoseconds expected by the generated binding), and `.tolerance`; do not emit an unsupported timeout key.

- [ ] **Step 5: Run focused and full JVM tests**

Run: `.\gradlew.bat :app:testOssDebugUnitTest`

Expected: PASS, including proof that a rule merely named Google keeps its original outbound.

- [ ] **Step 6: Commit configuration behavior**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/fmt app/src/main/java/io/nekohasekai/sagernet/route app/src/test
git commit -m "feat: route explicitly through custom proxy groups"
```

### Task 5: Preserve Targeted Selector Switching and Visible Runtime Errors

**Files:**
- Modify: `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/route/RouterSelection.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/database/RouterGroupRepository.kt`
- Modify: `app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt`
- Modify: `libcore/box.go`
- Test: `app/src/test/java/io/nekohasekai/sagernet/route/RouterSelectionTest.kt`

**Interfaces:**
- Produces: `RouterGroupRepository.select(routerId: Long, proxyId: Long): RouterSelectionPlan`.
- Uses native `SelectOutboundFor(selectorTag: String, outboundTag: String): Boolean` only for selector groups.

- [ ] **Step 1: Update selection tests for arbitrary stable tags and overlap**

Add tests proving selection in group A does not mutate group B even when both contain the same node, non-members are rejected, selector uses targeted switch, and URL-test topology changes request reload.

- [ ] **Step 2: Run selection tests and verify failure**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.route.RouterSelectionTest"`

Expected: FAIL on independent persisted selection/error behavior.

- [ ] **Step 3: Implement selection transaction and runtime action**

Validate group enabled/mode/member, update only that row's `selectedProxyId` and `selectedNodeKey`, call `selectOutboundFor(group.stableTag, proxyTag)` for active selector topology, and reload only when targeted switching is unavailable. Surface native false/exception through the existing service/UI error channel and never change another group.

- [ ] **Step 4: Keep the native API change minimal**

Retain the existing Go `SelectOutboundFor` method and Kotlin wrapper. Do not add new HTTP client APIs. Ensure all Java/Kotlin code imports `libcore.HTTPClient`, never the stale placeholder `libcore.HttpClient`.

- [ ] **Step 5: Run tests**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.route.RouterSelectionTest"`

Expected: PASS.

- [ ] **Step 6: Commit runtime selection**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/bg app/src/main/java/io/nekohasekai/sagernet/route app/src/main/java/moe/matsuri/nb4a libcore
git commit -m "feat: switch custom selector groups independently"
```

### Task 6: Add the Dedicated Custom Group List and Editor

**Files:**
- Create: `app/src/main/java/io/nekohasekai/sagernet/ui/RouterGroupListActivity.kt`
- Create: `app/src/main/java/io/nekohasekai/sagernet/ui/RouterGroupListFragment.kt`
- Create: `app/src/main/java/io/nekohasekai/sagernet/ui/RouterGroupSettingsActivity.kt`
- Create: `app/src/main/res/layout/layout_router_group_list.xml`
- Create: `app/src/main/res/layout/layout_router_group_row.xml`
- Create: `app/src/main/res/xml/router_group_preferences.xml`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt`
- Modify: `app/src/main/res/layout/layout_group.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Create: `app/src/androidTest/java/io/nekohasekai/sagernet/ui/RouterGroupSettingsActivityTest.kt`

**Interfaces:**
- Consumes: repository `all`, `preview`, `save`, `delete`, `select`.
- Produces: `EXTRA_ROUTER_ID = "router_id"`; zero means create.

- [ ] **Step 1: Write editor validation tests**

Test that create starts blank, enabled save requires sources, disabled draft may have no source, invalid include/exclude shows the correct preference error, URL-test fields are visible only in URL-test mode, preview lists exact names/count, rename retains ID/tag, and delete displays reference count rather than deleting.

- [ ] **Step 2: Compile Android tests and confirm missing UI**

Run: `.\gradlew.bat :app:compileOssDebugAndroidTestKotlin`

Expected: FAIL because the activities/resources do not exist.

- [ ] **Step 3: Replace fixed cards with a single navigation entry**

Remove `layout_router_item.xml`, fixed router card adapter/state, `ensureDefaultRouterGroups()`, and manual member dialog from `GroupFragment`. Keep the normal subscription RecyclerView unchanged. Add one “代理组” row/button that opens `RouterGroupListActivity`.

- [ ] **Step 4: Implement the list screen**

Show user order, name, selector/url-test mode, enabled/unavailable state, materialized member count, selected/current node, and `lastError`. Add create, edit, drag/reorder using the project's existing RecyclerView patterns. Selector row tap opens member selection; URL-test row exposes the existing test action when the service supports it.

- [ ] **Step 5: Implement the editor**

Use existing Preference widgets where possible. Source selection is a multi-choice dialog containing only `groupDao.subscriptions()`, stored as ordered IDs. On every name/mode/source/regex change, debounce a repository `preview` call and render `N nodes: name1, name2…`. Save calls repository validation; error text stays on the responsible field. Do not expose fallback/load-balance/nesting options.

- [ ] **Step 6: Compile resources and tests**

Run: `.\gradlew.bat :app:processOssDebugResources :app:compileOssDebugKotlin :app:compileOssDebugAndroidTestKotlin`

Expected: PASS.

- [ ] **Step 7: Commit group UI**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/io/nekohasekai/sagernet/ui app/src/main/res app/src/androidTest/java/io/nekohasekai/sagernet/ui
git commit -m "feat: add custom proxy group editor"
```

### Task 7: Add Explicit Proxy Group Selection to Route Editing

**Files:**
- Create: `app/src/main/java/io/nekohasekai/sagernet/ui/RouterGroupSelectActivity.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/widget/OutboundPreference.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/Constants.kt`
- Modify: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/io/nekohasekai/sagernet/fmt/RouterRouteSemanticTest.kt`

**Interfaces:**
- Produces: `OutboundPreference.VALUE_SELECT_ROUTER = "4"`.
- Produces: `DataStore.routeOutboundRouter: Long` backed by `Key.ROUTE_OUTBOUND + "Router"`.
- Produces: `RouterGroupSelectActivity.EXTRA_ROUTER_ID`.

- [ ] **Step 1: Add route serialization/model tests**

```kotlin
@Test fun selectingRouterAndLegacyTargetsAreMutuallyExclusive() {
    val router = serializeRouteChoice(value = "4", legacyProfileId = 99, routerGroupId = 7)
    assertEquals(0L, router.outbound)
    assertEquals(7L, router.routerGroupId)
    val direct = serializeRouteChoice(value = "1", legacyProfileId = 99, routerGroupId = 7)
    assertEquals(-1L, direct.outbound)
    assertEquals(0L, direct.routerGroupId)
}

@Test fun missingRouterSummaryIsInvalidInsteadOfProxy() {
    val rule = RuleEntity(outbound = 0, routerGroupId = 404)
    assertEquals(app.getString(R.string.router_reference_invalid), rule.displayOutbound())
}
```

Extract the pure `serializeRouteChoice(value, legacyProfileId, routerGroupId): RouteOutboundChoice` helper into `RouteOutboundChoice.kt` so this JVM test does not instantiate an Activity.

- [ ] **Step 2: Run route tests and verify failure**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.fmt.RouterRouteSemanticTest"`

Expected: FAIL because the route editor cannot store a group ID.

- [ ] **Step 3: Add the fifth outbound choice and picker**

Append `@string/route_proxy_group` / value `4` to the arrays. The picker queries enabled groups whose materialized membership is non-empty, returns an ID, and visually marks the current ID. Do not include disabled or unavailable groups.

- [ ] **Step 4: Serialize mutually exclusive route targets**

On init, choose value 4 when `routerGroupId > 0`. On selecting a group set `routeOutboundRouter`; on serialize set `routerGroupId` only for value 4 and set `outbound = 0L`. For values 0–3 set `routerGroupId = 0L` and retain the current legacy logic. Update `RuleEntity.displayOutbound()` and `OutboundPreference.getSummary()` to resolve the current group name or display an invalid-reference message.

- [ ] **Step 5: Run route and config tests**

Run: `.\gradlew.bat :app:testOssDebugUnitTest --tests "io.nekohasekai.sagernet.fmt.*Router*"`

Expected: PASS.

- [ ] **Step 6: Commit route UI**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/ui app/src/main/java/io/nekohasekai/sagernet/widget app/src/main/java/io/nekohasekai/sagernet/database app/src/main/java/io/nekohasekai/sagernet/Constants.kt app/src/main/res app/src/main/AndroidManifest.xml app/src/test
git commit -m "feat: select custom groups in route rules"
```

### Task 8: Make Backup Import/Export Round-Trip the New Relations Safely

**Files:**
- Modify: `app/src/main/java/io/nekohasekai/sagernet/fmt/BackupSerializer.kt`
- Modify: `app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt`
- Modify: `app/src/androidTest/java/io/nekohasekai/sagernet/ui/BackupSerializationTest.kt`

**Interfaces:**
- Produces backup version 3 keys: `routerGroups`, `routerSources`, `routerMembers`, `routerRuleRefs`.
- `routerRuleRefs` is a JSON array of `{ "ruleId": Long, "routerGroupId": Long }`, avoiding any change to the legacy `RuleEntity` Parcel payload.

- [ ] **Step 1: Add round-trip and old-backup tests**

```kotlin
@Test fun versionThreeRoundTripsRelationsAndVersionTwoDefaultsThem() {
    val json = JSONObject().put("version", 3)
    BackupSerializer.putParcelableArray(json, "routerSources", listOf(RouterGroupSource(1, 10), RouterGroupSource(2, 10)))
    assertEquals(
        listOf(RouterGroupSource(1, 10), RouterGroupSource(2, 10)),
        BackupSerializer.getParcelableArray(json, "routerSources", RouterGroupSource.CREATOR),
    )
    val old = JSONObject().put("version", 2)
    assertTrue(BackupSerializer.getParcelableArray(old, "routerSources", RouterGroupSource.CREATOR).isEmpty())
}

@Test fun routeReferencesUseASeparateBackwardCompatibleArray() {
    val refs = listOf(RouterRuleRef(3, 1), RouterRuleRef(4, 404))
    val json = BackupSerializer.putRouterRuleRefs(JSONObject(), refs)
    assertEquals(refs, BackupSerializer.getRouterRuleRefs(json))
}
```

The integration import test inserts referenced group `1` but not `404`, asserts both rule rows remain, and asserts rule `4` displays an invalid reference rather than being rewritten.

- [ ] **Step 2: Run Android test compilation and verify failure**

Run: `.\gradlew.bat :app:compileOssDebugAndroidTestKotlin`

Expected: FAIL because version 3 fields are absent.

- [ ] **Step 3: Implement version 3 export/import**

Set `BACKUP_VERSION = 3`. Export all three Router tables and non-zero rule references. Import in one Room transaction in dependency order: clear members/sources/groups, insert groups, insert valid source/member rows, restore rule references without changing any rule's legacy outbound. Old backups produce empty custom-group relations and leave their decoded rules unchanged.

- [ ] **Step 4: Run backup tests**

Run: `.\gradlew.bat :app:compileOssDebugAndroidTestKotlin`

If a device is connected: `.\gradlew.bat :app:connectedOssDebugAndroidTest --tests "io.nekohasekai.sagernet.ui.BackupSerializationTest"`

Expected: compile PASS and device test PASS when available.

- [ ] **Step 5: Commit backup compatibility**

```powershell
git add app/src/main/java/io/nekohasekai/sagernet/fmt/BackupSerializer.kt app/src/main/java/io/nekohasekai/sagernet/ui/BackupFragment.kt app/src/androidTest/java/io/nekohasekai/sagernet/ui/BackupSerializationTest.kt
git commit -m "feat: back up custom proxy group relations"
```

### Task 9: Turn the Native HTTPClient Mismatch into a Clean-Build Gate

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/moe/matsuri/nb4a/LibcoreAbiTest.kt`
- Inspect only: `app/libs/libcore.aar`

**Interfaces:**
- Produces Gradle task `verifyLibcoreAbi` and makes `preBuild` depend on it.
- Verifies `Libcore.newHttpClient:()Llibcore/HTTPClient;` consistently in AAR classes and compiled app references.

- [ ] **Step 1: Add an ABI test against the real AAR**

The test loads `libcore.Libcore` and asserts `newHttpClient().javaClass.name == "libcore.HTTPClient"`, plus checks `RawUpdater`/callers contain no `libcore.HttpClient` descriptor.

- [ ] **Step 2: Run from a clean app build directory and reproduce the gate**

Run: `.\gradlew.bat :app:clean :app:testOssDebugUnitTest --tests "moe.matsuri.nb4a.LibcoreAbiTest"`

Expected before completing the gate: FAIL if a placeholder jar or stale descriptor is on the compile/runtime classpath; otherwise PASS and record that the original installed APK was stale incremental output.

- [ ] **Step 3: Strengthen `verifyLibcore` into `verifyLibcoreAbi`**

Keep the existing checks for `classes.jar` and four JNI `libgojni.so` entries. Add class inspection using Gradle/JDK tooling to require `libcore/HTTPClient.class`, reject `libcore/HttpClient.class`, and inspect the compiled caller descriptor after Kotlin compilation. Do not generate or copy a placeholder libcore jar.

- [ ] **Step 4: Verify the packaged APK**

Run: `.\gradlew.bat :app:clean :app:assembleOssDebug`

Then inspect `app/build/outputs/apk/oss/debug/*.apk` and assert it contains all expected ABI libraries and exactly the `HTTPClient` class descriptor used by app bytecode.

Expected: build PASS; no `Llibcore/HttpClient;` string in compiled DEX/classes; `Llibcore/HTTPClient;` present.

- [ ] **Step 5: Commit the ABI gate**

```powershell
git add app/build.gradle.kts app/src/test/java/moe/matsuri/nb4a/LibcoreAbiTest.kt
git commit -m "build: reject mismatched libcore HTTP client ABI"
```

### Task 10: Full Regression, Scope Audit, and Android Handoff

**Files:**
- Modify if needed: only files already listed above
- Create: `docs/superpowers/verification/2026-09-04-custom-proxy-groups.md`

**Interfaces:**
- Produces a reproducible verification record and the APK path/hash.

- [ ] **Step 1: Run all JVM and Android-test compilation checks**

```powershell
.\gradlew.bat :app:testOssDebugUnitTest :app:compileOssDebugAndroidTestKotlin
```

Expected: PASS with no fixed-default/non-overlap/semantic-route tests remaining.

- [ ] **Step 2: Run lint and classify only real regressions**

Run: `.\gradlew.bat :app:lintOssDebug`

Expected: PASS, or record pre-existing unrelated findings separately without broad cleanup.

- [ ] **Step 3: Perform the final clean debug build**

Run: `.\gradlew.bat :app:clean :app:assembleOssDebug`

Expected: PASS and ABI gate runs automatically.

- [ ] **Step 4: Audit scope and protected files**

```powershell
git status --short
git diff --stat f8e6418
git diff -- app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt app/src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt
git status --short -- ../A7.yaml ../nekobox_isA8.json
```

Expected: no protected-file change; no automatic route reassignment; no unrelated DNS/TUN, AdBlock, `.invalid`, subscription link, or ordinary-profile mutation.

- [ ] **Step 5: Record hashes and static verification**

Write the exact Gradle exit results, APK absolute path, SHA-256, schema version, ABI descriptor check, test totals, lint result, and any unavailable emulator checks to the verification document.

- [ ] **Step 6: Install only when a connected authorized device is visible**

Run: `adb devices -l`. If exactly one authorized device is present, run `.\gradlew.bat :app:installOssDebug`; otherwise do not guess a target and leave the APK ready.

- [ ] **Step 7: Execute the real-device acceptance checklist**

On Android: upgrade without clearing data; refresh subscription 1 and 2; create `US1` with both sources and a US regex; create a second overlapping group; verify selector isolation; verify URL-test selects a reachable node; explicitly route one test rule to `US1`; refresh both sources; confirm route/group survival; confirm AdBlock, `.invalid`, App routing, DNS/TUN, subscriptions, and ordinary nodes remain functional. Capture the exact log line for any failure.

- [ ] **Step 8: Request code review and address only confirmed issues**

Use `superpowers:requesting-code-review`, compare implementation to the approved spec and this plan, rerun the affected test after each correction, then rerun Steps 1–4.

- [ ] **Step 9: Commit the verification record**

```powershell
git add docs/superpowers/verification/2026-09-04-custom-proxy-groups.md
git commit -m "docs: record custom proxy group verification"
```
