package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutScannerBinding
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScannerActivity : ThemedActivity() {

    private lateinit var binding: LayoutScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false

    // Bundled ML Kit barcode scanner restricted to QR for speed; works fully offline.
    private val barcodeScanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    private val finished = AtomicBoolean(false)
    private val importedN = AtomicInteger(0)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appbarInclude.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.fabTorch.setOnClickListener { toggleTorch() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Logs.w(e)
                Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyze) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                // Hide the torch button if the device has no flash unit.
                binding.fabTorch.visibility =
                    if (camera?.cameraInfo?.hasFlashUnit() == true) android.view.View.VISIBLE
                    else android.view.View.GONE
                binding.fabTorch.contentDescription = getString(R.string.scan_torch_turn_on)
            } catch (e: Exception) {
                Logs.w(e)
                Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @AndroidXOptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || finished.get()) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (text != null && !finished.getAndSet(true)) {
                    // First successful scan wins. Import first, then finish on the main
                    // thread, so importedN is populated before onDestroy() reads it for
                    // the "N profile(s)" toast (finishing first raced the background import).
                    runOnDefaultDispatcher {
                        importText(text)
                        onMainDispatcher { finish() }
                    }
                }
            }
            .addOnFailureListener { Logs.w(it) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit() != true) return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        binding.fabTorch.setImageResource(
            if (torchOn) R.drawable.ic_baseline_flash_on_24 else R.drawable.ic_baseline_flash_off_24
        )
        // Keep the accessibility label describing the action the button will perform.
        binding.fabTorch.contentDescription = getString(
            if (torchOn) R.string.scan_torch_turn_off else R.string.scan_torch_turn_on
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.scanner_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_import_file) {
            importCodeFile.launch("image/*")
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private val importCodeFile = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        runOnDefaultDispatcher {
            var foundQr = false
            var imported = 0
            try {
                uris.forEachTry { uri ->
                    val bitmap = decodeBoundedBitmap(uri)
                    val barcodes = try {
                        com.google.android.gms.tasks.Tasks.await(
                            barcodeScanner.process(InputImage.fromBitmap(bitmap, 0))
                        )
                    } finally {
                        bitmap.recycle()
                    }
                    val text = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (text != null) {
                        foundQr = true
                        imported += importText(text)
                    }
                }
                if (!foundQr) {
                    onMainDispatcher {
                        Toast.makeText(app, R.string.scan_no_qr_found, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            } finally {
                // Only finish once the import actually completed (importText is awaited
                // above) and at least one profile was created.
                if (imported > 0) onMainDispatcher { finish() }
            }
        }
    }

    /**
     * Decode a user-selected image bounded to [MAX_IMPORT_DIMEN] on its longer edge.
     * Arbitrary gallery images can be huge; decoding at full resolution risks an OOM
     * before any exception handler runs. ML Kit detects QR codes fine at this size.
     */
    private fun decodeBoundedBitmap(uri: android.net.Uri): android.graphics.Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(contentResolver, uri)
            ) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val longer = maxOf(info.size.width, info.size.height)
                if (longer > MAX_IMPORT_DIMEN) {
                    val scale = MAX_IMPORT_DIMEN.toFloat() / longer
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            // Pre-P: read bounds first, then decode with an inSampleSize so the full-res
            // bitmap is never materialized.
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val longer = maxOf(bounds.outWidth, bounds.outHeight)
            while (longer / sample > MAX_IMPORT_DIMEN) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            (contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }) ?: error("Cannot decode image")
        }
    }

    /**
     * Parse a decoded QR payload and create the resulting profile(s). Suspends until the
     * import completes and returns the number of profiles created (0 if none / on error),
     * so callers can wait before finishing. A SubscriptionFoundException opens the
     * subscription import flow instead.
     */
    private suspend fun importText(text: String): Int {
        return try {
            val results = RawUpdater.parseRaw(text)
            if (!results.isNullOrEmpty()) {
                val currentGroupId = DataStore.selectedGroupForImport()
                if (DataStore.selectedGroup != currentGroupId) {
                    DataStore.selectedGroup = currentGroupId
                }
                var n = 0
                for (profile in results) {
                    ProfileManager.createProfile(currentGroupId, profile)
                    n++
                }
                importedN.addAndGet(n)
                n
            } else {
                onMainDispatcher {
                    Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                }
                0
            }
        } catch (e: SubscriptionFoundException) {
            startActivity(Intent(this@ScannerActivity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = e.link.toUri()
            })
            0
        } catch (e: Throwable) {
            Logs.w(e)
            onMainDispatcher {
                val msg = getString(R.string.action_import_err) + "\n" + e.readableMessage
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            }
            0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        barcodeScanner.close()
        if (importedN.get() > 0) {
            val text = getString(R.string.action_import_msg) + "\n" + importedN.get() + " profile(s)"
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        // Bound for decoding imported images; large enough for ML Kit to read dense QR
        // codes, small enough to avoid OOM on arbitrary full-resolution gallery photos.
        private const val MAX_IMPORT_DIMEN = 2048
    }
}
