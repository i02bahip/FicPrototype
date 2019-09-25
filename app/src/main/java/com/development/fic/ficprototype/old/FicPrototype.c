#include <string.h>
#include <stdint.h>
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/video.h>
#include <pthread.h>

//----------------------------
#include <gst/sdp/sdp.h>
#define GST_USE_UNSTABLE_API
#include <gst/webrtc/webrtc.h>
/* For signalling */
#include <nice/nice.h>
#include <libsoup/soup.h>
#include <json-glib/json-glib.h>
#include <string.h>

#define STUN_SERVER " stun-server=stun://stun.l.google.com:19302 "
#define STR(x) #x
#define RTP_CAPS_OPUS(x) "application/x-rtp,media=audio,encoding-name=OPUS,payload=" STR(x)
#define RTP_CAPS_H264 "application/x-rtp, media=video, encoding-name=H264, payload="
//----------------------------

GST_DEBUG_CATEGORY_STATIC (debug_category);
#define GST_CAT_DEFAULT debug_category

/*
 * These macros provide a way to store the native pointer to CustomData, which might be 32 or 64 bits, into
 * a jlong, which is always 64 bits, without warnings.
 */
#if GLIB_SIZEOF_VOID_P == 8
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(*env)->GetLongField (env, thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)data)
#else
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(jint)(*env)->GetLongField (env, thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)(jint)data)
#endif

/* Structure to contain all our information, so we can pass it to callbacks */
typedef struct _CustomData {
    jobject app;            /* Application instance, used to call its methods. A global reference is kept. */
    GstElement *pipeline;   /* The running pipeline */
    GMainContext *context;  /* GLib context used to run the main loop */
    GMainLoop *main_loop;   /* GLib main loop */
    gboolean initialized;   /* To avoid informing the UI multiple times about the initialization */
    GstElement *video_sink; /* The video sink element which receives XOverlay commands */
    ANativeWindow *native_window; /* The Android native window where video will be rendered */
} CustomData;

/* These global variables cache values which are not changing during execution */
static pthread_t gst_app_thread;
static pthread_key_t current_jni_env;
static JavaVM *java_vm;
static jfieldID custom_data_field_id;
static jmethodID on_gstreamer_initialized_method_id;

//------------------------------------------

enum AppState {
    APP_STATE_UNKNOWN = 0,
    APP_STATE_ERROR = 1, /* generic error */
    SERVER_CONNECTING = 1000,
    SERVER_CONNECTION_ERROR,
    SERVER_CONNECTED, /* Ready to register */
    SERVER_REGISTERING = 2000,
    SERVER_REGISTRATION_ERROR,
    SERVER_REGISTERED, /* Ready to call a peer */
    SERVER_CLOSED, /* server connection closed by us or the server */
    PEER_CONNECTING = 3000,
    PEER_CONNECTION_ERROR,
    PEER_CONNECTED,
    PEER_CALL_NEGOTIATING = 4000,
    PEER_CALL_STARTED,
    PEER_CALL_STOPPING,
    PEER_CALL_STOPPED,
    PEER_CALL_ERROR,
};

static GMainLoop *loop;
static GstElement *pipe1, *webrtc1;

static SoupWebsocketConnection *ws_conn = NULL;
static enum AppState app_state = 0;
static const gchar *peer_id = "5545";
static const gchar *server_url = "wss://pbh.sytes.net:8443";
static gboolean disable_ssl = FALSE;


//------------------------------------------


/*
 * Private methods
 */

/* Register this thread with the VM */
static JNIEnv *attach_current_thread (void) {
    JNIEnv *env;
    JavaVMAttachArgs args;

    GST_DEBUG ("Attaching thread %p", g_thread_self ());
    args.version = JNI_VERSION_1_4;
    args.name = NULL;
    args.group = NULL;

    if ((*java_vm)->AttachCurrentThread (java_vm, &env, &args) < 0) {
        GST_ERROR ("Failed to attach current thread");
        return NULL;
    }

    return env;
}

/* Unregister this thread from the VM */
static void detach_current_thread (void *env) {
    GST_DEBUG ("Detaching thread %p", g_thread_self ());
    (*java_vm)->DetachCurrentThread (java_vm);
}

/* Retrieve the JNI environment for this thread */
static JNIEnv *get_jni_env (void) {
    JNIEnv *env;

    if ((env = pthread_getspecific (current_jni_env)) == NULL) {
        env = attach_current_thread ();
        pthread_setspecific (current_jni_env, env);
    }

    return env;
}


/* Retrieve errors from the bus and show them on the UI */
static void error_cb (GstBus *bus, GstMessage *msg, CustomData *data) {
    GError *err;
    gchar *debug_info;
    gchar *message_string;

    gst_message_parse_error (msg, &err, &debug_info);
    message_string = g_strdup_printf ("Error received from element %s: %s", GST_OBJECT_NAME (msg->src), err->message);
    g_clear_error (&err);
    g_free (debug_info);
    g_free (message_string);
    gst_element_set_state (data->pipeline, GST_STATE_NULL);
}

/* Notify UI about pipeline state changes */
static void state_changed_cb (GstBus *bus, GstMessage *msg, CustomData *data) {
    GstState old_state, new_state, pending_state;
    gst_message_parse_state_changed (msg, &old_state, &new_state, &pending_state);
    /* Only pay attention to messages coming from the pipeline, not its children */
    if (GST_MESSAGE_SRC (msg) == GST_OBJECT (data->pipeline)) {
        gchar *message = g_strdup_printf("State changed to %s", gst_element_state_get_name(new_state));
        g_free (message);
    }
}

/* Check if all conditions are met to report GStreamer as initialized.
 * These conditions will change depending on the application */
static void check_initialization_complete (CustomData *data) {
    JNIEnv *env = get_jni_env ();
    if (!data->initialized && data->native_window && data->main_loop) {
        GST_DEBUG ("Initialization complete, notifying application. native_window:%p main_loop:%p", data->native_window, data->main_loop);

        /* The main loop is running and we received a native window, inform the sink about it */
        gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (data->video_sink), (guintptr)data->native_window);

        (*env)->CallVoidMethod (env, data->app, on_gstreamer_initialized_method_id);
        if ((*env)->ExceptionCheck (env)) {
            GST_ERROR ("Failed to call Java method");
            (*env)->ExceptionClear (env);
        }
        data->initialized = TRUE;
    }
}

//-------------------------------------------------------------------------------------------------

static gboolean
cleanup_and_quit_loop (const gchar * msg, enum AppState state)
{
    if (msg)
        g_printerr ("WEBRTC TEST: %s\n", msg);
    if (state > 0)
        app_state = state;

    if (ws_conn) {
        if (soup_websocket_connection_get_state (ws_conn) ==
            SOUP_WEBSOCKET_STATE_OPEN)
            /* This will call us again */
            soup_websocket_connection_close (ws_conn, 1000, "");
        else
            g_object_unref (ws_conn);
    }

    if (loop) {
        g_main_loop_quit (loop);
        loop = NULL;
    }

    /* To allow usage as a GSourceFunc */
    return G_SOURCE_REMOVE;
}

static void
on_server_closed (SoupWebsocketConnection * conn G_GNUC_UNUSED,
                  gpointer user_data G_GNUC_UNUSED)
{
    app_state = SERVER_CLOSED;
    cleanup_and_quit_loop ("Server connection closed", 0);
}

static gboolean
setup_call (void)
{
    gchar *msg;

    if (soup_websocket_connection_get_state (ws_conn) !=
        SOUP_WEBSOCKET_STATE_OPEN)
        return FALSE;

    if (!peer_id)
        return FALSE;

    g_print ("WEBRTC TEST: Setting up signalling server call with %s\n", peer_id);
    app_state = PEER_CONNECTING;
    msg = g_strdup_printf ("SESSION %s", peer_id);
    soup_websocket_connection_send_text (ws_conn, msg);
    g_free (msg);
    return TRUE;
}

static gchar*
get_string_from_json_object (JsonObject * object)
{
    JsonNode *root;
    JsonGenerator *generator;
    gchar *text;

    /* Make it the root node */
    root = json_node_init_object (json_node_alloc (), object);
    generator = json_generator_new ();
    json_generator_set_root (generator, root);
    text = json_generator_to_data (generator, NULL);

    /* Release everything */
    g_object_unref (generator);
    json_node_free (root);
    return text;
}


static void
send_sdp_offer (GstWebRTCSessionDescription * offer)
{
    gchar *text;
    JsonObject *msg, *sdp;

    if (app_state < PEER_CALL_NEGOTIATING) {
        cleanup_and_quit_loop ("Can't send offer, not in call", APP_STATE_ERROR);
        return;
    }

    text = gst_sdp_message_as_text (offer->sdp);
    g_print ("WEBRTC TEST: Sending offer:\n%s\n", text);

    sdp = json_object_new ();
    json_object_set_string_member (sdp, "type", "offer");
    json_object_set_string_member (sdp, "sdp", text);
    g_free (text);

    msg = json_object_new ();
    json_object_set_object_member (msg, "sdp", sdp);
    text = get_string_from_json_object (msg);
    json_object_unref (msg);

    soup_websocket_connection_send_text (ws_conn, text);
    g_free (text);
}

/* Offer created by our pipeline, to be sent to the peer */
static void
on_offer_created (GstPromise * promise, gpointer user_data)
{
    GstWebRTCSessionDescription *offer = NULL;
    const GstStructure *reply;

    g_assert_cmphex (app_state, ==, PEER_CALL_NEGOTIATING);

    g_assert_cmphex (gst_promise_wait(promise), ==, GST_PROMISE_RESULT_REPLIED);
    reply = gst_promise_get_reply (promise);
    gst_structure_get (reply, "offer",
                       GST_TYPE_WEBRTC_SESSION_DESCRIPTION, &offer, NULL);
    gst_promise_unref (promise);

    promise = gst_promise_new ();
    g_signal_emit_by_name (webrtc1, "set-local-description", offer, promise);
    gst_promise_interrupt (promise);
    gst_promise_unref (promise);

    /* Send offer to peer */
    send_sdp_offer (offer);
    gst_webrtc_session_description_free (offer);
}

static void
on_negotiation_needed (GstElement * element, gpointer user_data)
{
    GstPromise *promise;

    app_state = PEER_CALL_NEGOTIATING;
    promise = gst_promise_new_with_change_func (on_offer_created, user_data, NULL);;
    g_signal_emit_by_name (webrtc1, "create-offer", NULL, promise);
}

static void
send_ice_candidate_message (GstElement * webrtc G_GNUC_UNUSED, guint mlineindex,
                            gchar * candidate, gpointer user_data G_GNUC_UNUSED)
{
    gchar *text;
    JsonObject *ice, *msg;
    if (app_state < PEER_CALL_NEGOTIATING) {
        cleanup_and_quit_loop ("Can't send ICE, not in call", APP_STATE_ERROR);
        return;
    }

    ice = json_object_new ();
    json_object_set_string_member (ice, "candidate", candidate);
    json_object_set_int_member (ice, "sdpMLineIndex", mlineindex);
    msg = json_object_new ();
    json_object_set_object_member (msg, "ice", ice);
    text = get_string_from_json_object (msg);
    json_object_unref (msg);

    g_print ("WEBRTC TEST: Send ICE candidate!!:\n%s\n",text);
    soup_websocket_connection_send_text (ws_conn, text);
    g_free (text);
}

static gboolean
start_pipeline (void)
{
    GstStateChangeReturn ret;
    GError *error = NULL;
// v4l2src device=/dev/video0 ! 'video/x-raw,width=1280,height=480,framerate=(fraction)30/1,colorimetry=(string)2:4:7:1' ! queue ! x264enc speed-preset=ultrafast tune=zerolatency byte-stream=true threads=4 key-int-max=15 bitrate=3000 ! h264parse config-interval=1 ! rtph264pay ! udpsink host=192.168.1.130 port=5000 sync=false alsasrc device=hw:1 ! queue ! audio/x-raw,width=16,depth=16,rate=11025,channel=1 ! queue ! audioconvert ! audioresample ! voaacenc ! aacparse ! udpsink host=192.168.1.130 port=5002 sync=false
    pipe1 =
            gst_parse_launch ("tee name=sendrecv ! queue ! fakesink "
                              "audiotestsrc is-live=true wave=red-noise ! queue ! opusenc ! rtpopuspay ! "
                              "queue ! " RTP_CAPS_OPUS(96) " ! sendrecv. ",
                    //gst_parse_launch ("webrtcbin bundle-policy=max-bundle name=sendrecv " STUN_SERVER
                    //"v4l2src device=/dev/video0 ! video/x-raw,width=1280,height=480,framerate=30/1,colorimetry=(string)2:4:7:1 ! "
                    //"queue ! x264enc speed-preset=ultrafast tune=zerolatency byte-stream=true threads=4 key-int-max=15 bitrate=3000 ! h264parse config-interval=1 ! rtph264pay ! queue ! " RTP_CAPS_H264 "96 ! sendrecv. ",

                    //"videotestsrc pattern=ball ! video/x-raw,width=1280,height=480 ! videoconvert ! queue ! vp8enc deadline=1 ! rtpvp8pay ! "
                    //"queue ! application/x-rtp,media=video,encoding-name=VP8,payload=96 ! sendrecv. ",

                    //"videotestsrc pattern=ball ! video/x-raw,width=1280,height=480,framerate=30/1,colorimetry=(string)2:4:7:1 ! videoconvert ! "
                    //" x264enc speed-preset=ultrafast tune=zerolatency byte-stream=true threads=4 key-int-max=15 bitrate=3000 ! h264parse config-interval=1 ! rtph264pay ! "RTP_CAPS_H264 "96 ! sendrecv. ",
                    //" openslessrc ! audioconvert ! audioresample ! voaacenc ! aacparse ! sendrecv. ",
                              &error);



    if (error) {
        g_printerr ("WEBRTC TEST: Failed to parse launch: %s\n", error->message);
        g_error_free (error);
        goto err;
    }

    webrtc1 = gst_bin_get_by_name (GST_BIN (pipe1), "sendrecv");
    g_assert_nonnull (webrtc1);

    /* This is the gstwebrtc entry point where we create the offer and so on. It
     * will be called when the pipeline goes to PLAYING. */
    g_signal_connect (webrtc1, "on-negotiation-needed",
                      G_CALLBACK (on_negotiation_needed), NULL);
    /* We need to transmit this ICE candidate to the browser via the websockets
     * signalling server. Incoming ice candidates from the browser need to be
     * added by us too, see on_server_message() */
    g_signal_connect (webrtc1, "on-ice-candidate",
                      G_CALLBACK (send_ice_candidate_message), NULL);

    gst_element_set_state (pipe1, GST_STATE_READY);

    /* Lifetime is the same as the pipeline itself */
    gst_object_unref (webrtc1);

    g_print ("WEBRTC TEST: Starting pipeline\n");
    ret = gst_element_set_state (GST_ELEMENT (pipe1), GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE)
        goto err;

    return TRUE;

    err:
    if (pipe1)
        g_clear_object (&pipe1);
    if (webrtc1)
        webrtc1 = NULL;
    return FALSE;
}

/* One mega message handler for our asynchronous calling mechanism */
static void
on_server_message (SoupWebsocketConnection * conn, SoupWebsocketDataType type,
                   GBytes * message, gpointer user_data)
{
    gchar *text;

    switch (type) {
        case SOUP_WEBSOCKET_DATA_BINARY:
            g_printerr ("WEBRTC TEST: Received unknown binary message, ignoring\n");
            return;
        case SOUP_WEBSOCKET_DATA_TEXT: {
            gsize size;
            const gchar *data = g_bytes_get_data (message, &size);
            /* Convert to NULL-terminated string */
            text = g_strndup (data, size);
            break;
        }
        default:
            g_assert_not_reached ();
    }

    /* Server has accepted our registration, we are ready to send commands */
    if (g_strcmp0 (text, "HELLO") == 0) {
        if (app_state != SERVER_REGISTERING) {
            cleanup_and_quit_loop ("ERROR: Received HELLO when not registering",
                                   APP_STATE_ERROR);
            goto out;
        }
        app_state = SERVER_REGISTERED;
        g_print ("WEBRTC TEST: Registered with server\n");
        /* Ask signalling server to connect us with a specific peer */
        if (!setup_call ()) {
            cleanup_and_quit_loop ("ERROR: Failed to setup call", PEER_CALL_ERROR);
            goto out;
        }
        /* Call has been setup by the server, now we can start negotiation */
    } else if (g_strcmp0 (text, "SESSION_OK") == 0) {
        if (app_state != PEER_CONNECTING) {
            cleanup_and_quit_loop ("ERROR: Received SESSION_OK when not calling",
                                   PEER_CONNECTION_ERROR);
            goto out;
        }

        app_state = PEER_CONNECTED;
        /* Start negotiation (exchange SDP and ICE candidates) */
        if (!start_pipeline ())
            cleanup_and_quit_loop ("ERROR: failed to start pipeline",
                                   PEER_CALL_ERROR);
        /* Handle errors */
    } else if (g_str_has_prefix (text, "ERROR")) {
        switch (app_state) {
            case SERVER_CONNECTING:
                app_state = SERVER_CONNECTION_ERROR;
                break;
            case SERVER_REGISTERING:
                app_state = SERVER_REGISTRATION_ERROR;
                break;
            case PEER_CONNECTING:
                app_state = PEER_CONNECTION_ERROR;
                break;
            case PEER_CONNECTED:
            case PEER_CALL_NEGOTIATING:
                app_state = PEER_CALL_ERROR;
            default:
                app_state = APP_STATE_ERROR;
        }
        cleanup_and_quit_loop (text, 0);
        /* Look for JSON messages containing SDP and ICE candidates */
    } else {
        JsonNode *root;
        JsonObject *object, *child;
        JsonParser *parser = json_parser_new ();
        if (!json_parser_load_from_data (parser, text, -1, NULL)) {
            g_printerr ("WEBRTC TEST: Unknown message '%s', ignoring", text);
            g_object_unref (parser);
            goto out;
        }

        root = json_parser_get_root (parser);
        if (!JSON_NODE_HOLDS_OBJECT (root)) {
            g_printerr ("WEBRTC TEST: Unknown json message '%s', ignoring", text);
            g_object_unref (parser);
            goto out;
        }

        object = json_node_get_object (root);
        /* Check type of JSON message */
        if (json_object_has_member (object, "sdp")) {
            int ret;
            GstSDPMessage *sdp;
            const gchar *text, *sdptype;
            GstWebRTCSessionDescription *answer;

            g_assert_cmphex (app_state, ==, PEER_CALL_NEGOTIATING);

            child = json_object_get_object_member (object, "sdp");

            if (!json_object_has_member (child, "type")) {
                cleanup_and_quit_loop ("ERROR: received SDP without 'type'",
                                       PEER_CALL_ERROR);
                goto out;
            }

            sdptype = json_object_get_string_member (child, "type");
            /* In this example, we always create the offer and receive one answer.
             * See tests/examples/webrtcbidirectional.c in gst-plugins-bad for how to
             * handle offers from peers and reply with answers using webrtcbin. */
            g_assert_cmpstr (sdptype, ==, "answer");

            text = json_object_get_string_member (child, "sdp");

            g_print ("WEBRTC TEST: Received answer:\n%s\n", text);

            ret = gst_sdp_message_new (&sdp);
            g_assert_cmphex (ret, ==, GST_SDP_OK);

            ret = gst_sdp_message_parse_buffer ((guint8 *) text, strlen (text), sdp);
            g_assert_cmphex (ret, ==, GST_SDP_OK);

            answer = gst_webrtc_session_description_new (GST_WEBRTC_SDP_TYPE_ANSWER,
                                                         sdp);
            g_assert_nonnull (answer);

            /* Set remote description on our pipeline */
            {
                GstPromise *promise = gst_promise_new ();
                g_signal_emit_by_name (webrtc1, "set-remote-description", answer,
                                       promise);
                gst_promise_interrupt (promise);
                gst_promise_unref (promise);
            }

            app_state = PEER_CALL_STARTED;
        } else if (json_object_has_member (object, "ice")) {
            const gchar *candidate;
            gint sdpmlineindex;

            child = json_object_get_object_member (object, "ice");
            candidate = json_object_get_string_member (child, "candidate");
            sdpmlineindex = json_object_get_int_member (child, "sdpMLineIndex");

            /* Add ice candidate sent by remote peer */
            g_signal_emit_by_name (webrtc1, "add-ice-candidate", sdpmlineindex,
                                   candidate);
        } else {
            g_printerr ("WEBRTC TEST: Ignoring unknown JSON message:\n%s\n", text);
        }
        g_object_unref (parser);
    }

    out:
    g_free (text);
}

static gboolean
register_with_server (void)
{
    gchar *hello;
    gint32 our_id;

    if (soup_websocket_connection_get_state (ws_conn) !=
        SOUP_WEBSOCKET_STATE_OPEN)
        return FALSE;

    our_id = g_random_int_range (10, 10000);
    g_print ("WEBRTC TEST: Registering id %i with server\n", our_id);
    app_state = SERVER_REGISTERING;

    /* Register with the server with a random integer id. Reply will be received
     * by on_server_message() */
    hello = g_strdup_printf ("HELLO %i", our_id);
    soup_websocket_connection_send_text (ws_conn, hello);
    g_free (hello);

    return TRUE;
}

static void
on_server_connected (SoupSession * session, GAsyncResult * res,
                     SoupMessage *msg)
{
    GError *error = NULL;

    ws_conn = soup_session_websocket_connect_finish (session, res, &error);
    if (error) {
        cleanup_and_quit_loop (error->message, SERVER_CONNECTION_ERROR);
        g_error_free (error);
        return;
    }

    g_assert_nonnull (ws_conn);

    app_state = SERVER_CONNECTED;
    g_print ("WEBRTC TEST: Connected to signalling server\n");

    g_signal_connect (ws_conn, "closed", G_CALLBACK (on_server_closed), NULL);
    g_signal_connect (ws_conn, "message", G_CALLBACK (on_server_message), NULL);

    /* Register with the server so it knows about us and can accept commands */
    register_with_server ();
}


/*
 * Connect to the signalling server. This is the entrypoint for everything else.
 */
static void
connect_to_websocket_server_async (void)
{
    SoupLogger *logger;
    SoupMessage *message;
    SoupSession *session;
    const char *https_aliases[] = {"wss", NULL};

    session = soup_session_new_with_options (SOUP_SESSION_SSL_STRICT, !disable_ssl,
                                             SOUP_SESSION_SSL_USE_SYSTEM_CA_FILE, TRUE,
                                             SOUP_SESSION_HTTPS_ALIASES, https_aliases, NULL);

    logger = soup_logger_new (SOUP_LOGGER_LOG_BODY, -1);
    soup_session_add_feature (session, SOUP_SESSION_FEATURE (logger));
    g_object_unref (logger);

    message = soup_message_new (SOUP_METHOD_GET, server_url);

    g_print ("WEBRTC TEST: Connecting to server...\n");

    /* Once connected, we will register */
    soup_session_websocket_connect_async (session, message, NULL, NULL, NULL,
                                          (GAsyncReadyCallback) on_server_connected, message);
    app_state = SERVER_CONNECTING;
}


/* Main method for the native code. This is executed on its own thread. */
static void *app_function (void *userdata) {
    JavaVMAttachArgs args;
    GstBus *bus;
    CustomData *data = (CustomData *)userdata;
    GSource *bus_source;
    GError *error = NULL;

    GST_DEBUG ("WEBRTC TEST: Creating pipeline in CustomData at %p", data);

    /* Create our own GLib Main Context and make it the default one */
    data->context = g_main_context_new ();
    g_main_context_push_thread_default(data->context);

    //VIDEO + AUDIO 2 PORTS
    data->pipeline = gst_parse_launch("udpsrc port=5000 ! application/x-rtp, media=video, clock-rate=90000, encoding-name=H264 ! rtph264depay ! h264parse ! avdec_h264 ! videoflip method=rotate-180 ! autovideosink sync=false udpsrc port=5002 ! audio/mpeg, mpegversion=4, channels=2, rate=11025, level=2, base-profile=lc, profile=lc, stream-format=raw, framed=true ! faad ! audioconvert ! audioresample ! autoaudiosink sync=false openslessrc ! audioconvert ! audioresample ! voaacenc ! aacparse ! udpsink host=192.168.1.138 port=5004 sync=false", &error);

    if (error) {
        gchar *message = g_strdup_printf("WEBRTC TEST: Unable to build pipeline: %s", error->message);
        g_clear_error (&error);
        g_free (message);
        return NULL;
    }

    gst_element_set_state(data->pipeline, GST_STATE_READY);

    data->video_sink = gst_bin_get_by_interface(GST_BIN(data->pipeline), GST_TYPE_VIDEO_OVERLAY);
    if (!data->video_sink) {
        GST_ERROR ("WEBRTC TEST: Could not retrieve video sink");
        return NULL;
    }

    /* Instruct the bus to emit signals for each received message, and connect to the interesting signals */
    bus = gst_element_get_bus (data->pipeline);
    bus_source = gst_bus_create_watch (bus);
    g_source_set_callback (bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach (bus_source, data->context);
    g_source_unref (bus_source);
    g_signal_connect (G_OBJECT (bus), "message::error", (GCallback)error_cb, data);
    g_signal_connect (G_OBJECT (bus), "message::state-changed", (GCallback)state_changed_cb, data);
    gst_object_unref (bus);

    /* Create a GLib Main Loop and set it to run */
    GST_DEBUG ("WEBRTC TEST: Entering main loop... (CustomData:%p)", data);
    data->main_loop = g_main_loop_new (data->context, FALSE);
    //--------------------------------------

    connect_to_websocket_server_async ();

    //--------------------------------------
    check_initialization_complete (data);
    g_main_loop_run (data->main_loop);
    GST_DEBUG ("WEBRTC TEST: Exited main loop");
    g_main_loop_unref (data->main_loop);
    data->main_loop = NULL;

    /* Free resources */
    g_main_context_pop_thread_default(data->context);
    g_main_context_unref (data->context);
    gst_element_set_state (data->pipeline, GST_STATE_NULL);
    gst_object_unref (data->video_sink);
    gst_object_unref (data->pipeline);

    return NULL;
}

/*
 * Java Bindings
 */

/* Instruct the native code to create its internal data structure, pipeline and thread */
static void gst_native_init (JNIEnv* env, jobject thiz) {
    CustomData *data = g_new0 (CustomData, 1);
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, data);
    GST_DEBUG_CATEGORY_INIT (debug_category, "tutorial-3", 0, "Android tutorial 3");
    gst_debug_set_threshold_for_name("tutorial-3", GST_LEVEL_DEBUG);
    GST_DEBUG ("Created CustomData at %p", data);
    data->app = (*env)->NewGlobalRef (env, thiz);
    GST_DEBUG ("Created GlobalRef for app object at %p", data->app);
    pthread_create (&gst_app_thread, NULL, &app_function, data);
}

/* Quit the main loop, remove the native thread and free resources */
static void gst_native_finalize (JNIEnv* env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Quitting main loop...");
    g_main_loop_quit (data->main_loop);
    GST_DEBUG ("Waiting for thread to finish...");
    pthread_join (gst_app_thread, NULL);
    GST_DEBUG ("Deleting GlobalRef for app object at %p", data->app);
    (*env)->DeleteGlobalRef (env, data->app);
    GST_DEBUG ("Freeing CustomData at %p", data);
    g_free (data);
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, NULL);
    GST_DEBUG ("Done finalizing");
}

/* Set pipeline to PLAYING state */
static void gst_native_play (JNIEnv* env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Setting state to PLAYING");
    gst_element_set_state (data->pipeline, GST_STATE_PLAYING);
}

/* Static class initializer: retrieve method and field IDs */
static jboolean gst_native_class_init (JNIEnv* env, jclass klass) {
    custom_data_field_id = (*env)->GetFieldID (env, klass, "native_custom_data", "J");
    on_gstreamer_initialized_method_id = (*env)->GetMethodID (env, klass, "onGStreamerInitialized", "()V");

    if (!custom_data_field_id || !on_gstreamer_initialized_method_id) {
        /* We emit this message through the Android log instead of the GStreamer log because the later
         * has not been initialized yet.
         */
        __android_log_print (ANDROID_LOG_ERROR, "tutorial-3", "The calling class does not implement all necessary interface methods");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void gst_native_surface_init (JNIEnv *env, jobject thiz, jobject surface) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    ANativeWindow *new_native_window = ANativeWindow_fromSurface(env, surface);
    GST_DEBUG ("Received surface %p (native window %p)", surface, new_native_window);

    if (data->native_window) {
        ANativeWindow_release (data->native_window);
        if (data->native_window == new_native_window) {
            GST_DEBUG ("New native window is the same as the previous one %p", data->native_window);
            if (data->video_sink) {
                gst_video_overlay_expose(GST_VIDEO_OVERLAY (data->video_sink));
                gst_video_overlay_expose(GST_VIDEO_OVERLAY (data->video_sink));
            }
            return;
        } else {
            GST_DEBUG ("Released previous native window %p", data->native_window);
            data->initialized = FALSE;
        }
    }
    data->native_window = new_native_window;

    check_initialization_complete (data);
}

static void gst_native_surface_finalize (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    GST_DEBUG ("Releasing Native Window %p", data->native_window);

    if (data->video_sink) {
        gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (data->video_sink), (guintptr)NULL);
        gst_element_set_state (data->pipeline, GST_STATE_READY);
    }

    ANativeWindow_release (data->native_window);
    data->native_window = NULL;
    data->initialized = FALSE;
}

/* List of implemented native methods */
static JNINativeMethod native_methods[] = {
        { "nativeInit", "()V", (void *) gst_native_init},
        { "nativeFinalize", "()V", (void *) gst_native_finalize},
        { "nativePlay", "()V", (void *) gst_native_play},
        { "nativeSurfaceInit", "(Ljava/lang/Object;)V", (void *) gst_native_surface_init},
        { "nativeSurfaceFinalize", "()V", (void *) gst_native_surface_finalize},
        { "nativeClassInit", "()Z", (void *) gst_native_class_init}
};

/* Library initializer */
jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;

    java_vm = vm;

    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        __android_log_print (ANDROID_LOG_ERROR, "tutorial-3", "Could not retrieve JNIEnv");
        return 0;
    }
    jclass klass = (*env)->FindClass (env, "com/development/fic/ficprototype/MainActivity");
    (*env)->RegisterNatives (env, klass, native_methods, G_N_ELEMENTS(native_methods));

    pthread_key_create (&current_jni_env, detach_current_thread);

    return JNI_VERSION_1_4;
}