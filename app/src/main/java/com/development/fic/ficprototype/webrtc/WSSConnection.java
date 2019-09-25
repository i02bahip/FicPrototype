/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.development.fic.ficprototype.webrtc;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import okhttp3.OkHttpClient;
import okhttp3.Request;


/**
 * Asynchronous websocket implementation.
 */
public class WSSConnection {
    private String wssUrl;
    private String peerId;
    private Handler mainHandler;
    private WSocketListener webSocketListener;
    private OkHttpClient wsClient = new OkHttpClient();

    public WSSConnection(String wssUrl, String peerId, Handler mainHandler) {
        this.wssUrl = wssUrl;
        this.peerId = peerId;
        this.mainHandler = mainHandler;
        this.webSocketListener = new WSocketListener(this.wssUrl, this.peerId, this.mainHandler);
    }

    public void connect() {
        Runnable connect = () -> connectToWS();
        new Thread(connect).start();
    }

    private void connectToWS() {
        try{
            Request request = new Request.Builder().url(wssUrl).build();
            wsClient.newWebSocket(request, webSocketListener);
            wsClient.dispatcher().executorService().shutdown();
        } catch (Exception e){
            Bundle msgBundle = new Bundle();
            ServerMsg serverMsg = new ServerMsg("Error connecting to Web Socket", null);
            msgBundle.putSerializable(FicConstants.HANDLER_SERVER_MSG, serverMsg);
            Message msg = new Message();
            msg.setData(msgBundle);
            mainHandler.sendMessage(msg);
        }
    }
}
