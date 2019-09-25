package com.development.fic.ficprototype.webrtc;

import java.io.Serializable;

import okhttp3.WebSocket;

public class ServerMsg implements Serializable {
    private String text;
    private WebSocket websocket;

    public ServerMsg(String text, WebSocket websocket) {
        this.text = text;
        this.websocket = websocket;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public WebSocket getWebsocket() {
        return websocket;
    }

    public void setWebsocket(WebSocket websocket) {
        this.websocket = websocket;
    }
}