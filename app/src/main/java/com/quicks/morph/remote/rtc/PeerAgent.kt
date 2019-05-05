package com.quicks.morph.remote.rtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.voiceengine.WebRtcAudioManager
import org.webrtc.voiceengine.WebRtcAudioRecord
import org.webrtc.voiceengine.WebRtcAudioTrack
import org.webrtc.voiceengine.WebRtcAudioUtils
import java.nio.charset.Charset
import java.util.concurrent.Executors

class PeerAgent(
    private val context: Context,
    private val enumerator: Camera2Enumerator = Camera2Enumerator(context)
) : PeerConnection.Observer, SdpObserver {

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>)
    }

    private val TAG = "PCRTCClient"

    private val executor = Executors.newSingleThreadExecutor()

    private var eglBase: EglBase? = null

    private var factory: PeerConnectionFactory? = null

    private var peerConnection: PeerConnection? = null

    private var dataChannel: DataChannel? = null

    private var audioSource: AudioSource? = null

    private var videoSource: VideoSource? = null

    private var videoCapturer: VideoCapturer? = null

    private var listener: Listener? = null

    private var queuedRemoteCandidates: ArrayList<IceCandidate>? = ArrayList()

    private var localSdp: SessionDescription? = null

    private val sdpMediaConstraints by lazy {
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        return@lazy sdpMediaConstraints
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        listener: Listener
    ) = executor.execute {

        if (factory == null) {
            initAgent(context)
        }

        this.listener = listener

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.enableDtlsSrtp = true

        val pc = factory?.createPeerConnection(rtcConfig, this)!!

        val init = DataChannel.Init()
        init.ordered = false

        dataChannel = pc.createDataChannel("MorphData", init)

        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)

        val mediaStream = factory?.createLocalMediaStream("MorphMedia")

        videoSource = factory?.createVideoSource(false)

        videoCapturer = tryCreateVideoCapturer()!!

        val surfaceTextureHelper = SurfaceTextureHelper
            .create("CaptureThread", eglBase!!.eglBaseContext)

        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.getCapturerObserver())

        videoCapturer!!.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, FPS)

        val localVideoTrack = factory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack!!.setEnabled(true)

        mediaStream!!.addTrack(localVideoTrack)

        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))

        audioSource = factory?.createAudioSource(audioConstraints)
        val localAudioTrack = factory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack!!.setEnabled(true)
        mediaStream.addTrack(localAudioTrack)

        pc.addStream(mediaStream)

        peerConnection = pc
        Log.d(TAG, "Peer connection created.")
    }


    fun createOffer() {
        executor.execute {
            peerConnection?.createOffer(this, sdpMediaConstraints)
        }
    }

    fun createAnswer() {
        executor.execute {
            peerConnection?.createAnswer(this, sdpMediaConstraints)
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (queuedRemoteCandidates != null) {
                queuedRemoteCandidates!!.add(candidate)
            } else {
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    fun removeRemoteIceCandidates(candidates: Array<IceCandidate>) {
        executor.execute {
            // Drain the queued remote candidates if there is any so that
            // they are processed in the proper order.
            drainCandidates()
            peerConnection?.removeIceCandidates(candidates)
        }
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        executor.execute {
            var sdpDescription = sdp.description
            sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_VP8, false)
            sdpDescription = setStartBitrate(AUDIO_CODEC_OPUS, false, sdpDescription, 32)
            val sdpRemote = SessionDescription(sdp.type, sdpDescription)
            peerConnection?.setRemoteDescription(this, sdpRemote)
        }
    }

    /* PeerConnection observer impl */

    override fun onIceCandidate(candidate: IceCandidate) {
        executor.execute {
            listener?.onIceCandidate(candidate)
        }
    }

    override fun onDataChannel(dc: DataChannel) {
        Log.d(TAG, "New Data channel" + dc.label())

        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    Log.d(TAG, "Received binary msg over $dc")
                    return
                }
                val data = buffer.data
                val bytes = ByteArray(data.capacity())
                data.get(bytes)
                val strData = String(bytes, Charset.forName("UTF-8"))
                Log.d(TAG, "Got msg: $strData over $dc")
            }

            override fun onBufferedAmountChange(p0: Long) {
                Log.d(TAG, "Data channel buffered amount changed: " + dc.label() + ": " + dc.state())
            }

            override fun onStateChange() {
                Log.d(TAG, "Data channel state changed: " + dc.label() + ": " + dc.state())
            }
        })
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Log.d(TAG, "IceConnectionReceiving changed to $receiving");
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        Log.d(TAG, "IceConnectionState: $newState")
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
        executor.execute{ listener?.onIceCandidatesRemoved(candidates) }
    }

    override fun onRemoveStream(stream: MediaStream) {
    }

    override fun onRenegotiationNeeded() {
    }

    override fun onAddTrack(receiver: RtpReceiver, p1: Array<out MediaStream>) {
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
        Log.d(TAG, "IceGatheringState: $newState")
    }

    override fun onAddStream(stream: MediaStream) {
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        //Log.d(TAG, "SignalingState: " + newState);
    }

    /* SdpObserver impl */

    override fun onSetSuccess() {
        executor.execute {
            if (peerConnection?.remoteDescription == null) {
                // We've just set our local SDP so time to send it.
                Log.d(TAG, "Local SDP set succesfully")
                localSdp?.let {
                    listener?.onLocalDescription(it)
                }
            } else {
                // We've just set remote description, so drain remote
                // and send local ICE candidates.
                Log.d(TAG, "Remote SDP set succesfully")
                drainCandidates()
            }
        }
    }

    override fun onCreateSuccess(origSdp: SessionDescription) {
        var sdpDescription = origSdp.description
        sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_VP8, false)

        val sdp = SessionDescription(origSdp.type, sdpDescription)
        localSdp = sdp
        executor.execute {
            Log.d(TAG, "Set local SDP from " + sdp.type)
            peerConnection?.setLocalDescription(this, sdp)
        }
    }

    override fun onSetFailure(p0: String?) {
    }

    override fun onCreateFailure(p0: String?) {
    }

    /* Helpers */

    private fun drainCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates!!.size + " remote candidates")
            for (candidate in queuedRemoteCandidates!!) {
                peerConnection?.addIceCandidate(candidate)
            }
            queuedRemoteCandidates = null
        }
    }

    // Create VideoCapturer
    private fun tryCreateVideoCapturer(): VideoCapturer? {
        for (deviceName in enumerator.deviceNames) {
            Logging.d(TAG, "Creating front facing camera capturer.")
            val videoCapturer = enumerator.createCapturer(deviceName, cameraHandler)
            if (videoCapturer != null) {
                return videoCapturer
            }
        }
        return null
    }

    private val cameraHandler = object : CameraVideoCapturer.CameraEventsHandler {

        override fun onCameraError(err: String?) {
            Log.d(TAG, "CAMERA ERROR: $err")
        }

        override fun onCameraOpening(err: String?) {
            Log.d(TAG, "CAMERA OPENING: $err")
        }

        override fun onCameraDisconnected() {
            Log.d(TAG, "CAMERA DISCONNECTED")
        }

        override fun onCameraFreezed(err: String?) {
            Log.d(TAG, "CAMERA FREEZED: $err")
        }

        override fun onFirstFrameAvailable() {
            Log.d(TAG, "FIRST FRAME AVAILABLE")
        }

        override fun onCameraClosed() {
            Log.d(TAG, "CAMERA CLOSED")
        }
    }

    private fun initAgent(context: Context) {

        eglBase = EglBase.create()

        // Initialize field trials.
        var fieldTrials = ""
        fieldTrials += VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL
        fieldTrials += VIDEO_FRAME_EMIT_FIELDTRIAL

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(fieldTrials)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true)
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false)
        WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false)
        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false)

        // Set audio record error callbacks.
        WebRtcAudioRecord.setErrorCallback(object : WebRtcAudioRecord.WebRtcAudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: $errorMessage")
            }
            override fun onWebRtcAudioRecordStartError(
                errorCode: WebRtcAudioRecord.AudioRecordStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: $errorCode. $errorMessage")
            }
            override fun onWebRtcAudioRecordError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordError: $errorMessage")
            }
        })

        WebRtcAudioTrack.setErrorCallback(object : WebRtcAudioTrack.ErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: $errorMessage")
            }
            override fun onWebRtcAudioTrackStartError(
                errorCode: WebRtcAudioTrack.AudioTrackStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: $errorCode. $errorMessage")
            }
            override fun onWebRtcAudioTrackError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackError: $errorMessage")
            }
        })

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        factory =  PeerConnectionFactory.builder()
            .setOptions(null)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun cleanUp() {

        dataChannel?.dispose()
        dataChannel = null

        peerConnection?.dispose()
        peerConnection = null

        audioSource?.dispose()
        audioSource = null

        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }

        videoCapturer?.dispose()
        videoCapturer = null

        videoSource?.dispose()
        videoSource = null

        eglBase?.release()
        eglBase = null

        factory?.dispose()
        factory = null

        Log.d(TAG, "PeerConnection closed.")
    }

}