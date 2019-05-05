package com.quicks.morph.remote.rtc

import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.Executors

private const val VIDEO_TRACK_ID = "ARDAMSv0"
private const val AUDIO_TRACK_ID = "ARDAMSa0"
private const val VIDEO_TRACK_TYPE = "video"
private const val TAG = "PCRTCClient"
private const val VIDEO_CODEC_VP8 = "VP8"
private const val VIDEO_CODEC_VP9 = "VP9"
private const val VIDEO_CODEC_H264 = "H264"
private const val VIDEO_CODEC_H264_BASELINE = "H264 Baseline"
private const val VIDEO_CODEC_H264_HIGH = "H264 High"
private const val AUDIO_CODEC_OPUS = "opus"
private const val AUDIO_CODEC_ISAC = "ISAC"
const val VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate"
private const val VIDEO_FLEXFEC_FIELDTRIAL = "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/"
const val VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL = "WebRTC-IntelVP8/Enabled/"
private const val VIDEO_H264_HIGH_PROFILE_FIELDTRIAL = "WebRTC-H264HighProfile/Enabled/"
private const val DISABLE_WEBRTC_AGC_FIELDTRIAL = "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/"
const val VIDEO_FRAME_EMIT_FIELDTRIAL = (PeerConnectionFactory.VIDEO_FRAME_EMIT_TRIAL + "/" + PeerConnectionFactory.TRIAL_ENABLED + "/")
const val AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate"
private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"
private const val AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl"
private const val DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement"
private const val HD_VIDEO_WIDTH = 1280
private const val HD_VIDEO_HEIGHT = 720
private const val FPS = 30
private const val BPS_IN_KBPS = 1000

class PeerAgent(
    private val factory: PeerConnectionFactory,
    private val enumerator: Camera2Enumerator
) : PeerConnection.Observer, SdpObserver {

    // Executor thread is started once in private ctor and is used for all
    // peer connection API calls to ensure new peer connection factory is
    // created on the same thread as previously destroyed factory.
    private val executor = Executors.newSingleThreadExecutor()

    private var peerConnection: PeerConnection? = null

    private var dataChannel: DataChannel? = null

    private var queuedRemoteCandidates: ArrayList<IceCandidate>? = ArrayList()

    private val sdpMediaConstraints by lazy {
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        return@lazy sdpMediaConstraints
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>
    ) {

        val videoCapturer = tryCreateVideoCapturer()!!

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.enableDtlsSrtp = true

        val pc = factory.createPeerConnection(rtcConfig, this)!!

        val init = DataChannel.Init()
        init.ordered = false

        dataChannel = pc.createDataChannel("DataBaby", init)

        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)

        val mediaStream = factory.createLocalMediaStream("ARDAMS")

        val videoSource = factory.createVideoSource(false)
        videoCapturer.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, FPS)
        val localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack.setEnabled(true)
        mediaStream.addTrack(localVideoTrack)

        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))

        val audioSource = factory.createAudioSource(audioConstraints)
        val localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack.setEnabled(true)
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

    override fun onIceCandidate(p0: IceCandidate?) {
        executor.execute {
            //events.onIceCandidate(candidate)
        }
    }

    override fun onDataChannel(dc: DataChannel) {
        Log.d(TAG, "New Data channel " + dc.label())

        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    Log.d(TAG, "Received binary msg over $dc");
                    return;
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
        executor.execute {
            Log.d(TAG, "IceConnectionState: $newState")
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    //events.onIceConnected()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    //events.onIceDisconnected()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    //reportError("ICE connection failed.")
                }
            }
        }
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
        Log.d(TAG, "IceGatheringState: $newState")
    }

    override fun onAddStream(stream: MediaStream) {
    }

    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        //Log.d(TAG, "SignalingState: " + newState);
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        //executor.execute(() -> events.onIceCandidatesRemoved(candidates));
    }

    override fun onRemoveStream(p0: MediaStream?) {
        //executor.execute(() -> remoteVideoTrack = null);
    }

    override fun onRenegotiationNeeded() {
        // No need to do anything; AppRTC follows a pre-agreed-upon
        // signaling/negotiation protocol.
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
    }


    /* SdpObserver impl */

    override fun onSetSuccess() {
        executor.execute {
            if (peerConnection?.remoteDescription == null) {
                // We've just set our local SDP so time to send it.
                Log.d(TAG, "Local SDP set succesfully")
                //events.onLocalDescription(localSdp);
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
        sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_VP8, false);

        val sdp = SessionDescription(origSdp.type, sdpDescription)

        executor.execute {
            Log.d(TAG, "Set local SDP from " + sdp.type)
            peerConnection?.setLocalDescription(this, sdp)
        }
    }

    override fun onSetFailure(p0: String?) {
    }

    override fun onCreateFailure(p0: String?) {
    }

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

}