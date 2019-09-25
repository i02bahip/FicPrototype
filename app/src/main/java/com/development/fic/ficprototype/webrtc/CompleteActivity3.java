package com.development.fic.ficprototype.webrtc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.development.fic.ficprototype.R;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.freedesktop.gstreamer.SDPMessage;
import org.freedesktop.gstreamer.WebRTCSDPType;
import org.freedesktop.gstreamer.WebRTCSessionDescription;
import org.freedesktop.gstreamer.elements.WebRTCBin;
import org.freedesktop.gstreamer.elements.WebRTCBin.CREATE_ANSWER;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.WebSocket;

public class CompleteActivity3 extends AppCompatActivity {
    private static final String TAG = "LULUCompleteActivity";
    private static final int RC_CALL = 111;
    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocket webSocket;
    private WSSConnection wsscon;

    private WebRTCBin webRTCBin;


    private CREATE_ANSWER createAnsw = answer -> {
        webRTCBin.setLocalDescription(answer);

        JSONObject message = new JSONObject();
        try {
            message.put("type", "answer");
            message.put("sdp", answer.getSDPMessage().toString());
            Log.d(TAG, "connectToSignallingServer: SEND ANSWER: " + answer.getSDPMessage().toString());
            webSocket.send("{\"sdp\":" + message.toString() + "}");
        } catch (JSONException e) {
            e.printStackTrace();
        }
    };


    @SuppressLint("HandlerLeak")
    private Handler mainHandler = new Handler(){
        private Bundle bundleMsg;
        @Override
        public void handleMessage(Message message) {
            this.bundleMsg = message.getData();
            if(bundleMsg.getSerializable(FicConstants.HANDLER_SERVER_MSG) instanceof ServerMsg){
                ServerMsg msg = (ServerMsg) bundleMsg.getSerializable(FicConstants.HANDLER_SERVER_MSG);
                handleWSocketServerMessage(msg);
            } else {
                Log.e(TAG, "UNKNOWN ERROR FROM WSOCKET RESPONSE \n");
            }
        }
    };

    public void handleWSocketServerMessage(ServerMsg msg) {
        String payload = msg.getText();
        webSocket = msg.getWebsocket();
        try {
            if(payload.contains(FicConstants.WSOCKET_MSG_JOINED)){
                Log.d(TAG, "Somebody has joined the room. Message: " + payload);
                //doCall();
            } else {
                if(payload.contains(FicConstants.WSOCKET_MSG_HELLO)) {
                    Log.d(TAG, "Connected with Web Socket server. Message: " + payload);
                } else {
                    JsonNode answer = mapper.readTree(payload);
                    if (answer.get(FicConstants.WSOCKET_MSG_SDP) != null) {
                        //doCall();
                        Log.d(TAG, "connectToSignallingServer: received a message OFFER:" + answer.toString());
                        Log.d(TAG, "connectToSignallingServer: SET REMOTE DESCRIPTION: " + answer.get("sdp").get("sdp").textValue());
                        SDPMessage sdpMessage = new SDPMessage();
                        sdpMessage.parseBuffer(answer.get("sdp").get("sdp").textValue());
                        WebRTCSessionDescription description = new WebRTCSessionDescription(WebRTCSDPType.OFFER, sdpMessage);
                        webRTCBin.setRemoteDescription(description);
                        webRTCBin.createAnswer(createAnsw);
                    } else {
                        if (answer.get(FicConstants.WSOCKET_MSG_ICE) != null) {
                            Log.d(TAG, "connectToSignallingServer: receiving candidates: " + answer.toString());
                            String candidate = answer.get("ice").get("candidate").textValue();
                            int sdpMLineIndex = answer.get("ice").get("sdpMLineIndex").intValue();
                            String candidateId = "0";//String.valueOf(sdpMLineIndex);
                            //IceCandidate iceCandidate = new IceCandidate(candidateId, sdpMLineIndex, candidate);
                            //peerConnection.addIceCandidate(iceCandidate);
                        }
                    }
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Problem reading payload: " + e + "\n");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webRTCBin = new WebRTCBin("sendrecv");
        webRTCBin.setStunServer("stun:stun.services.mozilla.com");
        webRTCBin.setStunServer("stun:stun.l.google.com:19302");

        start();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void start() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};


            wsscon = new WSSConnection(FicConstants.SERVERURL, FicConstants.PEERID, this.mainHandler);
            wsscon.connect();

            initializeSurfaceViews();

            //initializePeerConnectionFactory();

            //createVideoTrackFromCameraAndShowIt();

            //initializePeerConnections();

            //startStreamingVideo();
    }
    private void initializeSurfaceViews() {

    }

}
