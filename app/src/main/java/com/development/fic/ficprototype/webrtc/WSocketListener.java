package com.development.fic.ficprototype.webrtc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

class WSocketListener extends WebSocketListener {
    private static final String TAG = "FicLog CONNECTION";
    private String urlServer;
    private String peerId;
    private static Handler mainHandler;

    public WSocketListener(String urlServer, String peerId, Handler mainHandler) {
        this.urlServer = urlServer;
        this.peerId = peerId;
        this.mainHandler = mainHandler;

    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        Log.e(TAG, "onOpen");
        webSocket.send(FicConstants.WSOCKET_MSG_HELLO + " " + peerId);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        Log.e(TAG, "onMessage");
        Bundle msgBundle = new Bundle();
        ServerMsg serverMsg = new ServerMsg(text, webSocket);
        msgBundle.putSerializable(FicConstants.HANDLER_SERVER_MSG, serverMsg);
        Message msg = new Message();
        msg.setData(msgBundle);
        this.mainHandler.sendMessage(msg);
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
        webSocket.cancel();
        Log.d(TAG, "CLOSE: " + code + " " + reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        Log.d(TAG, "ON CLOSED MESSAGE: " + reason);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        Log.e(TAG, "ON FAILURE MESSAGE: " + t.toString());
    }
};