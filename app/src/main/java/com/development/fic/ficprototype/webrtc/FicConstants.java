package com.development.fic.ficprototype.webrtc;

public class FicConstants {
    //HANDLER MESSAGES
    public final static String HANDLER_MSG_ERROR = "error";
    public final static String HANDLER_SERVER_MSG = "ServerMessage";
    public final static String HANDLER_WSOCKET_OBJECT = "WsocketObject";

    //MESSAGES FROM WSOCKET SERVER
    public final static String WSOCKET_MSG_HELLO = "HELLO";
    public final static String WSOCKET_MSG_JOINED = "JOINED";
    public final static String WSOCKET_MSG_SDP = "sdp";
    public final static String WSOCKET_MSG_ICE = "ice";

    //CUSTOM APP MESSAGES
    public final static String SERVERURL ="wss://pbh.sytes.net:8443";
    public final static String PEERID ="5545";

}
