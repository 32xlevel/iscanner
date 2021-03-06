package tech.iscanner.iscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Handler
import android.util.AttributeSet
import android.util.SparseArray
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.core.util.forEach
import androidx.core.util.isEmpty
import com.google.android.gms.vision.Detector
import com.google.android.gms.vision.barcode.Barcode
import com.google.android.gms.vision.barcode.BarcodeDetector
import tech.iscanner.iscanner.exceptions.FlashException

/**
 * Main view for scanning QR codes and barcodes
 * Just override it and onScanned callback
 */
@Suppress("DEPRECATION")
class ScannableCamera @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleArr: Int = 0
) : SurfaceView(context, attrs, defStyleArr), Detector.Processor<Barcode> {

    private lateinit var onScannedCallback: OnScanned

    private var delayHandler: Handler?
    private var delayCallback: (() -> Unit)
    var delay: Long
    var isActiveDelay: Boolean

    private var detector: BarcodeDetector
    private var surfaceHolderCallback: SurfaceHolderCallback
    private var cameraSource: CameraSource

    private var originCamera: Camera? = null
    private var originCameraInitializerHandler: Handler?

    var isActiveScanner: Boolean

    init {
        delayHandler = Handler()
        delayCallback = { isActiveScanner = true }
        with(context.obtainStyledAttributes(attrs, R.styleable.ScannableCamera)) {
            delay = getInt(R.styleable.ScannableCamera_delayMillis, 1500).toLong()
            isActiveDelay = getBoolean(R.styleable.ScannableCamera_isActiveDelay, true)
            recycle()
        }

        detector = BarcodeDetector.Builder(context)
            .setBarcodeFormats(Barcode.ALL_FORMATS)
            .build()
        detector.setProcessor(this)
        cameraSource = CameraSource.Builder(context, detector)
            .setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
            .build()
        surfaceHolderCallback = SurfaceHolderCallback(cameraSource, context)
        holder.addCallback(surfaceHolderCallback)

        isActiveScanner = true

        originCameraInitializerHandler = Handler()
        originCameraInitializerHandler?.postDelayed({ originCamera = getCamera(cameraSource) }, 2500)
    }

    override fun onDetachedFromWindow() {
        holder.removeCallback(surfaceHolderCallback)
        delayHandler = null // for avoid memory leaks
        originCameraInitializerHandler = null
        cameraSource.stop()
        cameraSource.release()
        super.onDetachedFromWindow()
    }

    override fun release() { }

    override fun receiveDetections(barcodes: Detector.Detections<Barcode>?) {
        if (!isActiveScanner) return

        val detections = barcodes?.detectedItems ?: return
        if (detections.isEmpty()) return

        if (isActiveDelay) {
            isActiveScanner = false
            delayHandler?.postDelayed(delayCallback, delay)
        }

        if (::onScannedCallback.isInitialized) {
            onScannedCallback.onScanned(detections.toList())
        }
    }

    fun startCamera() {
        cameraSource.start(holder)
    }

    fun stopScanning() {
        isActiveScanner = false
        delayHandler = null
    }

    fun resumeScanning() {
        isActiveScanner = true
        delayHandler = Handler()
    }

    interface OnScanned {
        fun onScanned(barcodes: List<Barcode>)
    }

    fun onScanned(onScannedCallback: OnScanned) {
        this.onScannedCallback = onScannedCallback
    }

    /**
     * Change facing by isBackFacing param
     */
    fun changeFacing(isBackFacing: Boolean) {
        cameraSource.release()

        detector.setProcessor(this)
        cameraSource = CameraSource.Builder(context, detector)
            .setFacing(if (isBackFacing) 0 else 1)
            .setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
            .build()

        surfaceHolderCallback = SurfaceHolderCallback(cameraSource, context)
        holder.addCallback(surfaceHolderCallback)

        cameraSource.start(holder)
    }

    /**
     * De(activate) flash on device by isFlash param
     * If flash is not supported, throws FlashException
     */
    @Throws(FlashException::class)
    fun flash(isFlash: Boolean) {
        originCamera?.let {
            try {
                val params = it.parameters
                params.flashMode = if (isFlash) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                it.parameters = params
            } catch (e: Exception) {
                throw FlashException("Flash is not supported")
            }
        }
    }

    /**
     * Get Camera for set various parameters and manipulate it
     */
    private fun getCamera(cameraSource: CameraSource): Camera? {
        val declaredFields = CameraSource::class.java.declaredFields
        for (field in declaredFields) {
            if (field.type == Camera::class.java) {
                field.isAccessible = true
                return field.get(cameraSource) as? Camera
            }
        }
        return null
    }

    private class SurfaceHolderCallback(private val source: CameraSource, private val context: Context) : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder?) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                source.start(holder)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {

        }

        override fun surfaceDestroyed(holder: SurfaceHolder?) {
            source.stop()
        }
    }

    private fun <T> SparseArray<T>.toList(): List<T> {
        return ArrayList<T>().apply {
            this@toList.forEach { _, value -> add(value) }
        }
    }
}