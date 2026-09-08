package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import io.nekohasekai.sagernet.fmt.BackupSerializer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.nekohasekai.sagernet.fmt.KryoConverters
import java.text.SimpleDateFormat

class BackupFragment : ToolbarFragment(R.layout.layout_backup) {

    private var pendingExportName: String? = null
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null

    override fun onDestroyView() {
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportName = savedInstanceState?.getString("pendingExportName")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportName", pendingExportName)
        super.onSaveInstanceState(outState)
    }

    private fun backupFileName() =
        "asteria_backup_${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())}.json"

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        val pending = pendingExportName?.let { File(app.cacheDir, it) }
        pendingExportName = null
        if (data != null) {
            lifecycleScope.launch {
                try {
                    onDefaultDispatcher {
                        check(pending?.isFile == true) { "Backup snapshot is unavailable; export again" }
                        pending.inputStream().use { input ->
                            checkNotNull(app.contentResolver.openOutputStream(data, "wt")) {
                                "Unable to open backup destination"
                            }.use { input.copyTo(it) }
                        }
                    }
                    if (view != null) {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    if (view != null) {
                        snackbar(e.readableMessage).show()
                    }
                } finally {
                    pending?.delete()
                }
            }
        } else {
            pending?.delete()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.backup)

        val binding = LayoutBackupBinding.bind(view)

        binding.actionExport.setOnClickListener {
            if (currentJob?.isActive == true || pendingExportName != null) return@setOnClickListener
            val profiles = binding.backupConfigurations.isChecked
            val rules = binding.backupRules.isChecked
            val settings = binding.backupSettings.isChecked
            currentJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val cacheFile = onDefaultDispatcher {
                        val bytes = doBackup(profiles, rules, settings)
                        File(app.cacheDir, backupFileName()).apply { writeBytes(bytes) }
                    }
                    pendingExportName = cacheFile.name
                    startFilesForResult(exportSettings, cacheFile.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    pendingExportName = null
                    snackbar(e.readableMessage).show()
                }
            }
        }

        binding.actionShare.setOnClickListener {
            if (currentJob?.isActive == true || pendingExportName != null) return@setOnClickListener
            val profiles = binding.backupConfigurations.isChecked
            val rules = binding.backupRules.isChecked
            val settings = binding.backupSettings.isChecked
            currentJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val cacheFile = onDefaultDispatcher {
                        val bytes = doBackup(profiles, rules, settings)
                        File(app.cacheDir, backupFileName()).apply { writeBytes(bytes) }
                    }
                    onMainDispatcher {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json")
                                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .putExtra(
                                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                            app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                        )
                                    ), app.getString(R.string.abc_shareactionprovider_share_with)
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

    }

    private fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean
    ): ByteArray {
        val out = BackupSerializer.exportDatabase(SagerDatabase.instance, profile, rule).apply {
            if (setting) {
                BackupSerializer.putParcelableArray(this, "settings", PublicDatabase.kvPairDao.all())
            }
        }

        val jsonContent = out.toStringPretty()
        return jsonContent.toByteArray()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = activity ?: return
        try {
            val fileName = onDefaultDispatcher {
                activity.contentResolver.query(file, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                }?.takeIf { it.isNotBlank() }
                    ?: file.lastPathSegment.orEmpty().substringAfterLast('/').substringAfter(':')
            }
            if (!fileName.endsWith(".json", true) && !fileName.endsWith(".zip", true)) {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
                return
            }
            val content = onDefaultDispatcher {
                checkNotNull(activity.contentResolver.openInputStream(file)).use { input ->
                if (fileName.endsWith(".zip", true)) {
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytes().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }
            }

            val json = JSONObject(content)
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles")) {
                    import.backupConfigurations.isVisible = false
                }
                if (!json.has("rules")) {
                    import.backupRules.isVisible = false
                }
                if (!json.has("settings")) {
                    import.backupSettings.isVisible = false
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        val profiles = import.backupConfigurations.isChecked
                        val rules = import.backupRules.isChecked
                        val settings = import.backupSettings.isChecked
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        val appContext = requireContext().applicationContext
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.Default + NonCancellable) {
                                    finishImport(json, profiles, rules, settings)
                                    triggerFullRestart(appContext)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logs.w(e)
                                MessageStore.showMessage(e.readableMessage)
                            } finally {
                                try {
                                    dialog.dismiss()
                                } catch (e: Exception) {
                                    // Ignored if window already detached
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        KryoConverters.withStrictDeserialization {
            val decodedSettings = if (setting) {
                require(content.has("settings") && !content.isNull("settings")) {
                    "Backup settings are missing or invalid"
                }
                val list = BackupSerializer.getParcelableArray(content, "settings", KeyValuePair.CREATOR)
                val filteredList = list.filterNot { it.key in DataStore.DEPRECATED_SETTING_KEYS }
                val keys = HashSet<String>(filteredList.size)
                for (kv in filteredList) {
                    require(keys.add(kv.key)) { "Duplicate setting key in backup: ${kv.key}" }
                    kv.validate()
                }
                filteredList
            } else null

            data class DecodedProfiles(
                val profiles: List<ProxyEntity>,
                val groups: List<ProxyGroup>,
                val routerGroups: List<RouterGroup>,
                val routerMembers: List<RouterMember>,
                val routerSources: List<RouterGroupSource>,
                val validGroupIds: Set<Long>,
                val validRouterIds: Set<Long>,
                val validProxyIds: Set<Long>,
            )

            val decodedProfileData = if (profile) {
                require(content.has("profiles") && !content.isNull("profiles")) {
                    "Backup profiles are missing or invalid"
                }
                require(content.has("groups") && !content.isNull("groups")) {
                    "Backup groups are missing or invalid"
                }
                val profilesList = BackupSerializer.getParcelableArray(content, "profiles", ProxyEntity.CREATOR)
                val groupsList = BackupSerializer.getParcelableArray(content, "groups", ProxyGroup.CREATOR)
                val routerGroupsList = BackupSerializer.getParcelableArray(content, "routerGroups", RouterGroup.CREATOR)
                val routerMembersList = BackupSerializer.getParcelableArray(content, "routerMembers", RouterMember.CREATOR)
                val routerSourcesList = BackupSerializer.getParcelableArray(content, "routerSources", RouterGroupSource.CREATOR)

                require(groupsList.all { g -> g.id > 0L && (g.ungrouped || !g.name.isNullOrBlank()) }) {
                    "Backup contains invalid or blank proxy groups"
                }
                require(routerGroupsList.all { it.id > 0L && it.stableTag.isNotBlank() }) {
                    "Backup contains invalid or blank router groups"
                }

                profilesList.forEach { it.requireBean() }
                val groupIds = groupsList.mapTo(hashSetOf()) { it.id }
                require(profilesList.all { it.groupId in groupIds }) {
                    "Backup contains profiles without a group"
                }

                val routerIds = routerGroupsList.mapTo(hashSetOf()) { it.id }
                val proxyIds = profilesList.mapTo(hashSetOf()) { it.id }

                require(routerMembersList.all { it.routerId in routerIds && it.proxyId in proxyIds }) {
                    "Backup contains router members referencing missing routers or profiles"
                }
                require(routerSourcesList.all { it.routerId in routerIds && it.sourceGroupId in groupIds }) {
                    "Backup contains router sources referencing missing routers or groups"
                }

                // R05: Validate frontProxy and landingProxy on groups
                for (group in groupsList) {
                    if (group.frontProxy > 0L) {
                        require(group.frontProxy in proxyIds) {
                            "Group ${group.id} references missing frontProxy ${group.frontProxy}"
                        }
                    }
                    if (group.landingProxy > 0L) {
                        require(group.landingProxy in proxyIds) {
                            "Group ${group.id} references missing landingProxy ${group.landingProxy}"
                        }
                    }
                }

                // R05: Validate ChainBean references and cycles
                val chainEdges = HashMap<Long, List<Long>>()
                for (proxy in profilesList) {
                    val bean = proxy.requireBean()
                    if (bean is io.nekohasekai.sagernet.fmt.internal.ChainBean) {
                        require(bean.proxies.isNotEmpty()) {
                            "Chain proxy ${proxy.id} has empty proxy list"
                        }
                        for (targetId in bean.proxies) {
                            require(targetId in proxyIds) {
                                "Chain proxy ${proxy.id} references missing proxy $targetId"
                            }
                        }
                        chainEdges[proxy.id] = bean.proxies
                    }
                }
                val visitedChainNodes = HashSet<Long>()
                val callChainStack = HashSet<Long>()
                fun checkChainCycle(node: Long) {
                    if (node in callChainStack) {
                        throw IllegalArgumentException("Cyclic chain reference detected involving proxy $node")
                    }
                    if (node in visitedChainNodes) return
                    visitedChainNodes.add(node)
                    callChainStack.add(node)
                    for (target in chainEdges[node].orEmpty()) {
                        checkChainCycle(target)
                    }
                    callChainStack.remove(node)
                }
                for (node in chainEdges.keys) {
                    checkChainCycle(node)
                }

                DecodedProfiles(
                    profiles = profilesList,
                    groups = groupsList,
                    routerGroups = routerGroupsList,
                    routerMembers = routerMembersList,
                    routerSources = routerSourcesList,
                    validGroupIds = groupIds,
                    validRouterIds = routerIds,
                    validProxyIds = proxyIds,
                )
            } else null

            val decodedRules = if (rule) {
                require(content.has("rules") && !content.isNull("rules")) {
                    "Backup rules are missing or invalid"
                }
                val routerReferences = BackupSerializer.getRouterRuleReferences(content)
                val routerRuleRefList = BackupSerializer.getRouterRuleReferenceList(content).associateBy { it.ruleId }
                val rulesList = BackupSerializer.getParcelableArray(content, "rules") {
                    ParcelizeBridge.createRule(it)
                }.map { imported ->
                    val routerGroupId = routerReferences[imported.id] ?: 0L
                    imported.copy(routerGroupId = routerGroupId)
                }

                if (decodedProfileData != null) {
                    BackupSerializer.validateRuleReferences(rulesList, decodedProfileData.validRouterIds, decodedProfileData.validProxyIds)
                    val backupRouterTags = decodedProfileData.routerGroups.associate { it.id to it.stableTag }
                    for (ruleItem in rulesList) {
                        if (ruleItem.routerGroupId > 0L) {
                            val ref = routerRuleRefList[ruleItem.id]
                            if (ref?.routerStableTag != null) {
                                val actualTag = backupRouterTags[ruleItem.routerGroupId]
                                require(actualTag == ref.routerStableTag) {
                                    "Rule ${ruleItem.id} references router group ${ruleItem.routerGroupId} with mismatched tag: expected ${ref.routerStableTag}, actual $actualTag"
                                }
                            }
                        }
                    }
                } else {
                    // R03: Partial restore "只恢复规则" (Rules only, local database profiles kept)
                    val localRouters = SagerDatabase.routerGroupDao.all().associate { it.id to it.stableTag }
                    val localProxies = SagerDatabase.proxyDao.getAll().associate { it.id to it.routerStableId() }
                    val backupRouters = if (content.has("routerGroups") && !content.isNull("routerGroups")) {
                        runCatching {
                            BackupSerializer.getParcelableArray(content, "routerGroups", RouterGroup.CREATOR).associate { it.id to it.stableTag }
                        }.getOrNull().orEmpty()
                    } else emptyMap()

                    // R03 fix: read the stable-identity index emitted by the exporter when
                    // profiles were not included. Falls back to inline profiles if present.
                    val backupOutboundStableIds: Map<Long, String> =
                        BackupSerializer.getRuleOutboundStableIds(content).ifEmpty {
                            // Legacy fallback: backup was produced with profiles section
                            if (content.has("profiles") && !content.isNull("profiles")) {
                                runCatching {
                                    BackupSerializer.getParcelableArray(content, "profiles", ProxyEntity.CREATOR)
                                        .associate { it.id to it.routerStableId() }
                                }.getOrNull().orEmpty()
                            } else emptyMap()
                        }

                    for (ruleItem in rulesList) {
                        if (ruleItem.routerGroupId > 0L) {
                            val expectedTag = routerRuleRefList[ruleItem.id]?.routerStableTag
                                ?: backupRouters[ruleItem.routerGroupId]
                            require(expectedTag != null) {
                                "Cannot verify stable identity for router group ${ruleItem.routerGroupId} referenced by rule ${ruleItem.id}"
                            }
                            val localTag = localRouters[ruleItem.routerGroupId]
                            require(localTag != null && localTag == expectedTag) {
                                "Rule ${ruleItem.id} references router group ${ruleItem.routerGroupId} whose identity does not match local router (expected $expectedTag, found $localTag)"
                            }
                        } else if (ruleItem.outbound > 0L) {
                            // R03 fix: use the stable ID from ruleOutboundRefs instead of requiring profiles
                            val expectedStableId = backupOutboundStableIds[ruleItem.id]
                                ?: backupOutboundStableIds[ruleItem.outbound]
                            require(expectedStableId != null) {
                                "Cannot verify stable identity for profile ${ruleItem.outbound} referenced by rule ${ruleItem.id}: export does not contain profile identity information"
                            }
                            val localStableId = localProxies[ruleItem.outbound]
                            require(localStableId != null && localStableId == expectedStableId) {
                                "Rule ${ruleItem.id} references profile ${ruleItem.outbound} whose identity does not match local profile"
                            }
                        }
                    }
                }
                rulesList
            } else null

            // R02: Partial restore "只恢复配置" (Profiles only, local rules kept)
            if (decodedProfileData != null && decodedRules == null) {
                val existingRules = SagerDatabase.rulesDao.allRules()
                val incomingRouterTags = decodedProfileData.routerGroups.associate { it.id to it.stableTag }
                val incomingProxyTags = decodedProfileData.profiles.associate { it.id to it.routerStableId() }
                val localRouters = SagerDatabase.routerGroupDao.all().associate { it.id to it.stableTag }
                val localProxies = SagerDatabase.proxyDao.getAll().associate { it.id to it.routerStableId() }

                for (rule in existingRules) {
                    if (rule.routerGroupId > 0L) {
                        val localTag = localRouters[rule.routerGroupId]
                        require(localTag != null) {
                            "Existing rule ${rule.id} references missing local router ${rule.routerGroupId}"
                        }
                        val incomingTag = incomingRouterTags[rule.routerGroupId]
                        require(incomingTag != null && incomingTag == localTag) {
                            "Existing rule ${rule.id} references router ${rule.routerGroupId} whose identity does not match incoming configuration (local: $localTag, incoming: $incomingTag)"
                        }
                    } else if (rule.outbound > 0L) {
                        val localStableId = localProxies[rule.outbound]
                        require(localStableId != null) {
                            "Existing rule ${rule.id} references missing local profile ${rule.outbound}"
                        }
                        val incomingStableId = incomingProxyTags[rule.outbound]
                        require(incomingStableId != null && incomingStableId == localStableId) {
                            "Existing rule ${rule.id} references profile ${rule.outbound} whose identity does not match incoming configuration"
                        }
                    }
                }
            }

            SagerDatabase.instance.runInTransaction {
                if (decodedProfileData != null) {
                    SagerDatabase.routerGroupSourceDao.reset()
                    SagerDatabase.routerMemberDao.reset()
                    SagerDatabase.routerGroupDao.reset()
                    SagerDatabase.proxyDao.reset()
                    SagerDatabase.groupDao.reset()

                    SagerDatabase.groupDao.insert(decodedProfileData.groups)
                    SagerDatabase.proxyDao.insert(decodedProfileData.profiles)
                    if (decodedProfileData.routerGroups.isNotEmpty()) {
                        SagerDatabase.routerGroupDao.insert(decodedProfileData.routerGroups)
                    }
                    if (decodedProfileData.routerMembers.isNotEmpty()) {
                        SagerDatabase.routerMemberDao.insert(decodedProfileData.routerMembers)
                    }
                    if (decodedProfileData.routerSources.isNotEmpty()) {
                        SagerDatabase.routerGroupSourceDao.insert(decodedProfileData.routerSources)
                    }
                    SagerDatabase.routerGroupDao.clearInvalidSelections()
                }

                if (decodedRules != null) {
                    SagerDatabase.rulesDao.reset()
                    SagerDatabase.rulesDao.insert(decodedRules)
                }
            }
            if (decodedProfileData != null) {
                GroupManager.cleanupDanglingRouterMembers()
            }
            if (decodedSettings != null) {
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset()
                    PublicDatabase.kvPairDao.insert(decodedSettings)
                }
            }
        }
    }

    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }
    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
