package com.example.handtracking_16

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.components.*
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.glutil.EglManager

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MYTAG"
        private const val BINARY_GRAPH_NAME = "hand_tracking_mobile_gpu.binarypb"
//        private const val BINARY_GRAPH_NAME = "multi_hand_tracking_mobile_gpu.binarypb"
        private const val INPUT_VIDEO_STREAM_NAME = "input_video"
        private const val OUTPUT_VIDEO_STREAM_NAME = "output_video"
        private const val OUTPUT_HAND_PRESENCE_STREAM_NAME = "hand_presence"
        private const val OUTPUT_LANDMARKS_STREAM_NAME = "hand_landmarks"
        private val CAMERA_FACING = CameraHelper.CameraFacing.FRONT
        // Flips the camera-preview frames vertically before sending them into FrameProcessor to be
        // processed in a MediaPipe graph, and flips the processed frames back when they are displayed.
        // This is needed because OpenGL represents images assuming the image origin is at the bottom-left
        // corner, whereas MediaPipe in general assumes the image origin is at top-left.
        private const val FLIP_FRAMES_VERTICALLY = true

        init { // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
        // {@link SurfaceTexture} where the camera-preview frames can be accessed.
        private var previewFrameTexture: SurfaceTexture? = null
        // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
        private var previewDisplayView: SurfaceView? = null
        // Creates and manages an {@link EGLContext}.
        private var eglManager: EglManager = EglManager(null)
        // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
        // frames onto a {@link Surface}.
        private var processor: FrameProcessor? = null
        // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
        // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
        // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
        private var converter: ExternalTextureConverter? = null
        // Handles camera access via the {@link CameraX} Jetpack support library.
        private var cameraHelper: CameraXPreviewHelper? = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewDisplayView = SurfaceView(this)
        setupPreviewDisplayView()
        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this)
        setupProcess()
//        Log.d(TAG, "setupProcess4")
        PermissionHelper.checkAndRequestCameraPermissions(this)
    }
    override fun onResume() {
        super.onResume()
        converter = ExternalTextureConverter(eglManager.context)
        converter!!.setFlipY(FLIP_FRAMES_VERTICALLY)
        converter!!.setConsumer(processor)
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }
    override fun onPause() {
        super.onPause()
        converter!!.close()
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions!!, grantResults!!)
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    private fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper!!.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            previewFrameTexture = surfaceTexture
            // Make the display view visible to start showing the preview. This triggers the
            // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
            previewDisplayView?.visibility = View.VISIBLE
        }
        cameraHelper!!.startCamera(this, CAMERA_FACING,  /*surfaceTexture=*/null)
    }
    private fun setupPreviewDisplayView() {
        previewDisplayView?.visibility = View.GONE
        var  viewGroup: ViewGroup = findViewById(R.id.preview_display_layout)
        viewGroup.addView(previewDisplayView)
        previewDisplayView!!.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    processor?.videoSurfaceOutput?.setSurface(holder.surface)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) { // (Re-)Compute the ideal size of the camera-preview display (the area that the
                    // camera-preview frames get rendered onto, potentially with scaling and rotation)
                    // based on the size of the SurfaceView that contains the display.
                    val viewSize = Size(width, height)
                    Log.d(TAG,"viewSize: $viewSize")
                    val displaySize = cameraHelper!!.computeDisplaySizeFromViewSize(viewSize)
                    val isCameraRotated = cameraHelper!!.isCameraRotated
                    // Connect the converter to the camera-preview frames as its input (via
                    // previewFrameTexture), and configure the output width and height as the computed
                    // display size.
                    converter!!.setSurfaceTextureAndAttachToGLContext(
                        previewFrameTexture,
                        if (isCameraRotated) displaySize.height else displaySize.width,
                        if (isCameraRotated) displaySize.width else displaySize.height
                    )
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    processor?.videoSurfaceOutput?.setSurface(null)
                }
            })
    }

    private fun setupProcess() {
        Log.d(TAG, "setupProcess")
        eglManager = EglManager(null)
        Log.d(TAG, "ProcessFrame: ")
        Log.d(TAG, "=eglManager: $eglManager")
        Log.d(TAG, "=binary graph: $BINARY_GRAPH_NAME")
        Log.d(TAG, "=input: $INPUT_VIDEO_STREAM_NAME")
        Log.d(TAG, "=output: $OUTPUT_VIDEO_STREAM_NAME")
        processor = FrameProcessor(
            this,
            eglManager.nativeContext,
            BINARY_GRAPH_NAME,
            INPUT_VIDEO_STREAM_NAME,
            OUTPUT_VIDEO_STREAM_NAME
        )
        Log.d(TAG, "setupProcess3")
        processor!!.videoSurfaceOutput.setFlipY(FLIP_FRAMES_VERTICALLY)
        processor?.addPacketCallback(
            OUTPUT_LANDMARKS_STREAM_NAME
        ) { packet: Packet ->
            val landmarksRaw = PacketGetter.getProtoBytes(packet)
            val landmarks = LandmarkProto.NormalizedLandmarkList.parseFrom(landmarksRaw)
            if (landmarks == null) {
                Log.d(TAG, "[TS:" + packet.timestamp + "] No hand landmarks.")
                return@addPacketCallback
            }
            // Note: If hand_presence is false, these landmarks are useless.
            Log.d(
                TAG, "[TS: ${packet.timestamp}] #Landmarks for hand: ${landmarks.landmarkCount}\n ${
                    getLandmarksString(
                        landmarks
                    )
                }"
            )
        }
    }
    private fun getLandmarksString(landmarks: LandmarkProto.NormalizedLandmarkList): String {
        var landmarksString = ""
        for ((landmarkIndex, landmark) in landmarks.landmarkList.withIndex()) {
            landmarksString += ("\t\tLandmark["
                    + landmarkIndex
                    + "]: ("
                    + landmark.x
                    + ", "
                    + landmark.y
                    + ", "
                    + landmark.z
                    + ")\n")
        }
        return landmarksString
    }
}