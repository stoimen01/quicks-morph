package com.quicks.morph.remote.rtc

import android.content.Context
import android.util.Log
import org.webrtc.*

class Peer(
    rtcConfig: PeerConnection.RTCConfiguration,
    factory: PeerConnectionFactory,
    context: Context,
    eglBase: EglBase,
    private val listener: Listener,
    private val exec: (() -> Unit) -> Unit
) {

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>)
    }

    private val pc: PeerConnection

    private val enumerator: Camera2Enumerator = Camera2Enumerator(context)
    private val videoCapturer = tryCreateVideoCapturer()
    private val videoSource: VideoSource?
    private val surfaceTextureHelper: SurfaceTextureHelper?
    private val localVideoTrack: VideoTrack?

    private val audioSource = factory.createAudioSource(audioConstraints)
    private val localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)

    init {

        pc = factory.connectionOf(
            rtcConfig,
            onCandidate = {
                exec { listener.onIceCandidate(it) }
            },
            onCandidatesRemoved = {
                exec { listener.onIceCandidatesRemoved(it) }
            },
            onConnectionStateChange = {
                Log.d(TAG, "CONNECTION STATE CHANGED $it")
            },
            onDataChannel = {

            },
            onIceStateChange = {
                Log.d(TAG, "ICE STATE CHANGED $it")
            }
        )

        if (videoCapturer != null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoSource = factory.createVideoSource(false)
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            videoCapturer.startCapture(HD_VIDEO_WIDTH, HD_VIDEO_HEIGHT, FPS)
            localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
            localVideoTrack.setEnabled(true)
            pc.addTrack(localVideoTrack)
        } else {
            videoSource = null
            surfaceTextureHelper = null
            localVideoTrack = null
        }

        localAudioTrack.setEnabled(true)
        pc.addTrack(localAudioTrack)
    }

    fun createAnswer(
        offer: SessionDescription,
        onAnswer: (answer: SessionDescription) -> Unit,
        onError: () -> Unit
    ) = exec {

        var sdpDescription = offer.description
        sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_VP8, false)
        sdpDescription = setStartBitrate(AUDIO_CODEC_OPUS, false, sdpDescription, 32)
        val sdpRemote = SessionDescription(offer.type, sdpDescription)

        pc.setRemoteSdp(sdpRemote,
            onSuccess = { exec {
                Log.d(TAG, "REMOTE SDP WAS SET")
                //remote sdp set !
                pc.answerOf(
                    constraints,
                    onCreateSuccess = {
                        // answer created, let's set it as local desc
                        Log.d(TAG, "ANSWER CREATED")
                        exec {
                            pc.setLocalSdp(
                                it,
                                onSuccess = {
                                    Log.d(TAG, "ANSWER WAS SET AS LOCAL SDP")
                                    exec { onAnswer(it) }
                                },
                                onFail = {
                                    Log.d(TAG, "ERROR SETTING LOCAL SDP: $it")
                                    exec { onError() }
                                }
                            )
                        }
                    },
                    onCreateFail = {
                        Log.d(TAG, "ERROR CREATING ANSWER: $it")
                        exec { onError() }
                    }
                )
            }},
            onFail = {
                Log.d(TAG, "ERROR SETTING REMOTE SDP: $it")
                exec { onError() }
            }
        )
    }

    fun removeRemoteIceCandidates(candidates: Array<IceCandidate>) = exec {
        pc.removeIceCandidates(candidates)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) = exec {
        Log.d(TAG, "SETTING REMOTE CANDIDATE")
        pc.addIceCandidate(candidate)
    }

    fun dispose() = exec {
        audioSource.dispose()
        localAudioTrack.dispose()

        surfaceTextureHelper?.dispose()

        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
        videoCapturer?.dispose()
        videoSource?.dispose()

        localVideoTrack?.dispose()

        pc.close()
        pc.dispose()
    }

    private fun tryCreateVideoCapturer(): VideoCapturer? {
        for (deviceName in enumerator.deviceNames) {
            val videoCapturer = enumerator.createCapturer(deviceName, cameraHandler)
            if (videoCapturer != null) {
                Log.d(TAG, "VIDEO CAPTURER CREATED")
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

    companion object {

        private const val TAG = "RTC"

        private val constraints by lazy {
            val sdpMediaConstraints = MediaConstraints()
            sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            sdpMediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            return@lazy sdpMediaConstraints
        }

        private val audioConstraints by lazy {
            val audioConstraints = MediaConstraints()
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))
            return@lazy audioConstraints
        }

        private val dataInit by lazy {
            val init = DataChannel.Init()
            init.ordered = false
            return@lazy init
        }

    }

}