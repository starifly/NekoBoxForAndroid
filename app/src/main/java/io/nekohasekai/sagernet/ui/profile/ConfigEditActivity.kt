package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.subscribeAlways
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutEditConfigBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ListListener
import org.json.JSONObject

class ConfigEditActivity : ThemedActivity() {

    var dirty = false
    var key = Key.SERVER_CONFIG
    var useConfigStore = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as ConfigEditActivity).saveAndExit()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    lateinit var binding: LayoutEditConfigBinding

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (dirty) UnsavedChangesDialogFragment().apply { key() }
                .show(supportFragmentManager, null) else finish()
        }

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        binding = LayoutEditConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appbarInclude.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.config_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        // sora-editor: load the TextMate engine (JSON grammar + themes) once, then wire the
        // editor up with the JSON language and a color scheme that follows the app night mode.
        TextMateSetup.ensureInitialized(this)
        binding.editor.apply {
            typefaceText = Typeface.MONOSPACE
            isWordwrap = false
            TextMateSetup.applyTheme(this, Theme.usingNightMode())
            setEditorLanguage(TextMateLanguage.create(TextMateSetup.SCOPE_JSON, true))

            val content = if (useConfigStore) {
                DataStore.configurationStore.getString(key)
            } else {
                DataStore.profileCacheStore.getString(key)
            } ?: ""
            setText(content)

            subscribeAlways<ContentChangeEvent> {
                if (!dirty) {
                    dirty = true
                    DataStore.dirty = true
                }
            }
        }

        binding.actionTab.setOnClickListener {
            try {
                binding.editor.insertText("\t", 1)
            } catch (_: Exception) {
            }
        }
        binding.actionUndo.setOnClickListener {
            try {
                if (binding.editor.canUndo()) binding.editor.undo()
            } catch (_: Exception) {
            }
        }
        binding.actionRedo.setOnClickListener {
            try {
                if (binding.editor.canRedo()) binding.editor.redo()
            } catch (_: Exception) {
            }
        }
        binding.actionFormat.setOnClickListener {
            formatText()?.let {
                binding.editor.setText(it)
            }
        }

        binding.extendedKeyboard.apply {
            setKeyListener { char ->
                try {
                    binding.editor.insertText(char, char.length)
                } catch (_: Exception) {
                }
            }
            setHasFixedSize(true)
            submitList("{},:_\"".map { it.toString() })
            setBackgroundColor(getColorAttr(R.attr.primaryOrTextPrimary))
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.keyboardContainer) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                // systemBar insets are applied to the bottom of the keyboard
                if (imeVisible) {
                    bottomMargin = imeInsets.bottom - systemBarInsets.bottom
                } else {
                    bottomMargin = 0
                }
            }

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        // sora-editor requires release() to free its background worker thread/resources.
        if (::binding.isInitialized) binding.editor.release()
    }

    fun formatText(): String? {
        try {
            val txt = binding.editor.text.toString()
            if (txt.isBlank()) {
                return ""
            }
            return JSONObject(txt).toStringPretty()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
                .setMessage(e.readableMessage).show()
            return null
        }
    }

    fun saveAndExit() {
        formatText()?.let {
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, it)
            } else {
                DataStore.profileCacheStore.putString(key, it)
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // Route the action-bar up button through the back dispatcher so it honors the
        // unsaved-changes guard instead of finishing directly (avoids data loss).
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                saveAndExit()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
