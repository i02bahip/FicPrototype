package com.development.fic.ficprototype;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.widget.Toast;
import android.view.SurfaceView;
import android.util.Log;
import android.app.Activity;

import com.development.fic.ficprototype.bluetooth.controller.InputManagerCompat;
import com.google.gson.Gson;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.freedesktop.gstreamer.GStreamer;
import org.freedesktop.gstreamer.Element.PAD_ADDED;
import org.freedesktop.gstreamer.elements.WebRTCBin;
import org.freedesktop.gstreamer.elements.WebRTCBin.CREATE_OFFER;
import org.freedesktop.gstreamer.elements.WebRTCBin.ON_ICE_CANDIDATE;
import org.freedesktop.gstreamer.elements.WebRTCBin.ON_NEGOTIATION_NEEDED;
import org.freedesktop.gstreamer.elements.WebRTCBin.CREATE_ANSWER;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, SensorEventListener {
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativePlay();     // Set pipeline to PLAYING
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private long native_custom_data;      // Native code will use this to keep private data
    private BluetoothInputController m_bluetoothInputController;


    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private MqttHelper mqttHelper;

    private Payload payload = new Payload();

    private WebRTCBin webRTCBin;


    Gson gson = new Gson();

    GeomagneticField mGeoMag = null;
    //LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
    //Location location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);

    //---------------------------
    // Scheduler
    //---------------------------

    Handler handler = new Handler();
    private Runnable periodicUpdate = new Runnable () {
        @Override
        public void run() {
            // scheduled another events to be in 1 seconds later
            handler.postDelayed(periodicUpdate, 1*50);
            //mqttHelper.publishToTopic(gson.toJson(payload));
            mqttHelper.publishToTopic(payload.getServoAzimuth() + ","
                    + payload.getServoRoll() + ","
                    + payload.getMovement().getMotorRight().getStrForward() + ","
                    + payload.getMovement().getMotorLeft().getStrForward() + ","
                    + payload.getMovement().getMotorRight().getStrReverse() + ","
                    + payload.getMovement().getMotorLeft().getStrReverse()
            );
        }
    };

    // Called when the activity is first created.
    @Override
    public void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);

            // Initialize GStreamer and warn if it fails
            try {
                GStreamer.init(this);
                webRTCBin = new WebRTCBin("sendrecv");
                webRTCBin.setStunServer("stun:stun.services.mozilla.com");
                webRTCBin.setStunServer("stun:stun.l.google.com:19302");
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            setContentView(R.layout.activity_main);

            m_bluetoothInputController = new BluetoothInputController(this);

            nativePlay();

            SurfaceView sv = this.findViewById(R.id.surface_video);
            SurfaceHolder sh = sv.getHolder();
            sh.addCallback(this);

            nativeInit();

            sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);

            startMqtt();
        }

        protected void onDestroy() {
            nativeFinalize();
            super.onDestroy();
        }


        // Called from native code. Native code calls this once it has created its pipeline and
        // the main loop is running, so it is ready to accept commands.
        private void onGStreamerInitialized () {
            Log.i ("GStreamer", "Gst initialized. Restoring state, playing:" );
            // Restore previous playing state
            nativePlay();
            // Re-enable buttons, now that GStreamer is initialized
            final Activity activity = this;
        }

        static {
                System.loadLibrary("gstreamer_android");
                System.loadLibrary("FicPrototype");
                nativeClassInit();
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int width,
                int height) {
                Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
                nativeSurfaceInit (holder.getSurface());
        }

        public void surfaceCreated(SurfaceHolder holder) {
                Log.d("GStreamer", "Surface created: " + holder.getSurface());
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d("GStreamer", "Surface destroyed");
                nativeSurfaceFinalize ();
        }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        //check sensor type matches current sensor type set by button click

        if(event.sensor.getType() ==  Sensor.TYPE_GAME_ROTATION_VECTOR){
            //rotation sensor
            float[] rotMatrix = new float[9];
            float[] rotVals = new float[3];

            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
            SensorManager.remapCoordinateSystem(rotMatrix,
                    SensorManager.AXIS_X, SensorManager.AXIS_Y, rotMatrix);

            SensorManager.getOrientation(rotMatrix, rotVals);
            payload.setAzimuth((float) Math.toDegrees(rotVals[0]));
            payload.setPitch((float) Math.toDegrees(rotVals[1]));
            payload.setRoll((float) Math.toDegrees(rotVals[2]));

            if(payload.getFirstAzimuth() == null) {
                payload.setFirstAzimuth(payload.getAzimuth());
            }

        }


    }
    @Override
    protected void onResume() {
        super.onResume();
        m_bluetoothInputController.reset();
        /*mGeoMag = new GeomagneticField(mMyLatitude,
                mMyLongitude, mMyAltitude,
                System.currentTimeMillis());*/
        sensorManager.registerListener(this, rotationSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    /*public float getBearing(float heading) {
        if (mGeoMag == null) return heading;
        heading -= mGeoMag.getDeclination();
        return mMyLocation.bearingTo(mKabaLocation) - heading;
    }*/


    //-------------------------
    //MQTT
    //-------------------------

    private void startMqtt(){
        mqttHelper = new MqttHelper(getApplicationContext());
        mqttHelper.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean b, String s) {
                handler.post(periodicUpdate);
                Toast.makeText(getApplicationContext(),"Connection complete",Toast.LENGTH_LONG).show();
            }

            @Override
            public void connectionLost(Throwable throwable) {
                Toast.makeText(getApplicationContext(),"Connection lost",Toast.LENGTH_LONG).show();
            }

            @Override
            public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {

            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

            }
        });
    }

    public ArrayList<Integer> getGameControllerIds() {
        ArrayList<Integer> gameControllerDeviceIds = new ArrayList<Integer>();
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice dev = InputDevice.getDevice(deviceId);
            int sources = dev.getSources();

            // Verify that the device has gamepad buttons, control sticks, or both.
            if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                    || ((sources & InputDevice.SOURCE_JOYSTICK)
                    == InputDevice.SOURCE_JOYSTICK)) {
                // This device is a game controller. Store its device ID.
                if (!gameControllerDeviceIds.contains(deviceId)) {
                    gameControllerDeviceIds.add(deviceId);
                }
            }
        }
        return gameControllerDeviceIds;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        return m_bluetoothInputController.dispatchKeyEvent(event);
    }


    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        return m_bluetoothInputController.onGenericMotionEvent(event);
    }



    public class BluetoothInputController implements InputManagerCompat.InputDeviceListener
    {
        private final String TAG = "BluetoothInput";
        private final InputManagerCompat m_inputManager;
        private InputDevice m_inputDevice;
        private boolean m_isNightMode = false;

        public BluetoothInputController(Context context)
        {
            m_inputManager = InputManagerCompat.Factory.getInputManager(context);
            m_inputManager.registerInputDeviceListener(this, null);
        }

        // Iterate through the input devices, looking for controllers. Create a ship
        // for every device that reports itself as a gamepad or joystick.
        void findControllers()
        {
            int[] deviceIds = m_inputManager.getInputDeviceIds();
            for (int deviceId : deviceIds)
            {
                InputDevice dev = m_inputManager.getInputDevice(deviceId);
                int sources = dev.getSources();
                // if the device is a gamepad/joystick, create a ship to represent it
                if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                        ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK))
                {
                    // if the device has a gamepad or joystick
                    Log.e(TAG, "Bluetooth Device " + deviceId + " added");
                }
            }
        }

        public void reset()
        {
            findControllers();
        }

        public boolean onGenericMotionEvent(MotionEvent event) {
            m_inputManager.onGenericMotionEvent(event);

            // Check that the event came from a joystick or gamepad since a generic
            // motion event could be almost anything. API level 18 adds the useful
            // event.isFromSource() helper function.
            int eventSource = event.getSource();
            if ((((eventSource & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                    ((eventSource & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK))
                    && event.getAction() == MotionEvent.ACTION_MOVE) {
                int id = event.getDeviceId();
                if (-1 != id)
                {
                    handleGenericMotionEvent(event);
                }
            }
            return false;
        }

        public boolean dispatchKeyEvent(KeyEvent event)
        {
            if(event.getAction() == MotionEvent.ACTION_DOWN)
            {
                return onKeyDown(event.getKeyCode(), event);
            }
            else if(event.getAction() == MotionEvent.ACTION_UP)
            {
                return onKeyUp(event.getKeyCode(), event);
            }
            return false;
        }

        private boolean onKeyDown(int keyCode, KeyEvent event)
        {
            boolean handled = false;
            int deviceId = event.getDeviceId();
            if (deviceId != -1)
            {
                if (event.getRepeatCount() == 0)
                {
                    switch (keyCode)
                    {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_DPAD_LEFT");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_DPAD_RIGHT");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_DPAD_UP");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_DPAD_DOWN");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_X:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_X");
                            m_isNightMode = !m_isNightMode;
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_A:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_A");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_Y:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_Y");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_B:
                            Log.e(TAG,"Bluetooth Event : KeyDown:  KEYCODE_B");
                            handled = true;
                            break;
                        default:
                            if (isFireKey(keyCode))
                            {
                                Log.e(TAG,"Bluetooth Event : KeyDown:  FIRE");
                                handled = true;
                            }
                            break;
                    }
                }
            }
            return handled;
        }

        private boolean onKeyUp(int keyCode, KeyEvent event)
        {
            boolean handled = false;
            int deviceId = event.getDeviceId();
            if (deviceId != -1)
            {
                // Handle DPad keys and fire button on initial down but not on
                // auto-repeat.
                if (event.getRepeatCount() == 0)
                {
                    switch (keyCode)
                    {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            Log.e(TAG,"Bluetooth Event : KeyUp:  KEYCODE_DPAD_LEFT");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            Log.e(TAG,"Bluetooth Event : KeyUp: KEYCODE_DPAD_RIGHT");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            Log.e(TAG,"Bluetooth Event : KeyUp: KEYCODE_DPAD_UP");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            Log.e(TAG,"Bluetooth Event : KeyUp: KEYCODE_DPAD_DOWN");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_X:
                            Log.e(TAG,"Bluetooth Event : KeyUp:  KEYCODE_X");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_A:
                            Log.e(TAG,"Bluetooth Event : KeyUp:  KEYCODE_A");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_Y:
                            Log.e(TAG,"Bluetooth Event : KeyUp:  KEYCODE_Y");
                            handled = true;
                            break;
                        case KeyEvent.KEYCODE_BUTTON_B:
                            Log.e(TAG,"Bluetooth Event : KeyUp:  KEYCODE_B");
                            handled = true;
                            break;
                        default:
                            if (isFireKey(keyCode))
                            {
                                Log.e(TAG,"Bluetooth Event : KeyUp: FIRE");
                                handled = true;
                            }
                            break;
                    }
                }
            }
            return handled;
        }

        /**
         * Any gamepad button + the spacebar or DPAD_CENTER will be used as the fire
         * key.
         *
         * @param keyCode
         * @return true of it's a fire key.
         */
        private boolean isFireKey(int keyCode)
        {
            return KeyEvent.isGamepadButton(keyCode)
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE;
        }

        private boolean handleGenericMotionEvent(MotionEvent event)
        {
            // Process all historical movement samples in the batch.
            final int historySize = event.getHistorySize();
            for (int i = 0; i < historySize; i++)
            {
                processJoystickInput(event, i);
            }

            // Process the current movement sample in the batch.
            processJoystickInput(event, -1);
            return true;
        }

        private void processJoystickInput(MotionEvent event, int historyPos)
        {
            // Get joystick position.
            // Many game pads with two joysticks report the position of the
            // second
            // joystick
            // using the Z and RZ axes so we also handle those.
            // In a real game, we would allow the user to configure the axes
            // manually.
            if (null == m_inputDevice)
            {
                m_inputDevice = event.getDevice();
            }
            float x = getCenteredAxis(event, m_inputDevice, MotionEvent.AXIS_X, historyPos);
            if (x == 0)
            {
                x = getCenteredAxis(event, m_inputDevice, MotionEvent.AXIS_HAT_X, historyPos);
            }
            if (x == 0)
            {
                x = getCenteredAxis(event, m_inputDevice, MotionEvent.AXIS_Z, historyPos);
            }

            float y = getCenteredAxis(event, m_inputDevice, MotionEvent.AXIS_Y, historyPos);
            if (y == 0)
            {
                y = getCenteredAxis(event, m_inputDevice, MotionEvent.AXIS_HAT_Y, historyPos);
            }
            if (y == 0)
            {
                y = getCenteredAxis(event, m_inputDevice, MotionEvent.AXIS_RZ, historyPos);
            }
            Log.e(TAG, "Bluetooth Device : JoyStick : "
                    + payload.getMovement().getMotorRight().getStrForward()
                    + ","
                    + payload.getMovement().getMotorLeft().getStrForward()
                    + ","
                    + payload.getMovement().getMotorRight().getStrReverse()
                    + ","
                    + payload.getMovement().getMotorLeft().getStrReverse()
            );

            payload.getMovement().setMovement(x, y);
        }

        private float getCenteredAxis(MotionEvent event, InputDevice device, int axis, int historyPos)
        {
            final InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
            if (range != null)
            {
                final float flat = range.getFlat();
                final float value = historyPos < 0 ? event.getAxisValue(axis)
                        : event.getHistoricalAxisValue(axis, historyPos);

                // Ignore axis values that are within the 'flat' region of the
                // joystick axis center.
                // A joystick at rest does not always report an absolute position of
                // (0,0).
                if (Math.abs(value) > flat)
                {
                    return value;
                }
            }
            return 0;
        }

        @Override
        public void onInputDeviceAdded(int deviceId)
        {
            Log.e(TAG, "Bluetooth Device "+ deviceId +" Added");
        }

        @Override
        public void onInputDeviceChanged(int deviceId)
        {
            Log.e(TAG, "onInputDeviceChanged" + String.valueOf(InputDevice.getDevice(deviceId)));
        }

        @Override
        public void onInputDeviceRemoved(int deviceId)
        {
            Log.e(TAG, "Bluetooth Device Removed");
        }
    }


}
