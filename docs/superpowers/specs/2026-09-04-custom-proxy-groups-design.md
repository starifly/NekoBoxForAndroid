# NekoBox Custom Proxy Groups Design

## Goal

Add user-defined proxy groups to NekoBox for Android with FlClash-like source selection and filtering. A user can create any number of groups, select one or more subscription sources, filter their nodes, choose `selector` or `url-test`, and select the group as a route outbound. Existing subscription, routing, AdBlock, App routing, DNS, TUN, and ordinary node behavior must remain unchanged unless the user explicitly uses a custom group.

## Confirmed Scope

- Groups are created, named, edited, reordered, enabled, and deleted by the user.
- No predefined `US`, `US low`, `SG`, or `JP` groups are created.
- A node may belong to multiple custom groups.
- Each group selects one or more existing subscription groups as sources.
- The first version supports an optional include regular expression and an optional exclude regular expression against the displayed node name.
- An empty include expression includes every node from the selected sources. The exclude expression is applied after the include expression and wins when both match.
- Invalid regular expressions prevent saving and show a validation error.
- The first version supports only `selector` and `url-test` modes.
- `fallback`, `load-balance`, group nesting, protocol filtering, multiplier filtering, region enums, and nested logical condition trees are out of scope.
- Existing route rules can explicitly select a custom group as their outbound.
- No route is assigned to a custom group by its name or content automatically.
- Full Clash/Mihomo YAML import is out of scope.

## FlClash Reference Boundary

Use FlClash's proxy-group model and editor as behavioral references, especially its `use`, `filter`, `exclude-filter`, `url`, `interval`, and group-type fields. NekoBox remains a Kotlin/Room/sing-box application, so Flutter widgets and Mihomo runtime code will not be copied mechanically. Any concrete copied code must be compatible with GPL-3.0 and retain appropriate attribution.

References inspected at FlClash commit `62addf738a76b1a492e19af2dbabdb6d572b9e72`:

- `lib/models/clash_config.dart`
- `lib/database/groups.dart`
- `lib/views/profiles/overwrite/custom/groups.dart`

## Data Model

### Custom group

Reuse the existing Router group concept but make it fully user-defined. Each record stores:

- database ID;
- immutable stable tag generated at creation and never derived from the display name;
- unique non-blank display name;
- mode: `selector` or `url-test`;
- enabled state and user order;
- include regex and exclude regex;
- URL-test URL, interval, tolerance, and timeout using safe project defaults when omitted;
- selected node stable identity for selector mode.

The UI calls this feature "Proxy groups" or its Chinese equivalent. Internal Router naming may remain in implementation types where changing it would create unnecessary risk.

### Source relationship

Store a many-to-many relationship between a custom group and subscription `ProxyGroup` records. A group can use multiple subscriptions, and one subscription can feed multiple groups.

### Materialized membership

Persist the latest resolved members so selection state and error reporting survive refreshes. Membership is many-to-many: there is no uniqueness constraint on `proxyId` across different custom groups. Stable node identity is scoped by subscription source so identical nodes in different subscriptions are not conflated.

### Route relationship

Add an optional custom-group reference to `RuleEntity`. Preserve the existing numeric `outbound` field and its semantics for legacy proxy, direct, block, and profile targets. A route selects either its legacy outbound or a custom-group ID, never both. Configuration generation resolves the ID to the group's immutable stable tag. Renaming a group therefore does not break routes.

Database and backup versions must migrate incrementally. Existing debug installations using the current Router schema must also migrate without deleting subscriptions or rules.

## Membership Resolution

For each enabled group:

1. Load nodes belonging to the selected subscription sources.
2. Preserve source order and node order deterministically.
3. Apply the include regex when non-empty.
4. Apply the exclude regex and remove every match.
5. Deduplicate the same node within that group only.
6. Allow the same node to appear independently in other groups.
7. Materialize the result and remap the selected node using stable identity after subscription refresh.

Membership is recomputed after importing, updating, deleting, or clearing a subscription and before configuration generation if stored membership is stale. A failed or empty subscription refresh does not erase the last valid membership snapshot. Deleting a source removes that source from group criteria and triggers recomputation.

## Runtime Configuration

For every enabled non-empty group, `ConfigBuilder` emits one sing-box outbound using the stable tag:

- `selector`: member outbound tags plus the persisted selected member as `default`;
- `urltest`: member outbound tags plus the configured test URL, interval, tolerance, and timeout supported by the pinned sing-box version.

Normal node outbounds are built once and may be referenced by multiple groups. A group tag must not collide with system or node tags.

Changing a selector choice attempts a targeted runtime switch for that group. If safe targeted switching is unavailable, perform a full service reload and report failure visibly. Editing criteria or changing mode performs a full reload because group topology changed.

## Route Editing

Extend the existing route outbound picker with a "Proxy group" choice. Selecting it opens a list of enabled non-empty custom groups and stores the group ID. Rule summaries display the current group name.

No semantic name matching is permitted. Existing Google, Telegram, YouTube, App, AdBlock, `.invalid`, direct, block, and profile-target rules retain their stored outbound until the user edits them.

Deleting a group referenced by routes is blocked with a message listing the number of references. The user must reassign those routes first. This prevents silent traffic diversion.

## UI Flow

Add a dedicated custom-group list and editor rather than embedding fixed cards in the subscription list.

The editor contains:

- group name;
- mode (`selector` or `url-test`);
- subscription sources, multi-select;
- include regex;
- exclude regex;
- URL-test settings shown only in `url-test` mode;
- live preview showing the matched node count and node names;
- enabled switch and save/delete actions.

The runtime group view shows mode, member count, selected/current node, and the latest resolution error. A selector group opens its member list for manual selection. A URL-test group shows the core-selected current node and supports triggering the existing group test behavior where available.

## Error Handling

- Invalid regex: reject save and identify the invalid field.
- No source selected: allow saving a disabled draft only; an enabled group requires at least one source.
- No matched nodes: save the group but mark it unavailable. If an enabled route references it, service configuration/start fails with a clear group-specific error rather than silently using another outbound.
- Subscription refresh failure: retain the last valid members and show the refresh error.
- Missing source or member: remove stale relationships during reconciliation and keep the group itself.
- Missing referenced group: preserve the rule record, show it as invalid, and refuse to generate a silently altered route.
- Native core mismatch: clean builds must verify `libcore.aar`, its JNI libraries, and the compiled `newHttpClient()` descriptor before producing an APK.

## Migration from the Current Incorrect Implementation

Retain only reusable infrastructure:

- Router Room entities and DAOs where their schema remains suitable;
- stable node identity and refresh reconciliation;
- selector/url-test outbound generation;
- targeted selector switching in libcore;
- native-core build verification.

Remove or replace:

- automatic creation of four fixed groups;
- node exclusivity across groups;
- semantic route-name mapping to fixed tags;
- the fixed group cards and manual-only membership dialog;
- tests that assert four predefined groups or non-overlapping membership.

Existing routes, subscriptions, and settings outside this feature are not normalized or rewritten.

## Testing and Acceptance

### Automated

- Matcher tests for multiple sources, include/exclude precedence, invalid regex, deterministic order, and overlapping groups.
- Reconciliation tests for refreshed node IDs, renamed nodes, failed/empty refreshes, source deletion, and selected-node preservation.
- Room migration and DAO tests for current database versions through the new version.
- Backup round-trip and old-backup import tests.
- Config tests proving correct selector/url-test JSON, shared nodes across groups, stable tags, no dangling references, and no changes to unrelated routes.
- Route editor/model tests proving explicit group selection and preservation of legacy outbound values.
- Clean-build ABI check proving app bytecode and packaged libcore use the same `HTTPClient` descriptor.
- JVM tests, Android lint assessment, and debug APK build.

### Real Android device

1. Upgrade without losing existing subscriptions or routes.
2. Import and refresh at least two subscriptions.
3. Create `US1` using both subscriptions and a US include regex.
4. Create another group reusing at least one of the same nodes.
5. Verify selector mode changes only the selected group.
6. Verify URL-test mode chooses a reachable member.
7. Assign a route explicitly to `US1` and confirm runtime routing/log output.
8. Refresh both subscriptions and confirm both groups and the route remain valid.
9. Confirm AdBlock, `.invalid` load rules, App routing, DNS/TUN, and ordinary node operation remain unchanged.

## Delivery Boundary

Do not commit or ship an APK until the clean-build ABI check and automated tests pass. Static tests do not replace the real-device acceptance steps. Keep the current installed debug build recoverable until its subscriptions and settings have been migrated or backed up.
