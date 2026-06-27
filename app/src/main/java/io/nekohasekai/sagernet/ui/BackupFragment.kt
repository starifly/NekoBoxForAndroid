package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ktx.snackbar
import moe.matsuri.nb4a.utils.Util
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private lateinit var binding: LayoutBackupBinding
    private lateinit var backupData: ByteArray
    private var isWebDAVBackup = false
    private var isBackupInProgress = false
    private var isRestoreInProgress = false
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null
    private var restoreJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        super.onDestroyView()
        snackbar?.dismiss()
        snackbar = null
        // if a restore operation is in progress, cancel it
        if (isRestoreInProgress) {
            restoreJob?.cancel()
            restoreJob = null
            isRestoreInProgress = false
            MessageStore.showMessage(requireActivity(), R.string.restore_cancelled)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    requireActivity().contentResolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                backupData = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked,
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings,
                        "nekobox_backup_${Date().toLocaleString()}.json",
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                backupData = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked,
                )
                val shareDir = File(app.cacheDir, "share").apply { mkdirs() }
                val cacheFile = File(
                    shareDir,
                    "nekobox_backup_${Date().toLocaleString()}.json",
                )
                cacheFile.writeBytes(backupData)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM,
                                    FileProvider.getUriForFile(
                                        app,
                                        BuildConfig.APPLICATION_ID + ".cache",
                                        cacheFile,
                                    ),
                                ),
                            app.getString(R.string.abc_shareactionprovider_share_with),
                        ),
                    )
                }
            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        setupWebDAV(binding)
    }

    private fun setupWebDAV(binding: LayoutBackupBinding) {
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }

        binding.backupToWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            backupToWebDAV()
        }

        binding.restoreFromWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            restoreFromWebDAV()
        }
    }

    private fun backupToWebDAV() {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        isBackupInProgress = true
        val activity = requireActivity()
        runOnDefaultDispatcher {
            try {
                isWebDAVBackup = true
                val backupData = doBackup(
                    true, // back up configs and groups
                    true, // back up route rules
                    true, // back up settings
                )
                isWebDAVBackup = false

                val client = OkHttpClient()

                // normalize URL
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                // use an English-formatted timestamp as the file name, with a .zip suffix
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val version = BuildConfig.VERSION_NAME
                val fileName = "nekobox_backup_${version}_$timestamp.zip"

                // ensure baseUrl is a valid URL that uses TLS
                // (WebDAV backup contains all config keys, plaintext http:// is forbidden)
                val baseHttpUrl = WebDAVSecurity.requireSecureUrl(baseUrl)

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(fileName)
                    .build()

                Logs.d("WebDAV backup - Directory URL: $dirUrl")
                Logs.d("WebDAV backup - File URL: $fileUrl")

                // first check whether the directory exists
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header(
                        "Authorization",
                        Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: "",
                        ),
                    )
                    .header("Depth", "0")
                    .build()

                var needCreateDir = false
                client.newCall(propfindRequest).execute().use { response ->
                    Logs.d("WebDAV backup - PROPFIND response: ${response.code}")
                    when (response.code) {
                        404 -> needCreateDir = true
                        207 -> needCreateDir = false // directory exists
                        401 -> throw Exception("Authentication failed")
                        else -> {
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string()
                                Logs.e("WebDAV backup - PROPFIND error: $errorBody")
                                throw Exception("Failed to check directory (${response.code}): ${response.message}")
                            }
                        }
                    }
                }

                // create the directory if needed
                if (needCreateDir) {
                    Logs.d("WebDAV backup - Creating directory")
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header(
                            "Authorization",
                            Credentials.basic(
                                DataStore.webdavUsername ?: "",
                                DataStore.webdavPassword ?: "",
                            ),
                        )
                        .build()

                    client.newCall(mkcolRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string()
                            Logs.e("WebDAV backup - MKCOL error: $errorBody")
                            throw Exception("Failed to create directory (${response.code}): ${response.message}")
                        }
                    }
                }

                // use the correct Content-Type when uploading the file
                val putRequest = Request.Builder()
                    .url(fileUrl)
                    .put(backupData.toRequestBody("application/zip".toMediaType()))
                    .apply {
                        header(
                            "Authorization",
                            Credentials.basic(
                                DataStore.webdavUsername ?: "",
                                DataStore.webdavPassword ?: "",
                            ),
                        )
                    }
                    .build()

                client.newCall(putRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV backup - PUT error: $errorBody")
                        throw Exception("Upload failed (${response.code}): ${response.message}\n$errorBody")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, R.string.webdav_backup_success)
                }
            } catch (e: Exception) {
                isWebDAVBackup = false // ensure the flag is reset even when an exception occurs
                Logs.w(e)

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, e.message ?: "")
                    } else {
                        app.getString(R.string.webdav_backup_failed, e.message ?: "")
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${e.message ?: ""}"
                }

                onMainDispatcher {
                    MessageStore.showMessage(activity, errorMessage)
                }
            } finally {
                isBackupInProgress = false
            }
        }
    }

    private fun restoreFromWebDAV() {
        if (isRestoreInProgress) {
            showMessage(R.string.restore_in_progress)
            return
        }
        isRestoreInProgress = true
        val activity = requireActivity()
        restoreJob = runOnDefaultDispatcher {
            try {
                val client = OkHttpClient()
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Nekobox"

                // WebDAV backup contains all config keys, plaintext http:// is forbidden
                val baseHttpUrl = WebDAVSecurity.requireSecureUrl(baseUrl)

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                Logs.d("WebDAV restore - Directory URL: $dirUrl")

                // first list the directory contents to find the latest backup file
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header(
                        "Authorization",
                        Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: "",
                        ),
                    )
                    .header("Depth", "1")
                    .build()

                // get the latest backup file name
                val latestBackup = client.newCall(propfindRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - PROPFIND error: $errorBody")
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    Logs.d("WebDAV restore - Directory listing: $responseBody")

                    val patterns = listOf(
                        """<D:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</D:href>""".toRegex(),
                        """<d:href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</d:href>""".toRegex(),
                        """<href>[^<]*?nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</href>""".toRegex(),
                    )

                    val backupFiles = mutableListOf<String>()

                    for (pattern in patterns) {
                        val matches = pattern.findAll(responseBody)
                        matches.forEach { match ->
                            val href = match.value
                            Logs.d("WebDAV restore - Found backup file with pattern ${pattern.pattern}: $href")
                            val fileName = """nekobox_backup_[^<]*?\d{8}_\d{6}\.(json|zip)""".toRegex()
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }

                    Logs.d("WebDAV restore - Found ${backupFiles.size} backup files: ${backupFiles.joinToString()}")

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // download the latest backup file
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()
                Logs.d("WebDAV restore - File URL: $fileUrl")

                val getRequest = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header(
                        "Authorization",
                        Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: "",
                        ),
                    )
                    .build()

                val content = client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        Logs.e("WebDAV restore - GET error: $errorBody")
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.byteStream()?.use { it.readBytesBounded() }
                        ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                // process the content based on file type
                val backupContent = if (latestBackup.endsWith(".zip")) {
                    // ZIP file handling
                    ZipInputStream(content.inputStream()).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytesBounded().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    // JSON file handling
                    content.toString(Charsets.UTF_8)
                }

                // parse and import the backup data
                val json = JSONObject(backupContent)
                onMainDispatcher {
                    // if the Fragment has been destroyed, cancel the restore operation
                    if (!isAdded) {
                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                        return@onMainDispatcher
                    }

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
                            SagerNet.stopService()

                            val binding = LayoutProgressBinding.inflate(layoutInflater)
                            binding.content.text = getString(R.string.backup_importing)
                            val dialog = AlertDialog.Builder(requireContext())
                                .setView(binding.root)
                                .setCancelable(false)
                                .show()
                            runOnDefaultDispatcher {
                                runCatching {
                                    // check again whether it has been cancelled
                                    if (!isAdded) {
                                        MessageStore.showMessage(activity, R.string.restore_cancelled)
                                        return@runOnDefaultDispatcher
                                    }
                                    finishImport(
                                        json,
                                        import.backupConfigurations.isChecked,
                                        import.backupRules.isChecked,
                                        import.backupSettings.isChecked,
                                    )
                                    ProcessPhoenix.triggerRebirth(
                                        activity,
                                        Intent(activity, MainActivity::class.java),
                                    )
                                }.onFailure {
                                    Logs.w(it)
                                    onMainDispatcher {
                                        MessageStore.showMessage(activity, it.readableMessage)
                                    }
                                }

                                onMainDispatcher {
                                    dialog.dismiss()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(activity, e.readableMessage)
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): ByteArray {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put(
                    "profiles",
                    JSONArray().apply {
                        SagerDatabase.proxyDao.getAll().forEach {
                            put(it.toBase64Str())
                        }
                    },
                )

                put(
                    "groups",
                    JSONArray().apply {
                        SagerDatabase.groupDao.allGroups().forEach {
                            put(it.toBase64Str())
                        }
                    },
                )
            }
            if (rule) {
                put(
                    "rules",
                    JSONArray().apply {
                        SagerDatabase.rulesDao.allRules().forEach {
                            put(it.toBase64Str())
                        }
                    },
                )
            }
            if (setting) {
                put(
                    "settings",
                    JSONArray().apply {
                        PublicDatabase.kvPairDao.all().forEach {
                            put(it.toBase64Str())
                        }
                    },
                )
            }
        }

        val jsonContent = out.toStringPretty()
        return if (isWebDAVBackup) {
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)

                    val entry = ZipEntry("nekobox_backup.json").apply {
                        method = ZipEntry.DEFLATED
                    }

                    // write data
                    zos.putNextEntry(entry)
                    val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                    zos.write(bytes)
                    zos.closeEntry()

                    // ensure all data is written and compressed
                    zos.finish()
                }
                bos.toByteArray()
            }
        } else {
            // local export and share functionality uses JSON format
            jsonContent.toByteArray()
        }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = requireActivity()
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        try {
            val content = requireContext().contentResolver.openInputStream(file)!!.use { input ->
                if (fileName.endsWith(".zip")) {
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        zis.nextEntry?.let { entry ->
                            if (entry.name.endsWith(".json")) {
                                zis.readBytesBounded().toString(Charsets.UTF_8)
                            } else {
                                throw Exception("Invalid backup file format")
                            }
                        } ?: throw Exception("Invalid backup file format")
                    }
                } else {
                    input.readBytesBounded().toString(Charsets.UTF_8)
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
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                finishImport(
                                    json,
                                    import.backupConfigurations.isChecked,
                                    import.backupRules.isChecked,
                                    import.backupSettings.isChecked,
                                )
                                triggerFullRestart(requireContext())
                            }.onFailure {
                                Logs.w(it)
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(activity, it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(activity, e.readableMessage)
            }
        }
    }

    fun finishImport(content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean) {
        // Validate-then-commit: decode EVERY selected section into memory first. If any entry
        // is malformed the decode throws here, BEFORE any destructive reset() runs, so a bad
        // (or maliciously truncated) backup file can never partially wipe the live DB.
        //
        // profiles and groups are one logical section (a config backup always contains both);
        // they are decoded and committed together so neither is left without the other.
        val importConfigs = profile && content.has("profiles")
        val profiles = if (importConfigs) {
            decodeArray(content.getJSONArray("profiles")) { ProxyEntity.CREATOR.createFromParcel(it) }
        } else {
            null
        }
        val groups = if (importConfigs) {
            decodeArray(content.getJSONArray("groups")) { ProxyGroup.CREATOR.createFromParcel(it) }
        } else {
            null
        }
        val rules = if (rule && content.has("rules")) {
            decodeArray(content.getJSONArray("rules")) { ParcelizeBridge.createRule(it) }
        } else {
            null
        }
        val settings = if (setting && content.has("settings")) {
            decodeArray(content.getJSONArray("settings")) { KeyValuePair.CREATOR.createFromParcel(it) }
        } else {
            null
        }

        // Commit phase: only reached if every section above decoded successfully. Each
        // reset()+insert() pair runs in its own DB transaction so it is atomic - an
        // interruption can't leave a table cleared-but-not-repopulated.
        if (profiles != null && groups != null) {
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.proxyDao.reset()
                SagerDatabase.proxyDao.insert(profiles)
                SagerDatabase.groupDao.reset()
                SagerDatabase.groupDao.insert(groups)
            }
        }
        rules?.let {
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(it)
            }
        }
        settings?.let {
            PublicDatabase.instance.runInTransaction {
                PublicDatabase.kvPairDao.reset()
                PublicDatabase.kvPairDao.insert(it)
            }
        }
    }

    /**
     * Decode every base64'd, Parcel-marshalled entry in [array] into memory. Each entry is
     * decoded in its own try/finally so the Parcel is always recycled; a malformed entry
     * throws (aborting the whole import) before the caller commits any reset+insert.
     *
     * NOTE: Parcel.unmarshall on imported bytes is a known deserialization hazard and an
     * unstable persistence format (see Plan 014). This retains backward compatibility with
     * existing backups; the validate-then-commit ordering above removes the partial-wipe
     * risk. Migrating the encoding off Parcel is tracked as follow-up work.
     */
    private fun <T> decodeArray(array: JSONArray, create: (Parcel) -> T): List<T> {
        val out = ArrayList<T>(array.length())
        for (i in 0 until array.length()) {
            val data = Util.b64Decode(array[i] as String)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                out.add(create(parcel))
            } finally {
                parcel.recycle()
            }
        }
        return out
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
